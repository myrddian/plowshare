package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Exercises the public WS facade so malformed replies cannot be mistaken for durable success. */
class SdkContractsTest {
  private static final ObjectMapper JSON = SdkJson.mapper();

  static Stream<String> invalidJobs() {
    return Stream.of(
        "{}",
        "{\"id\":\"job_1\",\"agent\":\"worker\",\"state\":\"INVALID_STATE\",\"cancelRequested\":false}",
        "{\"id\":\"job_1\",\"agent\":\"worker\",\"state\":\"RUNNING\",\"cancelRequested\":\"false\"}",
        "{\"id\":\"job_1\",\"agent\":\"worker\",\"state\":\"RUNNING\"}",
        "{\"id\":5,\"agent\":\"worker\",\"state\":\"RUNNING\",\"cancelRequested\":false}",
        "{\"id\":\"job_1\",\"agent\":\"worker\",\"state\":\"RUNNING\",\"cancelRequested\":false,\"limits\":{\"noTurnCap\":false,\"noBudget\":false,\"modelCallsSpent\":-7}}",
        "{\"id\":\"job_1\",\"agent\":\"worker\",\"state\":\"RUNNING\",\"cancelRequested\":false,\"limits\":{\"noTurnCap\":false,\"noBudget\":false,\"modelCallsSpent\":1.5}}");
  }

  @ParameterizedTest
  @MethodSource("invalidJobs")
  void malformed_job_reply_is_invalid_without_replaying_the_request(String payload)
      throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      try {
                        var request = JSON.readTree(text);
                        ws.send(
                            "{\"id\":\""
                                + request.get("id").textValue()
                                + "\",\"type\":\"job.status\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"code\":\"OK\",\"payload\":"
                                + payload
                                + "}}");
                      } catch (IOException invalid) {
                        throw new AssertionError(invalid);
                      }
                    }
                  }));
      try (var client =
          new WsServerClient(
              server.url("/").toString(), "fixture", Plowshare.TransportMode.LEGACY)) {
        var failure = assertThrows(Plowshare.TransportException.class, () -> client.job("job_1"));
        assertEquals(Plowshare.Delivery.INVALID_RESPONSE, failure.delivery());
        assertEquals(1, server.getRequestCount());
      }
    }
  }

  @Test
  void optional_feature_flags_in_older_views_have_explicit_defaults() throws Exception {
    var json = SdkJson.mapper();
    var conversation =
        SdkJson.decode(
            json,
            json.readTree("{\"id\":\"cnv_fixture\",\"maxModelCalls\":12}"),
            ServerClient.Conversation.class);
    org.junit.jupiter.api.Assertions.assertFalse(conversation.noBudget());
    var outcome =
        SdkJson.decode(
            json,
            json.readTree(
                "{\"ending\":\"ANSWERED\",\"answered\":true,\"text\":\"done\",\"steps\":1,\"modelCalls\":1}"),
            ServerClient.RunOutcome.class);
    org.junit.jupiter.api.Assertions.assertFalse(outcome.resumable());
  }

  @Test
  void valid_nullable_limits_and_future_fields_are_preserved_by_contract() throws Exception {
    var reply =
        SdkJson.decode(
            JSON,
            JSON.readTree(
                """
        {"id":"job_1","agent":"worker","state":"RUNNING","cancelRequested":false,
         "limits":{"maxTurns":null,"noTurnCap":true,"maxModelCalls":null,"noBudget":true,"modelCallsSpent":0},
         "futureField":{"newCapability":true}}
        """),
            ServerClient.JobStatus.class);
    assertNull(reply.limits().maxTurns());
    assertTrue(reply.limits().noBudget());
  }

  @Test
  void dto_collections_are_immutable_and_counts_are_checked_for_direct_callers() {
    var original = new ArrayList<String>(List.of("memory_1"));
    var navigation = new ServerClient.Navigation("memory", original, "found", true, 1);
    original.clear();
    assertEquals(List.of("memory_1"), navigation.ids());
    assertThrows(UnsupportedOperationException.class, () -> navigation.ids().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServerClient.Navigation("memory", List.of(), "", true, -1));
  }

  @Test
  void duplicate_fields_and_trailing_values_are_rejected() {
    assertThrows(IOException.class, () -> JSON.readTree("{\"id\":\"one\",\"id\":\"two\"}"));
    assertThrows(IOException.class, () -> JSON.readTree("{} {}"));
  }
}
