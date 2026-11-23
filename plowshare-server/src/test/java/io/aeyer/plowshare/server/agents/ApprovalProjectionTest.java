package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.WebEvidenceRelease;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalProjectionTest {
  private EntryRecord entry(int ordinal, EntryKind kind, String text) {
    return entry(ordinal, kind, text, "run");
  }

  private EntryRecord entry(int ordinal, EntryKind kind, String text, String tool) {
    return new EntryRecord(
        "cnv_1",
        ordinal,
        kind,
        text,
        kind == EntryKind.TOOL_RESULT ? "call_1" : null,
        kind == EntryKind.ANSWER ? List.of(new ToolCall("call_1", tool, "{}")) : List.of(),
        null,
        null,
        1,
        null,
        null,
        null,
        null);
  }

  @Test
  void release_receipts_follow_the_declaring_batch_even_when_the_call_id_and_text_match() {
    String text = "Recorded legal evidence";
    var secondRelease =
        new WebEvidenceRelease(
            "call_1",
            WebEvidenceRelease.digest(text),
            "prompt_injection_jailbreak",
            Verb.FETCH,
            "second policy",
            Instant.now().plusSeconds(60));
    var messages =
        Projection.of(
            ApprovalProjection.settledResults(
                List.of(
                    entry(1, EntryKind.ANSWER, "", FetchTool.NAME),
                    entry(2, EntryKind.TOOL_RESULT, text),
                    entry(3, EntryKind.ANSWER, "", FetchTool.NAME),
                    entry(4, EntryKind.TOOL_RESULT, "Waiting"),
                    entry(5, EntryKind.DIAGNOSTIC, WebReleaseAudit.encode(secondRelease)),
                    entry(6, EntryKind.TOOL_RESULT, text))),
            false);

    var results =
        messages.stream().filter(message -> message.role() == ChatMessage.Role.TOOL).toList();
    assertEquals(List.of(text, text), results.stream().map(ChatMessage::content).toList());
    assertNull(results.getFirst().webRelease());
    assertEquals("second policy", results.getLast().webRelease().policy());
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

  @Test
  void a_late_journaled_result_returns_to_its_own_batch_after_an_id_is_reused() {
    var first = entry(1, EntryKind.ANSWER, "");
    var second = entry(2, EntryKind.ANSWER, "");
    var laterResult = entry(3, EntryKind.TOOL_RESULT, "Later result");
    var recovered =
        new EntryRecord(
            first.conversationId(),
            4,
            EntryKind.TOOL_RESULT,
            "Recovered result",
            "call_1",
            List.of(),
            null,
            null,
            2,
            null,
            null,
            null,
            null,
            null,
            null,
            first.ordinal());
    var messages =
        Projection.of(
            ApprovalProjection.settledResults(List.of(first, second, laterResult, recovered)),
            false);
    assertEquals(
        List.of("Recovered result", "Later result"),
        messages.stream()
            .filter(message -> message.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .toList());
  }
}
