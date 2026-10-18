package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.EntryRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalProjectionTest {
  private EntryRecord entry(int ordinal, EntryKind kind, String text) {
    return new EntryRecord(
        "cnv_1",
        ordinal,
        kind,
        text,
        kind == EntryKind.TOOL_RESULT ? "call_1" : null,
        kind == EntryKind.ANSWER ? List.of(new ToolCall("call_1", "run", "{}")) : List.of(),
        null,
        null,
        1,
        null,
        null,
        null,
        null);
  }

  @Test
  void a_recovered_result_replaces_the_wait_in_its_original_call_slot() {
    var projected =
        Projection.of(
            ApprovalProjection.settledResults(
                List.of(
                    entry(1, EntryKind.ANSWER, ""),
                    entry(2, EntryKind.TOOL_RESULT, "Waiting for a person"),
                    entry(3, EntryKind.DIAGNOSTIC, "Command approved; harness audit only"),
                    entry(4, EntryKind.TOOL_RESULT, "exit 0"))),
            false);
    assertEquals(2, projected.size());
    assertEquals("exit 0", projected.getLast().content());
    assertEquals("call_1", projected.getLast().toolCallId());
    assertFalse(projected.stream().anyMatch(message -> message.content().contains("approved")));
  }

  @Test
  void reusing_a_call_id_in_a_later_request_preserves_both_results() {
    var result =
        ApprovalProjection.settledResults(
            List.of(
                entry(1, EntryKind.ANSWER, ""), entry(2, EntryKind.TOOL_RESULT, "first"),
                entry(3, EntryKind.ANSWER, ""), entry(4, EntryKind.TOOL_RESULT, "second")));
    assertEquals(
        List.of("first", "second"),
        result.stream()
            .filter(row -> row.kind() == EntryKind.TOOL_RESULT)
            .map(EntryRecord::content)
            .toList());
  }
}
