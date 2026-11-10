package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Orchestration.CheckpointRepair;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportRepairEffectsTest {
  private static final String CONVERSATION = "cnv_repair";
  private static final UUID EVIDENCE = UUID.randomUUID();
  private static final int SEQUENCE = 31;
  private static final UUID REQUEST =
      UUID.nameUUIDFromBytes((CONVERSATION + ":" + SEQUENCE).getBytes(StandardCharsets.UTF_8));
  private static final ScriptStore.Repair REPAIR =
      new ScriptStore.Repair(
          new CheckpointRepair(30, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)),
          Set.of(EVIDENCE));

  @Test
  void only_advances_one_locked_stage_from_its_current_status() {
    var todo =
        new TodoItem(
            "stage",
            CONVERSATION,
            null,
            0,
            "Review",
            TodoStatus.IN_PROGRESS,
            null,
            true,
            "review",
            Instant.EPOCH);
    var done =
        new ScriptStore.Tool(
            "todo_write",
            """
        {"ops":[{"op":"update","id":"stage","status":"done","summary":"Retained review checked"}]}
        """,
            false);
    assertEquals(ReportRepairEffects.Effect.STAGE, validate(done, List.of(todo)));
    assertThrows(
        IllegalStateException.class,
        () -> validate(done, List.of(todo.withStatus(TodoStatus.DONE, Instant.EPOCH))));
    assertThrows(IllegalStateException.class, () -> validate(done, List.of()));
    assertThrows(
        IllegalStateException.class,
        () ->
            validate(
                new ScriptStore.Tool(
                    "todo_write", done.arguments().replace("\"summary\"", "\"text\""), false),
                List.of(todo)));
  }

  @Test
  void cannot_delegate_scan_change_evidence_or_reuse_a_request() {
    assertEquals(ReportRepairEffects.Effect.DRAFT, validate(report(EVIDENCE, REQUEST), List.of()));
    assertThrows(
        IllegalStateException.class, () -> validate(report(UUID.randomUUID(), REQUEST), List.of()));
    assertThrows(
        IllegalStateException.class,
        () -> validate(report(EVIDENCE, UUID.randomUUID()), List.of()));
    for (String tool :
        List.of("agent_run", "network_scan_request", "information_read", "model_infer"))
      assertThrows(
          IllegalStateException.class,
          () -> validate(new ScriptStore.Tool(tool, "{}", false), List.of()));
  }

  private static ScriptStore.Tool report(UUID revision, UUID request) {
    return new ScriptStore.Tool(
        "information_write",
        """
        {"operation":"report","name":"Draft","text":"Assessment","inputs":["%s"],"requestId":"%s"}
        """
            .formatted(revision, request),
        false);
  }

  private static ReportRepairEffects.Effect validate(ScriptStore.Tool tool, List<TodoItem> todos) {
    return ReportRepairEffects.validate(tool, CONVERSATION, SEQUENCE, todos, REPAIR);
  }
}
