package io.aeyer.plowshare.server.todos;

import io.aeyer.plowshare.server.archive.CompactionRecord;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.SpeakerHandles;
import io.aeyer.plowshare.server.orchestrations.OrchestrationState;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The todo board. Depends on nothing in {@code agents}, so {@code AgentsConfig.jobRuntime} can take
 * it as a parameter and hand it to the runtime before the registry reads {@code knownTools()}.
 */
@Configuration
public class TodosConfig {

  private static final Logger log = LoggerFactory.getLogger(TodosConfig.class);

  public static final String CHANGED = "todos.changed";

  @Bean
  public TodoStore todoStore(JdbcTemplate jdbc) {
    return new TodoStore(jdbc);
  }

  /**
   * Pushes and speakers are lazy for {@code EventsConfig.inbox}'s reason: the push side is the
   * event channel, built after the frame router. A batch from a run with no speaking session tells
   * nobody; {@code todos.read} still answers.
   *
   * <p>The orchestration store arrives through a provider so a context without the orchestration
   * beans still builds a board, one that refuses every locked move. Asking for it here creates no
   * cycle: the store depends only on {@code JdbcTemplate} and {@code UnitOfWork}.
   */
  @Bean
  public TodoBoard todoBoard(
      TodoStore store,
      UnitOfWork work,
      ObjectProvider<AccountPushes> pushes,
      ObjectProvider<SpeakerHandles> speakers,
      JdbcTemplate jdbc,
      ObjectProvider<CompactionStore> compactions,
      ObjectProvider<OrchestrationStore> orchestrations) {
    OrchestrationStore runs = orchestrations.getIfAvailable();
    return new TodoBoard(
        store,
        work,
        lockedMoves(runs),
        (session, conversation) -> tellClients(pushes, speakers, session, conversation),
        Instant::now,
        new TodoNotices.Jdbc(jdbc),
        conversation ->
            compactions.getIfAvailable() == null
                ? -1
                : compactions
                    .getObject()
                    .latest(conversation)
                    .map(CompactionRecord::throughOrdinal)
                    .orElse(-1),
        progressed(runs));
  }

  /**
   * A batch that moved a stage resets the nudges of the run its conversation conducts — spec
   * 2026-09-28's "progress resets the count". {@link TodoLists.Progressed#NONE} with no store.
   *
   * @param store the orchestration store, or {@code null} for a context without one
   */
  public static TodoLists.Progressed progressed(OrchestrationStore store) {
    return store == null ? TodoLists.Progressed.NONE : store::progressedIn;
  }

  /**
   * Spec §4.3's stage rules for a conversation a {@code running} orchestration conducts, read from
   * its row on every move, and {@link LockedMoves#REFUSE_ALL} for every other — including every
   * conversation, when there is no store. A return is counted against the row inside the batch's
   * own transaction; a count that loses refuses the whole batch.
   *
   * @param store the orchestration store, or {@code null} for a context without one
   */
  public static LockedMoves lockedMoves(OrchestrationStore store) {
    if (store == null) {
      return LockedMoves.REFUSE_ALL;
    }
    return new StageMoves(
        conversation ->
            store
                .byConductorConversation(conversation)
                .filter(run -> run.state() == OrchestrationState.RUNNING)
                .map(
                    run ->
                        new StageRules(
                            run.stages(),
                            run.maxReturns(),
                            run.returnsUsed(),
                            () -> {
                              if (!store.countReturn(conversation)) {
                                throw new TodoRefused(
                                    "todo_write refused: this orchestration has used all "
                                        + run.maxReturns()
                                        + " of its returns, or is no longer running."
                                        + " Nothing was changed.");
                              }
                            })));
  }

  /**
   * Pushed after a batch commits. Package-private and static, over the two providers rather than
   * closed over the bean, so the branches below are testable without standing up the bean: a {@code
   * null} session (a batch from a run with no speaking session) tells nobody, and a push that
   * throws is logged and swallowed rather than propagated -- {@code
   * io.aeyer.plowshare.server.events.Dispatcher}'s own grading of a lost side effect ({@code firing
   * ... could not be delivered} is logged at error), because the batch has already committed and a
   * lost notification is not the write's to fail over.
   */
  static void tellClients(
      ObjectProvider<AccountPushes> pushes,
      ObjectProvider<SpeakerHandles> speakers,
      String session,
      String conversation) {
    if (session == null) {
      return;
    }
    try {
      speakers
          .getIfAvailable(() -> SpeakerHandles.NONE)
          .handleOf(session)
          .ifPresent(
              handle ->
                  pushes
                      .getIfAvailable(() -> AccountPushes.NONE)
                      .push(handle, Map.of("kind", CHANGED, "conversation", conversation)));
    } catch (RuntimeException failed) {
      log.error(
          "todo list for conversation {} changed but its clients could not be told",
          conversation,
          failed);
    }
  }
}
