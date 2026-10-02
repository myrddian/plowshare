package io.aeyer.plowshare.server.config;

import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * The runtime configuration map could not be answered from, so nothing was read
 * and nothing was written.
 *
 * <p>Usually that is the database being unreachable, and that is what the
 * message says. It is not only that: {@link #unreachable} also takes the whole
 * {@code TransientDataAccessException} branch, which includes a write that lost
 * a row lock to another operator's concurrent write. That is deliberate and the
 * reasoning is on that method, which is the paragraph to read before trusting
 * this class's own noun.
 *
 * <h2>What this type exists to stop being said</h2>
 *
 * <p>Until it existed there was no {@link DataAccessException} handler anywhere
 * in {@code ApiExceptionHandler}, so a Postgres that had gone away reached an
 * operator's {@code PUT /v1/config/…} through {@code
 * @ExceptionHandler(Throwable.class)} — a 500 whose body ends <i>"This is a
 * fault in the server, not in the request."</i> Both halves of that sentence
 * are wrong here in the way that costs the most time: the database being down
 * is not a fault in this server's code, and the one person able to fix it is
 * sent reading a stack trace looking for one. {@code RuntimeConfig}'s class
 * comment records the gap and says the config surface is where it should be
 * closed; this is the closing.
 *
 * <p><b>And not by reusing {@code ArchiveUnavailableException}.</b> That type is
 * already mapped to 503, so borrowing it would fix the status in one line — and
 * its handler's body says the archive could not be reached and that this "says
 * nothing about what is remembered". A configuration write has nothing to do
 * with what is remembered, and an operator reading that about a failed {@code
 * PUT} learns something false about a different subsystem. A status is half the
 * answer; the sentence beside it is the other half.
 *
 * <h2>The classification is narrower than the archive's, and differs from it</h2>
 *
 * <p>Only a failure <em>to reach</em> the database becomes this. A {@code
 * DataIntegrityViolationException} — what V28's {@code CHECK (updated_by <>
 * '')} raises for a nameless write — is a bug in the caller of {@link
 * RuntimeConfig#put}, and answering 503 for it would advise an operator to wait
 * for a database that is perfectly healthy and will refuse the identical write
 * for ever. Advice that cannot come true is worse than none, which is the rule
 * {@code ApiExceptionHandler.unreadableDocument} already states in the other
 * direction. So an unclassified failure keeps the 500 it should have had all
 * along, and {@code
 * a_write_the_schema_refuses_is_a_fault_in_this_server_and_not_an_outage} is
 * what holds the line.
 *
 * <p><b>Three branches, where {@code ArchiveUnavailableException} takes four.</b>
 * That class also translates {@code CannotCreateTransactionException}, because
 * {@code Archive.recall} goes through a {@code TransactionTemplate} which asks
 * for the connection before any store is called. {@link RuntimeConfig} opens no
 * transaction — it is {@code JdbcTemplate} statements and nothing else — so
 * that branch is unreachable from here, and carrying it would be a case no test
 * in this package could ever bring.
 *
 * <p><b>That difference is why the rule is copied rather than shared, and the
 * copy has a cost worth naming.</b> The two classifications are not the same
 * rule, so a shared helper would have to take the union or a flag; and the
 * archive's is private to a class in another package that is about the archive.
 * What the copy costs is that "which JDBC failures mean the database is gone"
 * now has two homes, and a fifth branch discovered against a future driver has
 * to be added to both. The alternative — a shared {@code
 * DatabaseFailures} utility owned by neither package — was declined because
 * nothing else in this tree wants it yet, and a home invented for two callers
 * is a home the third caller does not fit either.
 */
public class ConfigUnavailableException extends RuntimeException {

    /**
     * Public because the handler that maps it lives in {@code server.api} and
     * the type is the whole of the contract between them.
     */
    public ConfigUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Run one call against the map, translating a failure to reach it and
     * nothing else.
     *
     * <p>Wrapped at the granularity of a whole request rather than per
     * statement, unlike {@code ArchiveUnavailableException.translating}. That
     * class wraps each call so the message names one operation an operator can
     * find; here a request <em>is</em> one operation — list the live keys, or
     * write this one — and there is no finer thing to name. Wrapping per
     * statement would produce "could not be reached to read a row" for a request
     * whose subject was a listing.
     *
     * @param what the operation, as a verb phrase, for the message: "list the
     *     live configuration", "write plowshare.documents.ingest-budget"
     */
    static <T> T translating(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException failed) {
            if (!unreachable(failed)) {
                throw failed;
            }
            throw new ConfigUnavailableException(
                    "the runtime configuration map could not be reached to " + what + ": "
                            + describe(failed),
                    failed);
        }
    }

    /**
     * The three branches of {@link DataAccessException} whose answer to the
     * caller is <em>wait and try again</em>, probed on the same spring-jdbc the
     * archive's copy was probed on.
     *
     * <ul>
     *   <li>{@code NonTransientDataAccessResourceException} — a dead socket,
     *       which arrives as {@code CannotGetJdbcConnectionException}. Nobody
     *       reached the database.
     *   <li>{@code RecoverableDataAccessException} — a Postgres in recovery.
     *       Reached, and not yet serving.
     *   <li>{@code TransientDataAccessException} — <b>wider than "unreachable",
     *       deliberately, and the earlier wording of this paragraph hid it.</b>
     *       It read "an exhausted pool or a statement that ran out of time is
     *       {@code QueryTimeoutException}", which names one subclass and reads
     *       as the whole branch. It is not. {@code ConcurrencyFailureException}
     *       also extends it, and under that sit {@code
     *       CannotAcquireLockException}, {@code DeadlockLoserDataAccessException}
     *       and {@code OptimisticLockingFailureException} — so two operators
     *       writing one key at once, one of them losing the row lock on {@link
     *       RuntimeConfig}'s {@code ON CONFLICT DO UPDATE}, lands here.
     * </ul>
     *
     * <p><b>A lost lock is not the database being unreachable, and it is still
     * classified as one on purpose.</b> The branch is not a taxonomy of what
     * went wrong; it is the set of failures for which 503 plus "try again" is
     * true advice, and a write that lost a lock is the clearest case of that in
     * the whole hierarchy — the database is healthy, the identical retry
     * succeeds, and the caller has nothing to correct. The cost is honestly a
     * cost: the message this class builds says "could not be reached", which for
     * a lock loss is the wrong noun for the right advice. It is left that way
     * because the alternative is a second sentence for a case that needs the
     * same status, the same retry and the same operator action, and {@link
     * #describe} carries the driver's own words for anyone who wants the noun.
     *
     * <p>{@code ArchiveUnavailableException.unreachable} has this disjunct too,
     * verbatim, so a lock failure has been classified this way since the archive
     * — this class inherits the behaviour rather than introducing it, and the
     * archive is deliberately not changed here.
     *
     * <p>Everything else — a constraint the schema refused, a SQL grammar error
     * that means this class's own statements are wrong — reaches its caller
     * untouched, and lands on the 500 that says the server broke, because it
     * did.
     */
    private static boolean unreachable(DataAccessException failed) {
        return failed instanceof NonTransientDataAccessResourceException
                || failed instanceof TransientDataAccessException
                || failed instanceof RecoverableDataAccessException;
    }

    /**
     * Spring's own message plus the driver's, one line each.
     *
     * <p>{@code ArchiveUnavailableException.describe}'s reasoning, and it is the
     * same reasoning because it is the same pair of messages: Spring's half says
     * what it was doing ("Failed to obtain JDBC Connection") and the driver's
     * says why ("Connection to localhost:5432 refused"), and only the pair is
     * actionable. The driver's is folded into the message rather than left in
     * the cause because this message is what reaches an HTTP body, where the
     * cause chain does not go.
     */
    private static String describe(DataAccessException failed) {
        Throwable root = failed;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String said = firstLine(failed.getMessage());
        String why = root == failed ? "" : firstLine(root.getMessage());
        String because =
                why.isBlank() ? "" : " (" + root.getClass().getSimpleName() + ": " + why + ")";
        return (said.isBlank() ? failed.getClass().getSimpleName() : said) + because;
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("").strip();
    }
}
