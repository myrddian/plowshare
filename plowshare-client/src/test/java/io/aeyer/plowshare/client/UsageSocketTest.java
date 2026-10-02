package io.aeyer.plowshare.client;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class UsageSocketTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static JsonNode report(String conversation,String input) {
        var totals=new LinkedHashMap<String,Object>();
        for(String key:List.of("calls","attempts","active_calls","incomplete_attempts","unknown_cost_attempts","input_tokens","output_tokens","input_tokens_known","output_tokens_known"))totals.put(key,"0");
        totals.put("input_tokens",input);totals.put("input_tokens_known","1");
        totals.put("costs",Map.of("USD","0.000002"));totals.put("usage_complete",true);totals.put("cost_complete",true);totals.put("complete",true);
        var value=new LinkedHashMap<String,Object>();value.put("filters",Map.of("type","usage.conversation","filter",Map.of("conversation",conversation)));value.put("totals",totals);value.put("groups",List.of());value.put("cursor",null);value.put("health",Map.of("watermark","3","as_of","2026-10-02T00:00:00Z","capture_enabled",true,"historical_usage","not_imported"));
        return JSON.valueToTree(value);
    }
    private static void reply(WebSocket socket,JsonNode request,Object payload) throws Exception {
        socket.send(JSON.writeValueAsString(Map.of("id",request.path("id").asText(),"type",request.path("type").asText(),"protocol_version","plowshare-v1","payload",Map.of("code","OK","payload",payload))));
    }
    private static void push(WebSocket socket,String subscription,int revision,String input) throws Exception {
        var frame=JSON.createObjectNode();frame.putNull("id");frame.put("type","usage.updated");frame.put("protocol_version","plowshare-v1");frame.set("payload",JSON.valueToTree(Map.of("subscription",subscription,"revision",revision,"report",report("root",input))));socket.send(frame.toString());
    }
    @Test void authenticated_reports_keep_decimal_strings_and_use_only_one_socket_upgrade() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();var frames=new CopyOnWriteArrayList<String>();
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket socket,String text){try{var request=JSON.readTree(text);frames.add(request.path("type").asText());reply(socket,request,report("root","9223372036854775807"));}catch(Exception failure){throw new AssertionError(failure);}}
                @Override public void onClosing(WebSocket socket,int code,String reason){socket.close(code,reason);}
            }));
            var client=new HttpServerClient(server.url("/").toString(),"fixture-token");
            try(var usage=client.usageSocket()) {
                var report=usage.request("usage.conversation",Map.of("conversation","root"));
                assertEquals("9223372036854775807",report.path("totals").path("input_tokens").asText());
                assertThrows(IllegalArgumentException.class,()->usage.request("agent.run",Map.of()));
            }
            var upgrade=server.takeRequest();assertTrue(upgrade.getPath().startsWith("/v1/events?session="));assertEquals("Bearer fixture-token",upgrade.getHeader("Authorization"));assertEquals(1,server.getRequestCount());assertEquals(List.of("usage.conversation"),frames);
        }
    }
    @Test void early_pushes_replace_reports_monotonically_and_reconnect_never_runs_a_model() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();var frames=new CopyOnWriteArrayList<String>();var transport=new AtomicReference<WebSocket>();var subscriptions=new AtomicInteger();
            WebSocketListener listener=new WebSocketListener() {
                @Override public void onMessage(WebSocket socket,String text){try{
                    var request=JSON.readTree(text);String type=request.path("type").asText();frames.add(type);transport.set(socket);
                    if(type.equals("usage.subscribe")) {
                        String id="s"+subscriptions.incrementAndGet();push(socket,id,2,"20");push(socket,id,1,"10");var report=report("root","5");reply(socket,request,Map.of("subscription",id,"revision",0,"filters",report.path("filters"),"report",report));
                    } else reply(socket,request,Map.of());
                }catch(Exception failure){throw new AssertionError(failure);}}
                @Override public void onClosing(WebSocket socket,int code,String reason){socket.close(code,reason);}
            };
            server.enqueue(new MockResponse().withWebSocketUpgrade(listener));server.enqueue(new MockResponse().withWebSocketUpgrade(listener));
            try(var usage=new HttpServerClient(server.url("/").toString()).usageSocket()) {
                var snapshots=new LinkedBlockingQueue<UsageSocket.Snapshot>();
                var view=usage.watch("usage.conversation",Map.of("conversation","root"),snapshots::add);
                UsageSocket.Snapshot latest=null;
                for(int i=0;i<3;i++){latest=snapshots.poll(3,TimeUnit.SECONDS);assertNotNull(latest);if(latest.revision()==2)break;}
                assertEquals(2,latest.revision());assertEquals("20",latest.report().path("totals").path("input_tokens").asText());
                snapshots.clear();transport.get().close(1000,"lost");assertTrue(snapshots.poll(3,TimeUnit.SECONDS).stale());
                usage.reconnect();assertEquals(2,subscriptions.get());view.close();
                assertEquals(List.of("usage.subscribe","usage.subscribe","usage.unsubscribe"),frames);assertEquals(2,server.getRequestCount());
            }
        }
    }
    @Test void callbacks_can_read_reports_without_deadlocking_the_socket_reader() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){
                @Override public void onMessage(WebSocket socket,String text){try{var request=JSON.readTree(text);var report=report("root","8");if(request.path("type").asText().equals("usage.subscribe"))reply(socket,request,Map.of("subscription","s","revision",0,"filters",report.path("filters"),"report",report));else reply(socket,request,report);}catch(Exception invalid){throw new AssertionError(invalid);}}
                @Override public void onClosing(WebSocket socket,int code,String reason){socket.close(code,reason);}
            }));
            try(var usage=new HttpServerClient(server.url("/").toString()).usageSocket()){
                var read=new CompletableFuture<JsonNode>();usage.watch("usage.conversation",Map.of("conversation","root"),snapshot->{try{read.complete(usage.request("usage.conversation",Map.of("conversation","root")));}catch(IOException failure){read.completeExceptionally(failure);}});
                assertEquals("8",read.get(3,TimeUnit.SECONDS).path("totals").path("input_tokens").asText());
            }
        }
    }
    @Test void refusals_and_foreign_scope_reports_are_visible_and_do_not_trigger_fallback() throws Exception {
        for(boolean refused:new boolean[]{true,false})try(var server=new MockWebServer()){
            server.start();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){
                @Override public void onMessage(WebSocket socket,String text){try{var request=JSON.readTree(text);if(refused)socket.send(JSON.writeValueAsString(Map.of("id",request.path("id").asText(),"protocol_version","plowshare-v1","payload",Map.of("code","BAD_REQUEST","said","Access revoked."))));else reply(socket,request,report("foreign","99"));}catch(Exception failure){throw new AssertionError(failure);}}
                @Override public void onClosing(WebSocket socket,int code,String reason){socket.close(code,reason);}
            }));
            try(var usage=new HttpServerClient(server.url("/").toString()).usageSocket()){
                var error=assertThrows(IOException.class,()->usage.request("usage.conversation",Map.of("conversation","root")));assertTrue(error.getMessage().contains(refused?"Access revoked":"unreadable"));
            }
            assertEquals(1,server.getRequestCount());
        }
    }
}
