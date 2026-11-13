package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.a2a.model.ExternalResult;
import io.aeyer.plowshare.protocol.RelayPort;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Package ownership changes must not change existing wire identities or durable observations. */
class RelayModelCompatibilityTest {
  private static final String REQUEST =
      """
      {"schema":"plowshare-a2a-dispatch/1","requestId":"11111111-1111-1111-1111-111111111111","peer":"peer","action":"SEND","message":{"parts":[{"text":"  question\\n"}]},"task":null,"context":null}
      """
          .strip();
  private static final String HASH =
      "f017f29b47dca564b725619006ef787c64f4bf032efb3db04ba1c43c789d6a16";
  private static final String RESULT =
      """
      {"task":{"id":"remote-task","contextId":"remote-context","status":{"state":"TASK_STATE_COMPLETED","timestamp":"2026-11-13T00:00:00Z"},"artifacts":[{"artifactId":"report","parts":[{"text":"  exact result\\n"}]}]}}
      """
          .strip();

  @Test
  void original_request_json_and_hash_stay_exact_after_rehome() throws Exception {
    assertEquals(REQUEST, RelayWorkCodec.writeRequest(RelayWorkCodec.request(REQUEST)));
    assertEquals(
        HASH, RelayPort.hash(RelayWorkCodec.writeRequest(RelayWorkCodec.request(REQUEST))));
  }

  @Test
  void v1_journal_reopens_with_original_hash_remote_ids_and_full_typed_result() throws Exception {
    String id = "11111111-1111-1111-1111-111111111111";
    String wire =
        """
        {"schema":"plowshare-a2a-journal/1","owner":"%s","generation":"22222222-2222-2222-2222-222222222222","entries":{"%s":{
        "requestId":"%s","hash":"%s","sourceEvent":"original-event","producer":"sdk:producer","peer":"peer","action":"SEND","task":null,"context":null,
        "phase":"CONFIRMED","observation":{"state":"COMPLETED","task":"remote-task","context":"remote-context","result":%s,"error":null},"occurredAt":"2026-11-13T00:00:00Z"}}}
        """
            .formatted("a".repeat(64), id, id, HASH, RESULT);
    var retained = RelayWorkCodec.snapshot(wire.getBytes(StandardCharsets.UTF_8));
    var entry = retained.entries().get(id);
    assertEquals(UUID.fromString(id), entry.requestId());
    assertEquals(HASH, entry.hash());
    assertEquals(RelayWork.Phase.CONFIRMED, entry.phase());
    var result = assertInstanceOf(ExternalResult.TaskResult.class, entry.observation().result());
    assertEquals("remote-task", result.task().id());
    assertEquals("remote-context", result.task().contextId());
    assertEquals(
        "  exact result\n", result.task().artifacts().getFirst().parts().getFirst().text());
    assertEquals(retained, RelayWorkCodec.snapshot(RelayWorkCodec.snapshot(retained)));
    var json = new ObjectMapper();
    assertEquals(
        json.readTree(RESULT),
        json.readTree(RelayWorkCodec.writeReport(entry.report())).get("observation").get("result"));
  }
}
