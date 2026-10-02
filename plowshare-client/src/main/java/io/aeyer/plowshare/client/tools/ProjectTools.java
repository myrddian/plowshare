package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.MemoryTools.oneLine;
import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Managing a project: name its workspace, lend it further directories and take
 * them back, move that workspace, move the project itself, drop the lot.
 *
 * <h2>Two different things are called moving, and they are one word apart</h2>
 *
 * <p><b>{@code project_workspace_set} moves a project's workspace; {@code
 * project_move} moves the project.</b> The first changes which directory on the
 * server's disk a project reaches — a checkout that went somewhere else. The
 * second changes what the project is <em>called</em>, and carries its whole
 * archive with it: a project's canonical name is {@code
 * <MACHINE>/<PATH>/<PROJ_NAME>}, so moving one to another machine <em>is</em>
 * renaming it, and since the server's V14 that is one row and one column with
 * every memory and every conversation reaching the project through a surrogate
 * id and staying correct.
 *
 * <p>The pair is a genuine hazard rather than a pedantic one, because a model
 * reads only the descriptions: two tools whose names both say "move" and whose
 * texts both say "when a checkout moves" would be chosen between by coin flip.
 * So each description opens by naming the other and saying which is which, and
 * neither uses the bare word "move" without an object.
 *
 * <p>{@code project_move} is also the only one of these that works on a
 * project with <b>no workspace</b>, and that is the common case rather than the
 * exotic one: {@code project_define} can only name a directory on the server's
 * own disk, so a project whose files live on somebody's laptop has never had a
 * row with a workspace in it — and it still has an archive, an id, and a name
 * worth moving.
 *
 * <p><b>Since the server's V15 that project does have a row, and none of these
 * four is what wrote it.</b> A client declaring a presence on its file channel
 * asserts the project, the machine and the root at once, and that declaration
 * <em>is</em> the registration — the party that holds the files is the only one
 * that can say where they are, which is the same rule that keeps these four out
 * of every agent's reach. So every other verb here refuses a project rooted on
 * another machine, naming it: <b>this server does not get to edit somebody
 * else's location</b>, and no directory on it is the right answer for one.
 *
 * <h2>Why these are here and nowhere else</h2>
 *
 * <p>The roots are the leash on what an agent may read and write, so the party
 * that sets them must not be a party the leash binds. That is not a rule this
 * file keeps — it is kept by what the server binds: {@code
 * JobRuntime.knownTools()}
 * never names these six, and {@code AgentRegistry.load} refuses at boot any
 * definition naming a tool outside that set. A definition declaring {@code
 * project_workspace_set} does not get denied at run time; it <b>fails to load
 * and takes the boot with it</b>, at the one moment somebody is watching.
 *
 * <p>The stakes are the paths no root may cover. An agent able to
 * move its own project's workspace could point it at the directory holding the
 * model API key, which defeats in one call the one rule this repository keeps
 * without exceptions; and it could point it at the agents directory, where
 * writing a definition grants itself any tool at the next boot. <b>{@code
 * project_lend} is the same stake and is quieter</b>, which is worth saying
 * rather than leaving to be inferred: it adds a directory without disturbing the
 * workspace, so an agent holding it could add {@code $HOME} to what it reads and
 * leave every sentence an operator would go and check unchanged. Excalibur draws
 * the same line — {@code workspace_set} is a tool the harness calls, never one
 * an agent holds.
 *
 * <h2>The paths are the server's</h2>
 *
 * <p>Every path here names a directory on the machine the <em>server</em> runs
 * on, which is usually but not always this one. They travel as the strings a
 * person typed and are never resolved in this process: a relative path made
 * absolute here would be absolute against this machine's working directory and
 * would name a different directory on the other side. The server resolves them
 * once, when the project is defined, and answers with what it stored — which is
 * why every tool here renders the server's answer rather than echoing the
 * request.
 *
 * <p><b>Two of the fields can be seen to do that and one cannot</b>, which
 * is worth saying rather than leaving the claim to read as though a test held all
 * of it. A workspace is transformed — a relative path comes back absolute — and
 * the exclusions come back <em>longer</em> than they went, so a fixture can tell
 * a rendered answer from an echoed request for both, and {@code ProjectToolsTest}
 * does. A project <em>name</em> is transformed on no path at all: the server
 * refuses a name it would have to change rather than changing it, so for that
 * field an echo and a render are the same string and no fixture can separate
 * them.
 *
 * <h2>Every line at column zero is one this renderer wrote</h2>
 *
 * <p>The same rule as {@code MemoryTools} and {@code AgentTools}: a project name
 * and a path are strings somebody else chose, so both go through {@link
 * MemoryTools#oneLine}.
 */
public final class ProjectTools {

    /** What to say when a list a person asked about has nothing in it — no
     *  exclusion beyond the server's own, or no directory lent beyond the
     *  workspace. Shared, because "(nothing)" rendered two ways would be two
     *  strings a fixture would have to tell apart for no reason. */
    static final String NOTHING_EXTRA = "(nothing)";

    private final ServerClient server;

    public ProjectTools(ServerClient server) {
        this.server = server;
    }

    public void registerOn(ToolRegistry registry) {
        registry.register("project_define", DEFINE_DESCRIPTION, defineSchema(), this::define);
        registry.register(
                "project_workspace_set", SET_DESCRIPTION, setSchema(), this::setWorkspace);
        registry.register("project_lend", LEND_DESCRIPTION, lendSchema(), this::lend);
        registry.register("project_unlend", UNLEND_DESCRIPTION, lendSchema(), this::unlend);
        registry.register("project_move", MOVE_DESCRIPTION, moveSchema(), this::move);
        registry.register("project_forget", FORGET_DESCRIPTION, forgetSchema(), this::forget);
    }

    // --- the tools -----------------------------------------------------------------

    public Object define(Map<String, Object> args) {
        String project = required(args, "project");
        String workspace = required(args, "workspace");
        List<String> exclusions = strings(args, "exclusions");

        ServerClient.ProjectView view =
                ask(() -> server.defineProject(project, workspace, exclusions));
        return "The project '" + oneLine(view.name()) + "' is at "
                + oneLine(view.workspace()) + " on the server's disk.\n\n" + reach(view)
                + "\n\n" + fence(view)
                + "\n\nA job started in this project reads and writes there, as far as the"
                + " running agent's own declared scopes allow. Defining a project again"
                + " replaces the workspace, these exclusions and anything the project was"
                + " lending; use project_workspace_set to move it and keep them, and"
                + " project_lend to add a directory without rewriting anything.";
    }

    /**
     * Lend the project further directories, and say what it reaches afterwards.
     *
     * <p>Answers with the whole list rather than with what was added, which is
     * the same rule the rest of this file follows for a different reason. Here it
     * is not that a relative path comes back absolute: it is that a person who
     * mistyped one path of three needs to read the result to find out, and a
     * confirmation echoing the request would confirm the typo.
     */
    public Object lend(Map<String, Object> args) {
        String project = required(args, "project");
        List<String> roots = requiredStrings(args, "roots");

        ServerClient.ProjectView view = ask(() -> server.lendProject(project, roots));
        return "The project '" + oneLine(view.name()) + "' is at "
                + oneLine(view.workspace()) + " on the server's disk.\n\n" + reach(view)
                + "\n\n" + fence(view)
                + "\n\nWhere the project IS did not change, and that is deliberate: its full"
                + " name is MACHINE/PATH/NAME, so lending by moving the workspace would"
                + " rename it. Runs already going pick this up at their next file request.";
    }

    /** The other direction. Same answer, so the same rendering. */
    public Object unlend(Map<String, Object> args) {
        String project = required(args, "project");
        List<String> roots = requiredStrings(args, "roots");

        ServerClient.ProjectView view = ask(() -> server.unlendProject(project, roots));
        return "The project '" + oneLine(view.name()) + "' is at "
                + oneLine(view.workspace()) + " on the server's disk.\n\n" + reach(view)
                + "\n\n" + fence(view)
                + "\n\nA directory the project was not lending is left out of that list"
                + " rather than reported as an error, so if something you asked to take back"
                + " is still there, it was spelled differently when it was lent.";
    }

    public Object setWorkspace(Map<String, Object> args) {
        String project = required(args, "project");
        String workspace = required(args, "workspace");

        ServerClient.ProjectView view =
                ask(() -> server.setProjectWorkspace(project, workspace));
        return "The project '" + oneLine(view.name()) + "' is now at "
                + oneLine(view.workspace()) + " on the server's disk.\n\n" + reach(view)
                + "\n\n" + fence(view)
                + "\n\nRuns already going pick this up at their next file request: the"
                + " server re-reads the project's workspace on every one, so a job started"
                + " before the move reads the new directory from here on, and nothing is"
                + " answered out of the old one.";
    }

    /**
     * Move the project itself: it gets a new name and keeps everything else.
     *
     * <p>Rendered from what was sent rather than from an answer, which is the
     * one place this file departs from its own "render the server's answer" rule
     * and does so for {@code forget}'s reason: the endpoint answers 204, because
     * nothing about the project is new except what it is called. The server
     * having accepted the call is the whole of the answer.
     */
    public Object move(Map<String, Object> args) {
        String project = required(args, "project");
        String to = required(args, "to");

        ask(() -> {
            server.moveProject(project, to);
            return null;
        });
        return "The project '" + oneLine(project) + "' is now called '" + oneLine(to)
                + "'.\n\nEverything it has remembered moved with it: its memories and its"
                + " conversations reach the project through an id rather than through its"
                + " name, so none of them was rewritten and none of them was lost. Its"
                + " workspace on the server, the directories lent alongside it and the"
                + " paths fenced off inside them are also"
                + " unchanged — this moved the project, not its files.\n\nRecall and"
                + " conversations in this project now answer to the new name. The old name"
                + " holds nothing, and a project defined under it later would be a new,"
                + " empty one.";
    }

    public Object forget(Map<String, Object> args) {
        String project = required(args, "project");

        ask(() -> {
            server.forgetProject(project);
            return null;
        });
        return "The project '" + oneLine(project) + "' no longer has a workspace, and nothing"
                + " it was lending is lent any more, so its jobs"
                + " reach no files on the server.\n\nIts memories are untouched: a project's"
                + " archive and its workspace are separate things, and nothing was deleted"
                + " from the archive here. Give it a workspace again with project_define.";
    }

    // --- rendering -------------------------------------------------------------------

    /**
     * What the project <em>does</em> reach, as the server reports it.
     *
     * <p><b>The workspace is named first and is named again here</b>, even
     * though every caller has just printed it. That is not redundancy: the two
     * sentences answer different questions — where the project is, and what its
     * jobs may open — and they were one sentence until a project could have more
     * than one directory. A person who read only "the project is at X" would
     * take X for the whole leash, which is what it used to be.
     *
     * <p>Rendered from the answer and never from the request, on {@link #fence}'s
     * rule: the server stores absolute paths and a relative one comes back
     * transformed, so an echo and a render are visibly different strings and
     * {@code ProjectToolsTest} can tell them apart.
     */
    private static String reach(ServerClient.ProjectView view) {
        // Null and not merely empty, because a client is a separately installed
        // binary and a server that predates the `lent` column sends the field
        // not at all. "(nothing)" is the honest answer for that server -- there
        // the workspace really is the whole leash -- and it is the same sentence
        // an up-to-date server gives for a project that lends nothing, which is
        // the right collapse: both mean this project reaches its workspace and
        // no more.
        List<String> roots = view.lent() == null ? List.of() : view.lent();
        String lent = roots.isEmpty()
                ? NOTHING_EXTRA
                : String.join(", ", roots.stream().map(MemoryTools::oneLine).toList());
        return "Its jobs reach " + oneLine(view.workspace()) + ", and these directories lent"
                + " alongside it: " + lent + ". A lent directory does not have to be inside"
                + " the workspace, and it does not change where the project is.";
    }

    /**
     * What the project may not reach, as the server reports it.
     *
     * <p>Rendered from the answer and never from the request, because the server
     * adds paths of its own that no project may override, so that its
     * configuration, its agent definitions, its sampling profiles and its own
     * console token are out of reach whatever a row says. How many they come to
     * is the server's business and not this renderer's — the rule is a location,
     * so on the ordinary deployment the whole of it is the one directory those
     * things sit inside. A person who
     * never sees the server's own paths has no way to know the leash is shorter
     * than the directory they just named, and no way to tell a workspace that
     * swallowed one of them from one that did not.
     */
    private static String fence(ServerClient.ProjectView view) {
        String paths = view.exclusions().isEmpty()
                ? NOTHING_EXTRA
                : String.join(", ", view.exclusions().stream().map(MemoryTools::oneLine).toList());
        // "covered", not "among them". The rendered list is the effective one,
        // and `ProjectStore.mandatoryExclusions` drops any mandatory path that
        // lies inside another -- so in the ordinary arrangement the list holds
        // the server's directory and neither of the two paths this sentence
        // used to name appears in it. The old wording was true about the
        // outcome and false about the list it was appended to, and that is not
        // a distinction a reader should have to make: `EndToEndTest` records an
        // assertion that passed on this boilerplate rather than on the
        // exclusions, and fixed the assertion while leaving the sentence.
        // The operator token joins the enumeration, because it joined the rule:
        // it is a complete and permanent credential for every gated route on
        // that server, and a person reading this sentence is the one deciding
        // whether a workspace covers it.
        return "Inside it, these paths are excluded: " + paths + ". The paths the server keeps"
                + " for itself -- its own directory, its configuration, its agent"
                + " definitions and its own console token -- are always covered, whether or"
                + " not each is named above, and cannot be granted to any project.";
    }

    // --- descriptions ----------------------------------------------------------------

    static final String DEFINE_DESCRIPTION = """
            Give a project a workspace: the directory on the SERVER's disk that \
            the project sits in. Replaces the whole definition — including any \
            directories the project was being lent.

            A project is the scope that owns memories; this is where the files \
            that go with them are. An agent runs in a project and reaches that \
            project's own directories and no other project's — it cannot widen \
            that, and there is no global workspace, because the global tier is \
            every project at once and names no single filesystem.

            The workspace is ONE directory and stays one: a project's full name \
            is MACHINE/PATH/NAME, so it is part of what the project is called. \
            To let a project read a second directory as well, use project_lend, \
            which adds without renaming anything.

            `workspace` is a path on the machine the server runs on, which may \
            not be this one. It must already exist and be a directory; the \
            server says so if it is not.

            This is for a project whose files are ON THE SERVER. A project \
            whose files are on another machine is not defined here at all — it \
            is rooted by the Plowshare client running on that machine, which is \
            the only party that can see those files. Asking for one of those is \
            refused, and the refusal names the machine that holds it.

            `exclusions` fences off paths inside anything the project reaches — \
            the workspace and anything lent alongside it. More are always added \
            and cannot be removed by anyone: the server's own configuration, \
            which holds its model API key, and its agents directory, where \
            writing a file would grant an agent new tools at the next restart. \
            The answer lists everything that is fenced off.""";

    static final String LEND_DESCRIPTION = """
            Let a project also read and write further directories on the \
            SERVER's disk, on top of its workspace. Adds; nothing already set \
            is replaced.

            This is the one to use when an agent cannot reach something it \
            should. The commonest case: directories whose name starts with a \
            dot — .github, .git, .config — are unreachable unless a root names \
            one, so an agent that cannot read .github/workflows/ci.yml needs \
            that directory lent explicitly.

            A lent directory does NOT have to be inside the workspace. It can \
            be anywhere on the server the mandatory exclusions do not cover, \
            and lending one of those reaches nothing rather than being an \
            error — the server drops a root it will not honour, and the answer \
            shows you what survived.

            It does NOT move the project. A project's full name is \
            MACHINE/PATH/NAME, so the workspace is part of its identity and \
            lending leaves it exactly where it was — which is why this is a \
            separate verb rather than project_workspace_set with two paths.

            Every directory must already exist and be a directory on the \
            server; the refusal names which one is not.""";

    static final String UNLEND_DESCRIPTION = """
            Stop lending a project directories it was lent. The workspace, the \
            exclusions and everything else are untouched.

            Not project_forget, which drops the project's workspace and \
            everything lent with it. This takes back only what you name.

            The paths are not checked against the server's disk, because the \
            usual reason to take a directory back is that it is gone. A \
            directory the project was not lending is left out of the answer \
            rather than being an error — read the answer to see what it \
            reaches now.""";

    static final String SET_DESCRIPTION = """
            Point an existing project at a different directory, keeping the \
            exclusions it already has and everything it is being lent.

            This is the one to use when a checkout moves to a different \
            directory. project_define writes a whole definition and therefore \
            replaces the exclusions and the lent directories; this changes only \
            the workspace, so paths somebody fenced off earlier stay fenced \
            off and directories somebody lent stay lent.

            It does NOT rename the project. If what moved is the project \
            itself — to a different machine, so that it needs a different name \
            — that is project_move, and it leaves the workspace alone.

            The project must already have a workspace — a name that has none is \
            an error rather than a new project, so a typo cannot quietly create \
            one holding a real directory. Use project_define for that.""";

    static final String MOVE_DESCRIPTION = """
            Move a PROJECT: give it a different name and carry its whole \
            archive with it. Not the same as project_workspace_set, which \
            moves a project's WORKSPACE — the directory it sits in — and \
            leaves its name alone.

            A project's full name is MACHINE/PATH/NAME, so a project that \
            moves to another machine gets a different name; this is how that \
            is recorded. Everything the project has remembered follows \
            automatically — memories and conversations reach it through an \
            internal id, so nothing is rewritten and nothing is lost.

            The workspace, the directories lent alongside it and the \
            exclusions are untouched. This moves the project, not its files.

            Refused if another project already has the name you asked for: \
            two projects cannot share one, and letting them would merge two \
            archives with nothing able to tell them apart afterwards. Also \
            refused while a live Plowshare client is rooting either name — \
            close that client first, move the project, then start it again.

            A project that has no workspace can still be moved. That is the \
            usual case for a project whose files are on a machine other than \
            the server.""";

    static final String FORGET_DESCRIPTION = """
            Drop a project's workspace, and every directory lent alongside it. \
            Its jobs go back to having no file access on the server. To take \
            back one lent directory and keep the rest, use project_unlend.

            Its memories are NOT touched. A project's archive and its workspace \
            are separate things: everything it has remembered stays readable and \
            still answers recalls. Nothing on disk is deleted either — this \
            forgets where the project's files are, not the files.

            A project that has no workspace is an error rather than silence, so \
            a mistyped name cannot read as a workspace successfully removed.""";

    // --- schemas -----------------------------------------------------------------------

    private static Map<String, Object> defineSchema() {
        Map<String, Object> exclusions = new LinkedHashMap<>();
        exclusions.put("type", "array");
        exclusions.put("items", string("A path inside one of the project's directories."));
        exclusions.put("description", "Paths that agents may not reach, inside the workspace"
                + " or inside anything lent alongside it."
                + " Omit for none. The server's configuration and its agents directory are"
                + " always excluded and are not yours to send.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", string("The project, by name."));
        properties.put("workspace", string(
                "The directory on the server's disk the project sits in. One directory: it is"
                        + " part of the project's full name. Use project_lend to add more."));
        properties.put("exclusions", exclusions);
        return object(properties, List.of("project", "workspace"));
    }

    /**
     * One schema for both lending verbs, because they take the same two
     * arguments and mean the same things by them.
     *
     * <p>Two copies would be two descriptions to keep true of one shape, and the
     * thing that distinguishes the tools — which direction the directories move —
     * is in the names and in the descriptions, where a model reads it.
     */
    private static Map<String, Object> lendSchema() {
        Map<String, Object> roots = new LinkedHashMap<>();
        roots.put("type", "array");
        roots.put("items", string("A directory on the server's disk."));
        roots.put("description", "The directories. At least one — a call naming none would"
                + " change nothing while answering as though it had.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", string("The project, which must already have a workspace."));
        properties.put("roots", roots);
        return object(properties, List.of("project", "roots"));
    }

    private static Map<String, Object> setSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", string("The project, which must already have a workspace."));
        properties.put("workspace", string(
                "The directory to point it at. Its lent directories are not touched."));
        return object(properties, List.of("project", "workspace"));
    }

    private static Map<String, Object> moveSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", string("The project, by the name it has now."));
        properties.put("to", string(
                "The name it should have instead. Nothing else may already have it."));
        return object(properties, List.of("project", "to"));
    }

    private static Map<String, Object> forgetSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", string(
                "The project whose workspace, and lent directories, to drop."));
        return object(properties, List.of("project"));
    }


    // --- arguments -------------------------------------------------------------------

    private static String required(Map<String, Object> args, String name) {
        Object value = args.get(name);
        String text = value == null ? null : value.toString();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("'" + name + "' is required and must not be empty");
        }
        return text;
    }

    /**
     * The same list, refused when it is absent or empty.
     *
     * <p>Separate from {@link #strings} rather than a flag on it, because the two
     * differ in what an omitted key <em>means</em>. On {@code exclusions} it means
     * "fence off nothing extra", which is a thing to want. On a lend it means a
     * call that changes nothing and answers as though it had — most likely a
     * caller whose list came out empty and did not notice, which is exactly what
     * a silent success hides.
     */
    private static List<String> requiredStrings(Map<String, Object> args, String name) {
        List<String> paths = strings(args, name);
        if (paths.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + name + "' is required and must name at least one directory, written"
                            + " [\"/one\", \"/two\"]. Nothing was written.");
        }
        return paths;
    }

    /**
     * A list of paths, or none.
     *
     * <p>A bare string is the natural mistype for a one-element list, and it is
     * refused with the shape to send rather than read as a single path: a
     * schema's {@code array} is what a model reads, and quietly accepting the
     * other shape here would make the two disagree about what this tool takes.
     */
    private static List<String> strings(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new IllegalArgumentException(
                    "'" + name + "' must be a list of paths, written [\"/one\", \"/two\"], and"
                            + " this one was sent as a single value. Nothing was written.");
        }
        List<String> paths = new ArrayList<>(items.size());
        for (Object item : items) {
            String text = item == null ? null : item.toString();
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "'" + name + "' holds an empty entry. Every entry is a path on the"
                                + " server's disk. Nothing was written.");
            }
            paths.add(text);
        }
        return List.copyOf(paths);
    }

    /**
     * One call to the server, with a dead server said to be a dead server.
     *
     * <p>The copy {@code AgentTools} carries, and for the reason {@code
     * MemoryTools.ServerUnreachableException} exists: an {@link IOException} here
     * means the server was never asked, and rendering that as a failed operation
     * would have a person define the same project twice — or believe a workspace
     * they still have is gone.
     */
    private <T> T ask(Call<T> call) {
        try {
            return call.get();
        } catch (IOException unreachable) {
            throw new MemoryTools.ServerUnreachableException(
                    "could not reach the Plowshare server at " + server.baseUrl() + " — "
                            + describe(unreachable)
                            + ". Nothing was changed: the server was never asked."
                            + " Check the server is running, then try again.",
                    unreachable);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.toString() : message;
    }

    @FunctionalInterface
    private interface Call<T> {
        T get() throws IOException;
    }
}
