package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceSnapshot;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllowanceNoticesTest {
  private static final AllowanceId ID = new AllowanceId(UUID.randomUUID());

  private static AllowanceSnapshot calls(Integer ceiling, int claimed) {
    return new AllowanceSnapshot(ID, ceiling, claimed);
  }

  @Test
  void opening_uses_live_limits_and_counts_the_current_response() {
    String note =
        new AllowanceNotices().beforeCall(64, 0, calls(64, 1), null, Set.of()).orElseThrow();
    assertTrue(note.contains("steps (max-turns): 64 of 64 remaining"), note);
    assertTrue(note.contains("model calls in the shared allowance: 64 of 64 remaining"), note);
    assertTrue(note.contains("Counts include this step and this model call"), note);
    assertTrue(note.contains("No allowance-request tool is offered"), note);
    assertFalse(note.contains("orchestration_ask"), note);
    assertFalse(note.contains("send_message"), note);
  }

  @Test
  void each_checkpoint_is_delivered_once_and_only_the_most_urgent_survives_a_jump() {
    var notices = new AllowanceNotices();
    notices.beforeCall(100, 0, calls(100, 1), null, Set.of()).orElseThrow();
    for (int remaining : new int[] {50, 20, 10, 5}) {
      String note =
          notices
              .beforeCall(100, 100 - remaining, calls(100, 101 - remaining), null, Set.of())
              .orElseThrow();
      assertTrue(note.contains("At most " + remaining + "% remains"), note);
      assertTrue(
          notices
              .beforeCall(100, 100 - remaining, calls(100, 101 - remaining), null, Set.of())
              .isEmpty());
    }
    var jumped = new AllowanceNotices();
    jumped.beforeCall(100, 0, calls(100, 1), null, Set.of());
    String note = jumped.beforeCall(100, 96, calls(100, 97), null, Set.of()).orElseThrow();
    assertTrue(note.contains("Final allowance warning"), note);
    assertTrue(note.contains("At most 5% remains"), note);
    assertTrue(note.contains("Finish now"), note);
    assertTrue(jumped.beforeCall(100, 97, calls(100, 98), null, Set.of()).isEmpty());
  }

  @Test
  void either_limit_can_warn_independently_and_shared_spending_is_included() {
    var notices = new AllowanceNotices();
    notices.beforeCall(100, 0, calls(200, 1), null, Set.of());
    String callWarning = notices.beforeCall(100, 1, calls(200, 191), null, Set.of()).orElseThrow();
    assertTrue(callWarning.contains("steps (max-turns): 99 of 100 remaining"), callWarning);
    assertTrue(callWarning.contains("10 of 200 remaining"), callWarning);
    assertTrue(callWarning.contains("Final allowance warning"), callWarning);
    String stepWarning = notices.beforeCall(100, 50, calls(200, 192), null, Set.of()).orElseThrow();
    assertTrue(stepWarning.contains("steps (max-turns): 50 of 100 remaining"), stepWarning);
  }

  @Test
  void raising_lowering_and_lifting_limits_are_reported_without_granting_anything() {
    var notices = new AllowanceNotices();
    notices.beforeCall(100, 0, calls(100, 1), null, Set.of());
    notices.beforeCall(100, 95, calls(100, 96), null, Set.of());
    String increased = notices.beforeCall(200, 96, calls(200, 97), null, Set.of()).orElseThrow();
    assertTrue(increased.startsWith("[Runtime allowance:"), increased);
    assertTrue(increased.contains("104 of 200 remaining"), increased);
    String halfway = notices.beforeCall(200, 100, calls(200, 101), null, Set.of()).orElseThrow();
    assertTrue(halfway.contains("At most 50% remains"), halfway);
    String decreased = notices.beforeCall(102, 101, calls(102, 102), null, Set.of()).orElseThrow();
    assertTrue(decreased.contains("Final allowance warning"), decreased);
    String lifted = notices.beforeCall(null, 102, calls(null, 103), null, Set.of()).orElseThrow();
    assertTrue(lifted.contains("steps (max-turns): unlimited"), lifted);
    assertTrue(lifted.contains("model calls in the shared allowance: unlimited"), lifted);
    assertFalse(lifted.contains("warning"), lifted);
    assertTrue(notices.beforeCall(null, 103, calls(null, 104), null, Set.of()).isEmpty());
  }

  @Test
  void finite_steps_still_warn_under_an_unlimited_call_allowance() {
    var notices = new AllowanceNotices();
    notices.beforeCall(10, 0, calls(null, 1), null, Set.of());
    String note = notices.beforeCall(10, 9, calls(null, 10), null, Set.of()).orElseThrow();
    assertTrue(note.contains("Final allowance warning"), note);
    assertTrue(note.contains("1 of 10 remaining"), note);
    assertTrue(note.contains("model calls in the shared allowance: unlimited"), note);
  }

  @Test
  void small_allowances_get_a_final_warning_on_the_last_response() {
    String one = new AllowanceNotices().beforeCall(1, 0, calls(1, 1), null, Set.of()).orElseThrow();
    assertTrue(one.contains("Final allowance warning"), one);
    var notices = new AllowanceNotices();
    notices.beforeCall(3, 0, calls(3, 1), null, Set.of());
    String last = notices.beforeCall(3, 2, calls(3, 3), null, Set.of()).orElseThrow();
    assertTrue(last.contains("Final allowance warning"), last);
  }

  @Test
  void request_guidance_only_names_an_offered_tool_and_preserves_authorization() {
    String orchestration =
        new AllowanceNotices()
            .beforeCall(1, 0, calls(1, 1), null, Set.of("orchestration_ask", "send_message"))
            .orElseThrow();
    assertTrue(orchestration.contains("Use orchestration_ask"), orchestration);
    assertTrue(orchestration.contains("harness also asks"), orchestration);
    assertFalse(orchestration.contains("Use send_message"), orchestration);
    String message =
        new AllowanceNotices()
            .beforeCall(1, 0, calls(1, 1), null, Set.of("send_message"))
            .orElseThrow();
    assertTrue(message.contains("Use send_message"), message);
    assertTrue(message.contains("A request does not grant an increase"), message);
  }

  @Test
  void a_small_shared_remainder_warns_even_when_the_wake_window_is_fresh() {
    String note =
        new AllowanceNotices()
            .beforeCall(100, 0, calls(400, 398), calls(40, 1), Set.of())
            .orElseThrow();
    assertTrue(note.contains("shared allowance: 3 of 400 remaining"), note);
    assertTrue(note.contains("model calls in this wake: 40 of 40 remaining"), note);
    assertTrue(note.contains("Final allowance warning"), note);
  }

  @Test
  void a_wake_window_warns_independently_from_an_unlimited_shared_allowance() {
    var notices = new AllowanceNotices();
    notices.beforeCall(100, 0, calls(null, 101), calls(10, 1), Set.of());
    String note =
        notices.beforeCall(100, 5, calls(null, 106), calls(10, 6), Set.of()).orElseThrow();
    assertTrue(note.contains("shared allowance: unlimited"), note);
    assertTrue(note.contains("model calls in this wake: 5 of 10 remaining"), note);
    assertTrue(note.contains("At most 50% remains"), note);
    String last =
        notices.beforeCall(100, 9, calls(null, 110), calls(10, 10), Set.of()).orElseThrow();
    assertTrue(last.contains("Final allowance warning"), last);
  }

  @Test
  void large_allowances_do_not_overflow_checkpoint_arithmetic() {
    var notices = new AllowanceNotices();
    notices.beforeCall(Integer.MAX_VALUE, 0, calls(Integer.MAX_VALUE, 1), null, Set.of());
    assertTrue(
        notices
            .beforeCall(Integer.MAX_VALUE, 1, calls(Integer.MAX_VALUE, 2), null, Set.of())
            .isEmpty());
    String finalWarning =
        notices
            .beforeCall(
                Integer.MAX_VALUE,
                Integer.MAX_VALUE - 1,
                calls(Integer.MAX_VALUE, Integer.MAX_VALUE),
                null,
                Set.of())
            .orElseThrow();
    assertTrue(finalWarning.contains("Final allowance warning"), finalWarning);
  }
}
