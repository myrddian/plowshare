package io.aeyer.plowshare.server.llm.dispatch;

/**
 * A model call could not be made.
 *
 * <p>Its own family rather than {@link RuntimeException} so a capability
 * interface can translate it into that capability's currency — {@code
 * DispatchingEmbeddingClient} turns every one of these into an {@link
 * io.aeyer.plowshare.server.llm.EmbeddingException}, which is what the write
 * path catches. A catch clause wide enough to swallow every runtime failure
 * would swallow the bugs too.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
