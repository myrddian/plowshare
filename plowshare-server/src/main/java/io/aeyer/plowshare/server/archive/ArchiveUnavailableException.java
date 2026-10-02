package io.aeyer.plowshare.server.archive;

import java.util.function.Supplier;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;

/**
 * The archive could not be asked, so nothing can be concluded from the answer.
 *
 * <p>Distinct from {@link ArchiveException} on purpose, and the distinction is
 * the whole point of the type. {@code ArchiveException} means the archive
 * answered and the caller was wrong about its contents — no memory with that
 * id, a target in another tier. This one means <b>nobody got an answer at
 * all</b>. Collapsing the two turns "the database is down" into a 404 through
 * {@code ApiExceptionHandler}, and into "nothing was found" through {@code
 * memory_recall} — which is the confident-empty-answer failure this project
 * exists to avoid, one layer below where it usually shows up.
 *
 * <h2>Why the split is here rather than in a wider catch upstream</h2>
 *
 * <p>{@code JobRuntime.dependencyFailure} decides whether a tool's failure ends
 * a run ({@code UNAVAILABLE}) or comes back to the model as a tool result it
 * may work around. It can only decide that from the exception's type, and a
 * dead Postgres used to arrive as a bare {@code DataAccessException} —
 * indistinguishable, at that layer, from a queue refusing a duplicate. That
 * method carried a paragraph from Task 6 to Task 10 saying so, and this class
 * is what deletes it: <b>the failing layer raises a named type, and the runtime
 * lists it.</b>
 *
 * <h2>The classification, measured</h2>
 *
 * <p>Probed against spring-jdbc/spring-tx 6.1.14 on 2026-08-29 by printing each
 * class's superclass chain. The two cases that matter are <em>siblings</em>,
 * not parent and child:
 *
 * <ul>
 *   <li>unreachable — {@code CannotGetJdbcConnectionException} &rarr; {@code
 *       DataAccessResourceFailureException} &rarr; {@code
 *       NonTransientDataAccessResourceException} &rarr; {@code
 *       NonTransientDataAccessException};
 *   <li>refused — {@code DataIntegrityViolationException} &rarr; {@code
 *       NonTransientDataAccessException}, which is what a foreign-key violation
 *       arrives as;
 *   <li>our own bug — {@code BadSqlGrammarException} &rarr; {@code
 *       InvalidDataAccessResourceUsageException} &rarr; {@code
 *       NonTransientDataAccessException}.
 * </ul>
 *
 * <p>So the branch is taken at {@link NonTransientDataAccessResourceException}
 * and not one level up. Catching {@code NonTransientDataAccessException} would
 * swallow a SQL typo as an outage; catching {@code DataAccessException} would do
 * that and break {@code ProposalStore.propose} besides — <b>and the worked
 * example for that was wrong in this javadoc for one commit, so it is spelled
 * out here.</b>
 *
 * <p>The <em>duplicate proposal</em> case raises nothing at all: {@code propose}
 * inserts with {@code ON CONFLICT (memory_id, action) WHERE state = 'pending' DO
 * NOTHING}, so a second pending row is a returned count of zero and an ordinary
 * {@code ArchiveException}. That was the example given here, and it cannot fire.
 *
 * <p>The live sibling is the <b>foreign key</b> on {@code memory_id}, and it is
 * live because {@code translating} sits <em>inside</em> {@code propose}'s {@code
 * try}. A caller naming a memory that was never written raises {@code
 * DataIntegrityViolationException}, which that method's own {@code catch} turns
 * into "no memory with id X" — a caller's mistake it can correct. Widen this
 * rule to {@code DataIntegrityViolationException} and the translation fires
 * first, from inside the {@code try}, the {@code catch} never runs, and that
 * mistake reaches the model as {@code UNAVAILABLE}. {@code propose}'s own inline
 * comment says this correctly; this one did not.
 *
 * <p>{@link TransientDataAccessException} and {@link
 * RecoverableDataAccessException} are on the unavailable side as well — a query
 * timeout, a lock this transaction could not take. That is a judgement about
 * what the two sides mean <em>to a run</em> rather than to a DBA: neither is a
 * mistake the model made, neither is one it can correct by rephrasing, and
 * handing either back as "the tool failed; you may try something else" invites
 * precisely the retry loop the wrong side produces.
 *
 * <p>Everything else under {@code DataAccessException} reaches its own caller
 * untouched, because the database answered it. {@code DuplicateKeyException} is
 * among them and does have a real path — {@code ProposalStore.release} catches
 * one when the waiting place a claim vacated has been taken — but that is a
 * different method from the one this javadoc used to name. See {@code
 * ArchiveUnavailableTest} for both sides of the line, driven rather than
 * described.
 *
 * <h2>The transaction manager fails first, and it is not a {@code
 * DataAccessException} at all</h2>
 *
 * <p>Measured on the same day, and it is the case a translation confined to the
 * stores would have missed on the most ordinary path: with Postgres unreachable,
 * {@code Archive.recall} does not get as far as {@code MemoryStore}. {@code
 * TransactionTemplate.execute} asks for the connection first and fails with
 * {@code org.springframework.transaction.CannotCreateTransactionException},
 * whose chain is {@code TransactionException} &rarr; {@code
 * NestedRuntimeException} — a different tree from {@code DataAccessException}
 * entirely. So {@code ArchiveConfig}'s {@code UnitOfWork} translates too, and
 * this method takes both roots.
 *
 * <p>Only {@code CannotCreateTransactionException} is translated out of that
 * tree, deliberately narrowly: it is the one measured to mean "the resource
 * could not be got". {@code UnexpectedRollbackException} and {@code
 * TransactionSystemException} reach their caller untouched, because neither has
 * been measured here and a guess that widened this rule would put our own bugs
 * on the outage side — which is the fault this whole class exists to avoid, in
 * the other direction.
 */
