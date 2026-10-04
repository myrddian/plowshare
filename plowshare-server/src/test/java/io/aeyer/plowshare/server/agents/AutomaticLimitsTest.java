package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AutomaticLimitsTest {
  static ProjectCaps enabled(boolean enabled) {
    return new ProjectCaps(
        new ProjectCaps.Setting(2, ProjectCaps.PROJECT_FILE),
        new ProjectCaps.Setting(2, ProjectCaps.PROJECT_FILE),
        ProjectCaps.Setting.UNSET,
        ProjectCaps.Setting.UNSET,
        ProjectCaps.FAILED_CHECKS_DEFAULT,
        new ProjectCaps.BooleanSetting(enabled, ProjectCaps.PROJECT_FILE),
        null);
  }

  static ConversationRecord owner(String id, Origin origin, String parent, Budget budget) {
    var record = mock(ConversationRecord.class);
    when(record.id()).thenReturn(id);
    when(record.origin()).thenReturn(origin);
    when(record.home()).thenReturn(Home.of("home"));
    when(record.parentId()).thenReturn(parent);
    when(record.budget()).thenReturn(budget);
    return record;
  }

  final AgentDefinition definition = mock(AgentDefinition.class);
  final Transcript log = mock(Transcript.class);

  @Test
  void grants_are_finite_shared_and_committed_before_the_ceiling_moves() {
    Budget budget = Budget.resumed(2, 2);
    List<Integer> grants = new ArrayList<>();
    var root = owner("root", Origin.TURN, null, budget);
    var child = owner("child", Origin.DELEGATION, "root", null);
    var limits =
        new AutomaticLimits(
            (project, session) -> enabled(true),
            id -> Optional.of(id.equals("child") ? child : root),
            (id, total, spent) -> {
              assertEquals("root", id);
              assertEquals(budget.spent(), spent);
              assertEquals(budget.limit() + 2, total);
              grants.add(total);
            });
    var cap = TurnCap.of(2);
    for (int taken = 2; taken <= 6; taken += 2) {
      assertTrue(limits.steps("child", cap, taken, definition, null, log));
      assertEquals(taken + 2, cap.turns());
      assertTrue(limits.budget("child", budget, definition, null, log));
      assertTrue(budget.trySpend());
      assertTrue(budget.trySpend());
      assertFalse(budget.trySpend());
    }
    assertEquals(List.of(4, 6, 8), grants);
    assertEquals(8, budget.spent());
  }

  @Test
  void disabled_missing_and_orchestration_ownership_do_not_grant() {
    for (Origin origin : List.of(Origin.TURN, Origin.ORCHESTRATION)) {
      var budget = Budget.resumed(2, 2);
      var limits =
          new AutomaticLimits(
              (p, s) -> enabled(origin != Origin.TURN),
              id -> Optional.of(owner("root", origin, null, budget)),
              (id, total, spent) -> fail("no grant expected"));
      assertFalse(limits.budget("root", budget, definition, null, log));
      assertFalse(limits.steps("root", TurnCap.of(2), 2, definition, null, log));
    }
    assertFalse(
        AutomaticLimits.NONE.budget("missing", Budget.resumed(2, 2), definition, null, log));
  }

  @Test
  void the_owning_project_decides_and_a_board_lease_never_inherits_a_parent_policy() {
    var budget = Budget.resumed(2, 2);
    var root = owner("root", Origin.TURN, null, budget);
    var child = owner("child", Origin.DELEGATION, "root", null);
    when(child.home()).thenReturn(Home.of("other"));
    var records = new HashMap<String, ConversationRecord>();
    records.put("root", root);
    records.put("child", child);
    var limits =
        new AutomaticLimits(
            (project, session) -> enabled(project.equals("other")),
            id -> Optional.ofNullable(records.get(id)),
            (id, total, spent) -> fail("only the owning project's permission can grant"));
    assertFalse(limits.budget("child", budget, definition, null, log));
    assertFalse(limits.steps("child", TurnCap.of(2), 2, definition, null, log));

    records.put("board", owner("board", Origin.BOARD, "root", null));
    var enabled =
        new AutomaticLimits(
            (project, session) -> enabled(true),
            id -> Optional.ofNullable(records.get(id)),
            (id, total, spent) -> fail("board allowances remain separately bounded"));
    assertFalse(enabled.budget("board", budget, definition, null, log));
    assertFalse(enabled.steps("board", TurnCap.of(2), 2, definition, null, log));
  }

  @Test
  void a_failed_commit_does_not_raise_or_reset_the_budget() {
    var budget = Budget.resumed(2, 2);
    assertThrows(
        IllegalStateException.class,
        () ->
            budget.increaseIfExhausted(
                2,
                next -> {
                  throw new IllegalStateException("store down");
                }));
    assertEquals(2, budget.limit());
    assertEquals(2, budget.spent());
    assertFalse(budget.trySpend());
  }

  @Test
  void concurrent_exhausted_delegates_receive_one_grant_and_charged_leases_cannot_grow()
      throws Exception {
    var budget = Budget.resumed(2, 2);
    AtomicInteger grants = new AtomicInteger();
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> calls = new ArrayList<>();
      for (int i = 0; i < 20; i++)
        calls.add(
            threads.submit(() -> budget.increaseIfExhausted(2, next -> grants.incrementAndGet())));
      for (var call : calls) call.get(5, TimeUnit.SECONDS);
    }
    assertEquals(1, grants.get());
    assertEquals(4, budget.limit());
    assertEquals(2, budget.spent());
    var lease = Budget.of(1, () -> {});
    assertTrue(lease.trySpend());
    assertFalse(lease.increaseIfExhausted(2, next -> fail("lease must stay bounded")));
    assertFalse(
        Budget.resumed(Integer.MAX_VALUE, Integer.MAX_VALUE)
            .increaseIfExhausted(2, next -> fail("cannot overflow")));
  }
}
