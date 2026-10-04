package io.aeyer.plowshare.server.faults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.server.agents.DefinitionAlreadyExistsException;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.api.BadRequestException;
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
import org.junit.jupiter.api.Test;

/**
 * One test per row of {@code ApiExceptionHandler}'s table (row 1 excepted, see below), plus the
 * hazards {@code dispatcher-survey.md} names.
 *
 * <p>Every test reads {@link Fault#code()} rather than a raw status number, so a passing suite is
 * also a check that {@link Faults} answers in {@link Code}'s own vocabulary and not a second one
 * that happens to agree today.
 */
class FaultsTest {

  // -- Row 2: CallerFault -------------------------------------------------

  @Test
  void a_caller_fault_is_bad_request() {
    Fault fault = Faults.of(new CallerFault("a blank project"));

    assertEquals(Code.BAD_REQUEST, fault.code());
    assertEquals("a blank project", fault.detail());
  }

  // -- Row 3: NotFoundException --------------------------------------------

  @Test
  void an_unresolved_handle_is_not_found() {
    Fault fault = Faults.of(new NotFoundException("no job called that"));

    assertEquals(Code.NOT_FOUND, fault.code());
    assertEquals("no job called that", fault.detail());
  }

  /**
   * The {@code faults} twin of row 3, raised by code that has no HTTP surface to throw {@link
   * NotFoundException} from.
   *
   * <p>Same {@link Code} and the same sentence, so the only thing that differs between a job id
   * refused to a controller and the same id refused to a frame handler is which surface asked.
   * Without this row a {@link NotFoundFault} would reach {@link
   * #an_unclassified_exception_is_the_catch_all()}'s 500, which is exactly the drift this class
   * exists to catch.
   */
  @Test
  void a_handle_named_below_the_http_surface_that_resolves_to_nothing_is_not_found() {
    Fault fault = Faults.of(new NotFoundFault("no job called 'job_999999'"));

    assertEquals(Code.NOT_FOUND, fault.code());
    assertEquals("no job called 'job_999999'", fault.detail());
  }

  // -- Row 4: ConflictException ---------------------------------------------

  @Test
  void a_name_collision_is_a_conflict() {
    Fault fault = Faults.of(new ConflictException("an agent already sits at that name"));

    assertEquals(Code.CONFLICT, fault.code());
    assertEquals("an agent already sits at that name", fault.detail());
  }

  /**
   * A definition name already on disk is a 409, and its <em>own</em> entry is what says so — not
   * anything reached by walking up from it.
   *
   * <p><b>{@code ArchiveRefusedException}'s hazard, pointing the other way.</b> {@link
   * DefinitionAlreadyExistsException} extends {@link IllegalArgumentException}, a type deliberately
   * absent from {@link Faults}'s table, so this row is reached only by {@link Faults#of} matching
   * the thrown class itself before it looks at any ancestor. The last two assertions are the
   * discriminating half, and without them this test would keep passing on a walk that had been
   * inverted: the superclass really is {@code IllegalArgumentException}, and that superclass really
   * does answer the catch-all, so the 409 above can only have come from this type's own row. A
   * future row mapping {@code IllegalArgumentException} to anything would be caught here rather
   * than in production, where it would show up as {@code
   * AgentDefinitionApiTest.replacing_an_existing_definition_without_overwrite_is_409} quietly
   * changing status.
   */
  @Test
  void a_definition_that_already_exists_is_a_conflict_before_its_superclass_is_consulted() {
    Fault fault =
        Faults.of(
            new DefinitionAlreadyExistsException("a definition called 'helper' is already there"));

    assertEquals(Code.CONFLICT, fault.code());
    assertEquals("a definition called 'helper' is already there", fault.detail());

    assertEquals(
        IllegalArgumentException.class,
        DefinitionAlreadyExistsException.class.getSuperclass(),
        "this test discriminates only while that is the superclass being skipped");
    assertEquals(
        Code.INTERNAL_ERROR,
        Faults.of(
                new IllegalArgumentException("a definition called 'helper' is" + " already there"))
            .code(),
        "the superclass answers the catch-all, so the 409 above is this type's own row");
  }

  // -- Row 5: ArchiveException ------------------------------------------

