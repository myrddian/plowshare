package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.a2a.IngressResponseTooLarge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Private storage accepts native bodies independently from the deliberately smaller HTTP view. */
class IngressReplyStorageTest {
  @TempDir Path directory;
  private static final UUID TASK = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID CONTEXT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID REQUEST = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final Instant AT = Instant.parse("2026-11-13T00:00:00Z");

  private FileIngressJournal open() throws IOException {
    return new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64));
  }

  private IngressWire.Task page(IngressWire.Chunk chunk) {
    return new IngressWire.Task(
        TASK.toString(),
        CONTEXT.toString(),
        REQUEST.toString(),
        "worker",
        "bdm_Request",
        "WORKING",
        null,
        List.of(chunk),
        AT);
  }

  private IngressWire.Chunk chunk(String body, int offset, boolean complete) {
    return new IngressWire.Chunk(
        "bdm_Large", body, Integer.toString(offset), complete, false, true, null, AT);
  }

  private IngressJournal.Projection store(FileIngressJournal journal, String body)
      throws IOException {
    int count = body.codePointCount(0, body.length());
    for (int offset = 0; offset < count; ) {
      int next = Math.min(count, offset + 16384);
      String text =
          body.substring(body.offsetByCodePoints(0, offset), body.offsetByCodePoints(0, next));
      journal.project("client", 1, page(chunk(text, offset, next == count)));
      offset = next;
    }
    return journal.checkpoint(journal.projection(TASK).orElseThrow(), null, "complete-body", false);
  }

  private Path replyFile() throws IOException {
    try (var paths = Files.walk(directory.resolve("ingress-replies"))) {
      var files = paths.filter(Files::isRegularFile).toList();
      assertEquals(1, files.size());
      return files.getFirst();
    }
  }

  @Test
  void oversized_ascii_is_durable_before_http_refusal_and_remains_lossless_after_reopen()
      throws Exception {
    String body = "a".repeat(1048577);
    try (var journal = open()) {
      var view = store(journal, body);
      assertThrows(IngressResponseTooLarge.class, () -> journal.replies(view, 1));
      assertTrue(view.incomplete().isEmpty());
      assertEquals("complete-body", view.resume());
    }
    try (var journal = open()) {
      var view = journal.projection(TASK).orElseThrow();
      assertEquals("complete-body", view.resume());
      assertThrows(IngressResponseTooLarge.class, () -> journal.replies(view, 1));
      byte[] bytes = Files.readAllBytes(replyFile());
      assertArrayEquals(
          body.getBytes(StandardCharsets.UTF_8),
          Arrays.copyOfRange(bytes, bytes.length - body.length(), bytes.length));
    }
  }

  @Test
  void unicode_and_worst_case_json_escaping_are_raw_bounded_evidence_not_a_json_body_limit()
      throws Exception {
    String body = "\u0001".repeat(1048577) + "🙂\"\\\n";
    byte[] original = body.getBytes(StandardCharsets.UTF_8);
    try (var journal = open()) {
      var view = store(journal, body);
      assertThrows(IngressResponseTooLarge.class, () -> journal.replies(view, 1));
    }
    try (var journal = open()) {
      var view = journal.projection(TASK).orElseThrow();
      assertEquals("complete-body", view.resume());
      assertThrows(IngressResponseTooLarge.class, () -> journal.replies(view, 1));
      byte[] file = Files.readAllBytes(replyFile());
      assertTrue(
          file.length < original.length + 65536,
          "Body escaping must not expand the private container sixfold");
      assertArrayEquals(
          original, Arrays.copyOfRange(file, file.length - original.length, file.length));
    }
  }

  @Test
  void whitespace_prefix_and_legacy_json_remain_readable_and_a_real_append_updates_the_container()
      throws Exception {
    String prefix = " ".repeat(16384);
    Path file;
    try (var journal = open()) {
      journal.project("client", 1, page(chunk(prefix, 0, false)));
      file = replyFile();
      record Legacy(UUID task, String client, long ordinal, IngressJournal.Assembly value) {}
      Files.writeString(
          file,
          IngressCodec.write(
              new Legacy(
                  TASK,
                  "client",
                  1,
                  new IngressJournal.Assembly("bdm_Large", prefix, false, false, true, null, AT))));
    }
    try (var journal = open()) {
      journal.project("client", 1, page(chunk("answer", 16384, true)));
      var view =
          journal.checkpoint(journal.projection(TASK).orElseThrow(), null, "complete-body", false);
      assertEquals(prefix + "answer", journal.replies(view, 1).getFirst().body());
      byte[] bytes = Files.readAllBytes(file);
      byte[] magic = "PLOWSHARE-INGRESS-REPLY/1\n".getBytes(StandardCharsets.US_ASCII);
      assertArrayEquals(magic, Arrays.copyOf(bytes, magic.length));
    }
  }

  @Test
  void corrupt_or_truncated_private_body_is_not_misclassified_as_an_http_size_refusal()
      throws Exception {
    try (var journal = open()) {
      store(journal, "a".repeat(1048577));
    }
    Path file = replyFile();
    byte[] original = Files.readAllBytes(file);
    byte[] corrupt = original.clone();
    corrupt[corrupt.length - 1] = 'b';
    for (var changed : List.of(corrupt, Arrays.copyOf(original, original.length - 1))) {
      Files.write(file, changed);
      try (var journal = open()) {
        var view = journal.projection(TASK).orElseThrow();
        var failure = assertThrows(IOException.class, () -> journal.replies(view, 1));
        assertFalse(failure instanceof IngressResponseTooLarge);
        assertEquals("Corrupt retained reply body", failure.getMessage());
      }
    }
  }
}
