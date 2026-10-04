package io.aeyer.plowshare.server.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

/**
 * The third state, on its own, away from a run.
 *
 * <p>What a delegation tree does with an uncapped budget is a later task's subject. This file is
 * the two states this class carries and the arithmetic — or the deliberate absence of it — between
 * them, following {@code TurnCapTest}'s shape for the sibling type.
 */
class BudgetTest {

  @Test
  void a_call_is_not_claimed_when_its_durable_charge_fails() {
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    Budget budget =
        Budget.of(
            1,
            () -> {
              if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("database unavailable");
              }
            });
    assertThatThrownBy(budget::trySpend).hasMessage("database unavailable");
    assertThat(budget.spent()).isZero();
    assertThat(budget.trySpend()).isTrue();
    assertThat(budget.spent()).isEqualTo(1);
    assertThat(budget.trySpend()).isFalse();
    assertThat(attempts.get()).isEqualTo(2);
  }

  @Test
  void shared_durable_claims_never_charge_past_the_ceiling() throws Exception {
    var charged = new java.util.concurrent.atomic.AtomicInteger();
    Budget budget = Budget.of(20, charged::incrementAndGet);
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var claims = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 64; i++) {
        claims.add(workers.submit(budget::trySpend));
      }
      int allowed = 0;
      for (var claim : claims) {
        if (claim.get(10, java.util.concurrent.TimeUnit.SECONDS)) {
          allowed++;
        }
      }
      assertThat(allowed).isEqualTo(20);
    }
    assertThat(charged.get()).isEqualTo(20);
    assertThat(budget.spent()).isEqualTo(20);
  }

  @Test
  void a_lifted_budget_never_refuses_a_call() {
    Budget budget = Budget.none();
    for (int i = 0; i < 10_000; i++) {
      assertThat(budget.trySpend()).isTrue();
    }
    assertThat(budget.spent()).isEqualTo(10_000);
  }

  @Test
  void a_lifted_budget_still_counts_what_it_spent() {
    Budget budget = Budget.lifted(7);
    assertThat(budget.spent()).isEqualTo(7);
    assertThat(budget.trySpend()).isTrue();
    assertThat(budget.spent()).isEqualTo(8);
  }

  @Test
  void a_lifted_budget_has_no_ceiling_to_report() {
    // TurnCap.turns()'s discipline: a number that does not exist is refused
    // rather than invented, so a caller has to ask capped() first.
    Budget budget = Budget.none();
    assertThat(budget.capped()).isFalse();
    assertThatThrownBy(budget::limit).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void an_ordinary_budget_is_still_capped_and_still_refuses() {
    Budget budget = Budget.of(2);
    assertThat(budget.capped()).isTrue();
    assertThat(budget.limit()).isEqualTo(2);
    assertThat(budget.trySpend()).isTrue();
    assertThat(budget.trySpend()).isTrue();
    assertThat(budget.trySpend()).isFalse();
  }

  /**
   * {@link Budget#of} used to throw a plain {@code IllegalArgumentException} here and leave every
   * caller across an HTTP surface to catch it and restate it as that surface's own 400 — {@code
   * api.AgentController.curate} did exactly that, and {@code requests.RequestedBudget#in} still
   * shows the shape of the translation this pins the end of. The domain check stays exactly here;
   * only the type it raises changed, to the one a caller with no HTTP surface of its own may throw
   * directly.
   */
  @Test
  void a_non_positive_budget_is_refused_as_the_callers_own_fault() {
    assertThatThrownBy(() -> Budget.of(0))
        .isInstanceOf(CallerFault.class)
        .hasMessageContaining("at least one model call");
    assertThatThrownBy(() -> Budget.of(-1)).isInstanceOf(CallerFault.class);
  }

  /**
   * {@code toString} is what a debugger or a log line calls without asking {@code capped()} first,
   * so it is the one accessor that must never throw — it has to have something to say about a
   * budget with no ceiling, not refuse to say it.
   */
  @Test
  void an_uncapped_budget_still_renders_a_message_instead_of_throwing() {
    Budget budget = Budget.none();
    budget.trySpend();
    budget.trySpend();

    assertThat(budget.toString()).contains("no ceiling").contains("2");
  }

  /**
   * Unlike {@code toString}, {@code remaining()} is a number, and there is no number of calls left
   * when there is no ceiling to run out of — that would be {@code limit() - spent()} with an absent
   * {@code limit()}, exactly the invented arithmetic this type exists to refuse.
   */
  @Test
  void an_uncapped_budget_has_nothing_remaining_to_report() {
    Budget budget = Budget.none();

    assertThatThrownBy(budget::remaining)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("capped()");
  }

  /**
   * Raising the ceiling of a budget that has none is not a smaller mistake than reading one that
   * has none — it is a request with nothing to grant, since {@code none()} already permits every
   * call a tree could make. The operator at {@code POST /v1/jobs/{id}/limits} sending it gets told
   * that, not a null pointer three calls into their own stack trace.
   */
  @Test
  void raising_an_uncapped_budgets_ceiling_is_refused_as_a_request_with_nothing_to_grant() {
    Budget budget = Budget.none();

    assertThatThrownBy(() -> budget.changeTo(10))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ceiling");

    assertThat(budget.capped()).isFalse();
    assertThat(budget.trySpend()).isTrue();
  }

  @Test
  void an_uncapped_budget_is_never_exhausted_however_much_it_has_spent() {
    // The predicate every caller that was reaching for `remaining() == 0`
    // actually wanted. TurnCap.stops is answerable in both states and that
    // is why nothing calls TurnCap.turns; this is the same question on this
    // type, and it must answer rather than throw for a budget with no
    // ceiling — which is the whole of what went wrong four times over.
    Budget budget = Budget.lifted(10_000);

    assertThat(budget.exhausted()).isFalse();
    assertThat(budget.trySpend()).isTrue();
    assertThat(budget.exhausted()).isFalse();
  }

  @Test
  void a_capped_budget_is_exhausted_exactly_when_it_has_nothing_left() {
    Budget budget = Budget.of(2);

    assertThat(budget.exhausted()).isFalse();
    assertThat(budget.trySpend()).isTrue();
    assertThat(budget.exhausted()).isFalse();
    assertThat(budget.trySpend()).isTrue();
    assertThat(budget.exhausted()).isTrue();
    assertThat(budget.trySpend()).isFalse();
  }

  @Test
  void a_budget_lowered_below_what_it_has_spent_is_exhausted() {
    // remaining() clamps to zero for this case and exhausted() must agree
    // with it: an operator who lowers a ceiling under a tree that has
    // already passed it has stopped that tree, and a predicate that read
    // `spent == limit` rather than `spent >= limit` would say it had not.
    Budget budget = Budget.resumed(10, 8);
    budget.changeTo(4);

    assertThat(budget.remaining()).isZero();
    assertThat(budget.exhausted()).isTrue();
  }

  // --- changeToOrRefuse: the guarded entry point POST /v1/jobs/{id}/limits
  // calls, in place of changeTo directly -------------------------------

  @Test
  void changing_to_at_least_what_is_spent_moves_the_ceiling() {
    Budget budget = Budget.resumed(10, 4);

    budget.changeToOrRefuse("job_1", 20);

    assertThat(budget.limit()).isEqualTo(20);
  }

  @Test
  void changing_to_below_what_is_spent_is_a_caller_fault_naming_both_numbers() {
    Budget budget = Budget.resumed(10, 8);

    assertThatThrownBy(() -> budget.changeToOrRefuse("job_1", 5))
        .isInstanceOf(CallerFault.class)
        .hasMessageContaining("job_1")
        .hasMessageContaining("already spent 8")
        .hasMessageContaining("cancel");

    // Nothing was changed, matching the message's own claim.
    assertThat(budget.limit()).isEqualTo(10);
  }

  @Test
  void changing_an_uncapped_budgets_ceiling_is_a_caller_fault_and_not_an_illegal_state() {
    // changeTo's own IllegalStateException, translated -- this is the
    // caller-facing door, and IllegalStateException is not a type this
    // codebase's HTTP surface maps to a status.
    Budget budget = Budget.none();

    assertThatThrownBy(() -> budget.changeToOrRefuse("job_1", 10))
        .isInstanceOf(CallerFault.class)
        .hasMessageContaining("no ceiling");
  }

  @Test
  void changing_to_a_non_positive_ceiling_is_a_caller_fault_and_not_an_illegal_argument() {
    Budget budget = Budget.of(5);

    assertThatThrownBy(() -> budget.changeToOrRefuse("job_1", 0))
        .isInstanceOf(CallerFault.class)
        .hasMessageContaining("at least one model call");
  }

  /**
   * The two doors refuse {@code 0} with one sentence, to the character.
   *
   * <p>{@code of} and {@code changeTo} answer the same number for the same reason, and a caller who
   * sent it reads whichever one their surface went through: {@code POST /v1/curate} builds a
   * budget, {@code POST /v1/jobs/&#123;id&#125;/limits} moves one, and {@code changeToOrRefuse}
   * re-raises {@code changeTo}'s message unchanged. Both now come out of one private method, so
   * there is no second string to drift — <b>what this test guards is the re-inlining</b>, an edit
   * that puts a literal back into one of the two and leaves the other alone. That is the one
   * failure the derivation cannot see, which is why this is a test about two strings rather than
   * about a refusal.
   */
  @Test
  void both_doors_refuse_a_non_positive_allowance_in_one_sentence() {
    Throwable building = catchThrowable(() -> Budget.of(0));
    Throwable moving = catchThrowable(() -> Budget.of(5).changeToOrRefuse("job_1", 0));

    assertThat(building).isInstanceOf(CallerFault.class);
    assertThat(moving).isInstanceOf(CallerFault.class);
    assertThat(moving.getMessage()).isEqualTo(building.getMessage());
  }
}
