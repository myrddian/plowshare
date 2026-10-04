package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Which directories each project's jobs may reach, and the paths none of them may, ever.
 *
 * <h2>A project is at one place and may reach several directories</h2>
 *
 * <p><b>{@code workspace} is the place; {@code workspace} prepended to {@code lent} is the
 * leash.</b> They are two columns and not one array because {@code workspace} is the {@code <PATH>}
 * of {@code <MACHINE>/<PATH>/<PROJ_NAME>} — so an identity composed from an array would rename the
 * project whenever the array reordered, and V14 exists so that a rename is survivable rather than
 * routine. V30 carries the whole argument, and the practical consequence for every method below is
 * short: {@link #define} and {@link #defineLending} write both, {@link #lend} and {@link #unlend}
 * write only {@code lent}, {@link #moveWorkspace} writes only {@code workspace}, and {@code
 * IS_THIS_SERVERS} and {@code CLAIM} test {@code workspace} alone and are untouched by any of it.
 *
 * <h2>Why this is a table and not configuration</h2>
 *
 * <p>Projects are the unit an operator manages — they group memories, jobs and now a file view, the
 * way a Claude project groups chats — and a server whose project list requires a restart is not a
 * server. Adding a project, or moving one's workspace, is an ordinary write here.
 *
 * <p><b>No agent can perform that write.</b> Not because this class checks who is calling, but
 * because 3a's {@code AgentRegistry.load} refuses at boot any definition naming a tool outside
 * {@code knownTools()}, and workspace management is never in that set. A definition that names it
 * does not get denied at run time — it fails to load and takes the boot with it.
 *
 * <h2>The mandatory exclusions</h2>
 *
 * <p>The rule and its argument belong to {@link #mandatoryExclusions}, which derives a set of
 * directories rather than a list of files and so answers with a length nothing here should predict;
 * what matters here is that {@link #effectiveExclusions} applies it to every project and no row
 * stores it, so no row can drop what it has to keep out of reach:
 *
 * <ul>
 *   <li><b>the server's configuration</b>, because it holds the LM Studio API key. This project's
 *       one rule with no exceptions is that the key appears in no log line, no exception message
 *       and no assertion, and a {@code file_read} of the config defeats all of it in one call;
 *   <li><b>the sampling directory</b>, for the same reason a definitions directory used to be its
 *       own entry here — see the paragraph below. A profile file decides what every agent on this
 *       server is sampled at, and a {@code models.yaml} decides which profile a model gets — both
 *       read at boot, neither visible in any request afterwards. Write access there is not a grant
 *       of capability, which is why it is listed third rather than first, but it is the same delay
 *       fuse: an agent that could edit a profile could put every summariser on this server back on
 *       the configuration that was measured returning 3 997 reasoning tokens and empty content, and
 *       the only report would be an ingest that stopped working.
 *   <li><b>the operator token file</b>, because it is a complete and permanent credential for every
 *       gated route on this server, {@code PUT /v1/config/&#123;key&#125;} included — {@code
 *       TokenStore.acceptOperator} files it as an ordinary access grant with no refresh partner and
 *       no expiry, and {@code AuthFilter} asks only {@code validAccess}, so nothing downstream can
 *       tell it from a session token. Listed last and added last, because at its default path it is
 *       already protected by {@link io.aeyer.plowshare.protocol.FileAccess#permits}'s
 *       hidden-component rule; {@link #mandatoryExclusions} says why the list wants it anyway.
 *   <li><b>the export directory</b>, because it holds ejected conversation payloads — the file
 *       bodies a tool returned, taken out of {@code entries.content} and written to disk in the
 *       open. That is other people's content, and the whole of the argument for moving it out of
 *       the database is that it stays readable without this server; a workspace that covered it
 *       would make it readable <em>through</em> this server, by an agent, in one {@code file_read}.
 *       It is the first entry here that is fenced for the confidentiality of <em>user</em> content
 *       rather than for a credential or for integrity, and {@link #mandatoryExclusions} says why
 *       that is a third kind and not a variation on the other two.
 *   <li><b>the data directory</b>, which is where all of that ends up once {@code DataLayout} has a
 *       place for it: {@code projects/&lt;project-id&gt;/exports/} sits under it, and so will
 *       everything else this server comes to own on disk. Naming the root is what makes the rule
 *       survive a subdirectory being added — the entry above it is kept anyway, because an operator
 *       may still point {@code export-directory} outside the tree, and then the root covers
 *       nothing.
 * </ul>
 *
 * <p>All five are held in Java rather than seeded into each new row because a default written into
 * a row is a default a row can be edited out of, and a mistake in that row is a mistake nobody gets
 * to notice: nothing fails, one more path is simply reachable. See {@code
 * a_workspace_that_contains_the_config_still_cannot_reach_it}, which sets a project's workspace to
 * the directory the configuration is in and finds the configuration excluded anyway.
 *
 * <p>Framework-free apart from {@link JdbcTemplate}, and wired by {@code AgentsConfig} rather than
 * annotated, <b>matching {@link ProposalStore} and not {@link MemoryStore}</b> — {@code
 * MemoryStore} <em>is</em> {@code @Repository} ({@code MemoryStore.java:48}), and {@code
 * ArchiveConfig} says why {@code ProposalStore} is not ({@code ArchiveConfig.java:52-59}): it takes
 * constructor arguments a component scan has nothing to supply. This store's are the four paths
 * above. An earlier version of this sentence claimed {@code MemoryStore} carried no annotation,
 * copied from {@code ProposalStore.java:64-66} where the same error still sits.
 *
 * <p><b>{@code AgentsConfig} and not {@code ArchiveConfig}, which is where this sentence said the
 * bean was until task 10.</b> The claim was unfalsifiable while it stood, because nothing wired
 * this store at all — it had a schema, a suite and no production caller — and the moment task 10
 * gave it a bean the sentence became checkable and turned out false. <b>It stayed in {@code
 * AgentsConfig} through the later task that retired the standalone directory key naming one of this
 * store's own paths</b>, and the reason changed with it: {@code AgentsConfig.definitionResolver}
 * needs a {@code ProjectStore} of its own — it asks {@code projects::exists} on every project-tier
 * cache miss — so the bean has to be reachable from the same configuration, or defining it would
 * put a cross-configuration dependency between {@code archive} and {@code agents} in the other
 * direction instead. The sampling directory this store still fences is {@code LlmProperties}' own
 * key and not {@code AgentsProperties}', which is evidence the old sentence's premise never
 * generalised past the one path it happened to name.
 */
public final class ProjectStore {

  /*
   * `lent` sits between `workspace` and `exclusions` because that is the order
   * of the leash: where the project is, what else is lent at that place, and
   * what is fenced off inside the result. ROW_MAPPER reads by label rather
   * than by index, so the order is documentation and not a contract — but a
   * `RETURNING` list an operator reads in a log should read like the rule.
   */
  private static final String COLUMNS =
      "name, workspace, lent, exclusions, project_type, write_paths";

  /*
   * WHAT THIS TABLE HOLDS CHANGED IN V14, AND EVERY QUERY BELOW PAYS FOR IT
   * SO THAT NO CALLER HAS TO.
   *
   * Until V14 a project with no workspace had no row: `define` inserted one,
   * `forget` deleted it, and a project that only ever held memories was
   * nowhere in this table at all. That is why V3 could say "no foreign key to
   * memories.project" -- the referent set was incomplete by design.
   *
   * A surrogate key needs the opposite. `memories.project_id` and
   * `conversations.project_id` are real references now, so every project that
   * exists needs a row, and `workspace` is NULL for one that has not been
   * granted a directory. `ProjectIds.toWrite` is what registers them.
   *
   * None of that is this class's answer to change. `find` is still "a project
   * that has never been given a workspace is a question, not a mistake";
   * `all` is still "every project that has a workspace"; `moveWorkspace`
   * still "does not create", and refuses a project that has none. So each
   * carries this predicate, and each answers exactly what it answered before
   * -- except `all`, a listing and not a leash, which carries only the
   * workspace half (its own javadoc says why).
   *
   * AND V15 ADDED THE SECOND HALF, WHICH IS THE ONE THAT IS NOT MERELY
   * BOOKKEEPING. `machine` names the box a project's files are on, NULL
   * meaning this server, so `workspace` stopped being "a directory here" and
   * became "the path component of a place". Every method below answers about
   * THIS SERVER'S LEASH -- which directory a job running here may reach -- and
   * a path on somebody's laptop is not one, however much it looks like one.
   *
   * The failure the `machine IS NULL` half prevents is silent, which is why it
   * is in the shared predicate rather than in each caller: a laptop rooting
   * `/Users/example/proj/ledger` on a server that happens to hold a directory of
   * that name would otherwise hand `LocalProvider` a leash over the SERVER'S
   * copy. Nothing fails; an agent reads the wrong tree.
   * `a_workspace_on_another_machine_is_never_answered_as_this_servers_leash`
   * is that fixture, and V15's comment on `projects.workspace` is where the
   * rule is written down.
   */
  private static final String IS_THIS_SERVERS = "workspace IS NOT NULL AND machine IS NULL";

  /*
   * An upsert, so that re-defining a project is one write rather than
   * forget-then-define — which is two, with a window in between where the
   * project has no leash at all.
   *
   * This used to say the upsert is what makes MOVING a workspace one call.
   * That stopped being true when `moveWorkspace` arrived: that method's
   * javadoc argues at length that `define` is exactly NOT how you move one,
   * because it replaces the exclusions. Re-definition is what is left, and it
   * is enough on its own — writing a whole definition over an existing project
   * is still one statement rather than two.
   *
   * The clock is Postgres's `now()` and not an injected Supplier<Instant> as
   * ProposalStore takes, because there is nothing here to inject it for:
   * ProjectRecord carries no instant, so no test can assert an exact one, and
   * both questions asked of this column — `redefining_a_project_dates_the_row_
   * to_the_new_definition` and `moving_a_workspace_dates_the_row_to_the_move`
   * — ask only whether it moved. (It was "the only question" until the second
   * arrived; an argument resting on a count is an argument that goes stale
   * when the count does.) A constructor argument with no reader is a seam
   * nothing pulls on.
   *
   * `defined_at = now()` on the update because the row is the *current*
   * definition: leaving the original instant there would date a workspace set
   * this morning to whenever the project was first named, which is the one
   * question an operator reading this column is asking.
   */
  /*
   * The trailing WHERE is V15's, and it makes "define is for server-rooted
   * projects" a property of the statement rather than of a check somebody
   * remembered to run first. `ON CONFLICT ... DO UPDATE ... WHERE` updates
   * nothing and reports zero rows when the predicate is false, so a `define`
   * aimed at a project whose files are on a laptop leaves the row exactly as
   * it was and comes back as a refusal that names the machine.
   *
   * A pre-check would have been the obvious spelling and it has a window: a
   * presence declared between the SELECT and the upsert would be overwritten
   * by a workspace on this server, leaving a row that says a project is on
   * `bench.local` at a path only this machine has. The condition belongs in
   * the statement that writes.
   */
  /*
   * `lent` is written here and is therefore REPLACED by a define, exactly as
   * `exclusions` is. That is not an oversight to be softened later: a define
   * writes a whole definition -- that is the sentence `project_define` has
   * always said and the reason `moveWorkspace` exists -- and a define that
   * kept the lent roots would leave a project reaching directories the
   * operator's new definition never mentioned. The verb that adds a directory
   * without rewriting anything is {@link #lend}, and the verb that changes the
   * place without touching what is lent is {@link #moveWorkspace}.
   */
  private static final String UPSERT =
      """
            INSERT INTO projects (name, workspace, lent, exclusions) VALUES (?, ?, ?, ?)
            ON CONFLICT (name) DO UPDATE SET
                workspace  = EXCLUDED.workspace,
                lent       = EXCLUDED.lent,
                exclusions = EXCLUDED.exclusions,
                defined_at = now()
            WHERE projects.machine IS NULL
            """;

  /*
   * ADDING WITHOUT READING FIRST, WHICH IS THE WHOLE REASON THIS IS SQL AND
   * NOT A LIST BUILT IN JAVA.
   *
   * The obvious spelling is a SELECT, a concatenation and an UPDATE, and it
   * has `moveWorkspace`'s window one level in: two operators lending a
   * directory at the same time each read the same list and the second write
   * silently discards the first's root. Concatenating inside the statement
   * makes the read and the write one act, so a concurrent lend is serialised
   * by the row lock rather than lost.
   *
   * The subquery is what keeps a root already lent from being appended twice.
   * `WITH ORDINALITY` and the `ORDER BY` are not decoration: `array_agg` over
   * an unordered `unnest` may emit in any order, and the order of this array
   * is what `ProjectRecord.roots()` renders to a model as the project's own
   * list. `coalesce` because `array_agg` over no rows is NULL, and `lent ||
   * NULL` is NULL -- which would silently empty the column on the one call
   * that changes nothing.
   *
   * A duplicate WITHIN the argument is removed in Java before it gets here,
   * because this subquery only compares against what is already stored.
   */
  private static final String LEND =
      """
            UPDATE projects SET
                lent = lent || (
                    SELECT coalesce(array_agg(fresh.root ORDER BY fresh.ord), '{}'::text[])
                    FROM unnest(?::text[]) WITH ORDINALITY AS fresh(root, ord)
                    WHERE NOT (fresh.root = ANY (projects.lent))
                ),
                defined_at = now()
            WHERE name = ?"""
          + " AND "
          + IS_THIS_SERVERS
          + " RETURNING "
          + COLUMNS;

  /*
   * The other direction, and `array_remove` is deliberately not what does it:
   * that removes one element per call, so unlending three roots would be three
   * statements or a nested expression nobody can read. Rebuilding the array
   * from `unnest` with the removals filtered out is one pass and keeps the
   * order of what survives, which is the property `roots()` depends on.
   *
   * Idempotent, and unlike `forget` it does not refuse a root it does not
   * hold. `forget` refuses a name no row has because it answers with nothing,
   * so silence would be indistinguishable from success; this answers with the
   * row, so an operator who mistyped a path reads the unchanged list back
   * immediately. Detecting the difference would need the array as it was
   * before the update, which Postgres 17 has no `OLD` in `RETURNING` to give
   * -- so the alternative is not a better refusal, it is a second statement
   * and the window this one does not have.
   */
  private static final String UNLEND =
      """
            UPDATE projects SET
                lent = (
                    SELECT coalesce(array_agg(kept.root ORDER BY kept.ord), '{}'::text[])
                    FROM unnest(projects.lent) WITH ORDINALITY AS kept(root, ord)
                    WHERE NOT (kept.root = ANY (?::text[]))
                ),
                defined_at = now()
            WHERE name = ?"""
          + " AND "
          + IS_THIS_SERVERS
          + " RETURNING "
          + COLUMNS;

  /*
   * THE REGISTRATION, AND ITS WHOLE PERMISSION RULE IS THE WHERE CLAUSE.
   *
   * Two states may be claimed and the third may not:
   *
   *   * `projects.machine = EXCLUDED.machine` -- the same machine declaring
   *     again. That is every reconnect (a presence is re-declared on each one)
   *     and it is also a client whose checkout moved, so the root is taken as
   *     given rather than compared;
   *   * `projects.machine IS NULL AND projects.workspace IS NULL` -- a project
   *     with no place at all. That is V15's third state, it is what every
   *     project an agent has written a memory into looks like, and claiming it
   *     keeps the row's id and therefore its whole archive;
   *   * anything else -- a different machine, or this server -- updates
   *     nothing, and the caller reads the row to say which.
   *
   * IS NOT DISTINCT FROM and not `=`: `machine` is nullable and `NULL = 'x'`
   * is NULL, which a WHERE treats as false. Here that happens to be the answer
   * wanted for the first arm, but writing `=` would leave the statement one
   * schema change away from being quietly wrong, and the second arm needs the
   * null-safe reading spelled out anyway.
   *
   * `exclusions` is not named, so a project claimed by a presence keeps
   * whatever fence it had, and a new row gets V3's DEFAULT '{}'. Exclusions
   * are the server's own leash and a client declaring where its files are is
   * not asking for one.
   */
  private static final String CLAIM =
      """
            INSERT INTO projects (name, machine, workspace) VALUES (?, ?, ?)
            ON CONFLICT (name) DO UPDATE SET
                machine    = EXCLUDED.machine,
                workspace  = EXCLUDED.workspace,
                defined_at = now()
            WHERE projects.machine IS NOT DISTINCT FROM EXCLUDED.machine
               OR (projects.machine IS NULL AND projects.workspace IS NULL)
            """;

  private final JdbcTemplate jdbc;
  private final Path configFile;
  private final Path samplingDir;
  private final Path tokenFile;
  private final Path exportDir;
  private final Path dataDir;

  /**
   * @param jdbc where the rows live
   * @param configFile the server's own configuration file — the one holding the model API key
   * @param samplingDir the directory of sampling profiles and the mapping from model to profile.
   *     Ordinarily absent on disk, which excludes nothing and is exactly the state {@link
   *     #define}'s {@code exclusions} javadoc describes: a path fenced off before it exists is
   *     fenced off from the moment somebody creates it, which is the moment it starts to matter
   * @param tokenFile where this deployment writes the operator token, or {@code null} for a
   *     deployment that writes none and therefore has none to fence off. Null rather than a
   *     default, deliberately: {@code plowshare.auth.token-file} is blank unless {@code
   *     PlowshareServerApplication.main} set it, and substituting {@code
   *     AuthConfig.defaultTokenFile()} for a blank value would put a path this process never writes
   *     into the list an operator reads — and would name the running operator's real credential in
   *     the exclusions of every test context in this repository. There is <b>no four-argument
   *     overload</b> supplying null for it: a fence that can be omitted at a call site and still
   *     compile is a fence that goes missing the next time somebody wires this store
   * @param exportDir where ejected conversation payloads are written, or {@code null} for a
   *     deployment that keeps none and therefore has nothing to fence. Null for the same reason
   *     {@code tokenFile} is: {@code plowshare.conversations.retention.export-directory} may be set
   *     to nothing at all, which {@code ArchiveConfig.payloadExport} reads as {@code
   *     PayloadExport.NONE}, and substituting the shipped default for that would fence off a
   *     directory this deployment never writes to. <b>It is adjacent to {@code tokenFile} and is
   *     also a nullable {@code Path}</b>, so a call site that swapped the two would compile — the
   *     same trap {@code 0cfabdd} removed from {@code mandatoryExclusions}. What catches it is that
   *     {@code a_projects_own_exclusions_are_kept_alongside_the_mandatory_ones} asserts the whole
   *     list rather than membership, and the two paths are distinct in every fixture in this
   *     repository
   * @param dataDir the root of the tree this server owns on disk, or {@code null} for a deployment
   *     that keeps none — see {@code DataProperties}, which argues why the blank is the default and
   *     why every test context in this repository is in it. It ordinarily <em>contains</em> the
   *     export directory, so on a default deployment it is the only one of the two that survives
   *     the collapse; both are taken because an operator who overrides {@code export-directory} has
   *     moved the payloads out from under it. <b>There is no separate {@code agentsDir} parameter
   *     any more</b> — agent and bot definitions live inside this tree, at {@code global/agents/}
   *     and {@code global/bots/}, so naming the root already fences them off; a directory added
   *     under it needs no second entry kept in step. See {@code ProjectStoreTest
   *     .no_workspace_may_reach_the_definitions_directories}
   */
  public ProjectStore(
      JdbcTemplate jdbc,
      Path configFile,
      Path samplingDir,
      Path tokenFile,
      Path exportDir,
      Path dataDir) {
    this.jdbc = jdbc;
    // Held as given and absolutised in mandatoryExclusions, not both. Doing
    // it here as well would be a second normalisation with no observable
    // effect — a line no test could ever fail on.
    this.configFile = configFile;
    this.samplingDir = samplingDir;
    this.tokenFile = tokenFile;
    this.exportDir = exportDir;
    this.dataDir = dataDir;
  }

  /**
   * Name a project's workspace, replacing whatever it had and lending nothing else.
   *
   * <p>Validates before it writes, and the two refusals are two sentences: "does not exist" and "is
   * not a directory" are different mistakes and an operator fixes them differently. That is
   * distinct from a workspace that <em>vanishes later</em>, which is not this method's business — a
   * root that disappears is advertised as unavailable when it is next asked for, rather than
   * quietly covering nothing.
   *
   * <p><b>This overload is kept, and it is kept for the call sites rather than for the meaning.</b>
   * There are around two hundred {@code .define(…)} calls across this repository's fixtures, every
   * one of them about something other than lending, and a fourth parameter would have edited all of
   * them to say {@code List.of()}. It delegates to {@link #defineLending}, so there is one
   * definition of what defining does; what it fixes is that the definition lends nothing extra.
   *
   * @param exclusions extra paths this project may not reach. Their existence is deliberately not
   *     checked: an exclusion naming a path that is not there yet excludes it from the moment it
   *     appears, and refusing one would mean an operator can only fence off what already exists.
   * @return the row as it was written — absolute, normalised paths
   * @throws ValidationException if the name is blank or has whitespace at one end (see {@link
   *     #named}); if the workspace is null, absent, or is not a directory; or if the exclusions
   *     list is null, holds a null, or holds the empty path
   */
  public ProjectRecord define(String name, Path workspace, List<Path> exclusions, String handle) {
    return defineLending(name, workspace, List.of(), exclusions, handle);
  }

  public ProjectRecord defineLending(
      String name, Path workspace, List<Path> lent, List<Path> exclusions, String handle) {
    new io.aeyer.plowshare.server.auth.AdminStore(jdbc).requireServerAdmin(handle);
    return withCreator(name, handle, () -> defineLending(name, workspace, lent, exclusions));
  }

  public void rootOn(String name, String machine, String root, String handle) {
    if (ClientProjects.privateProject(name)) {
      if (!new ProjectMembers(jdbc).isMember(name, handle))
        throw new ArchiveRefusedException("This account may not attach this client project");
      asserted(machine, "machine");
      asserted(root, "root");
      return;
    }
    Optional<String> owner = personalOwner(name);
    if (owner.isPresent()) {
      if (!owner.get().equals(handle))
        throw new ArchiveRefusedException("This personal space belongs to another account");
      asserted(machine, "machine");
      asserted(root, "root");
      return;
    }
    Optional<ProjectRecord> recorded = find(name);
    if (recorded.filter(ProjectRecord::serverProject).isPresent()) {
      if (!new ProjectMembers(jdbc).mayUse(name, handle))
        throw new ArchiveRefusedException("This account may not attach this project");
      asserted(machine, "machine");
      asserted(root, "root");
      return; // A checkout attaches to the identity; it never relocates the server workspace.
    }
    if (recorded.isEmpty())
      new io.aeyer.plowshare.server.auth.AdminStore(jdbc).requireServerAdmin(handle);
    withCreator(
        name,
        handle,
        () -> {
          rootOn(name, machine, root);
          return null;
        });
  }

  /** Atomic create; an existing project can never be overwritten by a retried request. */
  public ProjectRecord createServer(
      String name, String type, List<String> writePaths, String handle, Supplier<Path> workspace) {
    new io.aeyer.plowshare.server.auth.AdminStore(jdbc).requireServerAdmin(handle);
    ordinary(name);
    return withCreator(
        name,
        handle,
        () -> {
          if (!jdbc.queryForList(
                  "SELECT 1 FROM projects WHERE name = ? AND workspace IS NOT NULL",
                  Integer.class,
                  name)
              .isEmpty())
            throw new ArchiveRefusedException("Project already exists; creation was not repeated");
          defineLending(name, workspace.get(), List.of(), List.of());
          jdbc.update(
              connection -> {
                PreparedStatement statement =
                    connection.prepareStatement(
                        "UPDATE projects SET project_type = ?, write_paths = ? WHERE name = ?");
                statement.setString(1, type);
                statement.setArray(
                    2, connection.createArrayOf("text", writePaths.toArray(String[]::new)));
                statement.setString(3, name);
                return statement;
              });
          return find(name).orElseThrow();
        });
  }

  private <T> T withCreator(String name, String handle, Supplier<T> write) {
    return ArchiveUnavailableException.translating(
        "create a project with its first member",
        () ->
            new TransactionTemplate(
                    new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())))
                .execute(
                    status -> {
                      Long id = ProjectIds.toWrite(jdbc, Home.of(named(name)));
                      jdbc.queryForObject(
                          "SELECT id FROM projects WHERE id = ? FOR UPDATE", Long.class, id);
                      T result = write.get();
                      if (handle != null) {
                        jdbc.update(
                            "INSERT INTO project_members (project_id, handle, role)"
                                + " SELECT ?, ?, 'MANAGER' WHERE NOT EXISTS (SELECT 1 FROM project_members WHERE project_id = ?)"
                                + " ON CONFLICT DO NOTHING",
                            id,
                            handle,
                            id);
                      }
                      return result;
                    }));
  }

  public ProjectRecord define(String name, Path workspace, List<Path> exclusions) {
    return defineLending(name, workspace, List.of(), exclusions);
  }

  /**
   * The same act, naming the further directories lent at that place.
   *
   * <h2>A different name and not a fourth-argument overload</h2>
   *
   * <p>{@code 0cfabdd} removed exactly that trap from {@code mandatoryExclusions}: two overloads
   * the compiler could not tell apart, so a mis-ordered call bound silently and its assertion
   * passed on the wrong path. An overload of {@code define} taking {@code (String, Path,
   * List&lt;Path&gt;, List&lt;Path&gt;)} would not be ambiguous with the three argument form, but
   * every one of those ~200 fixtures would sit one comma away from meaning something else, and the
   * something else — exclusions read as lent roots — is a leash that grows rather than one that
   * fails. A distinct name means the plural form is a thing a caller asks for by name.
   *
   * <h2>What a define does to the lent roots, which is replace them</h2>
   *
   * <p>A define writes a <em>whole definition</em> — that is why {@link #moveWorkspace} exists at
   * all — so it replaces {@code lent} exactly as it replaces {@code exclusions}. The verb that adds
   * a directory without rewriting anything is {@link #lend}.
   *
   * <h2>The lent roots are validated, and the exclusions still are not</h2>
   *
   * <p>The asymmetry is not an inconsistency; it is the difference between a grant and a fence. An
   * exclusion naming a path that does not exist yet fences it off from the moment it appears, which
   * is the behaviour an operator wants. A lent root naming a path that does not exist grants
   * nothing and reports nothing — {@code FileAccess} would carry a root that covers no file, {@code
   * file_roots} would advertise it to a model, and the first refusal a person saw would be about
   * the file rather than about the typo. So each element is checked exactly as the workspace is, by
   * the same method, and <b>the refusal names the element</b>: with several roots in one call, "it
   * does not exist" without a path is a sentence an operator cannot act on.
   *
   * @param lent further directories this project's jobs may reach, beyond the workspace, in the
   *     order they should be rendered. <b>Not required to be inside the workspace</b> — a directory
   *     elsewhere is exactly what lending a second one means, and the only bound is the mandatory
   *     exclusions, which {@code FileAccess.of} applies to a lent root and to the workspace alike.
   *     Pass an empty list for none
   * @throws ValidationException additionally if the lent list is null, holds a null, holds the
   *     empty path, or names something that is not a directory on this server — naming which
   *     element
   */
  public ProjectRecord defineLending(
      String name, Path workspace, List<Path> lent, List<Path> exclusions) {
    ordinary(name);
    String project = named(name);
    ProjectRecord record =
        new ProjectRecord(
            project,
            validWorkspace(project, workspace),
            lentRoots(project, lent),
            normalised(exclusions));
    String[] lending = strings(record.lent());
    String[] excluded = strings(record.exclusions());
    int rows =
        ArchiveUnavailableException.translating(
            "define a project's workspace",
            () ->
                jdbc.update(
                    connection -> {
                      PreparedStatement statement = connection.prepareStatement(UPSERT);
                      statement.setString(1, record.name());
                      statement.setString(2, record.workspace().toString());
                      // createArrayOf and not a hand-built '{...}' literal: a path
                      // may legally contain a comma, a brace, a quote or a
                      // backslash, and every one of those needs escaping inside an
                      // array literal. The driver does it; an escaper written here
                      // would be a second, worse copy that fails on the paths
                      // nobody thought to test.
                      statement.setArray(3, connection.createArrayOf("text", lending));
                      statement.setArray(4, connection.createArrayOf("text", excluded));
                      return statement;
                    }));
    if (rows == 0) {
      // The upsert's WHERE declined it. The only thing that can decline it
      // is a machine, so this reads the row to say which -- on the refusal
      // path and never on the one that wrote.
      throw notThisServers(project, "given a workspace on this server");
    }
    return find(project).orElseThrow();
  }

  /**
   * Lend a project further directories at the place it already is, keeping everything else in the
   * row.
   *
   * <h2>What this buys that {@link #defineLending} does not</h2>
   *
   * <p>{@code 809338d} made a dot-prefixed path component unreachable unless a root names it, and
   * said the cost was recoverable by adding {@code .github} as an explicit root. This is that verb.
   * Doing it through a define would mean an operator reading the current workspace and the current
   * exclusions and posting all three back — two statements with a window between them and a fence
   * silently dropped whenever they forget, which is exactly the argument {@link #moveWorkspace}
   * makes one level along.
   *
   * <p><b>Additive, and one statement.</b> The concatenation happens inside the {@code UPDATE}
   * rather than in Java, so two operators lending at once cannot each read the same list and have
   * the second write discard the first's root.
   *
   * <p><b>A root already lent is not appended again</b>, and that is a convenience rather than a
   * rule: {@code ProjectRecord.roots()} does not deduplicate and {@code FileAccess.permits} answers
   * identically either way, so nothing is protected by this — what it avoids is a {@code
   * file_roots} listing that shows a model the same directory twice because a person retried a
   * command.
   *
   * <p><b>Identity is untouched, which is the whole reason this column exists.</b> {@code
   * workspace} is not named by the statement, so the {@code <PATH>} of {@code
   * <MACHINE>/<PATH>/<PROJ_NAME>} cannot move through this door. V30 carries that argument.
   *
   * @param roots the directories to lend, each checked to be a directory on this server exactly as
   *     a workspace is, and each named in its own refusal. May sit outside the workspace
   * @return the row as it now stands, including the roots that were already lent — returned by the
   *     statement rather than read back, for {@link #moveWorkspace}'s reason
   * @throws ArchiveException if no project of that name has a workspace on this server
   * @throws ValidationException if the name is blank or has whitespace at one end, or the list is
   *     null, empty, holds a null, holds the empty path, or names something that is not a directory
   */
  public ProjectRecord lend(String name, List<Path> roots) {
    ordinary(name);
    String project = named(name);
    // Empty is refused HERE and nowhere else in this file, and the asymmetry
    // is deliberate: an empty `exclusions` on a define is a whole definition
    // that fences nothing, which is a real thing to say, while an empty list
    // here is a lend that lends nothing -- a call with no effect, answering
    // "done", most likely a caller that filtered its own list down to
    // nothing and did not notice.
    List<Path> lending = lentRoots(project, roots);
    if (lending.isEmpty()) {
      throw new ValidationException(
          "project '"
              + project
              + "' was asked to lend no"
              + " directories at all; a lend with an empty list changes nothing, so this"
              + " is a caller that lost its list rather than an operator's intent");
    }
    return updatedLent(LEND, project, lending, "lend a project a directory", "lent a directory");
  }

  /**
   * Take lent directories back, leaving the workspace and everything else alone.
   *
   * <p><b>The paths are not checked for existence, and that is the difference from {@link #lend}
   * that matters.</b> The commonest reason to unlend something is that it is gone — a checkout
   * deleted, a mount unmounted — and a store that refused to remove a root because the root was
   * missing would leave the row permanently naming a directory nobody can take out of it. They are
   * still absolutised, because that is how they were stored and a relative spelling would match
   * nothing.
   *
   * <p><b>Idempotent, and it does not refuse a root it does not hold.</b> {@link #forget} refuses a
   * name no row has, on the grounds that silence would let a mistyped name read as success — but
   * {@code forget} answers with nothing, and this answers with the row. An operator who mistyped a
   * path reads the unchanged list straight back. Refusing instead would need the array as it was
   * before the statement, which is a second read and the window this method does not have.
   *
   * @param roots the directories to stop lending. A path the project does not lend is left out of
   *     the answer, which is the state the caller asked for
   * @return the row as it now stands
   * @throws ArchiveException if no project of that name has a workspace on this server
   * @throws ValidationException if the name is blank or has whitespace at one end, or the list is
   *     null, empty, holds a null, or holds the empty path
   */
  public ProjectRecord unlend(String name, List<Path> roots) {
    ordinary(name);
    String project = named(name);
    List<Path> removing = absolutised(roots, "roots");
    if (removing.isEmpty()) {
      throw new ValidationException(
          "project '"
              + project
              + "' was asked to unlend no"
              + " directories at all; a call that changes nothing is a caller that lost"
              + " its list rather than an operator's intent");
    }
    return updatedLent(
        UNLEND, project, removing, "stop lending a project a directory", "unlent a directory");
  }

  /**
   * The half {@link #lend} and {@link #unlend} share: one statement, one array parameter, the row
   * back or a refusal naming the machine.
   *
   * <p>Shared rather than written twice because the refusal is the subtle part. Both statements
   * carry {@code IS_THIS_SERVERS}, so both decline a project whose files are on a laptop, and both
   * have to say which machine — the argument {@code notThisServers} makes for {@link #define} and
   * {@link #forget} is the same one here, and a second copy of it is a second thing to keep true.
   */
  private ProjectRecord updatedLent(
      String statement, String project, List<Path> roots, String doing, String verb) {
    String[] paths = strings(roots);
    List<ProjectRecord> updated =
        ArchiveUnavailableException.translating(
            doing,
            () ->
                jdbc.query(
                    connection -> {
                      PreparedStatement prepared = connection.prepareStatement(statement);
                      prepared.setArray(1, connection.createArrayOf("text", paths));
                      prepared.setString(2, project);
                      return prepared;
                    },
                    ROW_MAPPER));
    if (updated.isEmpty()) {
      throw notThisServers(project, verb);
    }
    return updated.get(0);
  }

  /**
   * Write down that a project's files are on a particular machine, at a particular place on it.
   *
   * <h2>Declaring a presence <em>is</em> the registration</h2>
   *
   * <p>The design spec's §13.4 recorded that there is "no verb for 'this project exists and its
   * files are elsewhere'" and asked for one. There did not need to be a new one. A client already
   * declares {@code ?session=&machine=&root=&project=} when it opens the file channel — §12's "all
   * three or none" — and those are exactly the three things a row needs. A separate verb would be a
   * second way to assert the same fact, reachable by an operator who is not at the machine in
   * question, and the two would disagree the first time somebody typed the wrong path. So this is
   * called from the declaration and from nowhere else.
   *
   * <h2>The path is not validated, and must not be</h2>
   *
   * <p>{@link #define} checks that the workspace is a directory on the server's disk. There is
   * nothing equivalent to do here: <b>the server does not have that disk</b>, and a check it cannot
   * perform would be theatre — it could only ever test whether this machine happens to hold a path
   * of the same name, which is the one answer that is worse than none.
   *
   * <p>The client is the enforcement point for its own files and is therefore the authority on
   * them. That is the same reasoning that puts {@code ProjectTools} on the MCP side: <em>the party
   * that sets the leash must not be a party the leash binds</em>. What is checked is that the three
   * components name something at all, which {@code Presence} has already done more strictly on the
   * way in.
   *
   * <h2>What it refuses</h2>
   *
   * <p>A project already rooted somewhere else, whether that is another machine or this server.
   * §8.1: a project exists in exactly one location, so two places claiming one name is a conflict
   * rather than a choice — and the durable half of that rule has to be here, because {@code
   * PresenceRegistry} can only see claims that are <em>live</em>. A laptop claiming a project the
   * desk rooted last week and has since closed competes with nothing in the registry.
   *
   * <p><b>A server-rooted project is refused too, and that is a decision rather than a
   * fallthrough.</b> Such a row is a place somebody established — {@code define} checked that
   * directory on this disk — and an archive has been filed under it by agents that read those
   * files. Letting a presence take the name would put one id over two places and hand one project's
   * whole archive to another machine's files, which is the silent merge {@code
   * projects_name_is_unique} exists to prevent arriving by a door it does not cover. The refusal
   * names the workspace because the fix is on this side and it is lossless: {@link #forget} drops
   * the workspace and keeps the row, after which the project has no place, and this claims it with
   * its id and its whole archive intact.
   *
   * @param name the friendly project name the client declared — what {@code Home} carries and what
   *     the registry keys on
   * @param machine what the client calls the box it sits on
   * @param root where the project sits on that machine, as the client spells it. A {@link String}
   *     and never a {@link Path}, for {@code Presence}'s reason: this process must not resolve it,
   *     and a Windows client's {@code C:\work\ledger} is not this server's to normalise
   * @throws ArchiveRefusedException if another machine, or this server, already roots the project
   * @throws ValidationException if the name is blank or has whitespace at one end, or if either of
   *     the other two is blank
   */
  public void rootOn(String name, String machine, String root) {
    ordinary(name);
    String project = named(name);
    String box = asserted(machine, "machine");
    String place = asserted(root, "root");
    int rows =
        ArchiveUnavailableException.translating(
            "root a project on the machine that holds it",
            () -> jdbc.update(CLAIM, project, box, place));
    if (rows == 0) {
      throw heldByAnother(project, box);
    }
  }

  /**
   * The machine that roots this project, or empty when it is this server's or there is no row.
   *
   * <p><b>The design spec's §5 asked once, and answered from a column instead of from a filesystem
   * probe.</b> Both trust models are permanent and correct — {@code LocalProvider} reads the
   * server's disk, {@code RemoteProvider} reads a client's through the leash that client enforces —
   * and the requirement is that the code always knows which applies. It used to guess, by asking
   * whether this server holds the project's workspace, and §13.3 recorded what that cannot tell
   * apart: a project that has always lived on a laptop and one that lived here until somebody
   * deleted the directory are byte-identical in the row. They are not any more.
   *
   * <p><b>Empty conflates the server with a project that has no place at all, and that is correct
   * for every caller there is.</b> Neither is rooted elsewhere; what separates them is whether
   * {@code workspace} is there, which is {@link #find}'s question and not this one's. A caller that
   * needs both asks both.
   *
   * @throws ValidationException if the name is blank or has whitespace at one end — the same
   *     refusal {@link #find} gives, for the same reason: a name no row can hold is a mistake
   *     rather than an absence
   */
  public Optional<String> rootedElsewhere(String name) {
    String wanted = named(name);
    List<String> found =
        ArchiveUnavailableException.translating(
            "read which machine roots a project",
            () ->
                jdbc.queryForList(
                    "SELECT machine FROM projects WHERE name = ? AND machine IS NOT NULL",
                    String.class,
                    wanted));
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  /**
   * Point a project at a different directory, keeping everything else in the row.
   *
   * <p><b>An UPDATE and not the upsert, and the difference is what it does to the exclusions.</b>
   * {@link #define} writes a whole definition, so it replaces them; moving a workspace through it
   * would need the caller to read the old list and pass it back, which is two statements with a
   * window between them — the very thing the upsert exists to close — and which silently drops the
   * fence if the caller forgets. One statement keeps both properties: the project is never without
   * a leash, and the paths an operator fenced off survive the move.
   *
   * <p><b>It does not create.</b> {@code define} upserts because naming a project and moving it are
   * the same act when you are writing the whole definition; here they are not. A move that quietly
   * created would answer "done" to a mistyped name and leave a project nobody meant to define,
   * holding a real workspace.
   *
   * <p>Validated exactly as {@link #define} validates, by the same method: a path is checked where
   * it is written, not where it is typed.
   *
   * @return the row as it now stands. Returned by the statement rather than read back, which is not
   *     an optimisation: this method is told a workspace and never the exclusions, so a record it
   *     assembled would have to guess at the half it did not write, and a second SELECT would be a
   *     window a {@link #forget} can land in — leaving a second "no such project" branch that no
   *     test could ever reach
   * @throws ArchiveException if no project has that name
   * @throws ValidationException if the name is blank or has whitespace at one end, or the workspace
   *     is null, absent, or is not a directory
   */
  public ProjectRecord moveWorkspace(String name, Path workspace) {
    ordinary(name);
    String project = named(name);
    Path resolved = validWorkspace(project, workspace);
    List<ProjectRecord> updated =
        ArchiveUnavailableException.translating(
            "move a project's workspace",
            () ->
                jdbc.query(
                    "UPDATE projects SET workspace = ?, defined_at = now()"
                        + " WHERE name = ? AND personal_owner IS NULL AND "
                        + IS_THIS_SERVERS
                        + " RETURNING "
                        + COLUMNS,
                    ROW_MAPPER,
                    resolved.toString(),
                    project));
    if (updated.isEmpty()) {
      throw notThisServers(project, "moved");
    }
    return updated.get(0);
  }

  /**
   * Move a project: give it a different name, and its whole archive with it.
   *
   * <h2>One row and one column, which is the whole of V14</h2>
   *
   * <p>A project's canonical name is {@code <MACHINE>/<PATH>/<PROJ_NAME>} ({@code
   * 2026-09-03-harness-presence-design.md} §10), so <b>moving a project to another machine changes
   * its name</b>. Before the surrogate key that meant rewriting every {@code memories} row and
   * every {@code conversations} row of one project — in one transaction, or leaving the project
   * split across two names with half its history in each and nothing anywhere saying which was
   * live. Since V14 those tables reference the id, so this is a single-column update of a single
   * row and <b>atomicity is a property rather than something to engineer</b>. {@code
   * a_moved_project_carries_its_memories_and_its_conversations_with_it} asserts that rather than
   * trusting it.
   *
   * <h2>No {@code IS_THIS_SERVERS}, unlike the three methods above it</h2>
   *
   * <p>Deliberate and not an omission. Those three answer about a <b>leash</b>, and a project that
   * has never been given one has no leash to describe, move or drop. This answers about an
   * <b>identity</b>, and a project with no workspace still has one — it has memories, conversations
   * and an id, which is precisely what V14 made the table able to say. A project that only ever
   * held memories is the commonest thing a presence roots, since {@code define} can only name a
   * directory on the <em>server's</em> disk; refusing to move it would put the operation out of
   * reach of the projects that most need it.
   *
   * <h2>{@code defined_at} is not touched</h2>
   *
   * <p>The column dates the current <em>definition</em> of the workspace, which is what {@code
   * define}, {@link #moveWorkspace} and {@link #forget} all change and what an operator reads it to
   * learn. A move changes neither the directory nor the exclusions, so stamping it here would
   * answer "when was this project's workspace last set" with the date of a rename.
   *
   * @param from the project as it is called now
   * @param to what to call it. <b>Not validated as a canonical name</b>, and that is the task
   *     boundary: {@code projects.name} holds friendly names today (design spec §12.1), the
   *     presence registry keys on them (§12.2), and {@code Home} does not change (§10). This
   *     operation is name-agnostic by construction, which is what lets it be the same one row and
   *     one column on the day canonical names become the stored key
   * @throws ArchiveException if no project is called {@code from}. Silence would let a mistyped
   *     name read as a project successfully moved, which is {@link #forget}'s argument and the same
   *     absence
   * @throws ArchiveRefusedException if a project is already called {@code to}. The row is there and
   *     this will not be done to it — and it must be a sentence rather than {@code
   *     projects_name_is_unique}, because what the constraint prevents is <b>two projects' archives
   *     ending up under one id</b> and an operator reading "duplicate key value" learns none of
   *     that
   * @throws ValidationException if either name is blank or has whitespace at one end. Both ends,
   *     because no row can hold such a name either way
   */
  public void rename(String from, String to) {
    ordinary(from);
    ordinary(to);
    String current = named(from);
    String wanted = named(to);
    int rows;
    try {
      rows =
          ArchiveUnavailableException.translating(
              "move a project",
              () -> jdbc.update("UPDATE projects SET name = ? WHERE name = ?", wanted, current));
    } catch (DuplicateKeyException taken) {
      // OUTSIDE the translating call rather than inside it, which is the
      // shape ProposalStore.propose uses for its foreign key and which is
      // safe for the reason ArchiveUnavailableException's javadoc gives:
      // DuplicateKeyException is not on its unavailable list, so it is
      // rethrown untouched and arrives here. Nothing was written -- the
      // statement aborted -- so this leaves both projects exactly as they
      // were.
      //
      // Both names, and what it would have cost. "That name is taken"
      // would be accurate and useless: the harm is not that a write
      // failed, it is that had it succeeded the two projects would share
      // one id and therefore one archive, with no query able to say which
      // memory came from which. The one thing an operator can do about it
      // is choose another name, so the sentence ends there.
      throw new ArchiveRefusedException(
          "a project is already called "
              + wanted
              + ", so '"
              + current
              + "' was not moved onto it. Two projects cannot share"
              + " a name: memories and conversations reach a project through its id, so"
              + " one name over two projects would put both archives under whichever id"
              + " survived, and nothing could tell them apart afterwards. Move it to a"
              + " name nothing holds, or forget the project that is already there.");
    }
    if (rows == 0) {
      throw new ArchiveException("no project is called " + current);
    }
  }

  /**
   * The project with this name, or empty.
   *
   * <p>Empty is an ordinary answer, as it is for {@link MemoryStore#load}: a project that has never
   * been given a workspace is a question, not a mistake. A <em>malformed</em> name is the mistake,
   * and it is refused rather than answered — see {@link #named}.
   *
   * <p><b>For whatever routes a job to its files:</b> the refusal means {@code
   * find(home.project())} <em>throws</em> for a {@code Home} the protocol module itself accepts.
   * {@code Home.of} does not refuse edge whitespace (pinned by {@code HomeTest}), so {@code "
   * payments "} is a creatable, writable memory tier for which no workspace can ever be defined.
   * Throwing is the intended behaviour and not an oversight — a job in a tier that can never have a
   * workspace should stop, rather than run on with silently no file access — but it is a case the
   * caller has to handle on purpose.
   *
   * @throws ValidationException if the name is blank or has whitespace at one end
   */
  public Optional<ProjectRecord> find(String name) {
    String wanted = named(name);
    List<ProjectRecord> found =
        ArchiveUnavailableException.translating(
            "read a project by name",
            () ->
                jdbc.query(
                    "SELECT "
                        + COLUMNS
                        + " FROM projects"
                        + " WHERE name = ? AND personal_owner IS NULL AND "
                        + IS_THIS_SERVERS,
                    ROW_MAPPER,
                    wanted));
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  /** Internal project identity lookup for scoped configuration readers. */
  public Optional<String> nameForId(long id) {
    return ArchiveUnavailableException.translating(
        "read a project identity",
        () ->
            jdbc.queryForList("SELECT name FROM projects WHERE id = ?", String.class, id).stream()
                .findFirst());
  }

  /**
   * Whether a row with this id exists at all — the question {@code
   * io.aeyer.plowshare.server.agents.DefinitionResolver} asks on every project-tier cache miss, and
   * the whole reason this method exists rather than yet another caller of {@link #find}.
   *
   * <h2>Yes/no, and deliberately not a row</h2>
   *
   * <p><b>{@code SELECT 1}, never {@code SELECT} {@link #COLUMNS}.</b> A definition resolution
   * needs one bit — does the project this caller names still have a row, or was it deleted out from
   * under a directory the filesystem still holds — and {@link #find} would answer that by
   * constructing a {@link ProjectRecord}, decoding two {@code TEXT[]} columns through {@link
   * #paths}, for every miss. That is work spent building an object nobody reads a single field of,
   * on a path measured to run once per project rather than once per call — see {@code
   * DefinitionResolver#readProject} — but "runs less often" is not "may cost more each time" so
   * long as the cheaper shape is this easy to write.
   *
   * <p><b>By id, unlike every other query in this file.</b> Every other method here keys on {@code
   * name}, because a job or an operator names a project the way a person types it. {@code
   * DefinitionResolver.Caller#projectId()} is already a surrogate id — resolved once, the way V14's
   * own javadoc argues a long-lived reference should be — so asking by id is not a second lookup
   * layered on the first; it is the one lookup this caller was always going to make.
   *
   * <p><b>No {@code IS_THIS_SERVERS}, and that is not an oversight.</b> The three leash methods
   * filter on it because a project rooted on another machine has no <em>local</em> workspace for
   * this server to serve — but a project's {@code agents/} and {@code bots/} definitions are
   * server-side regardless of which machine holds its files (this class's own javadoc, "one of the
   * two project-level sources on the caller's own machine" is the other one). A project rooted
   * elsewhere still exists, and its definitions tier is exactly as real as a server-rooted
   * project's; excluding it here would make a perfectly good project's own {@code bots/} directory
   * unreachable for a reason that has nothing to do with definitions at all.
   *
   * @return {@code true} if any row — rooted here, rooted elsewhere, or rooted nowhere at all — has
   *     this id
   */
  public boolean exists(long id) {
    return ArchiveUnavailableException.translating(
        "check whether a project exists",
        () ->
            !jdbc.queryForList("SELECT 1 FROM projects WHERE id = ?", Integer.class, id).isEmpty());
  }

  /**
   * {@link #exists}'s inverse: the surrogate key behind a name, for the one caller that has a name
   * and needs the id — {@code requests.RequestedProjectId}, translating a request's own {@code
   * project} field into the id the {@code DefinitionResolver.Caller} that {@code agents.Callers}
   * builds carries. A name a person typed is what the API layer holds; the id is what {@link
   * io.aeyer.plowshare.server.agents.DefinitionResolver} caches and asks {@link #exists} about.
   *
   * <p>{@code null} for a name no row has, which is an ordinary answer and not a mistake — the same
   * reading {@link #find} gives a workspace-less project. A caller building a {@code Caller} from
   * it gets {@code projectId == null}, which resolves to the boot set exactly as the global tier
   * does; there is no third state to invent here; a project that has never been written to has no
   * definitions tier to distinguish from "none at all".
   *
   * <p>No {@code IS_THIS_SERVERS}, matching {@link #exists}'s own reasoning: a project's
   * definitions tier is server-side regardless of which machine holds its files, so a project
   * rooted elsewhere must resolve to an id here just as readily as one rooted on this server.
   *
   * @throws ValidationException if the name is blank or has whitespace at one end, {@link #named}'s
   *     own refusal
   */
  public Long id(String name) {
    String wanted = named(name);
    return ArchiveUnavailableException.translating(
        "look up a project's id",
        () -> {
          List<Long> found =
              jdbc.queryForList("SELECT id FROM projects WHERE name = ?", Long.class, wanted);
          return found.isEmpty() ? null : found.get(0);
        });
  }

  /**
   * Every project that has a workspace, by name.
   *
   * <p><b>Ordered by name, which is the listing's order and not an incidental one.</b> {@code GET
   * /v1/projects} answers in whatever order this returns, so a console that renders it twice
   * renders it the same way both times without sorting client-side.
   *
   * <p>This javadoc used to record that the method had no production caller deliberately, and that
   * a {@code GET /v1/projects} would be speculation because no spec named it and no client tool
   * called it. The console's projects screen is that caller. The other half of the old note still
   * holds and is why the method would survive losing it again: it is also an <em>instrument</em>,
   * the way {@code ProjectStoreTest} asks "one row, not two" — the question that catches a
   * redefinition that inserted and a move that created — and there is no other way to ask it.
   *
   * <p><b>Every machine's, and not {@code IS_THIS_SERVERS}.</b> A listing grants no leash, so the
   * half of that predicate that keeps a laptop's path from being read as this server's directory
   * has nothing to protect here — and carrying it hid every project a client rooted, which left the
   * machine each listed row names always null. {@link #rootedElsewhere} is what tells a caller
   * whose files a row describes.
   */
  public Optional<String> personalOwner(String name) {
    return ArchiveUnavailableException.translating(
        "read Personal ownership",
        () ->
            jdbc
                .queryForList(
                    "SELECT personal_owner FROM projects WHERE name = ? AND personal_owner IS NOT NULL",
                    String.class,
                    name)
                .stream()
                .findFirst());
  }

  public ProjectRecord attachClient(
      String name, String workspace, String machine, String session, String handle) {
    String key = ClientProjects.key(name, session, handle);
    asserted(workspace, "workspace");
    asserted(machine, "machine");
    return withCreator(
        key,
        handle,
        () -> {
          jdbc.update(
              "UPDATE projects SET workspace = ?, machine = ?, lent = '{}', exclusions = '{}', project_type = 'DISJOINT', write_paths = ARRAY['.']::TEXT[], union_since = NULL WHERE name = ?",
              workspace,
              machine,
              key);
          return jdbc.queryForObject(
              "SELECT " + COLUMNS + " FROM projects WHERE name = ?", ROW_MAPPER, key);
        });
  }

  public List<ProjectRecord> allForSession(String handle, String session) {
    List<ProjectRecord> rows = new ArrayList<>(allFor(handle));
    if (session != null && handle != null)
      rows.addAll(
          ArchiveUnavailableException.translating(
              "list this client's projects",
              () ->
                  jdbc.query(
                      "SELECT "
                          + COLUMNS
                          + " FROM projects WHERE workspace IS NOT NULL AND name LIKE ? ORDER BY name",
                      ROW_MAPPER,
                      ClientProjects.prefix(session, handle) + "%")));
    return List.copyOf(rows);
  }

  public String roleFor(String project, String handle) {
    return new ProjectMembers(jdbc).role(project, handle).map(Enum::name).orElse(null);
  }

  public List<ProjectRecord> allFor(String handle) {
    if (io.aeyer.plowshare.server.auth.ServiceCredentials.principal(handle)) {
      var grants = new ProjectMembers(jdbc);
      return all().stream().filter(row -> grants.mayUse(row.name(), handle)).toList();
    }
    return ArchiveUnavailableException.translating(
        "list the projects",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + " FROM projects WHERE workspace IS NOT NULL"
                    + " AND name NOT LIKE 'client:%' AND (personal_owner IS NULL OR personal_owner = ?)"
                    + " AND (personal_owner = ? OR EXISTS (SELECT 1 FROM admins a WHERE a.handle = ? AND a.enabled AND a.server_admin AND NOT a.bootstrap)"
                    + " OR EXISTS (SELECT 1 FROM project_members m WHERE m.project_id = projects.id AND m.handle = ?)) ORDER BY name",
                ROW_MAPPER,
                handle,
                handle,
                handle,
                handle));
  }

  private void ordinary(String name) {
    if (ClientProjects.privateProject(name))
      throw new ArchiveRefusedException(
          "Client projects cannot be published or administered as server projects");
    if (name != null && (name.startsWith("personal:") || name.startsWith("Personal:"))) {
      throw new ArchiveRefusedException(
          "Personal space is managed by its account and cannot be changed as an ordinary project");
    }
  }

  public List<ProjectRecord> all() {
    return ArchiveUnavailableException.translating(
        "list the projects",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + " FROM projects WHERE workspace IS NOT NULL"
                    + " ORDER BY name",
                ROW_MAPPER));
  }

  /**
   * Drop a project's workspace, leaving its memories alone.
   *
   * <p><b>It nulls the workspace; it does not delete the row.</b> Until V14 it deleted, and could:
   * nothing referenced this table. Now {@code memories.project_id} and {@code
   * conversations.project_id} do, and a delete would either be refused by the foreign key or — with
   * the wrong {@code ON DELETE} — quietly move a project's whole history into the global tier.
   *
   * <p>The answer is the same one it always was, and it is better than it was: a project that loses
   * its workspace keeps everything it has ever remembered, its jobs go back to having no local file
   * access, and it keeps its id. So a project forgotten and later re-defined is the <em>same</em>
   * project rather than a new one wearing its name, which is what the row deletion could not
   * promise.
   *
   * <p>The exclusions go with the workspace, because they only mean anything inside one: an
   * exclusion is a hole in a leash, and a project with no leash has nothing for them to be a hole
   * in. Leaving them would let a later {@link #moveWorkspace} — which keeps a project's own
   * exclusions on purpose — resurrect fences an operator had already dropped.
   *
   * <p><b>The lent roots go with it too, and for the sharper half of the same argument.</b> A stale
   * exclusion that survived a forget would fence off more than an operator meant; a stale lent root
   * would <em>reach</em> more. The two columns are not symmetrical in what they cost when they are
   * left behind: {@code projects_machine_has_a_place} lets a forgotten project be claimed by a
   * client on another machine, and a row that still lent {@code /Users/example/keys} would hand
   * that path to whoever re-defined the name next, having never been told it was there. Clearing it
   * means "this project has no place" is one fact and not a place with an empty middle.
   *
   * <p>One project, never the table: the {@code WHERE} is the whole of the difference between this
   * and wiping every workspace on the server, and {@code
   * one_projects_workspace_is_not_another_projects} is what holds it there. Removing it left a
   * suite of single-row fixtures entirely green.
   *
   * <h2>It does not touch the project's directory under the data tree</h2>
   *
   * <p><b>A forgotten project keeps its exports, and this is a decision rather than an
   * omission.</b> Since {@code DataLayout} there are server-owned <em>files</em> keyed by project
   * id, and "what happens to them when a project is forgotten" is a question that has to be
   * answered somewhere rather than discovered by whoever looks in the directory first.
   *
   * <p>Two things settle it. This method is <b>metadata</b> — it nulls three columns and keeps the
   * row, the memories, the conversations and the id — and deleting a person's file bodies on the
   * strength of a metadata edit is the surprising direction: an operator dropping a workspace is
   * saying "this project's files are not on my disk any more", which is a statement about
   * <em>their</em> tree and not about this server's. And the id survives, so a project forgotten
   * and re-defined is the same project and finds its own exports where it left them — which is
   * precisely what keying by id rather than by name bought.
   *
   * <p>The cost, stated rather than left to be found: <b>a project forgotten and never re-defined
   * leaves a directory nothing reaches for.</b> {@code Retention} sweeps conversations and jobs by
   * age and has no notion of an orphaned tree, so those bytes stay until somebody removes them by
   * hand. That is the price of the safe direction, and it is a price paid in disk rather than in
   * somebody's data.
   *
   * @throws ArchiveException if no project has that name. Silence would let a mistyped name read as
   *     a workspace successfully removed, which is the one outcome an operator would not check.
   * @throws ValidationException if the name is blank or has whitespace at one end
   */
  public void forget(String name) {
    ordinary(name);
    String wanted = named(name);
    int rows =
        ArchiveUnavailableException.translating(
            "forget a project's workspace",
            () ->
                jdbc.update(
                    "UPDATE projects SET workspace = NULL, lent = '{}',"
                        + " exclusions = '{}', defined_at = now()"
                        + " WHERE name = ? AND personal_owner IS NULL AND "
                        + IS_THIS_SERVERS,
                    wanted));
    if (rows == 0) {
      throw notThisServers(wanted, "forgotten");
    }
  }

  /**
   * Everything this project may not reach: its own exclusions, plus the ones no project may
   * override.
   *
   * <p><b>This, and never {@link ProjectRecord#exclusions()}, is what a containment check is built
   * from.</b> The record carries the row; this carries the rule. A check built from the row alone
   * is a check a {@code projects} row can switch off.
   *
   * <p>The mandatory exclusions themselves, and why they are not stored in the row, are {@link
   * #mandatoryExclusions}'s to explain.
   *
   * @throws ArchiveException if no project has that name — rather than answering with the mandatory
   *     exclusions, which are a real containment set for a project that does not exist and would
   *     let a typo look like a working answer.
   * @throws ValidationException if the name is blank or has whitespace at one end
   */
  public List<Path> effectiveExclusions(String name) {
    // No named() of its own: find does it, and it does it before it answers,
    // so by the time this supplier runs the name has already been through
    // the same refusal. A second call here would be a line no mutant could
    // kill, which is a line carrying no rule.
    return effectiveExclusions(
        find(name)
            .orElseThrow(
                () -> new ArchiveException("no project named " + name + " has a workspace")));
  }

  /**
   * The same rule, applied to a row the caller has already read.
   *
   * <p><b>This overload exists so that a caller needing the workspace <em>and</em> the exclusions
   * reads the row once.</b> Doing it as two calls opens a window: a {@link #forget} landing between
   * them makes the second raise {@link ArchiveException} for a project the first had just answered
   * for. {@code LocalProvider} is that caller, and the window was exactly the operation its
   * per-call re-read exists to honour — an operator revoking a workspace — so the third exception
   * type arrived on the one path the design is about. Reading once removes the window, rather than
   * adding a branch no test could ever reach.
   *
   * <p>It is not a way round {@link ProjectRecord#exclusions()}'s hazard; it is the opposite. The
   * record is still the row and still carries only what the row stores. This is the store applying
   * the rule <em>to</em> that row, which is why it lives here: a caller that combined the two lists
   * itself would be the second copy of the rule, and the copy that drifts is the one deciding what
   * an agent may read.
   *
   * @param project a row from {@link #find} or {@link #all}
   */
  public List<Path> effectiveExclusions(ProjectRecord project) {
    // A Set, because a row is free to list a mandatory exclusion as well and
    // a duplicate would make `effectiveExclusions().size()` a number nothing
    // could predict. *Linked*, because the order is part of the answer: the
    // mandatory ones come first, so an operator reading a refusal sees the
    // rule before the project's own preferences. Pinned by
    // `a_projects_own_exclusions_are_kept_alongside_the_mandatory_ones`,
    // which asserts the whole list — a plain HashSet passes a `contains`
    // assertion and leaves that ordering an accident.
    Set<Path> all =
        new LinkedHashSet<>(
            mandatoryExclusions(configFile, samplingDir, tokenFile, exportDir, dataDir));
    all.addAll(project.exclusions());
    return List.copyOf(all);
  }

  /**
   * The exclusions no {@code projects} row may override.
   *
   * <p>Static and taking the paths it needs, so that the rule is one expression with no store, no
   * database and no project behind it: whatever a row says, these are in {@link
   * #effectiveExclusions}'s answer.
   *
   * <h2>Why the working directory, and not a list of configuration files</h2>
   *
   * <p><b>This returned exactly two paths until a security review measured what that missed.</b> It
   * named {@code application.yml} — one file, from one property — and <b>Spring Boot loads
   * configuration from four locations</b>: {@code classpath:/}, {@code classpath:/config/}, {@code
   * file:./} and {@code file:./config/}, later winning. Measured against the real {@link
   * io.aeyer.plowshare.protocol.FileAccess}, with a workspace one level above this server's working
   * directory and the old two-path rule in force:
   *
   * <pre>
   *   permits(application.yml)         = false
   *   permits(config/application.yml)  = true   &lt;- and it OUTRANKS the excluded one
   *   permits(application.properties)  = true
   *   permits(application-prod.yml)    = true
   * </pre>
   *
   * <p>Two consequences, and the second is worse. <b>A read leak:</b> an operator who keeps
   * configuration in {@code ./config/application.yml} — a first-class documented Spring layout —
   * has the live model API key in a file {@code file_read} can open, which defeats this
   * repository's one rule with no exceptions by a route no {@code grep} over source can find. <b>A
   * write escalation with a delay fuse:</b> {@link
   * io.aeyer.plowshare.server.files.LocalProvider#write} creates missing parent directories, so an
   * agent holding {@code workspace:write} over a workspace covering this directory could
   * <em>create</em> {@code ./config/application.yml} and rewrite {@code
   * plowshare.llm.sampling-directory} and {@code plowshare.workspace.config-file} for the next
   * boot. That is the exact escalation the sampling-directory exclusion exists to stop, arriving
   * through the other exclusion.
   *
   * <p><b>So the rule is a location and not a filename.</b> The working directory is where Spring
   * Boot searches whatever this server is configured to call its configuration file, under every
   * name, extension and profile it accepts — {@code application-prod.yml} is as loadable as {@code
   * application.yml}, and no list of names can be complete because a profile name is arbitrary.
   * Excluding the directory is <em>derived from one fact</em> — that {@code file:./} and {@code
   * file:./config/} are always searched — where a list of four names would be the same defect one
   * size larger.
   *
   * <p><b>What it costs, stated rather than discovered.</b> A workspace that contains this server's
   * own working directory now reaches nothing inside it, and a workspace that <em>is</em> that
   * directory is dropped entirely by {@link io.aeyer.plowshare.protocol.FileAccess#of} and reported
   * as such. That is the configuration the attack needs, and refusing it by name is better than
   * serving it. A workspace anywhere else is untouched, which is every workspace in this
   * repository's tests and every ordinary deployment, where the server's install directory is not
   * somebody's project.
   *
   * <p>The configured file is still listed on its own account, because an operator may point {@code
   * PLOWSHARE_CONFIG_FILE} at somewhere the working directory does not cover — {@code
   * /etc/plowshare/application.yml} — and that file holds the key wherever it is.
   *
   * <h2>Why the token file is here as well as behind the hidden rule</h2>
   *
   * <p><b>Not redundant, and both are worth building.</b> {@link
   * io.aeyer.plowshare.protocol.FileAccess#permits} refuses every path with a dot-prefixed
   * component below its root, which protects {@code ~/.config/plowshare/console-token} <em>because
   * of where it happens to sit</em>. {@code plowshare.auth.token-file} is configurable: an operator
   * who points it at {@code /srv/plowshare/console-token} loses the predicate's protection with
   * nothing anywhere saying so, and that path is not a special case anybody would think to
   * re-check.
   *
   * <p>The two answer different questions. The predicate is <em>what class of path is a model not
   * shown by default</em>; this list is <em>what does this server know about itself</em>. A
   * deployment moves the second and cannot move the first, which is exactly why one of them has to
   * follow the configuration.
   *
   * <p>It is the only entry here that is a file rather than a directory, and that is not the shape
   * of the working-directory argument above — it is deliberate. There is no location to derive: the
   * token has exactly one path and this server wrote it, where a configuration file has four search
   * locations and every profile name. Excluding {@code ~/.config/plowshare/} instead would fence
   * off {@code lm-key} and anything else an operator keeps beside it, which is more than this
   * server knows about itself and therefore more than it should assert.
   *
   * <h2>Why the export directory, which is neither a credential nor a fuse</h2>
   *
   * <p><b>The other four are the server's own secrets and the server's own inputs. This one is
   * other people's content.</b> An ejected payload is the body a tool returned — a file somebody's
   * {@code code_reviewer} read, a search result, a document — lifted out of {@code entries.content}
   * and written to disk deliberately in the open, because {@code PayloadExport}'s whole argument is
   * that an export nobody can read without this server is not an export. Every property that makes
   * it a good archive ({@code cat} works, {@code grep -r} works, the manifest is JSON Lines) makes
   * it a good thing for an agent to find, and a workspace covering it hands one conversation's
   * ejected bodies to whatever runs in another.
   *
   * <p><b>It was fenced before this, by accident of a default, which is not the same as being
   * fenced.</b> The shipped value is {@code exports}, relative, so it resolves under the working
   * directory and the working-directory rule above covered it. An operator who sets {@code
   * PLOWSHARE_EXPORT_DIR=/srv/plowshare-exports} — the ordinary thing to do when the payloads
   * outgrow the disk the server is installed on — moves it outside every entry in this list, and
   * nothing anywhere says so. That is the shape the token file was found in on the same day: safe
   * because of where a default happened to put it rather than because of a rule, and the fix is the
   * same one, which is to name it.
   *
   * <p><b>Not derived from a location, unlike the working directory.</b> There is one path and this
   * server writes it; there is no search order to cover and no set of filenames that could be
   * incomplete. It is the same reasoning the token file is listed under, one kind of secret along.
   *
   * @param tokenFile where the operator token is written, or null for a deployment that writes none
   *     — see the constructor for why null and not the default path
   *     <h2>Why the data directory as well as the export directory</h2>
   *     <p><b>Ordinarily the second is inside the first and the collapse drops it</b>, which is the
   *     arrangement this server ships and the reason the list an operator reads stays short. Both
   *     are here because the two can come apart in the one direction that matters: {@code
   *     plowshare.conversations.retention.export-directory} is an override, and an operator who
   *     sets it has taken the payloads <em>out</em> of the tree the data directory covers. Keeping
   *     only the root would then fence a directory holding nothing and leave the one holding
   *     people's ejected file bodies open — which is the shape this whole list keeps being wrong
   *     in.
   *     <p>The root is named on its own account for the opposite reason: it is where everything
   *     this server comes to own on disk will be, and a rule stated over the root does not have to
   *     be restated when a subdirectory is added. Naming only the leaves is how the export
   *     directory came to be missing in the first place.
   * @param exportDir where ejected payloads are written, or null for a deployment that keeps none
   * @param dataDir the root of this server's own tree, or null for a deployment that keeps none
   */
  public static List<Path> mandatoryExclusions(
      Path configFile, Path samplingDir, Path tokenFile, Path exportDir, Path dataDir) {
    return mandatoryExclusionsStartedIn(
        configFile, samplingDir, tokenFile, exportDir, dataDir, Path.of(""));
  }

  /**
   * The same rule with the working directory named, which is what makes it testable.
   *
   * <p><b>A distinct name rather than a fifth parameter, and review is why.</b> When this was an
   * overload, {@code mandatoryExclusions(config, agents, sampling, workingDir)} bound silently to
   * the four-argument form with the working directory sitting in the {@code tokenFile} slot — it
   * compiled, and the assertion it fed passed on the wrong path. That is not hypothetical: it is
   * exactly how {@code the_working_directory_is_excluded_even_when_the_config_lives_elsewhere} came
   * to assert nothing for as long as it did, caught only when the token file made the arity change
   * again. Two overloads whose trailing parameters are both {@code Path} cannot be told apart by
   * the compiler; two names can.
   *
   * <p>A JVM cannot change its working directory, so the public overload's {@code Path.of("")} is a
   * constant for the life of the process and a test driving it could only ever assert against the
   * directory Gradle happened to start in. This overload lets a test build the whole arrangement —
   * a server directory, a configuration inside it, and a workspace above both — under a
   * {@code @TempDir}, which is the only way the attacker's side of this can be written down.
   *
   * @param tokenFile where the operator token is written, or null for none
   * @param exportDir where ejected payloads are written, or null for none. <b>This overload is the
   *     only way to write the test that matters for it</b>: at its shipped default the export
   *     directory is inside the working directory and is dropped from the answer as a descendant,
   *     so a suite driving the public form could only ever measure the arrangement in which this
   *     entry changes nothing
   * @param dataDir the root of this server's own tree, or null for none. The same reason as {@code
   *     exportDir} for being reachable through this overload: at the shipped {@code data} it is
   *     relative, lands inside the working directory and is dropped by the collapse, so a suite
   *     driving the public form could only measure the arrangement where it changes nothing
   * @param workingDirectory where this server was started, and therefore where Spring Boot looks
   *     for {@code application*} and for {@code config/}
   */
  static List<Path> mandatoryExclusionsStartedIn(
      Path configFile,
      Path samplingDir,
      Path tokenFile,
      Path exportDir,
      Path dataDir,
      Path workingDirectory) {
    // Ordered, and with anything already inside something else dropped: the
    // default configuration puts the configured file *inside* the working
    // directory, so two of these are ordinarily redundant and the ordinary
    // answer is one path.
    //
    // Dropping a descendant cannot change an answer, which is why this is a
    // tidy-up rather than a rule. `permits` compares the depth of the
    // deepest covering exclusion against the deepest covering root, and
    // `FileAccess.of` has already dropped any root an exclusion covers -- so
    // no surviving root can lie inside E, and every candidate under a
    // dropped descendant of E is still covered by E itself. What it buys is
    // that the list an operator is shown says the rule once instead of
    // spelling out its own consequences.
    Set<Path> named =
        new LinkedHashSet<>(
            List.of(absolute(workingDirectory), absolute(configFile), absolute(samplingDir)));
    if (tokenFile != null) {
      // Conditional and not folded in with an empty-path stand-in for
      // "none". `absolute(Path.of(""))` is the working directory, which is
      // already in the set, so a stand-in would work today by coincidence
      // and would start excluding the server's own directory the day the
      // working directory left this list.
      named.add(absolute(tokenFile));
    }
    if (exportDir != null) {
      // Conditional for tokenFile's reason exactly: null here is "this
      // deployment keeps no export", and a `Path.of("")` stand-in would
      // resolve to the working directory -- already in the set today, so
      // the substitution would be invisible until the day the working
      // directory left this list, at which point a deployment that keeps
      // NO exports would start fencing off the server's own directory.
      named.add(absolute(exportDir));
    }
    if (dataDir != null) {
      // Added after the export directory and not before it, so that on the
      // ordinary deployment -- where the exports are inside the tree --
      // the collapse keeps the root and drops the leaf, and the list an
      // operator reads says the rule once. The other order returns the
      // same set, since the collapse compares every candidate against
      // every other; what it would change is nothing, which is why this is
      // a comment and not an assertion.
      named.add(absolute(dataDir));
    }
    List<Path> candidates = new ArrayList<>(named);
    List<Path> kept = new ArrayList<>(candidates.size());
    for (Path path : candidates) {
      if (candidates.stream().noneMatch(other -> !other.equals(path) && path.startsWith(other))) {
        kept.add(path);
      }
    }
    return List.copyOf(kept);
  }

  /**
   * A project name that keys the same project everywhere.
   *
   * <h2>Refused rather than stripped, and the difference is the whole point</h2>
   *
   * <p><b>{@code Home} does not strip</b> — that is {@code HomeTest}'s to state and to hold; it is
   * asserted there rather than described here, because it is another module's behaviour and this
   * whole rule rests on it. Nor does anything strip before it: {@code RequestedHome.in}, which
   * {@code MemoryController}, {@code ProposalController} and {@code agents.Runs} all call, each
   * passing the raw request field. So {@code " payments "} is a real, writable memory tier.
   *
   * <p><b>A store that stripped here would make the two leashes key differently.</b> Workspaces
   * under {@code "payments"}, that tier's memories under {@code " payments "}, and a job in the
   * padded tier handed the unpadded project's workspace — breaking the claim this slice inherits,
   * that an agent cannot reach another project's files for the same reason it cannot read another
   * project's memories. Quietly, in the direction nobody checks.
   *
   * <p>So the pair is refused where somebody can still see it. Internal spaces are untouched —
   * {@code Home} keeps those, so the two already agree about them. What this does <em>not</em> fix
   * is that {@code " payments "} remains creatable as a memory tier; {@link #find} says what that
   * means for a caller routing a job to its files.
   */
  /**
   * Why an operation that only makes sense for this server's own disk did nothing.
   *
   * <p><b>Two absences that look identical from a row count and are not.</b> {@link #define},
   * {@link #moveWorkspace} and {@link #forget} all write through a predicate carrying {@code
   * machine IS NULL}, so each of them reports zero rows both for a project that has no workspace
   * here and for a project whose files are on another machine. The first is {@link
   * ArchiveException}'s absence and has always been; the second is a refusal — the row is there, it
   * is simply not this server's to edit — and reporting it as "no project named X has a workspace"
   * would send an operator to define one, which is advice that cannot work and which V15 exists
   * because §13.4 caught the system giving.
   *
   * @param verb what was being done to the project, as a past participle, so that one sentence
   *     serves three callers without any of them reading as though it were written for another
   * @return the exception to throw, so that the caller's {@code throw} is at the site the reader is
   *     looking at
   */
  private ArchiveException notThisServers(String project, String verb) {
    Optional<String> elsewhere = rootedElsewhere(project);
    if (elsewhere.isEmpty()) {
      return new ArchiveException("no project named " + project + " has a workspace");
    }
    return new ArchiveRefusedException(
        "the project '"
            + project
            + "' is rooted on the"
            + " machine '"
            + elsewhere.get()
            + "', so it cannot be "
            + verb
            + " here: its"
            + " files are not on the machine this server runs on and no directory here is"
            + " the right answer for it. A project exists in exactly one location. Point it"
            + " somewhere else by re-declaring it from the client on '"
            + elsewhere.get()
            + "', or move the project to a different name.");
  }

  /**
   * Why {@link #rootOn} claimed nothing: somebody else holds the place.
   *
   * <p>Read after the write declined rather than before it, which is what makes the claim atomic —
   * see {@code CLAIM}. The row is re-read here, so a project that stopped being rooted in between
   * reports the honest third sentence rather than an invented one.
   */
  private ArchiveRefusedException heldByAnother(String project, String claimant) {
    List<Place> held =
        jdbc.query(
            "SELECT machine, workspace FROM projects WHERE name = ?",
            (rs, row) -> new Place(rs.getString("machine"), rs.getString("workspace")),
            project);
    if (held.isEmpty() || (held.get(0).machine() == null && held.get(0).where() == null)) {
      // Neither of the two refusals is true any more. Nothing is invented
      // to fill the gap: the claim did not go through and the next
      // declaration -- a client reconnects and re-declares -- will find
      // the row in whatever state it is actually in.
      return new ArchiveRefusedException(
          "the project '"
              + project
              + "' could not be"
              + " rooted on '"
              + claimant
              + "': something else was writing to it at the"
              + " same moment. Nothing was changed; declare the presence again.");
    }
    Place place = held.get(0);
    if (place.machine() == null) {
      return new ArchiveRefusedException(
          "the project '"
              + project
              + "' is rooted on the"
              + " machine this server runs on, at "
              + place.where()
              + ", so '"
              + claimant
              + "' cannot also root it. A project exists in exactly one location, and this"
              + " one's memories were formed by agents reading those files. If the project"
              + " has moved to '"
              + claimant
              + "', drop the workspace here first — that"
              + " keeps the row, so the project keeps its whole archive — and then declare"
              + " the presence again.");
    }
    return new ArchiveRefusedException(
        "the project '"
            + project
            + "' is rooted on '"
            + place.machine()
            + "' at "
            + place.where()
            + ", so '"
            + claimant
            + "' cannot"
            + " also root it. A project exists in exactly one location, so two machines"
            + " claiming one name is a conflict rather than a choice: root this one under"
            + " a name nothing holds, or stop '"
            + place.machine()
            + "' rooting it.");
  }

  /**
   * Where a row says a project is: the machine, or null for this server, and the path on it. Never
   * a {@link Path} — see {@link #rootOn}.
   */
  private record Place(String machine, String where) {}

  /**
   * A component of a place, as the client that holds the files spelled it.
   *
   * <p>Trimmed and refused when blank, and nothing more. {@code Presence} already applies the
   * stricter rule — a root has to be absolute on the machine that holds it — at the point the claim
   * arrives, which is where it belongs: this store is also reachable from a test and from any later
   * caller, and what it owes the database is that no column holds a string that names nothing.
   */
  private static String asserted(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new ValidationException(
          "field '"
              + what
              + "' is required and must not be"
              + " empty; a project rooted on a blank "
              + what
              + " names no place");
    }
    return value.strip();
  }

  private static String named(String name) {
    // Blank first, and the two messages share no clause: "   " is blank, and
    // reporting it as edge whitespace would name a fixable typo where the
    // real answer is that no name was given at all.
    if (name == null || name.isBlank()) {
      throw new ValidationException("field 'name' is required and must not be empty");
    }
    if (!name.equals(name.strip())) {
      throw new ValidationException(
          "project name '"
              + name
              + "' has whitespace at one end; Home does not strip, so this name would key"
              + " its memories and its workspace as two different projects");
    }
    return name;
  }

  private static Path validWorkspace(String name, Path workspace) {
    if (workspace == null) {
      throw new ValidationException("field 'workspace' is required");
    }
    return validDirectory(name, "workspace", workspace);
  }

  /**
   * Every lent root, absolutised and checked, with the refusal naming the one that is wrong.
   *
   * <p><b>Per element, and the message is per element too.</b> A define may carry several lent
   * roots, so "it does not exist" with no path in it is a sentence an operator has to bisect their
   * own command to act on — which is the same reason {@code validWorkspace} names the workspace it
   * refused rather than saying "the workspace". The two sentences stay the workspace's two, word
   * for word, because "does not exist" and "is not a directory" are the same two mistakes here and
   * an operator fixes them the same two ways.
   *
   * <p>The blank-element check is {@code normalised}'s, one column across and for a reason that is
   * worse here than there. Measured on JDK 21, {@code Path.of("").toAbsolutePath().normalize()} is
   * the process's working directory — so a blank exclusion silently fences off the server's whole
   * checkout, and a blank <em>lent root</em> silently grants it. The one that grants is checked
   * first for the same reason it is checked at all.
   */
  private static List<Path> lentRoots(String name, List<Path> lent) {
    List<Path> resolved = absolutised(lent, "lent");
    List<Path> checked = new ArrayList<>(resolved.size());
    for (Path root : resolved) {
      checked.add(validDirectory(name, "lent root", root));
    }
    return checked;
  }

  /**
   * A list of paths made absolute, with nothing said about whether they exist.
   *
   * <p>Shared by {@link #lentRoots} and {@link #unlend}, which need the same normalisation and
   * disagree about the check: a root being lent has to be a directory, and a root being taken back
   * has to be removable whether or not it still is one. Splitting them here is what lets {@link
   * #unlend} take back a mount that has gone.
   *
   * <p>Order is kept and duplicates within the argument are dropped, keeping the first spelling:
   * the order is what {@code ProjectRecord.roots()} renders, and a caller that named one directory
   * twice in one call would otherwise put it in the column twice — which the statement's own
   * deduplication cannot see, since it only compares against what is already stored.
   */
  private static List<Path> absolutised(List<Path> paths, String field) {
    if (paths == null) {
      throw new ValidationException(
          "field '" + field + "' is required; pass an empty list for none");
    }
    Set<Path> distinct = new LinkedHashSet<>();
    for (Path path : paths) {
      if (path == null) {
        throw new ValidationException("field '" + field + "' must not contain a null path");
      }
      if (path.toString().isEmpty()) {
        throw new ValidationException(
            "field '"
                + field
                + "' must not contain the empty"
                + " path; it resolves to the server's working directory rather than to"
                + " nothing");
      }
      distinct.add(absolute(path));
    }
    return List.copyOf(distinct);
  }

  /**
   * The two refusals a granted directory can earn, said the same way wherever the directory came
   * from.
   *
   * @param what how to name the thing in the refusal — {@code "workspace"} or {@code "lent root"}.
   *     It is followed by the resolved path, so a project lending four directories is told which
   *     one it stumbled on
   */
  private static Path validDirectory(String name, String what, Path path) {
    Path resolved = absolute(path);
    /*
     * NOFOLLOW on the existence test and following on the directory test,
     * and the asymmetry is deliberate. Measured on this host (macOS 26.6.2,
     * JDK 21) by `a_workspace_reached_through_a_symlink_is_accepted` and
     * `a_workspace_that_is_a_dangling_symlink_is_refused_as_not_a_directory`:
     *
     *  - a symlink to a real directory is accepted, because
     *    Files.isDirectory follows it. That is the behaviour a workspace
     *    wants — an operator who symlinks a checkout into place has a
     *    workspace — and it is also the reason the containment core needs a
     *    real-path step of its own rather than trusting these strings;
     *  - a symlink whose target is gone is NOT reported as absent, because
     *    Files.exists(NOFOLLOW) sees the link itself. Calling that "does not
     *    exist" would send an operator looking for a path that is sitting
     *    right there; "is not a directory" is what is actually wrong with it.
     */
    if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
      throw new ValidationException(
          "project '" + name + "' cannot take " + what + " " + resolved + ": it does not exist");
    }
    if (!Files.isDirectory(resolved)) {
      throw new ValidationException(
          "project '"
              + name
              + "' cannot take "
              + what
              + " "
              + resolved
              + ": it is not a directory");
    }
    return resolved;
  }

  /*
   * One place the driver's array argument is built from a list of paths, so
   * that `lent` and `exclusions` cannot come to disagree about spelling.
   */
  private static String[] strings(List<Path> paths) {
    return paths.stream().map(Path::toString).toArray(String[]::new);
  }

  private static List<Path> normalised(List<Path> exclusions) {
    if (exclusions == null) {
      throw new ValidationException("field 'exclusions' is required; pass an empty list for none");
    }
    List<Path> absolute = new ArrayList<>(exclusions.size());
    for (Path excluded : exclusions) {
      if (excluded == null) {
        throw new ValidationException("field 'exclusions' must not contain a null path");
      }
      // The empty path, checked before absolute() rather than after,
      // because absolute() is what makes it dangerous: measured on JDK 21,
      // Path.of("").toAbsolutePath().normalize() is the process's working
      // directory exactly, so a blank element silently becomes an
      // exclusion covering the server's whole checkout. Path.of("  ") is
      // not the same thing and is not refused — it is a directory named
      // two spaces, which is merely strange.
      if (excluded.toString().isEmpty()) {
        throw new ValidationException(
            "field 'exclusions' must not contain the empty path;"
                + " it resolves to the server's working directory rather than to nothing");
      }
      // absolute(), not the path as given. Every exclusion fixture is
      // easy to build from a temp directory, which is already absolute —
      // so a store that stored a relative exclusion verbatim passes a
      // whole file of tests while the fence it wrote matches no candidate
      // task 2 will ever compare against. An exclusion that excludes
      // nothing and reports nothing is the worst of the failures here.
      absolute.add(absolute(excluded));
    }
    return absolute;
  }

  /*
   * THE ONE PLACE THIS RULE LIVES. Absolute and normalised, but never
   * toRealPath(), and it applies to workspaces and exclusions alike.
   *
   * A symlink resolution here would make the stored workspace a path the
   * operator never typed, so the link is kept as given —
   * `a_workspace_reached_through_a_symlink_is_accepted` measures that.
   *
   * The cost is that NEITHER SIDE of a containment comparison is canonical
   * when it leaves this class, and whatever compares them has to real-path
   * both. Two concrete ways that bites, both measured: a workspace stored as
   * a symlink makes `<link>/application.yml` textually unlike the real config
   * path in the exclusion list; and on this host /tmp is a symlink to
   * /private/tmp and /var to /private/var, so a config file configured under
   * either yields a mandatory exclusion that can never match a canonicalised
   * candidate. Canonicalising only the candidate is therefore worse than
   * canonicalising neither.
   */
  private static Path absolute(Path path) {
    return path.toAbsolutePath().normalize();
  }

  /*
   * No null branch on the array. `exclusions` is NOT NULL DEFAULT '{}', so
   * there is no absent list to decode — a defensive branch here would be one
   * no test could reach and no caller could cause, which is the shape
   * ProposalStore.utc rejects for the same reason.
   */
  private static final RowMapper<ProjectRecord> ROW_MAPPER =
      (rs, rowNum) ->
          new ProjectRecord(
              rs.getString("name"),
              Path.of(rs.getString("workspace")),
              paths(rs.getArray("lent")),
              paths(rs.getArray("exclusions")),
              rs.getString("project_type"),
              List.of((String[]) rs.getArray("write_paths").getArray()));

  /*
   * Shared by both arrays for the reason the null branch is still absent: both
   * columns are NOT NULL DEFAULT '{}' -- V3 for `exclusions`, V30 for `lent`
   * -- so neither can arrive absent, and a defensive branch would be one no
   * test could reach and no caller could cause. Two hand-rolled loops would
   * have been the other spelling, and the second copy is the one that gets
   * a `Path.of` wrong.
   */
  private static List<Path> paths(Array column) throws java.sql.SQLException {
    String[] values = (String[]) column.getArray();
    List<Path> paths = new ArrayList<>(values.length);
    for (String each : values) {
      paths.add(Path.of(each));
    }
    return paths;
  }
}
