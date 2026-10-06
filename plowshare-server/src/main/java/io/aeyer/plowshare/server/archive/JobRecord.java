package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Outcome;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@code jobs}: a run that happened, and how it ended if anything ever filed one.
 *
 * <h2>What this is not</h2>
 *
 * <p><b>Not {@code agents.Job}, and the two must not be collapsed.</b> {@code Job} is a live handle
 * on a run in this process: it can be cancelled, it holds the {@code Budget} and the {@code
 * TurnCap} an operator can move while the run goes on, and it stops existing when the process does.
 * This is the durable record that the run happened, and nothing here can be moved or cancelled. The
 * first is the authority on what is running <em>now</em>; the second is the only thing that can
 * still be asked next week.
 *
 * <p>{@code V24__jobs.sql} argues the split at length. The short version is that a NULL {@link
 * #endedAt()} means "this process never filed an outcome", which is a run in flight or a run lost
 * to a restart, and no durable row can tell those apart.
 *
 * <h2>The absences travel together</h2>
 *
 * <p>{@link #endedAt()}, {@link #ending()}, {@link #steps()} and {@link #modelCalls()} are all null
 * or all present — three CHECK constraints hold it in the schema and {@link #ended()} is the one
 * question a caller should be asking instead of picking one of the four to test. The boxed {@link
 * Integer}s are for that reason and not for {@code TurnRecord}'s: a count of zero is ordinary here
 * (a run whose runtime failed before it could do anything files two zeroes, and they are honest),
 * so absent has to be a different value from nought rather than merely a rare one.
 *
 * <p><b>{@link Home} and not a project string</b>, on {@link ConversationRecord}'s reasoning: the
 * column is a surrogate key since {@code V14} and the global tier is the absence of a project
 * rather than a project named "global", so a record carrying a nullable {@code String} would put
 * the null-means-global rule in every caller.
 *
 * @param id {@code job_} plus {@code MemoryIds}' sortable suffix, so a listing ordered by it is a
 *     listing in the order runs were started
 * @param agent what the job is polled under: an agent's name for one agent's run, and the caller's
 *     own for work that is not — a curator pass, an ingest
 * @param home the tier the run answered in; {@link Home#global()} for a row whose project is NULL
 * @param startedAt when it was submitted, on the same instant its id was minted from
 * @param endedAt when an outcome was filed, or null. See the class note
 * @param ending which of {@link Outcome.Ending}'s eight, or null
 * @param steps how many turns it took, or null
 * @param modelCalls how many model calls it made, or null
 * @param conversation owning conversation while that row remains available, or null if unrecorded
 *     or removed; its identity alone does not authorize reads
 */
public record JobRecord(
    String id,
    String agent,
    Home home,
    Instant startedAt,
    Instant endedAt,
    Outcome.Ending ending,
    Integer steps,
    Integer modelCalls,
    String conversation) {

  /** Older records and non-agent work have no recorded owning conversation. */
  public JobRecord(
      String id,
      String agent,
      Home home,
      Instant startedAt,
      Instant endedAt,
      Outcome.Ending ending,
      Integer steps,
      Integer modelCalls) {
    this(id, agent, home, startedAt, endedAt, ending, steps, modelCalls, null);
  }

  public JobRecord {
    id = ArchiveValues.identity(id, "job id");
    agent = ArchiveValues.identity(agent, "agent");
    if (conversation != null)
      conversation = ArchiveValues.identity(conversation, "conversation id");
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(startedAt, "startedAt");
    if ((endedAt == null) != (ending == null)
        || (endedAt == null) != (steps == null)
        || (endedAt == null) != (modelCalls == null))
      throw new IllegalArgumentException("job outcome fields must be all present or all absent");
    if (endedAt != null && endedAt.isBefore(startedAt))
      throw new IllegalArgumentException("job cannot end before its start");
    if (steps != null && steps < 0 || modelCalls != null && modelCalls < 0)
      throw new IllegalArgumentException("negative job counters");
  }

  /**
   * Whether an outcome was ever filed for this run.
   *
   * <p>The question worth asking, and the reason it is a method rather than four null tests spread
   * over the callers: the four ending columns are written in one statement and the schema refuses
   * any other combination, so a caller testing {@code ending() != null} and a caller testing {@code
   * endedAt() != null} are asking the same question in two ways that could drift apart in a
   * reader's head but never in the data.
   */
  public boolean ended() {
    return endedAt != null;
  }
}
