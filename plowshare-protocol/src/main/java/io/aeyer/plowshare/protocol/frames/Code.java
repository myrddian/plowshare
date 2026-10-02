package io.aeyer.plowshare.protocol.frames;

/**
 * The one status vocabulary the socket and HTTP surfaces share.
 *
 * <h2>Extracted from a table, not invented beside it</h2>
 *
 * <p>{@code ApiExceptionHandler}'s {@code @ExceptionHandler} table is the
 * existing exception-to-status mapping this enum exists to reuse rather than
 * duplicate — the spec's own words for why a second mapping over one set of
 * exceptions is the drift this migration most risks. That table has fifteen
 * rows, but a row is not an outcome: several rows answer the same status with
 * the same slug, for reasons the handler's own javadoc argues at length
 * ({@code ArchiveRefusedException} extending {@code ArchiveException} so
 * Spring's most-specific rule can pick 409 over 404 is one row's story, not
 * two distinct outcomes).
 *
 * <h2>Sixteen constants: twelve from the table, and four successes</h2>
 *
 * <p>One constant per distinct {@code (status, slug)} pair the table actually
 * produces, plus {@link #OK} for the response the table never had to describe
 * because nothing failed:
 *
 * <ul>
 *   <li>{@link #BAD_REQUEST} folds rows 1 and 2 ({@code BadRequestException},
 *       {@code CallerFault}) — the same status and the same slug, differing
 *       only in which layer is allowed to raise them;
 *   <li>{@link #NOT_FOUND} folds rows 3 and 5 ({@code NotFoundException},
 *       {@code ArchiveException});
 *   <li>{@link #CONFLICT} folds rows 4, 6, 7 and 8 ({@code ConflictException},
 *       {@code ArchiveRefusedException}, {@code Turn.Refused}, {@code
 *       PresenceConflictException}) — four exception types the handler
 *       deliberately keeps apart, because each is a different layer's decision,
 *       but that answer the same status with the same slug;
 *   <li>row 10 ({@code UnstorableImageException}) runs the fold backwards: one
 *       slug, {@code image_not_stored}, answering three different statuses from
 *       the exception's own {@code Reason} switch. One slug driving three
 *       statuses is three outcomes, not one, so it becomes three constants —
 *       {@link #IMAGE_NOT_STORED_EMPTY}, {@link #IMAGE_NOT_STORED_UNRECOGNISED},
 *       {@link #IMAGE_NOT_STORED_TOO_LARGE} — rather than collapsing the way
 *       {@link #CONFLICT} did;
 *   <li>rows 9, 11, 12, 13 and 14 are each already their own {@code (status,
 *       slug)} pair: {@link #UNSUPPORTED_DOCUMENT}, {@link #VALIDATION_FAILED},
 *       {@link #ARCHIVE_UNAVAILABLE}, {@link #EMBEDDING_UNAVAILABLE}, {@link
 *       #CONFIG_UNAVAILABLE};
 *   <li>row 15, the catch-all, becomes {@link #INTERNAL_ERROR}.
 * </ul>
 *
 * <p>Twelve from the table, plus {@link #OK}, is thirteen. {@link
 * #MODEL_UNAVAILABLE} came later and from outside the table: {@code
 * schedule.read} waits on a model with a person waiting on it, and has no HTTP
 * twin for {@code ApiExceptionHandler} to hold a row for.
 *
 * <h2>And three more the table could never have named</h2>
 *
 * <p><b>Deriving this enum from a table of failures left it unable to say any
 * success but 200</b>, which was a real gap rather than a tidy one:
 * {@code ApiExceptionHandler} enumerates what went wrong, so 202, 204 and 201
 * appear nowhere in it — while six endpoints across four controllers answer 202
 * and two answer 201. A frame handler beside one of those had nothing true to
 * return, and the reachable mistake was {@link #OK}: telling a client its work
 * had finished when what it was handed is a job handle to poll.
 *
 * <p>{@link #ACCEPTED}, {@link #NO_CONTENT} and {@link #CREATED} close it, and
 * {@code FrameShapeTest.every_success_a_controller_can_answer_is_one_a_frame_can_say}
 * is what would notice the next one — it reads the controllers rather than this
 * file, so a status added over there fails over here.
 *
 * <h2>Why a constant carries an HTTP status at all</h2>
 *
 * <p>The whole point of extracting this table is that a socket frame and an
 * HTTP response must never disagree about what one exception means, and a
 * mapping that lived only on the HTTP side would leave the socket side to
 * reconstruct a status from the slug — which is a second mapping wearing a
 * first mapping's clothes. {@link #httpStatus()} is read by whatever produces
 * a {@code ResponseEntity}; the enum constant itself, carried as {@link
 * Outcome#code()}, is what a socket frame's wire value is. Neither surface
 * computes its answer from the other's vocabulary; both read this one.
 *
 * <h2>{@link #OK} has no slug</h2>
 *
 * <p>A slug is a word for the failure a caller can act on — {@code
 * "not_found"}, {@code "conflict"}. A response that succeeded has nothing of
 * that kind to say, and giving it a slug anyway would train a client to switch
 * on {@code "ok"} the way it switches on the rest, which is a habit not worth
 * building for a case that {@link #httpStatus()} alone already answers. {@link
 * #OK} carries a {@code null} slug, matching the way {@link Outcome#said()}
 * carries {@code null} rather than a placeholder for a server that has nothing
 * to say.
 */
