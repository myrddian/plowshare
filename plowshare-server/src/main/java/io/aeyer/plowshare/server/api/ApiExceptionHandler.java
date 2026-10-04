package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.config.ConfigUnavailableException;
import io.aeyer.plowshare.server.documents.UnreadableDocumentException;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.Fault;
import io.aeyer.plowshare.server.faults.Faults;
import io.aeyer.plowshare.server.images.UnstorableImageException;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.session.PresenceConflictException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps the archive's unchecked exceptions to HTTP status codes, mirroring Anchor's {@code
 * ApiExceptionHandler}.
 *
 * <h2>"Whose fault is this?" is the question every line here answers</h2>
 *
 * <p>The reader of these statuses is a model, and what it does next is decided by them: a 4xx makes
 * it change its request, a 503 makes it wait and retry, a 500 makes it stop and report that the
 * archive is broken. Sending the wrong one sends the agent off fixing something it cannot reach.
 * That is not hypothetical — see {@link BadRequestException} for the misconfigured base URL that
 * arrived at the model as a complaint about its own proposal.
 *
 * <ul>
 *   <li>{@link BadRequestException} → 400 Bad Request. <b>The caller's fault, and nothing else.</b>
 *       This surface throws it for a blank project, a blank question, an invalidation with no
 *       reason — things the caller can correct by sending different arguments. It replaces a
 *       blanket {@code IllegalArgumentException} rule whose own javadoc called itself narrow while
 *       catching every {@code IllegalArgumentException} in the stack.
 *   <li>{@link io.aeyer.plowshare.server.faults.CallerFault} → 400 Bad Request, the same as {@link
 *       BadRequestException} and for the same reason. The two differ only in who may raise them:
 *       that one is this surface's, and this one belongs to the code underneath it, which has no
 *       status to return and no request to answer. See {@code CallerFault}'s own javadoc for why
 *       one type serving both jobs was a conflation rather than a convenience.
 *   <li>{@link ArchiveException} → 404 Not Found. It is thrown for a read of an id nothing was ever
 *       written under, and for a verdict naming a target id that does not resolve — every case is a
 *       caller reference to an id the archive cannot honour, which is what 404 means. This is the
 *       rule the plan's own required test checks: an unknown id must not come back 200 with a null
 *       body, because a caller that cannot tell "no such memory" from "a memory with no content"
 *       eventually reports the wrong one to a user.
 *   <li>{@link ArchiveRefusedException} → 409 Conflict. <b>The row is there and the archive will
 *       not do this to it</b>: a promotion of a tombstone, a proposal a person already settled, a
 *       verdict naming a target in another tier. This is the case the paragraph here used to record
 *       as a three-part "Known imprecision" and could not fix, because the exception carried no
 *       discriminator beyond its message and telling the cases apart here would have meant matching
 *       on prose. It carries one now; {@code ArchiveRefusedException} holds the classification of
 *       every site, and it is not repeated here. The clause above stays the fallback, so a throw
 *       nobody classified still answers 404.
 *       <p><b>What is still imprecise, kept from that paragraph because it is still true:</b> a
 *       verdict with <em>no</em> target id is malformed rather than conflicting and 400 is what it
 *       means, and {@code PromotionQueue.approve}'s wrap turns a database that could not be reached
 *       into this too, where 503 is what that means. 409 is nearer than 404 for both. Neither is
 *       worth a third archive type for one throw site apiece.
 *   <li>{@link PresenceConflictException} → 409 Conflict, and by a third separate handler. <b>A
 *       live presence is in the way</b>: a project a running client roots cannot be moved out from
 *       under it, at either end of the move. Not an archive type because the archive is never asked
 *       — {@code ProjectController.move} refuses first, so "nothing was written" is exactly true.
 *   <li>{@link Turn.Refused} → 409 Conflict as well, and by a separate handler rather than by
 *       widening the one above. <b>The conversation is there and will not take this turn</b>: its
 *       budget is spent, or it already has one in flight. Not folded into {@code
 *       ArchiveRefusedException} because that type's javadoc carries a list of every site it covers
 *       and every one of them is an operation on a row; this decision is the agents layer's and is
 *       made before any row operation is attempted.
 *   <li>{@link ValidationException} → 422 Unprocessable Entity, matching Anchor's convention for a
 *       well-formed request whose content fails domain rules a 400 doesn't quite name.
 *   <li>{@link EmbeddingException} → 503 Service Unavailable. <b>A side service is down; the
 *       archive is fine.</b> A recall whose question cannot be embedded has to fail — there is
 *       nothing to rank by, and handing back whatever rows a fallback ordering produced would look
 *       exactly like a search that worked. But failing without being able to say <em>why</em> is
 *       its own bug: this exception was unmapped, so such a recall reached the model as a bare 500
 *       carrying Spring's error document, which reads as "the archive is broken" — a conclusion an
 *       agent acts on. 503 is the one status that means "ask again later", and later is exactly
 *       when this request succeeds.
 *   <li>{@link ConfigUnavailableException} → 503 Service Unavailable, and it is the one entry here
 *       whose reader is a <b>person</b> rather than a model. {@code RuntimeConfigController} is an
 *       operator surface with no agent tool behind it, deliberately. Before this handler existed a
 *       Postgres that had gone away reached that operator through the catch-all below — a 500
 *       ending "This is a fault in the server, not in the request", which sent the one person who
 *       could fix the database looking for a bug in this code. Reusing {@link
 *       ArchiveUnavailableException} would have fixed the status and left the body talking about
 *       what is remembered, which a configuration write is not; see that exception's own class
 *       comment for why the two are not one type.
 *   <li><b>Everything else</b> → 500 Internal Server Error, naming the exception. The catch-all is
 *       the point: an unmapped failure that escapes to Spring's default handling produces an error
 *       document whose {@code message} is blank unless {@code server.error.include-message} is set,
 *       so the agent is told a number and nothing else. Naming the type costs nothing here and is
 *       the difference between an operator who can grep for the fault and one who cannot.
 * </ul>
 *
 * <h2>Why this extends {@link ResponseEntityExceptionHandler}</h2>
 *
 * <p>Only because of the catch-all. A handler for {@code Throwable} in a bare advice class outranks
 * Spring's own {@code DefaultHandlerExceptionResolver}, so it would swallow the standard MVC
 * exceptions too and turn a malformed JSON body — a 400 if anything is — into a 500, which is the
 * very confusion the rest of this class exists to remove. This base class registers those
 * exceptions with their correct statuses as more specific handlers, which take precedence over
 * {@code Throwable}, leaving the catch-all with exactly the failures nobody has classified.
 *
 * <h2>Delegating to {@link Faults}, not deciding here</h2>
 *
 * <p>Every method below used to build its {@link ResponseEntity} from a status and a body it picked
 * itself. Now each one reads a {@link Fault} from {@link Faults#of(Throwable)} and only shapes it
 * into the {@code ResponseEntity} this surface returns — the status, the {@code error} slug, and
 * the {@code detail} sentence are decided in exactly one place, which is what lets a socket frame
 * dispatcher (which never runs through {@code @RestControllerAdvice}, since that machinery only
 * fires for HTTP dispatch) answer identically for the same exception without a second table to keep
 * in step with this one. The {@code @ExceptionHandler} annotations, and the number of methods
 * carrying them, are unchanged — Spring's own most-specific-handler resolution is what makes {@link
 * #conflict} win over {@link #notFound} for a thrown {@link ArchiveRefusedException}, exactly as it
 * did before this class delegated anything, and removing a method here would remove the annotation
 * Spring dispatches on, not just a line of logic.
 *
 * <p><b>{@link BadRequestException} is the one row {@link Faults} does not carry</b>, and {@link
 * #badRequest} below still builds its {@link Fault} inline rather than calling {@link
 * Faults#of(Throwable)}. See {@link Faults}'s own class javadoc for why: an {@code InvariantsTest}
 * guard holds that type to exactly one holder outside {@code api/}, and {@code Faults} living in
 * {@code faults/} would become an unargued second one the moment it imported it. Reading {@link
 * Code#BAD_REQUEST} directly here still answers from the one shared vocabulary; only the "which
 * exception is this" step for this single row stays local, and it stays local because this type
 * structurally cannot reach any other caller of {@link Faults#of}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /**
   * The one row {@link Faults} cannot carry — see this class's own javadoc, and {@code Faults}'s,
   * for why.
   */
  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<Map<String, String>> badRequest(BadRequestException e) {
    log.debug("malformed request: {}", e.getMessage());
    return toResponse(new Fault(Code.BAD_REQUEST, safeMessage(e)));
  }

  /**
   * The same fault as {@link #badRequest}, raised by code under the HTTP surface rather than on it.
   *
   * <p>Not folded into {@link #badRequest} by giving the two exceptions a common supertype: they
   * have none, and inventing one would put the HTTP surface's type and the domain's into a
   * hierarchy whose only purpose is to save four lines. See {@link CallerFault}'s own javadoc for
   * why one type serving both jobs was a conflation rather than a convenience.
   */
  @ExceptionHandler(CallerFault.class)
  public ResponseEntity<Map<String, String>> callerFault(CallerFault e) {
    return toResponse(Faults.of(e));
  }

  /**
   * A handle that does not resolve — a job id this process does not know.
   *
   * <p>Its own type rather than {@code ArchiveException}, because a job that is gone is an ordinary
   * answer and not a sign anything is broken: jobs live in memory and a restart really does lose
   * them, by design. Same status, different vocabulary, and neither message has to hedge about the
   * other's case.
   */
  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<Map<String, String>> noSuchHandle(NotFoundException e) {
    return toResponse(Faults.of(e));
  }

  /**
   * A write this surface refuses because something is already at the name it named, and not because
   * the request itself is wrong.
   *
   * <p>See {@link ConflictException}'s own class javadoc for why this is its own type rather than
   * folded into {@link #badRequest} or {@link #conflict}.
   */
  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<Map<String, String>> nameCollision(ConflictException e) {
    return toResponse(Faults.of(e));
  }

  @ExceptionHandler(ArchiveException.class)
  public ResponseEntity<Map<String, String>> notFound(ArchiveException e) {
    return toResponse(Faults.of(e));
  }

  /**
   * A row that exists and an operation the archive will not perform on it.
   *
   * <p>Written beside {@link #notFound} because the pair is the point, exactly as {@link
   * #archiveUnavailable} is written beside it for the other neighbour. {@code
   * ArchiveRefusedException} <em>extends</em> {@code ArchiveException}, so both handlers match and
   * Spring's most-specific rule is what decides — {@code
   * an_archive_refusal_is_a_conflict_and_not_a_missing_row} and {@code
   * an_unclassified_archive_failure_is_still_a_missing_row} fail in one direction each, because a
   * build that always took either handler satisfies the other test on its own. That the subclass
   * wins is a Spring behaviour and is measured by those two rather than assumed — and now that the
   * decision itself lives in {@link Faults}, {@code
   * FaultsTest.an_archive_refusal_is_a_conflict_and_not_a_missing_row} pins the same fact one layer
   * down, for the surface that has no Spring dispatch to rely on at all.
   */
  @ExceptionHandler(ArchiveRefusedException.class)
  public ResponseEntity<Map<String, String>> conflict(ArchiveRefusedException e) {
    return toResponse(Faults.of(e));
  }

  /**
   * A conversation that is there and cannot take this turn.
   *
   * <p>The same status as {@link #conflict} above and deliberately not the same type. That one
   * carries a list of every site classified as an archive refusal, and each is an operation on a
   * row; a conversation with nothing left to spend, or one already speaking, is a decision the
   * agents layer makes before any row operation is attempted. Folding it in would make that list
   * describe two layers and be checkable at neither.
   *
   * <p>409 rather than 400 because the caller cannot correct it by sending different arguments —
   * the utterance was well formed and the conversation is real. Rather than 500 because nothing is
   * broken: this is the server doing exactly what the budget was for.
   */
  @ExceptionHandler(Turn.Refused.class)
  public ResponseEntity<Map<String, String>> conversationRefused(Turn.Refused e) {
    return toResponse(Faults.of(e));
  }

  /**
   * A live presence is in the way of a claim on a project.
   *
   * <p>Its own handler for {@link Turn.Refused}'s reason: it is not an operation on a row and the
   * archive never sees it. {@code ProjectController.move} refuses before the store is asked at all,
   * because a project a live session roots cannot be renamed out from under that session — the
   * registry is runtime state keyed on the name, and nothing spans the two authorities.
   *
   * <p>409 and not 422: the request is well formed and the name is real. What is wrong is the state
   * of the system, and it is a state a person changes by closing a client — which is what the
   * message says and why it names the machine.
   */
  @ExceptionHandler(PresenceConflictException.class)
  public ResponseEntity<Map<String, String>> presenceInTheWay(PresenceConflictException e) {
    return toResponse(Faults.of(e));
  }

  /**
   * A document this server cannot turn into text.
   *
   * <p>{@code 415} and not {@code 400}: the request was well formed, and what it carried is a
   * format this server does not read. The distinction is the one a caller acts on — a {@code 400}
   * says "send this again, correctly", and there is no correct way to send a PDF to a server that
   * has no PDF library. What the message says instead is where conversion belongs.
   *
   * <p>And never {@code 503}. {@code EmbeddingException}'s {@code 503} tells a caller to try again
   * later; an upload refused by this type will be refused identically for ever, and advice that
   * cannot come true is worse than none.
   */
  @ExceptionHandler(UnreadableDocumentException.class)
  public ResponseEntity<Map<String, String>> unreadableDocument(UnreadableDocumentException e) {
    return toResponse(Faults.of(e));
  }

  /**
   * An upload this server will not hold, at one of three statuses.
   *
   * <p><b>Three and not one, which is the difference from {@link #unreadableDocument} above.</b>
   * Every refusal of that type is the same fact — this server does not read that format — so one
   * status says all of it. These are three different facts with three different remedies: send
   * something ({@code 400}), convert it ({@code 415}), or make it smaller ({@code 413}). A caller
   * told {@code 415} about a perfectly good PNG that was merely too big would convert it,
   * repeatedly, and get the same answer.
   *
   * <p>The status comes from {@code UnstorableImageException.Reason} and never from the message, so
   * that this method is not parsing prose to pick a number — that switch now lives in {@link
   * Faults}, and this method only asks for the answer.
   */
  @ExceptionHandler(UnstorableImageException.class)
  public ResponseEntity<Map<String, String>> unstorableImage(UnstorableImageException e) {
    return toResponse(Faults.of(e));
  }

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<Map<String, String>> invalidProposal(ValidationException e) {
    return toResponse(Faults.of(e));
  }

  /**
   * A database nobody could reach is 503 and never 404.
   *
   * <p>Written beside {@link #notFound} because the pair is the point. {@code ArchiveException} is
   * 404 and means "the archive does not hold that", which is an answer. {@code
   * ArchiveUnavailableException} means nobody got an answer at all, and a caller that took the 404
   * at face value would have taken a lie. They are unrelated types for exactly this reason; see
   * {@code an_unavailability_is_not_a_missing_record}.
   */
  @ExceptionHandler(ArchiveUnavailableException.class)
  public ResponseEntity<Map<String, String>> archiveUnavailable(ArchiveUnavailableException e) {
    return toResponse(Faults.of(e));
  }

  /**
   * The embedding endpoint could not be reached or would not answer.
   *
   * <p>Logged at warn rather than debug, unlike the three above: those are facts about one request,
   * and this is a fact about the deployment. Somebody has to see it. The logging itself now happens
   * inside {@link Faults#of(Throwable)} rather than here — see that class's javadoc for why moving
   * it there, rather than leaving it in this method, is what lets a frame dispatcher's calls to
   * {@link Faults#of} get the same log line an HTTP request would.
   */
  @ExceptionHandler(EmbeddingException.class)
  public ResponseEntity<Map<String, String>> embeddingUnavailable(EmbeddingException e) {
    return toResponse(Faults.of(e));
  }

  /**
   * The runtime configuration map could not be reached.
   *
   * <p>Written beside {@link #archiveUnavailable} rather than folded into it, and the two bodies
   * are the reason. That one closes by saying the failure "says nothing about what is remembered",
   * which is the sentence a caller of the memory API needs; an operator whose {@code PUT
   * /v1/config} failed needs to know that nothing was written and that the server is still running
   * on the values it already had. Same status, different question answered.
   *
   * <p>The body says plainly that the request was not at fault, because the sentence it replaces
   * said the opposite. {@code an_unreachable_map_does_not_blame_the_request} asserts that absence
   * directly rather than trusting this paragraph.
   *
   * <p>At {@code warn} for {@link #embeddingUnavailable}'s reason: a database nobody can reach is a
   * fact about the deployment, not about the one request that noticed.
   */
  @ExceptionHandler(ConfigUnavailableException.class)
  public ResponseEntity<Map<String, String>> configUnavailable(ConfigUnavailableException e) {
    return toResponse(Faults.of(e));
  }

  /**
   * Anything nobody classified: the archive, or the code around it, broke.
   *
   * <p>Logged with the stack trace, because unlike every other handler here this one has no idea
   * what happened and the trace is the only thing that does — that logging lives in {@code
   * Faults}'s own catch-all, reached the same way every other row is, through {@link
   * Faults#of(Throwable)}.
   */
  @ExceptionHandler(Throwable.class)
  public ResponseEntity<Map<String, String>> unexpected(Throwable e) {
    return toResponse(Faults.of(e));
  }

  /**
   * The one place a {@link Fault} becomes the {@link ResponseEntity} this surface returns: its
   * {@link Fault#httpStatus()} as the status, and a body of exactly {@link Fault#error()} and
   * {@link Fault#detail()} — the shape every method above has always returned.
   */
  private ResponseEntity<Map<String, String>> toResponse(Fault fault) {
    return ResponseEntity.status(HttpStatus.valueOf(fault.httpStatus()))
        .body(Map.of("error", fault.error(), "detail", fault.detail()));
  }

  private String safeMessage(Throwable e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
