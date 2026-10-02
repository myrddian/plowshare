package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Turns a {@link Home} into the value a {@code project_id} column is compared
 * against.
 *
 * <p>Since {@code V14__project_ids.sql} a project is its id and not its name.
 * Names are still what a person types, what the API takes and what every MCP
 * tool names — so the translation happens exactly once, here.
 *
 * <p><b>"and no id ever leaves the archive" is what this paragraph used to say,
 * and the data directory made it false.</b> {@code
 * DataLayout.exportsFor} names a directory {@code projects/&lt;id&gt;/}, so an
 * id is now something an operator sees in a path and a backup carries. That is
 * deliberate and is the same reason the column is a surrogate: {@code
 * ProjectStore.rename} exists, and a directory named for a name is one a rename
 * orphans with nothing failing. What is still true, and is the part worth
 * keeping, is that the <em>translation</em> happens only here — no caller
 * outside this package turns a name into an id, and {@link #forDirectory} is how
 * the one that needs a path gets one.
 *
 * <h2>Three answers, not two</h2>
 *
 * <p>A {@code Home} arriving at a store is one of three things, and conflating
 * the last two is the whole hazard this class exists to remove:
 *
 * <ul>
 *   <li><b>global</b> — {@code null}, exactly as V1 says: "Global is the
 *       absence of a project rather than a project named 'global'."
 *   <li><b>a project that exists</b> — its id.
 *   <li><b>a project nothing has ever written to</b> — which is an ordinary
 *       question, not a mistake. {@code Home.of("ledger")} is creatable and
 *       readable before anything is in it, and a read of it must come back
 *       empty.
 * </ul>
 *
 * <p>The third case cannot answer {@code null}. {@code null} is the global
 * tier, so a read for an unknown project that resolved to it would hand back
 * every global memory as that project's — the tier every agent everywhere
 * reads, returned under a name that has never been defined. It answers {@link
 * #NONE} instead.
 *
 * <h2>Why a value and not an {@code Optional}</h2>
 *
 * <p>Every read that filters by home does it through one predicate — {@code
 * project_id IS NOT DISTINCT FROM CAST(? AS BIGINT)} — and there are nine of
 * them across {@link MemoryStore}, {@link ConversationStore} and {@link
 * ProposalStore}. An {@code Optional} would put a branch in front of each, and
 * each would have to invent its own empty answer: an empty list here, a zero
 * there, an empty {@code Optional} somewhere else. Nine branches is nine places
 * to get "an unknown project is not the global tier" wrong, and the branch on
 * the far side is the one no test would reach.
 *
 * <p>{@link #NONE} makes it arithmetic instead. The predicate is unchanged, the
 * query runs, and it matches nothing — which is the true answer.
 */
final class ProjectIds {

    /**
     * An id no project row holds, so a query filtered on it returns nothing.
     *
     * <p>Not a convention: {@code projects_id_is_positive} is a CHECK on the
     * table, and the identity that fills the column starts at 1. A row carrying
     * this cannot be written, so a read filtered on it cannot be wrong.
     */
    static final long NONE = 0L;

    private static final String LOOK_UP = "SELECT id FROM projects WHERE name = ?";

    /*
     * ON CONFLICT DO UPDATE and not DO NOTHING, and the difference is the
     * RETURNING. `DO NOTHING` returns no row when somebody else won the race,
     * so the one caller that needs an id would get nothing back exactly when two
     * writers arrived at once. Setting the name to what it already is makes the
     * statement answer with the existing row's id and leaves the row otherwise
     * untouched.
     *
     * `workspace` is not named, so registering a project this way never grants
     * one and never takes one away. A project written into by an agent has a row
     * and no workspace, which is V3's "a project may hold memories with no
     * workspace" written down instead of inferred from the absence of a row.
     */
    private static final String REGISTER = """
            INSERT INTO projects (name) VALUES (?)
            ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
            RETURNING id
            """;

    private ProjectIds() {}

    /*
     * NEITHER METHOD TRANSLATES A DEAD DATABASE ITSELF, and that is deliberate.
     * Both are called from inside a caller's
     * `ArchiveUnavailableException.translating(...)` supplier -- as an argument
     * to the statement, which Java evaluates there -- so a connection failure is
     * already described by the operation the caller asked for. Translating here
     * as well would win the race and replace "index a tier could not be reached"
     * with "resolve a project name could not be reached", which names a step
     * nobody asked for and sends an operator looking for a method that is not in
     * the stack they care about.
     */

    /**
     * The id to filter a read by: {@code null} for global, the project's id, or
     * {@link #NONE} for a name no project has.
     *
     * <p>Reading does not register. A recall against a project nobody has
     * written to must not leave a row behind saying somebody did — the
     * projects listing is what an operator reads, and filling it with names
     * that were only ever asked about would make it a log of typos.
     */
    static Long toRead(JdbcTemplate jdbc, Home home) {
        if (home.isGlobal()) {
            return null;
        }
        Long found = lookUp(jdbc, home.project());
        return found == null ? NONE : found;
    }

    /**
     * The id to write into a row: {@code null} for global, and otherwise the
     * project's id, registering the project if this is the first anything has
     * been written to it.
     *
     * <p><b>Writing registers, and it has to.</b> {@code memories.project_id}
     * is a foreign key now, so a memory formed in a project no operator has
     * defined has nowhere to point — and refusing that write would make
     * remembering something depend on somebody having already run {@code
     * define}, which is exactly what V3 and V6 both refuse ("tying them would
     * make talking depend on somebody having already run define"). Registering
     * keeps that promise and pays for the constraint at the same time.
     *
     * <p>It runs in whatever transaction the caller is already in, because it
     * goes through the same {@link JdbcTemplate} and so the same thread-bound
     * connection. A memory write that rolls back takes its project
     * registration with it.
     */
    static Long toWrite(JdbcTemplate jdbc, Home home) {
        if (home.isGlobal()) {
            return null;
        }
        Long found = lookUp(jdbc, home.project());
        return found != null ? found : jdbc.queryForObject(REGISTER, Long.class, home.project());
    }

    /**
     * The id a project's own directory under the data tree is named for.
     *
     * <p><b>A third method rather than a reading of {@link #toRead}'s answer,
     * because the third case has to be different here.</b> A read of an unknown
     * project is an ordinary question and answers {@link #NONE}, which matches
     * no row — the true answer. A <em>path</em> for an unknown project cannot be
     * {@code NONE}: that is 0, {@code projects/0/} is a real directory, and it
     * would collect the ejected payloads of every project that failed to resolve
     * into one place named after none of them. Two conversations' file bodies in
     * one directory, with nothing anywhere saying they are unrelated, is the one
     * outcome an export must not have.
     *
     * <p><b>It does not register either</b>, unlike {@link #toWrite}, and the
     * difference is what is being written. A memory formed in an undefined
     * project must not be refused — V3 and V6 both say so — so writing one
     * registers. An export is written <em>about a conversation that already
     * exists</em>, and {@code conversations.project_id} is a foreign key, so its
     * project has a row by construction. A missing one is not a first write; it
     * is a state nothing in this server can produce, and creating a row to paper
     * over it would put a project into the operator's listing that nobody named.
     *
     * @param home whose conversation it is, or global
     * @return the id, or {@code null} for the global tier — which {@code
     *     DataLayout} spells as a directory that is not in the id namespace at
     *     all
     * @throws ArchiveException if the project has no row
     */
    static Long forDirectory(JdbcTemplate jdbc, Home home) {
        if (home.isGlobal()) {
            return null;
        }
        Long found = lookUp(jdbc, home.project());
        if (found == null) {
            throw new ArchiveException(
                    "no project named " + home.project() + " has a row, so there is no id to name"
                            + " a directory with. A conversation's project is a foreign key, so"
                            + " this state is not one this server can reach by writing");
        }
        return found;
    }

    /**
     * The look-up all three share, answering {@code null} for a name no
     * project has.
     *
     * <p><b>{@link #toWrite} asks this first rather than going straight to the
     * upsert, and it is not a saving of one round trip.</b> {@code ON CONFLICT
     * DO UPDATE} takes a row lock on the project for the rest of the
     * transaction, so an upsert on every write would make two agents writing
     * into one project wait for each other — a project-wide write lock bought
     * for a row that is almost always already there. The upsert stays as the
     * branch that actually creates, where the lock is what makes a race safe.
     */
    private static Long lookUp(JdbcTemplate jdbc, String project) {
        List<Long> found = jdbc.queryForList(LOOK_UP, Long.class, project);
        return found.isEmpty() ? null : found.get(0);
    }
}
