package io.aeyer.plowshare.server.faults;

/**
 * The caller named a handle and nothing is under it, raised by code that has no HTTP surface of its
 * own.
 *
 * <h2>Why this exists beside {@code api.NotFoundException}</h2>
 *
 * <p>{@link CallerFault}'s argument, one status along. The two mean the same thing and answer the
 * same {@code Code}; what differs is who may raise them. {@code NotFoundException} belongs to the
 * HTTP surface — a controller throws it, {@code ApiExceptionHandler} maps it, the caller gets a
 * 404. This one belongs to everything under that surface, where there is no request, no status, and
 * often no HTTP at all.
 *
 * <p><b>What it was invented for, and what it makes possible.</b> {@code JobStore.get} meant "no
 * job called that" and said it as an {@code IllegalArgumentException}, a type whose plain reading
 * is "bad argument". {@code AgentController.find} then caught it by name and restated it as a
 * {@code NotFoundException}, which is the only reason {@code GET /v1/jobs/&#123;id&#125;} answers
 * 404 today. A frame handler calling the same store would reach {@link Faults}'s catch-all instead
 * and answer <b>500</b> for an id that was merely misspelled. Naming the fault in a type both
 * surfaces resolve is what removes that difference, rather than teaching a second dispatcher the
 * same translation.
 *
 * <p><b>A sibling of {@link CallerFault}, not a subclass of it.</b> Extending it would be
 * defensible — a caller naming something absent is a caller fault in the ordinary sense — and it
 * was rejected for a specific reason: nothing catches {@code CallerFault} today, and a subtype
 * would make the first {@code catch (CallerFault)} anybody writes silently swallow 404s into 400s.
 * The two answer different statuses, so they are two types, and {@link Faults#of} keeps them apart
 * structurally rather than by anyone remembering which is which.
 *
 * <p><b>Not an existing archive type, either.</b> {@code ArchiveException} already maps to 404 and
 * would have needed no new class, but it means "the archive does not hold this row" — a statement
 * about Postgres — and a job is a handle in one process's map that a restart really does lose, by
 * design. {@code ApiExceptionHandler.noSuchHandle}'s own javadoc draws exactly that line and says
 * why: same status, different vocabulary, and neither message has to hedge about the other's
 * subject. Borrowing the archive's type for an in-memory miss would have put a database's name on a
 * fact that has nothing to do with one.
 *
 * <p>Unchecked, for {@link CallerFault}'s reason: a handle that does not resolve sits on every
 * path, and a checked exception would force every frame between here and a surface to handle or
 * re-declare a failure that is never recoverable without the caller naming something else.
 */
public class NotFoundFault extends RuntimeException {

  public NotFoundFault(String message) {
    super(message);
  }

  public NotFoundFault(String message, Throwable cause) {
    super(message, cause);
  }
}
