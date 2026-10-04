package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.JobDelta;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One job's event stream: the only place a {@link JobEvent} is built.
 *
 * <h2>The containment rule is in these five signatures</h2>
 *
 * <p>A run says what it is doing by calling a method here, and <b>there is no method that takes a
 * tool's arguments, a model's prose, an {@link Outcome}'s text or detail, or a throwable.</b> That
 * is deliberate and it is the strongest form the rule can take: an event cannot replay a file's
 * contents because the only way to publish one is through a signature with nowhere to put them.
 *
 * <ul>
 *   <li>{@link #toolCalled} takes a <em>name</em>, so a caller minded to be helpful about what a
 *       tool was doing has nothing to be helpful with;
 *   <li>{@link #ended} takes an {@link Ending} and two counts, and never the {@link Outcome} that
 *       carries them. {@code Outcome.detail()} holds an exception's type <em>and its message</em> —
 *       which is exactly the shape {@code Scribe}'s javadoc refuses to put in a reason, "a
 *       transport-layer message can carry a URL, a header or a request body" — so this seam is
 *       never handed the object holding it.
 * </ul>
 *
 * <p>{@code JobEvent}'s own javadoc carries the rest of the argument, including why the failure's
 * type is not carried either.
 *
 * <p><b>{@link #alive} is the fifth and takes nothing but the agent's name</b>, which is the same
 * rule arriving at its easiest case: a beat says that a run exists, and there is nothing about a
 * run's contents for a caller to be helpful with. The two numbers on it are read off this object
 * rather than passed in — see below — so not even a count is a caller's to choose there.
 *
 * <h2>The only state on a watch is the two counts, and that is why</h2>
 *
 * <p>This class is otherwise a stamp: an id, a session, a seam. It remembers the {@code steps} and
 * {@code modelCalls} of the last {@link #modelCall} because a beat has to carry them and <b>nothing
 * else is in a position to know them</b>. {@link JobStore} owns the timer and knows only that a job
 * exists; the counts live in {@link JobRuntime}'s loop, which the store deliberately cannot see.
 * The one object both ends already share is this one, and every count that has ever been published
 * has gone through it — so remembering the last is a copy of something rather than a second
 * measurement of it, which is the property that keeps a beat from inventing a third vocabulary for
 * a run's progress.
 *
 * <h2>A publisher that throws does not end a run</h2>
 *
 * <p>The stream is droppable and the run is not. {@link #publish} therefore swallows a {@link
 * RuntimeException} out of the seam, and logs <b>the type and not the message</b>, on {@code
 * Scribe}'s reasoning again: the one production implementation writes to a socket, and what a
 * socket's failure says about itself is a URL away from being a thing this project does not log.
 */
public final class JobWatch {

  private static final Logger log = LoggerFactory.getLogger(JobWatch.class);

  /**
   * A run nobody is watching: a delegated child, a curator's ruling, a test. Its id is empty
   * because nothing reads it — {@link JobEvents#NONE} drops every event before the id can matter.
   */
  public static final JobWatch UNWATCHED = new JobWatch("", null, JobEvents.NONE);

  private final String job;

  /**
   * Nullable: a job submitted with no session. Handed to the seam as-is, because "no session" and
   * "a session nothing is attached to" are the same answer there.
   */
  private final String session;

  private final JobEvents events;

  /**
   * What the last {@link #modelCall} reported, for {@link #alive} to repeat.
   *
   * <p>Volatile and not an {@code AtomicInteger} pair: they are written on the job's own thread and
   * read on the timer's, and a beat that read a new {@code modelCalls} beside an old {@code steps}
   * would be off by one for fifty microseconds and then right again. Nothing decides anything on
   * this number — the contractual record is {@code Outcome} — so a torn pair costs a listener one
   * frame that was briefly stale, and paying for a lock on the job's own thread to prevent it would
   * be the wrong trade.
   */
  private volatile int steps;

  private volatile int modelCalls;

  /**
   * @param job the job id every event from this watch is stamped with
   * @param session the session the job was submitted under, or null
   * @param events where the events go
   */
  public JobWatch(String job, String session, JobEvents events) {
    this.job = Objects.requireNonNull(job, "job");
    this.session = session;
    this.events = Objects.requireNonNull(events, "events");
  }

  /**
   * The run has begun. {@link JobStore} sends it, because that is what mints the id and starts the
   * thread.
   */
  public void started(String agent) {
    publish(JobEvent.started(job, agent));
  }

  /** One model call has been claimed and is about to be made. */
  public void modelCall(String agent, int steps, int modelCalls) {
    // Remembered BEFORE the publish, so a beat that lands in the same
    // millisecond as the event reports the same pair rather than the one
    // before it. Both orders are honest and this one cannot be read as the
    // stream going backwards.
    this.steps = steps;
    this.modelCalls = modelCalls;
    publish(JobEvent.modelCall(job, agent, steps, modelCalls));
  }

  /**
   * The run is still going.
   *
   * <p><b>Said by {@link JobStore} on a timer and never by the run itself</b>, which is the
   * distinction that decides where this is sent from: a run that is inside a twelve-minute model
   * call has no line to reach for this on, and a run that could reach one would be a run reporting
   * its own liveness — the one thing a stuck run is least able to do honestly. What the store knows
   * is that the job exists and has not finished, and that is exactly what this says.
   *
   * @param agent whose turn it is, as every other kind carries it
   */
  public void alive(String agent) {
    publish(JobEvent.alive(job, agent, steps, modelCalls));
  }

  /**
   * One tool is about to run.
   *
   * @param tool the name <em>this server</em> registered the tool under. Never the name a model
   *     asked for: {@link JobRuntime} reads it off the tool's own schema, so a model that invents a
   *     name produces no event rather than an event carrying its invention
   */
  void scriptCommand(String agent, String tool, int commands, int calls) {
    scriptProgress(commands, calls);
    toolCalled(agent, tool);
  }

  void scriptProgress(int commands, int calls) {
    this.steps = commands;
    this.modelCalls = calls;
  }

  public void toolCalled(String agent, String tool) {
    publish(JobEvent.toolCalled(job, agent, tool));
  }

  /**
   * The run is over and its outcome is filed.
   *
   * <p>Sent by {@link JobStore} <em>after</em> {@code Job.finish}, so a listener that reads this
   * and immediately asks {@code GET /v1/jobs/&#123;id&#125;} finds a job that is done rather than
   * one that is about to be.
   *
   * @param ending the constant, whose {@code name()} goes on the wire. The outcome's text and
   *     detail are not parameters and are not sent
   */
  public void ended(String agent, Ending ending, int steps, int modelCalls) {
    publish(JobEvent.ended(job, agent, ending.name(), steps, modelCalls));
  }

  /**
   * Whether anybody asked to see this run's tokens.
   *
   * <p>Asked once per model call rather than once per delta: the answer cannot usefully change
   * mid-call, and the point of asking at all is to avoid work on the thread reading the model's
   * response.
   */
  public boolean streaming() {
    return events.streaming(session);
  }

  /** A piece of the model reasoning, on its way past. Dropped freely. */
  public void thinking(String text) {
    stream(JobDelta.thinking(job, text));
  }

  /** A piece of the model answering, on its way past. Dropped freely. */
  public void answering(String text) {
    stream(JobDelta.answering(job, text));
  }

  /**
   * <b>Swallows everything, and says nothing about it.</b>
   *
   * <p>{@link #publish} logs at debug when an event will not go, which is already quiet. This does
   * not log at all: deltas arrive in their hundreds per model call, on the thread reading the
   * model's response, and a line each would be the firehose again with a log file on the end of it.
   */
  private void stream(JobDelta delta) {
    try {
      events.stream(session, delta);
    } catch (RuntimeException broken) {
      // Nothing. A lost delta costs nobody anything -- the answer is the
      // outcome's text and arrives whole -- and this runs where noise is
      // most expensive.
    }
  }

  private void publish(JobEvent event) {
    try {
      events.publish(session, event);
    } catch (RuntimeException broken) {
      // The type and not the message; see the class javadoc. DEBUG rather
      // than WARN because a droppable stream failing is not an operator's
      // problem, and a job that is producing events is producing them at a
      // rate that would make a warning a flood.
      log.debug(
          "a '{}' event for job {} could not be published ({})",
          event.kind(),
          job,
          broken.getClass().getSimpleName());
    }
  }
}
