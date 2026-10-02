package io.aeyer.plowshare.server.fetch;

/**
 * What one call to {@link PageFetcher#fetch} produced: a page, or the reason
 * there is not one — never both, and never an exception.
 *
 * <p>{@code failure == null} is what {@link #isOk()} actually tests, and it
 * is the field a caller should read rather than {@code page != null}: {@link
 * #ok} always pairs a page with a null failure and {@link #failed} always
 * pairs a failure with a null page, so the two tests agree on every value
 * this record's own factories can produce, but {@code failure} is the one
 * spelled out on {@link FetchFailure} — a caller asking "why not" reads it
 * directly instead of inferring absence from a null page.
 *
 * <h2>Never an exception, on {@code SearchProvider}'s own argument</h2>
 *
 * <p>{@link BuiltinFetcher#fetch} must never throw, for the reason {@code
 * SearchProvider}'s javadoc gives for {@code search}: the layer above this
 * port — {@code FetchService}, not built in this task — is the thing that
 * decides what a failure <em>means</em> for the agent that asked for a page:
 * report it back verbatim, retry it, or fall through to a different {@code
 * PageFetcher} once the reserved {@code FETCH} verb gives one a wire to
 * arrive over. That decision needs a value in hand to inspect. A thrown
 * exception would resolve it by accident, as whatever unwinds the caller's
 * own stack, and the specific taxonomy {@link FetchFailure} draws — a dead
 * host is not a bad status is not an unreadable content type — would be lost
 * to a single catch clause guessing from an exception's type and message.
 *
 * <h2>One {@code message} field, not one per failure kind</h2>
 *
 * <p>{@link FetchFailure#REMOTE_STATUS} carries its status code inside
 * {@code message} ({@code "HTTP 503"}) rather than as a numbered field
 * alongside it, on the same reasoning {@code RemoteSearchProvider} already
 * applies to its own non-2xx case: this record has exactly one thing to say
 * about a failure to a reader — human-legible detail — and every failure
 * kind here has exactly one such detail to give. A second field used by
 * exactly one of three enum cases would be null for the other two more often
 * than it would be read.
 */
public record FetchAnswer(String url, ExtractedPage page, FetchFailure failure, String message, byte[] sourceBytes, String mediaType, String finalUrl) {

    public FetchAnswer(String url, ExtractedPage page, FetchFailure failure, String message) {
        this(url, page, failure, message, null, null, url);
    }

    public boolean isOk() {
        return failure == null;
    }

    public static FetchAnswer ok(String url, ExtractedPage page) {
        return new FetchAnswer(url, page, null, null);
    }

    public static FetchAnswer failed(String url, FetchFailure failure, String message) {
        return new FetchAnswer(url, null, failure, message);
    }
}
