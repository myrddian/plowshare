package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Rule 1's second half (spec 2026-09-29 §3): a phase child cannot be marked done while the run
 * started under it is still live. Which run that is, is {@code orchestrations.phase_todo} (V60),
 * written when the child starts under the todo its parent marked in progress. Only a move to {@code
 * done} is refused: dropping a phase is how a conductor records one it gave up on.
 */
public final class PhaseRunsGate implements TodoTools.BeforeApply {

  private final OrchestrationStore store;
  private final TodoLists todos;

  /**
   * @param store where a run's live children and their phase todos are read
   * @param todos the conductor's list, read before the batch
   */
  public PhaseRunsGate(OrchestrationStore store, TodoLists todos) {
    this.store = Objects.requireNonNull(store, "store");
    this.todos = Objects.requireNonNull(todos, "todos");
  }

  @Override
  public List<TodoOp> check(String conversation, List<TodoOp> ops, Home home) {
    Optional<OrchestrationRecord> run =
        store.byConductorConversation(conversation).filter(found -> !found.state().terminal());
    if (run.isEmpty()) {
      return ops;
    }
    Map<String, TodoItem> items =
        todos.list(conversation).stream()
            .collect(Collectors.toMap(TodoItem::id, item -> item, (a, b) -> a));
    for (int i = 0; i < ops.size(); i++) {
      if (!(ops.get(i) instanceof TodoOp.Update update) || update.status() != TodoStatus.DONE) {
        continue;
      }
      TodoItem item = items.get(update.id());
      if (item == null || item.locked()) {
        continue;
      }
      Optional<OrchestrationRecord> live = store.livePhaseRun(run.get().id(), item.id());
      if (live.isPresent()) {
        throw new TodoRefused(
            "todo_write refused operation "
                + (i + 1)
                + ": `"
                + item.text()
                + "` cannot be done while its run "
                + live.get().id()
                + " is still "
                + live.get().state().wire()
                + ": its report comes to you"
                + " when it ends. Nothing was changed.");
      }
    }
    return ops;
  }
}
