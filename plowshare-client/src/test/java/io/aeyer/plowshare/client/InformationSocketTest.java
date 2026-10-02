package io.aeyer.plowshare.client;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class InformationSocketTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void the_mcp_information_tool_refuses_publication_before_contacting_the_server() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var server=(ServerClient)java.lang.reflect.Proxy.newProxyInstance(ServerClient.class.getClassLoader(),new Class<?>[]{ServerClient.class},
                (proxy,method,args)->{calls.incrementAndGet();throw new AssertionError("publication must not reach the transport");});
        var registry=new io.aeyer.plowshare.client.mcp.ToolRegistry();
        new io.aeyer.plowshare.client.tools.InformationTools(server).registerOn(registry);
        var tool=registry.find("information").orElseThrow();
        for(String operation:java.util.List.of("share","unshare","finalise","delete","migration.adopt","migration.release")) {
            assertThrows(IllegalArgumentException.class,()->tool.handler().apply(Map.of("operation",operation,"payload",Map.of("revision",UUID.randomUUID().toString()))));
        }
        assertEquals(0,calls.get());
    }
    @Test void authenticated_requests_are_correlated_over_websocket_and_keep_mutation_receipts() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();var received=new AtomicReference<String>();
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket socket,String text) {
                    received.set(text);
                    try {
                        var request=JSON.readTree(text);
                        socket.send("{\"kind\":\"information.changed\",\"sequence\":1}");
                        socket.send(JSON.writeValueAsString(Map.of("id","unrelated","protocol_version","plowshare-v1","payload",Map.of("code","OK"))));
                        socket.send(JSON.writeValueAsString(Map.of("id",request.path("id").asText(),"protocol_version","plowshare-v1","payload",Map.of("code","ACCEPTED","payload",Map.of("revision","retained")))));
                    }catch(Exception invalid){throw new AssertionError(invalid);}
                }
                @Override public void onClosing(WebSocket socket,int code,String reason){socket.close(code,reason);}
            }));
            var receipt=UUID.randomUUID().toString();
            var client=new HttpServerClient(server.url("/").toString(),"test-access");
            assertEquals(Map.of("revision","retained"),client.information("upload",Map.of("scope",Map.of("kind","personal"),"requestId",receipt,"name","paper","text","Exact source.")));
            var upgrade=server.takeRequest();
            assertTrue(upgrade.getPath().startsWith("/v1/events?session="));
            assertEquals("Bearer test-access",upgrade.getHeader("Authorization"));
            var frame=JSON.readTree(received.get());assertEquals("information.upload",frame.path("type").asText());
            assertEquals(receipt,frame.path("payload").path("requestId").asText());
            assertEquals(1,server.getRequestCount());
        }
    }
    @Test void refusals_remain_refusals_and_disconnect_never_replays_a_mutation() throws Exception {
        for(boolean refusal:new boolean[]{true,false}) try(var server=new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket socket,String text) {
                    try {
                        if(refusal)socket.send(JSON.writeValueAsString(Map.of("id",JSON.readTree(text).path("id").asText(),"protocol_version","plowshare-v1","payload",Map.of("code","BAD_REQUEST","said","Input permission revoked."))));
                        else socket.close(1000,"uncertain delivery");
                    }catch(Exception invalid){throw new AssertionError(invalid);}
                }
                @Override public void onClosing(WebSocket socket,int code,String reason){socket.close(code,reason);}
            }));
            var client=new HttpServerClient(server.url("/").toString());
            if(refusal)assertEquals("the Plowshare server answered 422: Input permission revoked.",assertThrows(ServerClient.ServerError.class,()->client.information("finalise",Map.of("requestId",UUID.randomUUID().toString()))).getMessage());
            else assertTrue(assertThrows(java.io.IOException.class,()->client.information("finalise",Map.of("requestId",UUID.randomUUID().toString()))).getMessage().contains("keep requestId"));
            assertEquals(1,server.getRequestCount());
        }
    }
}
