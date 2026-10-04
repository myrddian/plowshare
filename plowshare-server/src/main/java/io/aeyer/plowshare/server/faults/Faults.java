package io.aeyer.plowshare.server.faults;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.server.agents.DefinitionAlreadyExistsException;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.api.ConflictException;
import io.aeyer.plowshare.server.api.NotFoundException;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.config.ConfigUnavailableException;
import io.aeyer.plowshare.server.documents.UnreadableDocumentException;
import io.aeyer.plowshare.server.events.ModelUnavailableException;
import io.aeyer.plowshare.server.images.UnstorableImageException;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.session.PresenceConflictException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one exception-to-{@link Code} mapping the socket and HTTP surfaces both read, so that {@code
 * ConflictException} means 409 in exactly one place.
 *
 * <h2>Extracted from {@code ApiExceptionHandler}, not copied beside it</h2>
 *
 * <p>{@code @RestControllerAdvice} never fires for a {@code WebSocketHandler} — Spring only
 * consults it while dispatching an HTTP request — so a frame dispatcher cannot reuse {@code
 * ApiExceptionHandler} by calling into it the way another controller would. The choice this class
 * answers is not "share the mapping or not", it is "share it by extracting the decision into a
 * function neither surface owns, or let the socket surface grow its own {@code instanceof} chain
 * beside the existing one." The spec's own words for the second option: "two mappings over one set
 * of exceptions is the drift this migration most risks." {@code ApiExceptionHandler} now calls
 * {@link #of(Throwable)} for every row it used to decide unassisted; see its class javadoc for the
 * one row that still doesn't (below).
 *
 * <h2>Resolution is structural, not a matter of declaration order</h2>
 *
 * <p>{@code ArchiveRefusedException extends ArchiveException}, and the table gives them different
 * statuses — 404 for the general case, 409 for this one. On the HTTP surface that works today only
 * because Spring's dispatcher picks the most specific {@code @ExceptionHandler} method for a thrown
 * type. This class has no framework doing that for it, so {@link #of(Throwable)} does it directly:
 * it walks {@code e.getClass()} and then {@link Class#getSuperclass()} repeatedly, returning the
 * first match found in {@link #MAPPERS}. Because {@code ArchiveRefusedException.class} is checked
 * before its superclass {@code ArchiveException.class} ever is, a thrown {@code
 * ArchiveRefusedException} always resolves through its own entry — this is true regardless of what
 * order the two entries were inserted into {@link #MAPPERS}, because the walk's order comes from
 * the JVM's own class hierarchy, not from iteration order over a {@code Map}. Nobody reshuffling
 * {@link #buildMappers()} can silently invert the two: {@code
 * FaultsTest.an_archive_refusal_is_a_conflict_and_not_a_missing_row} pins the specific case, and
 * {@code FaultsTest.an_unlisted_subclass_of_archive_exception_still_resolves_to_not_found} pins
 * that the walk also reaches an ancestor's entry for a type nobody listed by name, rather than
 * falling through to {@link #catchAll} for lack of an exact match — the failure mode a plain {@code
 * Map<Class<?>, Fault>.get(e.getClass())} lookup (no walk) would have had no way to avoid, and a
 * hand-ordered {@code instanceof} chain could get backwards by putting the general case first.
 *
 * <p><b>{@link DefinitionAlreadyExistsException} is the same hazard read from the other end.</b> It
 * extends {@code IllegalArgumentException}, which this table deliberately has no row for — "a bad
 * argument" is not a status — so its 409 is reached only because the walk matches the thrown class
 * before it looks at any ancestor. Nothing today would answer differently if the walk were
 * inverted, because the superclass resolves to nothing either way; what would change is the day
 * somebody adds an {@code IllegalArgumentException} row, at which point an inverted walk would
 * quietly turn every definition collision into whatever that row said. {@code
 * FaultsTest.a_definition_that_already_exists_is_a_conflict_before_its_superclass_is_consulted}
 * asserts the 409 <em>and</em> that the superclass answers {@link #catchAll}, which is what makes
 * it a pin on the walk rather than on the row.
 *
 * <h2>One exception type, three statuses</h2>
 *
 * <p>{@link UnstorableImageException} answers 400, 415 or 413 depending on its own {@link
 * UnstorableImageException.Reason}, not on which of three exception types was thrown — carried over
 * unchanged from {@code ApiExceptionHandler.unstorableImage}'s own reasoning for why this is one
 * mapper reading a field rather than three separate entries in {@link #MAPPERS}.
 *
 * <h2>Logging moved here, and did not stay in {@code ApiExceptionHandler}</h2>
 *
 * <p>The alternative was to leave every {@code log.debug}/{@code warn}/{@code error} call sitting
 * in {@code ApiExceptionHandler} and have this class return only the wire-facing {@link Fault}.
 * That would have left a socket frame that throws {@code ArchiveUnavailableException} with no log
 * line at all — nobody would learn the archive went down from a request that never touches {@code
 * ApiExceptionHandler} — or it would have made the future frame dispatcher reimplement the same
 * debug/warn/error table a second time, which is exactly the "two mappings over one set of
 * exceptions" drift this class exists to prevent, just moved from status codes to log levels. So
 * the decision that a caller-shaped fault logs at {@code debug}, a broken dependency logs at {@code
 * warn}, and an unclassified failure logs at {@code error} with its stack trace lives here, once,
 * and both surfaces get it for free by calling {@link #of(Throwable)}.
 *
 * <h2>What is deliberately not one of {@link #MAPPERS}'s entries</h2>
 *
 * <p>{@code BadRequestException} is row 1 of the table and is not mapped here. It cannot be: {@code
 * InvariantsTest. the_http_surfaces_caller_fault_type_is_held_by_exactly_one_file_outside_it} holds
 * that type to exactly one holder outside {@code api/} — {@code RuntimeConfigController} — and this
 * class, living in {@code faults/}, would become an unargued second holder the moment it imported
 * it. That is not a gap a socket frame handler can ever hit, because the same invariant is also why
 * nothing outside {@code api/} can throw the type in the first place: a frame handler calls the
 * same services a controller calls, never a controller, so it is structurally incapable of
 * receiving a {@code BadRequestException} to hand to {@link #of(Throwable)}. {@code
 * ApiExceptionHandler.badRequest} maps this one row directly, reading {@link Code#BAD_REQUEST} the
 * same way every mapper below does. {@code
 * FaultsTest.bad_request_exception_is_not_one_of_this_classs_cases_on_purpose} pins that this is
 * deliberate rather than a row somebody forgot.
 */
public final class Faults {

  private static final Logger log = LoggerFactory.getLogger(Faults.class);

  private static final Map<Class<? extends Throwable>, Function<Throwable, Fault>> MAPPERS =
      buildMappers();

  private Faults() {}

  /**
   * The {@link Fault} — a {@link Code} and a detail sentence — that {@code e} answers with, on
   * whichever surface asks.
   *
   * <p>Walks from {@code e.getClass()} up through {@link Class#getSuperclass()} until a class in
   * {@link #MAPPERS} is found, so a subclass always resolves through its own entry before an
   * ancestor's — see the class javadoc for why that walk, and not a plain keyed lookup or an {@code
   * instanceof} chain, is what makes that structural. Anything the walk finds no entry for at all —
   * including {@link Throwable} itself — falls to {@link #catchAll(Throwable)}.
   */
  public static Fault of(Throwable e) {
    for (Class<?> c = e.getClass(); c != null; c = c.getSuperclass()) {
      Function<Throwable, Fault> mapper = MAPPERS.get(c);
      if (mapper != null) {
        return mapper.apply(e);
      }
    }
    return catchAll(e);
  }

  private static Map<Class<? extends Throwable>, Function<Throwable, Fault>> buildMappers() {
    Map<Class<? extends Throwable>, Function<Throwable, Fault>> m = new HashMap<>();

    // Row 2: CallerFault. Same status and slug as row 1's BadRequestException,
    // which this class does not map at all -- see the class javadoc.
    m.put(
        CallerFault.class,
        e -> {
          log.debug("malformed request: {}", e.getMessage());
          return new Fault(Code.BAD_REQUEST, safeMessage(e));
        });

    // Row 3: a handle (e.g. a job id) that does not resolve.
    m.put(
        NotFoundException.class,
        e -> {
          log.debug("a handle did not resolve: {}", e.getMessage());
          return new Fault(Code.NOT_FOUND, safeMessage(e));
        });

    // Row 3a: the same thing, said by code below the HTTP surface. Row 3's
    // type may not leave api/, so a service that means "no such job" needs
    // one of its own -- see NotFoundFault's class javadoc.
    m.put(
        NotFoundFault.class,
        e -> {
          log.debug("a handle did not resolve: {}", e.getMessage());
          return new Fault(Code.NOT_FOUND, safeMessage(e));
        });

    // Row 4: a write that collided with something already there.
    m.put(
        ConflictException.class,
        e -> {
          log.debug("a write collided with something already there: {}", e.getMessage());
          return new Fault(Code.CONFLICT, safeMessage(e));
        });

    // Row 4a: a definition name already on disk that the caller did not ask
    // to replace. A subtype of IllegalArgumentException, which this table
    // has no entry for at all -- so this row is reached only by #of
    // matching the thrown class before any ancestor, exactly as row 6 is.
    // FaultsTest.a_definition_that_already_exists_is_a_conflict_before_its_
    // superclass_is_consulted pins both halves of that.
    m.put(
        DefinitionAlreadyExistsException.class,
        e -> {
          log.debug("a definition already sits at that name: {}", e.getMessage());
          return new Fault(Code.CONFLICT, safeMessage(e));
        });

    // Row 5: the archive does not hold what the caller named.
    m.put(
        ArchiveException.class,
        e -> {
          log.debug("archive reference did not resolve: {}", e.getMessage());
          return new Fault(Code.NOT_FOUND, safeMessage(e));
        });

    // Row 6: the archive holds the row and will not do this to it. Checked
    // before ArchiveException above by the walk in #of, never by ordering
    // here -- see the class javadoc.
    m.put(
        ArchiveRefusedException.class,
        e -> {
          log.debug("the archive refused an operation on a row it holds: {}", e.getMessage());
          return new Fault(Code.CONFLICT, safeMessage(e));
        });

    // Row 7: a conversation with nothing left to spend, or already speaking.
    m.put(
        Turn.Refused.class,
        e -> {
          log.debug("a conversation would not take a turn: {}", e.getMessage());
          return new Fault(Code.CONFLICT, safeMessage(e));
        });

    // Row 8: a live presence claims the project another claim named.
    m.put(
        PresenceConflictException.class,
        e -> {
          log.debug("a live presence is in the way: {}", e.getMessage());
          return new Fault(Code.CONFLICT, safeMessage(e));
        });

    // Row 9: a document format this server has no reader for.
    m.put(
        UnreadableDocumentException.class,
        e -> {
          log.debug("a document could not be read: {}", e.getMessage());
          return new Fault(Code.UNSUPPORTED_DOCUMENT, safeMessage(e));
        });

    // Row 10: one exception, three statuses from its own Reason.
    m.put(UnstorableImageException.class, e -> unstorableImage((UnstorableImageException) e));

    // Row 11: a well-formed request whose content fails a domain rule.
    m.put(
        ValidationException.class,
        e -> {
          log.debug("proposal failed validation: {}", e.getMessage());
          return new Fault(Code.VALIDATION_FAILED, safeMessage(e));
        });

    // Row 12: the archive could not be reached at all.
    m.put(
        ArchiveUnavailableException.class,
        e -> {
          log.warn("the archive could not be reached: {}", e.getMessage());
          return new Fault(
              Code.ARCHIVE_UNAVAILABLE,
              "the archive could not be reached, so this request was never"
                  + " answered: "
                  + safeMessage(e)
                  + ". Nothing was read and nothing was written, and this says"
                  + " nothing about what is remembered.");
        });

    // Row 13: the embedding endpoint did not answer.
    m.put(
        EmbeddingException.class,
        e -> {
          log.warn("the embedding endpoint did not answer: {}", e.getMessage());
          return new Fault(
              Code.EMBEDDING_UNAVAILABLE,
              "the embedding endpoint could not be reached, so this request"
                  + " could not be answered: "
                  + safeMessage(e)
                  + ". The archive itself is intact and nothing was lost; this"
                  + " will work again once the endpoint is back.");
        });

    // Row 14: the runtime configuration map could not be reached.
    m.put(
        ConfigUnavailableException.class,
        e -> {
          log.warn("the runtime configuration map could not be reached: {}", e.getMessage());
          return new Fault(
              Code.CONFIG_UNAVAILABLE,
              "the runtime configuration map could not be reached, so this"
                  + " request was never answered: "
                  + safeMessage(e)
                  + ". Nothing was read and nothing was written, and this server"
                  + " is still running on the values it already had. There is"
                  + " nothing wrong with the request; it will work once the"
                  + " database is reachable again.");
        });

    // Row 14a: a synchronous reading's model could not be reached. Frame-only -- schedule.read
    // has no HTTP twin -- so it is not a row of ApiExceptionHandler's table. The detail is
    // the exception's own sentence and never its cause's, which can name an endpoint; the
    // cause was logged, with its stack, where it was caught.
    m.put(
        ModelUnavailableException.class,
        e -> {
          log.debug("a model was unavailable: {}", e.getMessage());
          return new Fault(
              Code.MODEL_UNAVAILABLE,
              safeMessage(e)
                  + ". There is nothing wrong"
                  + " with the request; it will work once the model is reachable again.");
        });

    return Map.copyOf(m);
  }

  private static Fault unstorableImage(UnstorableImageException e) {
    log.debug("an image was not stored: {}", e.getMessage());
    Code code =
        switch (e.reason()) {
          case EMPTY -> Code.IMAGE_NOT_STORED_EMPTY;
          case UNRECOGNISED -> Code.IMAGE_NOT_STORED_UNRECOGNISED;
          case TOO_LARGE -> Code.IMAGE_NOT_STORED_TOO_LARGE;
        };
    return new Fault(code, safeMessage(e));
  }

  /**
   * Row 15: nobody classified this failure. Logged with the stack trace, because unlike every
   * mapper above this one has no idea what happened and the trace is the only thing that does.
   */
  private static Fault catchAll(Throwable e) {
    log.error("unhandled failure answering a request", e);
    return new Fault(
        Code.INTERNAL_ERROR,
        "the Plowshare server failed to answer this request: "
            + e.getClass().getSimpleName()
            + ": "
            + safeMessage(e)
            + ". This is a fault in the server, not in the request.");
  }

  private static String safeMessage(Throwable e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
