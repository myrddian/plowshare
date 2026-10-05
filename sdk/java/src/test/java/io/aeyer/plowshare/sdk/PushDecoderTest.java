package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.AccountEvent;
import java.io.IOException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PushDecoderTest {
  static Stream<String> malformedPushes() {
    return Stream.of(
        "{\"kind\":\"inbox.changed\"}",
        "{\"kind\":\"orchestration.resumed\",\"orchestration\":\"orc_one\"}",
        "{\"kind\":\"orchestration.resumed\",\"orchestration\":\"orc_one\",\"requestId\":\"bad\"}",
        "{\"kind\":\"orchestration.resumed\",\"requestId\":\"00000000-0000-0000-0000-000000000001\"}",
        "{\"kind\":\"inbox.changed\",\"unread\":\"1\"}",
        "{\"kind\":\"inbox.changed\",\"unread\":-1}",
        "{\"kind\":\"information.changed\",\"revision\":\"bad\",\"sequence\":1,\"generation\":1}",
        "{\"kind\":\"todos.changed\",\"conversation\":\"cnv\\nforged\"}",
        "{\"protocol_version\":\"different\",\"type\":\"inbox.changed\",\"payload\":{\"unread\":1}}",
        "{\"job\":\"job_1\",\"part\":\"UNKNOWN\",\"text\":\"delta\"}");
  }

  @ParameterizedTest
  @MethodSource("malformedPushes")
  void malformed_notifications_are_refused_before_callbacks_can_receive_them(String input)
      throws Exception {
    var json = SdkJson.mapper();
    assertThrows(IOException.class, () -> PushDecoder.decode(json, json.readTree(input)));
  }

  @Test
  void resumed_notification_reaches_the_typed_listener_with_its_durable_request_key()
      throws Exception {
    var json = SdkJson.mapper();
    var requestId = UUID.randomUUID();
    var event = new AccountEvent.OrchestrationResumed("orc_one", requestId);
    assertEquals(event, PushDecoder.decode(json, json.valueToTree(event)));
    assertEquals("orchestration.resumed", event.kind());
  }

  @Test
  void accepted_notification_is_explicit_and_immutable() throws Exception {
    var json = SdkJson.mapper();
    assertEquals(
        new AccountEvent.InboxChanged(2),
        PushDecoder.decode(json, json.readTree("{\"kind\":\"inbox.changed\",\"unread\":2}")));
  }
}
