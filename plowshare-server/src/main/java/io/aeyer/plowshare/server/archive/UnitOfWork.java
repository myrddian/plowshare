package io.aeyer.plowshare.server.archive;

import java.util.function.Supplier;

/**
 * How the archive's database work is made atomic, supplied by whoever wires
 * {@link Archive}.
 *
 * <p>{@link Archive} knows <em>which</em> statements must not half-apply — a
 * supersession is two saves, a batch read is n saves — and it has always been
 * the only place that knows. What it must not know is the mechanism, because it
 * is deliberately framework-free. This interface is that seam: one plain method,
 * no Spring on the archive's side of it, and a production implementation in
 * {@link ArchiveConfig} that is a {@code TransactionTemplate}.
 *
 * <h2>Why this replaced {@code @Transactional} on the controller</h2>
 *
 * <p>The boundary used to be five {@code @Transactional} annotations on {@code
 * MemoryController}, which wrapped the <em>whole</em> endpoint — including the
 * call to the embedding model that {@code Archive} makes on the write and
 * recall paths. Two things followed, and both were live bugs:
 *
 * <ul>
 *   <li><b>Writes were lost.</b> {@code Archive.embed} catches {@code
 *       EmbeddingException} and nothing wider, on the stated reasoning that
 *       "the row is already committed by the time this runs". With the model
 *       call inside the transaction that sentence was false, so any other
 *       exception out of the embedding client rolled the committed row back.
 *       A {@code LLM_BASE_URL} missing its {@code http://} made OkHttp throw
 *       {@code IllegalArgumentException} on every single write, and every
 *       memory anyone wrote was discarded and reported to the agent as {@code
 *       400 bad_request}.
 *   <li><b>A pooled connection was pinned across the model call.</b> Connect
 *       10s plus read 30s per attempt, twice, plus backoff — some eighty
 *       seconds of a Hikari connection held by a thread that is waiting on LM
 *       Studio. Ten concurrent writes against the default pool of ten stop the
 *       server, reads included.
 * </ul>
 *
 * <p>An after-commit {@code TransactionSynchronization} is the obvious fix for
 * the first and does not fix the second. Spring runs {@code afterCommit} — and
 * {@code afterCompletion} — before the cleanup that unbinds the connection, so
 * a hook there still holds it. Measured against this project's own Hikari pool
 * rather than taken from the documentation: inside {@code afterCommit} the
 * connection is still bound and the pool reports one active connection; inside
 * {@code afterCompletion}, the same; only once {@code TransactionTemplate
 * .execute} returns does the pool report zero. The transaction has to be
 * <em>over</em> — committed and cleaned up — before the model is called, which
 * is what a scoped unit of work inside {@link Archive} gets and what neither an
 * annotation on the outermost method nor a synchronisation hook can.
 */
public interface UnitOfWork {

    /**
     * Run this database work as one atomic unit and return its result.
     *
     * <p>Implementations must roll back if {@code work} throws, and must have
     * released the underlying connection by the time this method returns —
     * everything {@link Archive} does after calling this assumes the pool has
     * its connection back.
     */
    <T> T inTransaction(Supplier<T> work);

    /** Run a side effect once committed; implementations joining an outer transaction defer it. */
    default void afterCommit(Runnable action) {
        action.run();
    }

    /**
     * No transaction: run the work as it comes, statement by statement.
     *
     * <p>For tests that assert on the archive's <em>rules</em> against a real
     * Postgres in autocommit, where a rollback boundary would change nothing
     * they look at. Not for production wiring — {@code
     * TransactionBoundaryTest.a_supersession_that_fails_halfway_leaves_no_new_record}
     * is what fails when this is used there.
     */
    UnitOfWork NONE = new UnitOfWork() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
}
