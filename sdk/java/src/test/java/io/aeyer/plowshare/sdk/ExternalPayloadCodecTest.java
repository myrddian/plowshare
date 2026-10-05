package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ExternalMessage;
import io.aeyer.plowshare.protocol.ExternalResult;
import io.aeyer.plowshare.protocol.IntegrationPayload;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalPayloadCodecTest {
  @Test
  void declared_integration_arguments_are_immutable_typed_values_and_keep_the_wire_shape()
      throws Exception {
    String wire =
        """
        {"parts":[{"data":{"schema":"plowshare-integration/1","binding":"house",
        "operation":"actions.execute","arguments":{"action":"notify",
        "parameters":{"message":"hello","volume":0.25,"enabled":true}}}}]}
        """;
    ExternalMessage message = ExternalPayloadCodec.message(wire);
    var parameters = message.parts().getFirst().data().arguments().parameters();
    assertInstanceOf(IntegrationPayload.TextParameter.class, parameters.get("message"));
    assertInstanceOf(IntegrationPayload.NumberParameter.class, parameters.get("volume"));
    assertInstanceOf(IntegrationPayload.BooleanParameter.class, parameters.get("enabled"));
    assertThrows(UnsupportedOperationException.class, parameters::clear);
    var json = new ObjectMapper();
    assertEquals(json.readTree(wire), json.readTree(json.writeValueAsString(message)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"parts\":[]}",
        "{\"parts\":[{\"text\":12}]}",
        "{\"parts\":[{\"text\":\"one\",\"url\":\"https://example.test\"}]}",
        "{\"parts\":[{\"text\":null}]}",
        "{\"parts\":[{\"raw\":\"%%%\"}]}",
        "{\"parts\":[{\"url\":\"file:///private/data\"}]}",
        "{\"parts\":[{\"url\":\"https://user:secret@example.test\"}]}",
        "{\"parts\":[{\"text\":\"ok\",\"filename\":\"../secret\"}]}",
        "{\"parts\":[{\"data\":{\"schema\":\"unknown/1\"}}]}",
        "{\"parts\":[{\"data\":[1,2,3]}]}",
        "{\"parts\":[{\"text\":\"ok\",\"unknown\":true}]}",
        "{\"parts\":[{\"text\":\"one\",\"text\":\"two\"}]}",
        "{\"parts\":[{\"text\":\"ok\"}]} {}"
      })
  void malformed_or_unregistered_parts_are_refused_before_use(String wire) {
    assertThrows(IOException.class, () -> ExternalPayloadCodec.message(wire));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"task\":{\"id\":\"t\",\"contextId\":\"c\",\"status\":{\"state\":\"unknown\"}}}",
        "{\"task\":{\"id\":\"t\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}",
        "{\"task\":{\"id\":\"t\",\"contextId\":\"c\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},\"history\":[{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"contextId\":\"foreign\",\"parts\":[{\"text\":\"x\"}]}]}}",
        "{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"x\"}]}}",
        "{\"acknowledged\":\"true\"}",
        "{\"acknowledged\":true,\"verification\":\"proved\"}",
        "{\"acknowledged\":true,\"response\":{\"arbitrary\":\"object\"}}",
        "{\"states\":{},\"acknowledged\":true}",
        "{\"unregisteredResult\":{}}",
        "{\"task\":{\"id\":\"t\",\"contextId\":\"c\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\",\"timestamp\":1}}}",
        "{\"task\":{\"id\":\"t\",\"contextId\":\"c\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\",\"timestamp\":1.5}}}"
      })
  void invalid_result_members_and_unregistered_results_are_refused(String wire) {
    assertThrows(IOException.class, () -> ExternalPayloadCodec.result(wire));
  }

  @Test
  void a_remote_task_is_typed_and_preserves_its_observed_state() throws Exception {
    var result =
        ExternalPayloadCodec.result(
            """
        {"task":{"id":"task","contextId":"context","status":{"state":"TASK_STATE_COMPLETED"},
        "artifacts":[{"artifactId":"report","parts":[{"url":"https://example.test/report","mediaType":"text/plain"}]}]}}
        """);
    var task = assertInstanceOf(ExternalResult.TaskResult.class, result).task();
    assertEquals("TASK_STATE_COMPLETED", task.status().state());
    assertEquals("text/plain", task.artifacts().getFirst().parts().getFirst().mediaType());
  }
}
