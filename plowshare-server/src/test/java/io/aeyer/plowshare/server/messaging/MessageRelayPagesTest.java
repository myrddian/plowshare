package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageRelayPagesTest {
  private final MessageIngress messages = mock(MessageIngress.class);
  private final MessageRelayRepository repository = mock(MessageRelayRepository.class);
  private final MessageRelayPages pages =
      new MessageRelayPages(messages, repository, UnitOfWork.NONE);
  private static final UUID TASK = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  private MessageIngress.Task view(long revision) {
    return new MessageIngress.Task(
        TASK,
        TASK,
        TASK,
        revision,
        "worker",
        "bdm_Request",
        "COMPLETED",
        "ANSWERED",
        null,
        List.of(),
        AT);
  }

  private MessageRelayRepository.Chunk chunk(long seq, String id, String body, int length) {
    return new MessageRelayRepository.Chunk(seq, id, body, length, true, false, "ANSWERED", AT);
  }

  @Test
  void slices_supplementary_unicode_then_resumes_only_after_the_captured_head() {
    when(repository.head("bdm_Request")).thenReturn(17L);
    when(repository.chunks("bdm_Request", 17, 0, 0))
        .thenReturn(List.of(chunk(17, "bdm_Answer", "😀".repeat(16384), 16387)));
    var initial = pages.admitted(view(7), "client", TASK, "receive-event");
    var cursor = MessageRelayPayload.Cursor.read(initial.next());
    assertEquals(16384, cursor.offset());
    assertEquals(17, cursor.through());
    assertNull(initial.resume());
    when(messages.summary("alice", "project", "channel", "client", TASK)).thenReturn(view(8));
    when(repository.chunks("bdm_Request", 17, 17, 16384))
        .thenReturn(List.of(chunk(17, "bdm_Answer", " \n\t", 16387)));
    var family =
        new MessageRelayRepository.Family(
            "channel",
            "alice",
            "project",
            9,
            "sdk:alice",
            "commands",
            "subscriber",
            TASK,
            "shared",
            null,
            "worker",
            "results",
            TASK);
    var last = pages.snapshot(family, "client", TASK, TASK, "inspect-event", initial.next());
    assertEquals("8", last.revision());
    assertEquals("16384", last.task().replies().getFirst().offset());
    assertEquals(" \n\t", last.task().replies().getFirst().body());
    assertNull(last.next());
    assertEquals(17, MessageRelayPayload.Cursor.read(last.resume()).sequence());
    verify(repository).head("bdm_Request");
    when(repository.head("bdm_Request")).thenReturn(23L);
    when(repository.chunks("bdm_Request", 23, 17, 0))
        .thenReturn(List.of(chunk(23, "bdm_New", "later", 5)));
    var followed = pages.snapshot(family, "client", TASK, TASK, "follow-event", last.resume());
    assertEquals(
        List.of("bdm_New"),
        followed.task().replies().stream().map(MessageRelayPayload.Reply::id).toList());
    verify(messages, never()).task("alice", "project", "channel", "client", TASK);
  }

  @Test
  void foreign_task_cursor_and_missing_or_out_of_range_history_are_refused() {
    when(messages.summary("alice", "project", "channel", "client", TASK)).thenReturn(view(1));
    var family =
        new MessageRelayRepository.Family(
            "channel",
            "alice",
            "project",
            9,
            "sdk:alice",
            "commands",
            "subscriber",
            TASK,
            "shared",
            null,
            "worker",
            "results",
            TASK);
    String foreign = new MessageRelayPayload.Cursor(UUID.randomUUID(), 1, 0, 0, false).encode();
    assertThrows(
        MessageRefused.class,
        () -> pages.snapshot(family, "client", TASK, TASK, "inspect", foreign));
    String missing = new MessageRelayPayload.Cursor(TASK, 20, 19, 0, false).encode();
    when(repository.chunks("bdm_Request", 20, 19, 0)).thenReturn(List.of());
    assertThrows(
        MessageRefused.class,
        () -> pages.snapshot(family, "client", TASK, TASK, "inspect", missing));
    verify(repository, never()).head("bdm_Request");
  }
}