public class ArchiveUnavailableException extends RuntimeException {

    /**
     * Public because the type is the contract — {@code
     * JobRuntime.dependencyFailure} switches on it, and a layer that knows its
     * database is gone must be able to say so without going through {@link
     * #translating}. The message is the caller's to write; the one this class
     * builds is only the shape the stores use.
     */
    public ArchiveUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Run one database call, translating a failure to reach the database and
     * nothing else.
     *
     * <p>Wraps the call rather than the whole of a store method, so the {@code
     * what} names one operation an operator can act on. Every JDBC call in
     * {@link MemoryStore} and {@link ProposalStore} goes through here; that is
     * the enforcement, since a call added outside it would silently reopen the
     * gap this class closed.
     *
     * @param what the operation, as a verb phrase, for the message: "index a
     *     tier", "file a proposal". It is the only part of the message a
     *     reader can use to find the call site.
     */
    static <T> T translating(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException | TransactionException failed) {
            if (!unreachable(failed)) {
                throw failed;
            }
            throw new ArchiveUnavailableException(
                    "the archive could not be reached to " + what + ": " + describe(failed),
                    failed);
        }
    }

    private static boolean unreachable(NestedRuntimeException failed) {
        return failed instanceof NonTransientDataAccessResourceException
                || failed instanceof TransientDataAccessException
                || failed instanceof RecoverableDataAccessException
                || failed instanceof CannotCreateTransactionException;
    }

    /**
     * Spring's own message plus the driver's, one line each.
     *
     * <p>Spring's half says what it was doing ("Failed to obtain JDBC
     * Connection") and the driver's says why ("Connection to localhost:5432
     * refused"), and only the pair is actionable — the first without the second
     * sends an operator looking at the wrong thing. The driver's is included
     * rather than left in the cause because this message is what reaches {@code
     * Outcome.detail} and an HTTP body, where the cause chain does not go.
     *
     * <p>Pinned by {@code
     * no_database_password_reaches_the_message_or_any_cause_in_the_chain},
     * which builds a datasource with a known password and walks the whole
     * chain. That is a measurement of these two messages on this driver, not a
     * general claim that a JDBC message never carries a credential.
     */
    private static String describe(NestedRuntimeException failed) {
        String said = firstLine(failed.getMessage());
        Throwable root = failed;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String why = root == failed ? "" : firstLine(root.getMessage());
        String because = why.isBlank()
                ? ""
                : " (" + root.getClass().getSimpleName() + ": " + why + ")";
        return (said.isBlank() ? failed.getClass().getSimpleName() : said) + because;
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("").strip();
    }
}
