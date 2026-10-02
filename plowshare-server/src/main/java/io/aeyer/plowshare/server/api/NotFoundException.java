package io.aeyer.plowshare.server.api;

/**
 * A handle the caller holds does not resolve — a job id this process does not
 * know.
 *
 * <p>Distinct from {@code ArchiveException}, which also answers 404 but means
 * the <em>archive</em> does not hold something. Jobs live in memory and are
 * gone after a restart by design, so "no job called that" is an ordinary
 * answer here and not a sign anything is broken; keeping the two types apart
 * means neither message has to hedge about the other's case.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
