package io.aeyer.plowshare.server.faults;

/**
 * The caller sent something this server can name as wrong, raised by code that has no HTTP surface
 * of its own.
 *
 * <h2>Why this exists beside {@code api.BadRequestException}</h2>
 *
 * <p>They mean the same thing and map to the same status. What differs is who may raise them.
 * {@code BadRequestException} belongs to the HTTP surface: a controller throws it, {@code
 * ApiExceptionHandler} maps it, and the caller gets a 400. This one belongs to everything under
 * that surface — an agent tool, a service, a helper moved down out of a controller — where there is
 * no request, no status, and often no HTTP at all.
 *
 * <p><b>The distinction is not tidiness; it was a conflation with a guard on it.</b> {@code
 * SearchTool} and {@code FetchTool} are in-process agent tools: an agent calls one, the throw
 * reaches the agent runtime, and the handler never runs. They were importing the HTTP surface's
 * type to say "the caller was wrong", which made one type an HTTP mapping key in one place and a
 * domain signal in another. {@code
 * InvariantsTest.the_http_surfaces_caller_fault_type_is_held_by_exactly_one_file_outside_it} pinned
 * that so it could not widen quietly, and this type is what lets it shrink instead.
 *
 * <p><b>Whose fault is this?</b> is the question {@code ApiExceptionHandler} says every line of it
 * answers. This type is that question answered in the domain's own words rather than in HTTP's.
 *
 * <p>Unchecked, for {@code archive.ValidationException}'s reason: a caller fault sits on every
 * path, and a checked exception would force every frame between here and a surface to handle or
 * re-declare a failure that is never recoverable without the caller changing what it sent.
 */
public class CallerFault extends RuntimeException {

  public CallerFault(String message) {
    super(message);
  }

  public CallerFault(String message, Throwable cause) {
    super(message, cause);
  }
}
