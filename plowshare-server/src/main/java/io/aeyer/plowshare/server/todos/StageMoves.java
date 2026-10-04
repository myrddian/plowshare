package io.aeyer.plowshare.server.todos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Spec §4.3's stage movement, for a conversation that has an orchestration's rules. */
public final class StageMoves implements LockedMoves {

  private final Function<String, Optional<StageRules>> rulesFor;

  public StageMoves(Function<String, Optional<StageRules>> rulesFor) {
    this.rulesFor = Objects.requireNonNull(rulesFor, "rulesFor");
  }

  @Override
  public Decision decide(TodoItem current, TodoStatus to, String summary, List<TodoItem> list) {
    Optional<StageRules> found = rulesFor.apply(current.conversation());
    if (found.isEmpty()) {
      return REFUSE_ALL.decide(current, to, summary, list);
    }
    StageRules rules = found.get();
    List<String> order = rules.stages().stream().map(StageRules.Stage::id).toList();
    for (String stageId : order) {
      long claims =
          list.stream().filter(item -> item.locked() && stageId.equals(item.stageId())).count();
      if (claims > 1) {
        return new Refused("stage '" + stageId + "' appears more than once on this list");
      }
    }
    int index = order.indexOf(current.stageId());
    if (index < 0) {
      return new Refused(current.id() + " is not a stage of this orchestration");
    }
    Map<String, TodoItem> byStage =
        list.stream()
            .filter(item -> item.locked() && item.stageId() != null)
            .collect(Collectors.toMap(TodoItem::stageId, item -> item, (a, b) -> a));
    String id = current.stageId();
    Optional<TodoItem> running =
        order.stream()
            .map(byStage::get)
            .filter(Objects::nonNull)
            .filter(item -> item.status() == TodoStatus.IN_PROGRESS)
            .findFirst();

    if (to == TodoStatus.IN_PROGRESS && current.status() == TodoStatus.PENDING) {
      if (running.isPresent()) {
        return new Refused(
            "stage '"
                + running.get().stageId()
                + "' is in progress; finish it"
                + " or return from it before starting another");
      }
      for (int i = 0; i < index; i++) {
        TodoItem earlier = byStage.get(order.get(i));
        if (earlier == null || earlier.status() != TodoStatus.DONE) {
          return new Refused(
              "stage '" + id + "' cannot start before '" + order.get(i) + "' is done");
        }
      }
      return Allowed.PLAIN;
    }
    if (to == TodoStatus.DONE) {
      if (current.status() != TodoStatus.IN_PROGRESS) {
        return new Refused("stage '" + id + "' must be in progress before it is done");
      }
      if (summary == null || summary.isBlank()) {
        return new Refused(
            "stage '"
                + id
                + "' needs a summary of what was done before it is"
                + " done. Put status:done and a nonblank summary in the SAME update operation;"
                + " adding a summary in a later operation does not repair this atomic batch");
      }
      // Rule 1 (spec 2026-09-29 §3): a stage with children is done only when they are.
      // Measured 22:31:16, orc_31893856D8F462A1: `phases` moved to done with three phase
      // children pending, and the README phase never ran. Dropped and done are both ends.
      List<TodoItem> open =
          list.stream()
              .filter(item -> current.id().equals(item.parent()))
              .filter(
                  item ->
                      item.status() == TodoStatus.PENDING
                          || item.status() == TodoStatus.IN_PROGRESS)
              .sorted(Comparator.comparingInt(TodoItem::position))
              .toList();
      if (!open.isEmpty()) {
        String why =
            "`"
                + id
                + "` has "
                + open.size()
                + " not done: "
                + open.stream().map(TodoItem::text).collect(Collectors.joining(", "));
        // Measured 2026-09-29/30, orc_318DFD3782228160: the root filed its phases under
        // `plan`, ran two, and was refused eleven times on this rule before it moved them.
        // Where the definition marks a stage that holds the phases, a stage that is not it
        // says where they go. Stated as a rule, not as a claim about these children.
        Optional<String> holder =
            rules.stages().stream()
                .filter(StageRules.Stage::holdsPhases)
                .map(StageRules.Stage::id)
                .findFirst();
        if (holder.isPresent() && !holder.get().equals(id)) {
          why +=
              ". Phase items belong under `"
                  + holder.get()
                  + "`, not `"
                  + id
                  + "`: drop them here and add them there";
        }
        return new Refused(why);
      }
      return Allowed.PLAIN;
    }
    if (to == TodoStatus.IN_PROGRESS && current.status() == TodoStatus.DONE) {
      if (running.isEmpty()) {
        return new Refused("no stage is in progress to return from");
      }
      String from = running.get().stageId();
      StageRules.Stage fromStage = rules.stages().get(order.indexOf(from));
      if (!fromStage.mayReturnTo().contains(id)) {
        return new Refused("stage '" + from + "' does not list '" + id + "' in may-return-to");
      }
      if (rules.returnsUsed() >= rules.maxReturns()) {
        return new Refused(
            "this orchestration has used all " + rules.maxReturns() + " of its returns");
      }
      List<TodoItem> reset = new ArrayList<>();
      // The returned-to stage keeps its own status change (the board applies that), but its
      // stale summary from before must not survive the return: a fresh "done" still needs a
      // fresh summary.
      reset.add(current.withSummary(null, current.updatedAt()));
      for (int i = index + 1; i < order.size(); i++) {
        TodoItem later = byStage.get(order.get(i));
        if (later != null && later.status() != TodoStatus.PENDING) {
          reset.add(
              later
                  .withStatus(TodoStatus.PENDING, later.updatedAt())
                  .withSummary(null, later.updatedAt()));
        }
      }
      return new Allowed(reset, List.of(rules.countReturn()));
    }
    if (to == TodoStatus.PENDING) {
      return new Refused("a stage goes back to pending only when an earlier stage is returned to");
    }
    return new Refused(
        "stage '" + id + "' cannot move from " + current.status().wire() + " to " + to.wire());
  }
}
