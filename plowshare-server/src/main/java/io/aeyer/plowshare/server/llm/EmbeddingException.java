package io.aeyer.plowshare.server.llm;

/**
 * The embedding endpoint could not be reached, refused, or answered with
 * something that is not an embedding.
 *
 * <p>Unchecked, and deliberately its own type rather than a bare {@code
 * RuntimeException}: the write path catches it on purpose — a memory written
 * while the endpoint is down must still be stored — and a catch clause wide
 * enough to swallow every runtime failure would swallow the bugs too.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
