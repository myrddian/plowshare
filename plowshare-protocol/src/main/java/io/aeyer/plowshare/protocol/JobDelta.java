package io.aeyer.plowshare.protocol;

/**
 * A piece of what a model is producing, on its way past.
 *
 * <h2>Why this is not a {@link JobEvent}</h2>
 *
 * <p>It would have been one field's work to add a fifth {@code kind} and be done. <b>That is the
 * change this type exists to refuse</b>, for two reasons that are about different things.
 *
 * <p><b>The console consumes bare {@code JobEvent}s and its handler is an if/else-if on {@code
 * kind} with no else.</b> A fifth kind would reach it, and a firehose would reach it — measured
 * volumes are 6 571 characters of reasoning against 1 965 of answer on one call, arriving as
 * hundreds of deltas where the console expects four events a turn. A type it has never heard of
 * carries no {@code kind} at all and falls out of that chain without being mentioned, which is what
 * makes "do not edit the console" possible rather than merely intended.
 *
 * <p><b>And a {@code JobEvent} is something a client can recover.</b> Miss one and {@code GET
 * /v1/jobs/&#123;id&#125;} still says how the run ended; that is the whole reason the event queue
 * is allowed to drop. A delta is not recoverable and does not need to be — nothing is lost when one
 * goes missing, because {@code Outcome.text} is still the answer. The two have opposite delivery
 * requirements and sharing a type would invite them to share a queue, which is the exact failure
 * this design is built to prevent: <b>a token must never be able to evict an ending.</b>
 *
 * <h2>What it does not carry</h2>
 *
 * <p><b>No sequence number and no total.</b> Deltas are droppable by design, so a number that made
 * the holes countable would invite somebody to try to fill them — the same reasoning {@code
 * JobEvent} gives for not having one, arrived at from the other direction.
 *
 * <p><b>Not the assembled text.</b> A client watching these must not stitch its own answer and show
 * that: what an answer <em>is</em> stays {@code Outcome.text} from the final model call, and an
 * assembly that dropped a delta would disagree with the outcome while looking perfectly complete.
 *
 * @param job the job these belong to, as {@code GET /v1/jobs/&#123;id&#125;} spells it. One session
 *     may watch several runs at once, so a client that could not tell them apart would be watching
 *     an interleaving
 * @param part whether this is the model thinking or the model answering. <b>Carried rather than
 *     merged</b>: a terminal that ran the two together would be unreadable, and the volumes are not
 *     comparable
 * @param text the characters this delta added. Never null, never empty
 */
public record JobDelta(String job, Part part, String text) {

  /** Which of the two streams a delta belongs to. */
  public enum Part {

    /** The model reasoning. Never stored, never part of an answer. */
    THINKING,

    /** The model answering. The same characters the outcome will carry. */
    ANSWER
  }

  /** The model thinking, out loud. */
  public static JobDelta thinking(String job, String text) {
    return new JobDelta(job, Part.THINKING, text);
  }

  /** The model answering. */
  public static JobDelta answering(String job, String text) {
    return new JobDelta(job, Part.ANSWER, text);
  }
}