  @Test
  void an_archive_reference_that_does_not_resolve_is_not_found() {
    Fault fault = Faults.of(new ArchiveException("no memory called that"));

    assertEquals(Code.NOT_FOUND, fault.code());
    assertEquals("no memory called that", fault.detail());
  }

  /**
   * An unlisted subclass of {@code ArchiveException} still answers 404, by walking up the class
   * hierarchy to the entry that <em>is</em> listed rather than falling through to the catch-all for
   * lack of an exact-class match. A plain {@code Map<Class<?>, Fault>} keyed by {@code getClass()}
   * with no such walk would send this straight to {@link
   * #an_unclassified_exception_is_the_catch_all()}'s 500 instead.
   */
  @Test
  void an_unlisted_subclass_of_archive_exception_still_resolves_to_not_found() {
    class SomeOtherArchiveFailure extends ArchiveException {
      SomeOtherArchiveFailure(String message) {
        super(message);
      }
    }

    Fault fault = Faults.of(new SomeOtherArchiveFailure("a target nothing indexes"));

    assertEquals(Code.NOT_FOUND, fault.code());
  }

  // -- Row 6: ArchiveRefusedException, the hazard's own pin ---------------

  /**
   * {@code ArchiveRefusedException extends ArchiveException}, and the two rows disagree — 404 for
   * the supertype, 409 for this one. {@link Faults#of} must answer 409 here, which is only true if
   * resolution looks at the thrown class before it looks at any of its ancestors. See {@link
   * Faults}'s own javadoc for how that is made structural rather than a matter of which order two
   * {@code Map} entries happen to be declared in.
   */
  @Test
  void an_archive_refusal_is_a_conflict_and_not_a_missing_row() {
    Fault fault = Faults.of(new ArchiveRefusedException("that tombstone will not be promoted"));

    assertEquals(Code.CONFLICT, fault.code());
    assertEquals("that tombstone will not be promoted", fault.detail());
  }

  // -- Row 7: Turn.Refused --------------------------------------------------

  @Test
  void a_conversation_that_will_not_take_a_turn_is_a_conflict() {
    Fault fault = Faults.of(new Turn.Refused("conversation c1 has spent all its budget"));

    assertEquals(Code.CONFLICT, fault.code());
    assertEquals("conversation c1 has spent all its budget", fault.detail());
  }

  // -- Row 8: PresenceConflictException -------------------------------------

  @Test
  void a_live_presence_in_the_way_is_a_conflict() {
    Fault fault = Faults.of(new PresenceConflictException("that project is taken"));

    assertEquals(Code.CONFLICT, fault.code());
    assertEquals("that project is taken", fault.detail());
  }

  // -- Row 9: UnreadableDocumentException -----------------------------------

  @Test
  void an_unreadable_document_is_unsupported() {
    Fault fault = Faults.of(new UnreadableDocumentException("no reader for .foo"));

    assertEquals(Code.UNSUPPORTED_DOCUMENT, fault.code());
    assertEquals("no reader for .foo", fault.detail());
  }

  // -- Row 10: UnstorableImageException, all three reasons -----------------

  @Test
  void an_empty_image_upload_is_bad_request() {
    Fault fault =
        Faults.of(
            new UnstorableImageException(UnstorableImageException.Reason.EMPTY, "nothing arrived"));

    assertEquals(Code.IMAGE_NOT_STORED_EMPTY, fault.code());
    assertEquals(400, fault.httpStatus());
    assertEquals("image_not_stored", fault.error());
  }

  @Test
  void an_unrecognised_image_format_is_unsupported() {
    Fault fault =
        Faults.of(
            new UnstorableImageException(
                UnstorableImageException.Reason.UNRECOGNISED, "not one of the four formats"));

    assertEquals(Code.IMAGE_NOT_STORED_UNRECOGNISED, fault.code());
    assertEquals(415, fault.httpStatus());
    assertEquals("image_not_stored", fault.error());
  }

  @Test
  void a_too_large_image_is_payload_too_large() {
    Fault fault =
        Faults.of(
            new UnstorableImageException(
                UnstorableImageException.Reason.TOO_LARGE, "over the cap"));

    assertEquals(Code.IMAGE_NOT_STORED_TOO_LARGE, fault.code());
    assertEquals(413, fault.httpStatus());
    assertEquals("image_not_stored", fault.error());
  }

