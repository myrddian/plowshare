package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PacingTest {
  @Test
  void runaway_capture_keeps_head_tail_counts_and_timing_with_bounded_memory() {
    AtomicLong ticker = new AtomicLong();
    Pacing.Call call = new Pacing(ticker::get).sending();
    Deltas sink = call.timing(Deltas.DISCARDING);
    ticker.set(5_000_000);
    sink.thought("initial plan:");
    for (int i = 0; i < 100; i++) sink.thought("repeating ".repeat(1000));
    sink.thought("final repetition");
    sink.answered("partial response");
    ticker.set(600_000_000_000L);
    String diagnostic = call.failedOutput("completion deadline exceeded");
    assertTrue(diagnostic.contains("initial plan:"));
    assertTrue(diagnostic.contains("final repetition"));
    assertTrue(diagnostic.contains("characters omitted"));
    assertTrue(diagnostic.contains("reasoning_characters=1000029"));
    assertTrue(diagnostic.contains("first_delta_ms=5"));
    assertTrue(diagnostic.contains("elapsed_ms=600000"));
    assertTrue(diagnostic.length() < 18000);
  }

  @Test
  void every_failed_diagnostic_part_fits_trajectory_and_preserves_final_tail_and_tools() {
    Pacing.Call call = new Pacing(() -> 0).sending();
    Deltas sink = call.timing(Deltas.DISCARDING);
    sink.thought("initial plan:" + "🔁".repeat(30_000) + "final reasoning loop!");
    sink.answered("start answer" + "x".repeat(30_000) + "final answer fragment");
    sink.toolCall(0, "read", "start args" + "y".repeat(30_000) + "final tool fragment");
    var parts = call.failedOutputParts("deadline");
    assertTrue(parts.size() <= 7);
    assertTrue(parts.size() > 1);
    for (int index = 0; index < parts.size(); index++) {
      String part = parts.get(index);
      assertTrue(part.length() <= EntryStore.MOST_CHARACTERS_PER_ENTRY);
      assertTrue(part.startsWith("ERROR: incomplete model call; diagnostic part " + (index + 1)));
      assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
      for (int at = 0; at < part.length(); at++) {
        if (Character.isHighSurrogate(part.charAt(at))) {
          assertTrue(at + 1 < part.length() && Character.isLowSurrogate(part.charAt(at + 1)));
          at++;
        } else assertFalse(Character.isLowSurrogate(part.charAt(at)));
      }
    }
    String combined = String.join("", parts);
    assertTrue(combined.contains("initial plan"));
    assertTrue(combined.contains("final reasoning loop"));
    assertTrue(combined.contains("final answer fragment"));
    assertTrue(combined.contains("final tool fragment"));
    assertTrue(combined.contains("reasoning_characters="));
    assertTrue(combined.contains("characters omitted"));
  }

  @Test
  void diagnostic_nul_is_visible_and_cannot_make_its_postgres_text_record_fail() {
    Pacing.Call call = new Pacing(() -> 0).sending();
    call.timing(Deltas.DISCARDING).thought("before\0after");
    String part = call.failedOutputParts("deadline").getFirst();
    assertFalse(part.contains("\0"));
    assertTrue(part.contains("before␀after"));
  }

  @Test
  void small_capture_retains_exact_text_even_across_head_tail_boundary() {
    Pacing.Call call = new Pacing(() -> 0).sending();
    Deltas sink = call.timing(Deltas.DISCARDING);
    sink.thought("a".repeat(8190));
    sink.thought("bcdef");
    assertEquals("a".repeat(8190) + "bcdef", call.thought());
  }
}
