package io.aeyer.plowshare.server.api;

/**
 * The caller sent something this surface can name as wrong: a blank project, a
 * blank question, an invalidation with no reason.
 *
 * <p><b>Why this type exists at all.</b> {@link ApiExceptionHandler} used to map
 * every {@link IllegalArgumentException} to 400, describing the rule in its own
 * javadoc as "deliberately narrow: this is the exception {@code Home.of} throws
 * for a blank project name". It was not narrow. {@code IllegalArgumentException}
 * is thrown by libraries, by the JDK and by ordinary bugs anywhere below a
 * controller, and every one of them was reported to the agent as a complaint
 * about its own proposal. That is not hypothetical: an operator who set {@code
 * plowshare.llm.base-url} to {@code localhost:1234/v1} — no {@code http://} —
 * made OkHttp throw {@code IllegalArgumentException}, and a write that failed
 * because the <em>server</em> was misconfigured came back {@code 400
 * bad_request}, as though the memory had been the problem. An agent told its
 * proposal was rejected rewrites the proposal. It cannot fix a base URL, and it
 * will never learn that is what needs fixing.
 *
 * <p>So the 400 rule now maps a type only this package throws, and only for
 * something the caller can actually correct. Everything else keeps its own
 * status: an unreachable embedding endpoint is 503, and anything unrecognised
 * is a 500 that says the server broke. The archive is never blamed on the
 * agent, and the agent is never blamed for the archive.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
