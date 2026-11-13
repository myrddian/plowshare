package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aeyer.plowshare.a2a.A2aClient;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A real public SDK socket and an independent A2A HTTP fixture; no server classes or database. */
class RelaySdkTest {
  @TempDir Path directory;

  @Test
  void dispatches_through_public_relay_operations_to_an_independent_a2a_peer() throws Exception {
    var json =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    var gateway = new RelayAdapterTest.FakeGateway();
    var command = RelayAdapterTest.send();
    gateway.sources.add(RelayAdapterTest.event(command));
    var operations = new ArrayList<String>();
    try (var broker = new MockWebServer();
        var remote = new MockWebServer()) {
      remote.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              try {
                assertEquals("Bearer peer-secret", request.getHeader("Authorization"));
                if (request.getPath().equals("/.well-known/agent-card.json"))
                  return new MockResponse()
                      .setBody(
                          """
              {"name":"Peer","description":"Independent fixture","version":"1","supportedInterfaces":[{"url":"ENDPOINT","protocolBinding":"JSONRPC","protocolVersion":"1.0"}],"capabilities":{},"defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[]}
              """
                              .replace("ENDPOINT", remote.url("/rpc").toString()));
                var rpc = json.readTree(request.getBody().readUtf8());
                assertEquals("SendMessage", rpc.path("method").textValue());
                assertEquals("1.0", request.getHeader("A2A-Version"));
                var message = rpc.path("params").path("message");
                assertEquals(command.requestId().toString(), message.path("messageId").textValue());
                assertEquals("ROLE_USER", message.path("role").textValue());
                assertEquals("  question\n", message.path("parts").get(0).path("text").textValue());
                var response = json.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", rpc.get("id"));
                var task = response.putObject("result").putObject("task");
                task.put("id", "remote-task");
                task.put("contextId", "remote-context");
                task.putObject("status").put("state", "TASK_STATE_COMPLETED");
                var artifact = task.putArray("artifacts").addObject();
                artifact.put("artifactId", "stable-result");
                artifact.putArray("parts").addObject().put("text", "Remote answer");
                return new MockResponse().setBody(json.writeValueAsString(response));
              } catch (IOException invalid) {
                throw new AssertionError(invalid);
              }
            }
          });
      broker.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var request = json.readTree(text);
                        String operation = request.path("type").textValue();
                        operations.add(operation);
                        var body = request.get("payload");
                        var response = json.createObjectNode();
                        response.set("id", request.get("id"));
                        response.put("type", operation);
                        response.put("protocol_version", "plowshare-v1");
                        var outcome = response.putObject("payload");
                        outcome.put("code", "OK");
                        switch (operation) {
                          case "relay.consume" ->
                              outcome.set(
                                  "payload",
                                  json.valueToTree(
                                      gateway.consume(
                                          json.treeToValue(body, RelayPort.Consume.class))));
                          case "relay.publish" ->
                              outcome.set(
                                  "payload",
                                  json.valueToTree(
                                      gateway.publish(
                                          json.treeToValue(body, RelayPort.Publish.class))));
                          case "relay.ack" ->
                              outcome.set(
                                  "payload",
                                  json.valueToTree(
                                      gateway.acknowledge(
                                          json.treeToValue(body, RelayPort.Ack.class))));
                          default -> throw new AssertionError("Unexpected operation: " + operation);
                        }
                        socket.send(json.writeValueAsString(response));
                      } catch (IOException invalid) {
                        throw new AssertionError(invalid);
                      }
                    }
                  }));
      try (var connection =
              Plowshare.connect(
                  broker.url("/").toString(),
                  "fixture-token",
                  UUID.randomUUID().toString(),
                  Duration.ofSeconds(3),
                  null,
                  Plowshare.TransportMode.LEGACY,
                  Plowshare.DispatchMode.ORDERED);
          var peer =
              new A2aClient(remote.url("/rpc").toString(), "peer-secret", Duration.ofSeconds(3));
          var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
        var relay = new RelayClient(connection);
        var sdk =
            new RelayGateway() {
              public RelayPort.Batch consume(RelayPort.Consume request) throws IOException {
                return relay.consume(request);
              }

              public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
                return relay.publish(request);
              }

              public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
                return relay.acknowledge(request);
              }

              public RelayLog.Page log(RelayLog.Query request) throws IOException {
                return relay.log(request);
              }
            };
        var adapter =
            new RelayAdapter(
                RelayAdapterTest.BINDING,
                sdk,
                journal,
                Map.of("peer", peer),
                () -> RelayAdapterTest.TIME);
        assertTrue(adapter.tick());
        assertFalse(adapter.tick());
        assertEquals(
            List.of("relay.consume", "relay.publish", "relay.ack", "relay.consume"), operations);
        assertEquals(2, remote.getRequestCount());
        assertTrue(gateway.publications.getFirst().text().contains("stable-result"));
        assertEquals(
            "COMPLETED", journal.find(command.requestId()).orElseThrow().observation().state());
      }
    }
  }
}
