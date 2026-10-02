package io.aeyer.plowshare.server.llm.dispatch;

/**
 * A stream stopped because the caller said it no longer wanted it.
 *
 * <p><b>Not a failure, and deliberately not an {@link LlmException}.</b> Nothing
 * went wrong with the endpoint: it was generating perfectly well and somebody
 * pressed stop. A run that ends here is {@code CANCELLED} and not {@code
 * UNAVAILABLE}, and the two must not be able to masquerade as each other — an
 * operator reading "the model could not be reached" for a cancelled job goes
 * looking for a host that is fine.
 *
 * <p><b>What it does not decide is how the caller's own work ends.</b> This says
 * "the read stopped"; whether that makes a job cancelled, or retried, or
 * something else is the caller's to decide, which is why this carries no ending
 * and no prose beyond the pool's name. {@code JobRuntime} catches it and writes
 * the same sentence its turn-boundary cancellation check writes.
 *
 * <p>Raised by {@link LlmTransport#stream} when the {@code abandoned} predicate
 * it was given answers true, which it is asked once per chunk. See there for the
 * granularity that buys and the granularity it does not.
 */
public class CallerAbandonedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CallerAbandonedException(String pool) {
        // No cause and no stack trace. There is no throwable underneath — the
        // caller's own flag is the whole story — and a run cancelled on purpose
        // is not an incident anybody needs a stack for. Suppression off for the
        // same reason: nothing adds to this.
        super("pool '" + pool + "': the caller abandoned the stream", null, false, false);
    }
}
