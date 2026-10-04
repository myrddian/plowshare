package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.hooks.Summarised;
import java.util.Objects;

/**
 * The transitions between runs, as something their doors call: a log opening, a log closing, a
 * result delivered, an approval answered and a fold (spec 2026-09-28-hooks-reach-the-log).
 *
 * <p><b>Never throws, and never holds up a transition.</b> Each of these stages happens after the
 * fact, so a hook here observes, annotates or notifies, and one that breaks is recorded and passed
 * over (spec §2.4). {@link #NONE} is a server, or a fixture, with nothing to run.
 */
public interface LogStages {

  LogStages NONE = new LogStages() {};

  /** {@code log.open}: the log's row is committed and its first turn has not started. */
  default void opened(LogOpened opened) {}

  /**
   * {@code log.close}: the log will take no more turns. At most once per log.
   *
   * @param ending lower case on the wire, always: a run's {@code Outcome.Ending} name lower-cased,
   *     an orchestration's state's wire name, or a conversation's new lifecycle's
   */
  default void closed(String log, String ending) {}

  /**
   * {@code log.close} from the transcript of the turn that ends the log, which knows its own
   * ordinal: that turn's row is already written while its conversation still reads as speaking, so
   * only the transcript can say which turn the close belongs to (spec
   * 2026-09-28-hooks-reach-the-log decision 7).
   *
   * @param atTurn the ending turn's ordinal, which the close is filed at and counts to
   */
  default void closed(String log, String ending, int atTurn) {
    closed(log, ending);
  }

  /** A conversation moved: a person's closes on its first move out of {@code ACTIVE}. */
  default void moved(ConversationRecord moved) {}

  /** {@code delivery.pre}: the text to deliver, with any hook's notes appended. */
  default String deliveryPre(String sourceLog, String destination, String text) {
    return text;
  }

  /** {@code delivery.post}: the result was delivered. */
  default void deliveryPost(String sourceLog, String destination, String text) {}

  /**
   * {@code approval.post}: a person answered an approval, or revoked a standing one, and the store
   * has changed (spec 2026-09-28-hooks-reach-the-log §3). Recorded in, and told to the owner of,
   * the root conversation the approval is filed against (decision 7).
   *
   * @param log {@code run_approvals.conversation}
   * @param approval the approval's id, {@code run_approvals.id}
   * @param decision {@code allow}, {@code deny} or {@code revoke}
   * @param scope what was allowed or revoked; null for a denial
   */
  default void approvalAnswered(String log, String approval, String decision, String scope) {}

  /**
   * {@code fold.post}: the folder has summarised a span and the fold is not yet saved, on the
   * fold's own thread (spec 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29). What the hooks
   * keep follows the folder's summary verbatim; what they notify waits in {@link Held} until the
   * fold is saved. Records are written now. Fails open: {@link Held#NOTHING}.
   *
   * @param log the conversation being folded
   * @param atTurn the ordinal the fold files its own diagnostic at, where the records go
   * @param summarised the span and the folder's summary of it
   */
  default Held foldPost(String log, int atTurn, Summarised summarised) {
    return Held.NOTHING;
  }

  /**
   * What {@code fold.post} left for the fold: the kept text, and its notices held back (spec
   * 2026-09-28-hooks-reach-the-log §3). The fold runs {@link #tell} only once the log has the fold,
   * and drops it otherwise: nobody hears of a fold that did not happen.
   *
   * @param kept the kept text, several a blank line apart, or {@code ""}
   * @param tell sends the held notices to the log owner's inbox; never throws by contract
   */
  record Held(String kept, Runnable tell) {

    public static final Held NOTHING = new Held("", () -> {});

    public Held {
      Objects.requireNonNull(kept, "kept");
      Objects.requireNonNull(tell, "tell");
    }
  }

  /**
   * One log, as it opens. Its owner is read from its row, where the door wrote it.
   *
   * @param agent the agent whose log it is, or {@code null} for a {@code TURN} log
   * @param parent the delegating log, on a {@code DELEGATION} log only
   * @param session the session whose {@code .plowshare/hooks/} this log is snapshotted with, or
   *     {@code null} (spec 2026-09-30-local-hooks-are-served decision 4)
   * @param inherits the log whose local hooks this one carries instead of reading any: a delegated
   *     child's parent, a nested orchestration's parent conductor; otherwise {@code null} (decision
   *     4)
   */
  record LogOpened(
      String log,
      Origin origin,
      Home home,
      String agent,
      boolean bot,
      String parent,
      String session,
      String inherits) {

    public LogOpened {
      Objects.requireNonNull(log, "log");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(home, "home");
    }

    /** A log opened by a door that names no session and inherits nothing. */
    public LogOpened(
        String log, Origin origin, Home home, String agent, boolean bot, String parent) {
      this(log, origin, home, agent, bot, parent, null, null);
    }

    /** A person's conversation, as its open doors open one: a TURN log names no agent. */
    public static LogOpened ofConversation(ConversationRecord opened) {
      return ofConversation(opened, null);
    }

    /** The same, opened from {@code session}, whose local hooks it is snapshotted with. */
    public static LogOpened ofConversation(ConversationRecord opened, String session) {
      return new LogOpened(
          opened.id(), Origin.TURN, opened.home(), null, false, null, session, null);
    }
  }
}