  // -- Row 11: ValidationException -------------------------------------------

  @Test
  void a_proposal_that_fails_validation_is_unprocessable() {
    Fault fault = Faults.of(new ValidationException("no reason was given"));

    assertEquals(Code.VALIDATION_FAILED, fault.code());
    assertEquals("no reason was given", fault.detail());
  }

  // -- Row 12: ArchiveUnavailableException -----------------------------------

  @Test
  void an_unreachable_archive_is_service_unavailable_and_says_nothing_was_lost() {
    Fault fault =
        Faults.of(
            new ArchiveUnavailableException("the connection pool has no free connections", null));

    assertEquals(Code.ARCHIVE_UNAVAILABLE, fault.code());
    assertTrue(fault.detail().contains("Nothing was read and nothing was written"));
    assertTrue(fault.detail().contains("the connection pool has no free connections"));
  }

  // -- Row 13: EmbeddingException ---------------------------------------------

  @Test
  void an_unreachable_embedding_endpoint_is_service_unavailable() {
    Fault fault = Faults.of(new EmbeddingException("connection refused", null));

    assertEquals(Code.EMBEDDING_UNAVAILABLE, fault.code());
    assertTrue(fault.detail().contains("connection refused"));
    assertTrue(fault.detail().contains("archive itself is intact"));
  }

  // -- Row 14: ConfigUnavailableException --------------------------------------

  @Test
  void an_unreachable_config_map_is_service_unavailable_and_does_not_blame_the_request() {
    Fault fault = Faults.of(new ConfigUnavailableException("the database went away", null));

    assertEquals(Code.CONFIG_UNAVAILABLE, fault.code());
    assertTrue(fault.detail().contains("the database went away"));
    assertTrue(fault.detail().contains("There is nothing wrong with the request"));
  }

  // -- Row 14a: ModelUnavailableException ---------------------------------------

  @Test
  void an_unreachable_model_is_service_unavailable_and_says_only_its_own_sentence() {
    Fault fault =
        Faults.of(
            new ModelUnavailableException(
                "the model that reads schedules could not be reached", null));

    assertEquals(Code.MODEL_UNAVAILABLE, fault.code());
    assertTrue(
        fault.detail().contains("the model that reads schedules could not be reached"),
        fault.detail());
    assertTrue(fault.detail().contains("nothing wrong with the request"), fault.detail());
  }

  // -- Row 15: the catch-all -----------------------------------------------

  @Test
  void an_unclassified_exception_is_the_catch_all() {
    Fault fault = Faults.of(new IllegalStateException("something nobody classified"));

    assertEquals(Code.INTERNAL_ERROR, fault.code());
    assertTrue(fault.detail().contains("IllegalStateException"));
    assertTrue(fault.detail().contains("something nobody classified"));
    assertTrue(fault.detail().contains("fault in the server, not in the request"));
  }

  /**
   * {@code BadRequestException} is deliberately not one of {@link Faults}'s cases, and this pins
   * that it is not by accident.
   *
   * <p>{@code InvariantsTest.the_http_surfaces_caller_fault_type_is_held_by_
   * exactly_one_file_outside_it} holds {@code BadRequestException} to exactly one holder outside
   * {@code api/}: {@code RuntimeConfigController}. {@link Faults} lives outside {@code api/} too,
   * so it cannot import that type without becoming a second holder and failing that guard — and it
   * does not need to: {@code BadRequestException} can only ever be thrown by code inside {@code
   * api/}, the same invariant holds that boundary shut, so no frame handler calling {@link
   * Faults#of} can ever be handed one. {@code ApiExceptionHandler.badRequest} maps this row
   * directly instead, reading {@code Code.BAD_REQUEST} the same way this class does. If this test
   * ever starts failing because {@code Faults} gained a case for it, that is the invariant above
   * breaking, not this one.
   */
  @Test
  void bad_request_exception_is_not_one_of_this_classs_cases_on_purpose() {
    Fault fault = Faults.of(new BadRequestException("a blank project"));

    assertEquals(Code.INTERNAL_ERROR, fault.code());
  }
}
