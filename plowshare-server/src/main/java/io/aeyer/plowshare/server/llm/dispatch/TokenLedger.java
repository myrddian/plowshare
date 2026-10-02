package io.aeyer.plowshare.server.llm.dispatch;

/**
 * Compatibility success-only observer for dispatchers constructed without durable accounting.
 * Production capture uses {@link InferenceAccounting} instead and never books both paths.
 *
 * <p>This interface cannot represent retries, failures, cancellation, unknown consumption, or
 * durable admission. It is retained for existing callers and fixtures, not used as a billing
 * authority. Implementations must be thread-safe. Exceptions are logged and swallowed after
 * successful inference; they must never contain credentials or discard usable content.
 */
public interface TokenLedger {

    void record(LedgerEntry entry);
}