public enum Code {

    /** The request succeeded. Carries no slug — see the class javadoc. */
    OK(200, null),

    /**
     * The request was taken and is not finished. Carries no slug, on {@link #OK}'s
     * reasoning.
     *
     * <p><b>This one was missing, and the reason is worth recording.</b> This
     * enum was first derived from {@code ApiExceptionHandler}'s table, which
     * enumerates <em>failures</em> — so every status the HTTP surface answers
     * that is neither 200 nor a fault had no constant at all. Six endpoints
     * across four controllers answer 202: an agent run, a resumed conversation,
     * a digest, and two document reads. Each returns a job handle a caller polls,
     * which is exactly the shape this code exists to distinguish from a finished
     * answer — a frame that reported 200 for one would tell a client its work was
     * done.
     */
    ACCEPTED(202, null),

    /**
     * The request succeeded and there is nothing to send back. Carries no slug,
     * on {@link #OK}'s reasoning.
     *
     * <p>Distinct from {@link #OK} with an empty payload, and the distinction is
     * the caller's: an empty list is an answer about the world, and no content is
     * a statement that this endpoint never had one to give.
     */
    NO_CONTENT(204, null),

    /**
     * Something is there now that was not before. Carries no slug, on {@link #OK}'s
     * reasoning.
     *
     * <p>Kept apart from {@link #OK} because the HTTP surface already keeps them
     * apart — {@code AgentController.define} answers 200 or 201 from one method
     * depending on whether the write created the definition or replaced it, and a
     * frame collapsing the two would drop a fact a caller can currently read.
     */
    CREATED(201, null),

    /**
     * The caller's fault, correctable by sending different arguments.
     * {@code ApiExceptionHandler} rows 1–2: {@code BadRequestException} and
     * {@code CallerFault}.
     */
    BAD_REQUEST(400, "bad_request"),

    /**
     * A reference that does not resolve. {@code ApiExceptionHandler} rows 3
     * and 5: {@code NotFoundException} and {@code ArchiveException}.
     */
    NOT_FOUND(404, "not_found"),

    /**
     * Something is already there, or already in a state that will not take
     * this. {@code ApiExceptionHandler} rows 4, 6, 7 and 8: {@code
     * ConflictException}, {@code ArchiveRefusedException}, {@code
     * Turn.Refused}, {@code PresenceConflictException}.
     */
    CONFLICT(409, "conflict"),

    /**
     * The request was well formed and named a document format this server
     * does not read. {@code ApiExceptionHandler} row 9: {@code
     * UnreadableDocumentException}.
     */
    UNSUPPORTED_DOCUMENT(415, "unsupported_document"),

    /**
     * An image was not stored because nothing was sent. One of three statuses
     * {@code UnstorableImageException} answers with the one slug {@code
     * image_not_stored} — see the class javadoc's row-10 entry.
     */
    IMAGE_NOT_STORED_EMPTY(400, "image_not_stored"),

    /**
     * An image was not stored because its format is not one this server
     * recognises. The second of {@code UnstorableImageException}'s three
     * statuses.
     */
    IMAGE_NOT_STORED_UNRECOGNISED(415, "image_not_stored"),

    /**
     * An image was not stored because it is too large. The third of {@code
     * UnstorableImageException}'s three statuses.
     */
    IMAGE_NOT_STORED_TOO_LARGE(413, "image_not_stored"),

    /**
     * A well-formed request whose content fails a domain rule a 400 does not
     * quite name. {@code ApiExceptionHandler} row 11: {@code
     * ValidationException}.
     */
    VALIDATION_FAILED(422, "validation_failed"),

    /**
     * The archive could not be reached; nothing was read and nothing was
     * written. {@code ApiExceptionHandler} row 12: {@code
     * ArchiveUnavailableException}.
     */
    ARCHIVE_UNAVAILABLE(503, "archive_unavailable"),

    /**
     * The embedding endpoint did not answer. {@code ApiExceptionHandler} row
     * 13: {@code EmbeddingException}.
     */
    EMBEDDING_UNAVAILABLE(503, "embedding_unavailable"),

    /**
     * The runtime configuration map could not be reached. {@code
     * ApiExceptionHandler} row 14: {@code ConfigUnavailableException}.
     */
    CONFIG_UNAVAILABLE(503, "config_unavailable"),

    /**
     * The model a synchronous reading needed did not answer; nothing was
     * proposed and nothing was saved. Not a row of {@code ApiExceptionHandler}'s
     * table: {@code schedule.read} is a frame with no HTTP twin. {@code Faults}:
     * {@code events.ModelUnavailableException}.
     */
    MODEL_UNAVAILABLE(503, "model_unavailable"),

    /**
     * Nobody classified this failure. {@code ApiExceptionHandler} row 15, the
     * {@code Throwable} catch-all.
     */
    INTERNAL_ERROR(500, "internal_error");

    private final int httpStatus;
    private final String slug;

    Code(int httpStatus, String slug) {
        this.httpStatus = httpStatus;
        this.slug = slug;
    }

    /**
     * The HTTP status this outcome answers with, on the surface that still
     * has one.
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * {@code ApiExceptionHandler}'s {@code "error"} body key, for every
     * constant but {@link #OK} — which returns {@code null}, per the class
     * javadoc.
     */
    public String slug() {
        return slug;
    }
}
