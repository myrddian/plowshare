package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.JobDelta;
import io.aeyer.plowshare.protocol.JobEvent;

/**
 * Somewhere to put a job's events, if anything is listening.
 *
 * <h2>Why this interface exists rather than the runtime holding a socket</h2>
 *
 * <p>The same argument {@code files.SessionChannel} makes for the other role, in the other
 * direction: {@code agents/} must not know about WebSockets, and {@code api/} already depends on
 * {@code agents/}. The one production implementation is {@code ws.EventChannelHandler}; putting the
 * seam here is what lets the dependency run in the one direction the module already runs in, and
 * what lets {@link JobStore} and {@link JobRuntime} be tested against a one-line fake rather than
 * against a port.
 *
 * <h2>Two rules for an implementation, and a job's liveness rests on both</h2>
 *
 * <ul>
 *   <li><b>it must not block.</b> {@link #publish} is called on the virtual thread running the job,
 *       between a budget claim and a model call, and a job whose next turn waits on a socket an
 *       operator has stopped reading is a job held hostage by a user interface. {@code
 *       EventChannelHandler} hands the event to a bounded queue drained by the listener's own
 *       thread and returns; there is nowhere in its {@code publish} that waits on the network;
 *   <li><b>it should not throw</b>, and {@link JobWatch} does not trust that: a publisher that
 *       throws must not end a run, because the event stream is droppable and the run is not.
 * </ul>
 *
 * <p><b>A session id that names nothing is an ordinary argument here.</b> Null for a run nobody's
 * client asked for, an id nothing ever attached to, a session holding only a file provider — all
 * three mean the same thing to this seam, and the answer to all three is that the event goes
 * nowhere. The stream is droppable: what a run came to is in {@link JobStore} behind {@code GET
 * /v1/jobs/&#123;id&#125;}, which is the only contractual record of it.
 */
@FunctionalInterface
public interface JobEvents {

  /**
   * Nobody is listening and nobody will be: every event is dropped. What a {@link JobStore} built
   * without a publisher uses, which is every context in this repository that has no event channel
   * wired.
   */
  JobEvents NONE = (session, event) -> {};

  /**
   * Deliver one event to whatever holds the listener role on {@code session}, or drop it.
   *
   * @param session the id the job was submitted under, or null for a job that was submitted with
   *     none
   * @param event what the job did. Never null
   */
  void publish(String session, JobEvent event);

  /**
   * Put one piece of what a model is producing in front of a listener.
   *
   * <p><b>Defaulted to doing nothing, and that is the opt-out for every implementor that has no
   * business streaming.</b> {@link #NONE} and the several test doubles that implement this
   * interface want lifecycle events and nothing else; a second abstract method would have made each
   * of them write an empty body to say so.
   *
   * <p>Everything {@link #publish} promises holds here too — it must not block, it must not throw a
   * run down — with one difference stated rather than inherited: <b>a delta may be dropped freely
   * and an event may not.</b> A lost event costs a client something it must ask {@code GET
   * /v1/jobs/&#123;id&#125;} to recover; a lost delta costs nothing at all, because the answer is
   * the outcome's text and arrives whole.
   *
   * @param session whose listener should see it, or null for nobody
   * @param delta the piece, never null
   */
  default void stream(String session, JobDelta delta) {
    // Nobody is watching the tokens. See the javadoc.
  }

  /**
   * Whether this session asked for deltas, so a caller can skip building them.
   *
   * <p><b>Not the same question as "will this be delivered".</b> {@link #stream} answers that for
   * itself and drops what nobody asked for. This exists so that a runtime producing deltas on the
   * thread reading a model's response can avoid <em>constructing</em> them at all — that thread is
   * the one every part of this design is about not slowing, and a {@code JobDelta} per chunk built
   * only to be dropped is work it does not have to do.
   *
   * <p>Defaulted to false, so an implementor that streams nothing says nothing.
   */
  default boolean streaming(String session) {
    return false;
  }
}
