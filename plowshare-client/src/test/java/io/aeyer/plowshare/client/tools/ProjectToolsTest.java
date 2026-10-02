package io.aeyer.plowshare.client.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.WriteResult;
import java.io.IOException;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The six tools a person manages a project's directories with, against a stubbed
 * {@link ServerClient}.
 *
 * <h2>These are on this surface and on no other</h2>
 *
 * <p>The roots are the leash on what an agent may read and write, so the party
 * that sets them must not be a party the leash binds. On the server side that is
 * enforced structurally — {@code JobRuntime.knownTools()} never names these, and
 * a definition that does refuses the boot — and {@code AgentsConfigTest} is
 * where that is asserted. What is asserted here is the other half: that the six
 * exist at all, that they reach the endpoints they claim to, and that the
 * answers say enough for a person to see the leash they have just set.
 */
class ProjectToolsTest {

    private StubServerClient server;
    private ProjectTools tools;

    @BeforeEach
    void setUp() {
        server = new StubServerClient();
        tools = new ProjectTools(server);
    }

    // --- what the surface offers ----------------------------------------------------

    @Test
    void the_six_project_tools_are_registered() {
        ToolRegistry registry = new ToolRegistry();

        tools.registerOn(registry);

        assertEquals(
                List.of("project_define", "project_workspace_set", "project_lend",
                        "project_unlend", "project_move", "project_forget"),
                registry.tools().stream().map(ToolRegistry.Tool::name).toList());
    }

    /**
     * The two tools with "move" in their meaning each name the other, because a
     * model choosing between them reads nothing else.
     *
     * <p>{@code project_workspace_set} moves a project's <em>workspace</em> and
     * {@code project_move} moves the <em>project</em>. Left undistinguished,
     * those two descriptions are a coin flip with an archive on one side of it —
     * and the failure is silent, because either tool succeeds at what it does.
     */
    @Test
    void each_of_the_two_moves_says_which_one_it_is_not() {
        assertTrue(ProjectTools.SET_DESCRIPTION.contains("project_move"),
                "the workspace one sends a reader to the other: "
                        + ProjectTools.SET_DESCRIPTION);
        assertTrue(ProjectTools.MOVE_DESCRIPTION.contains("project_workspace_set"),
                "and the other one back: " + ProjectTools.MOVE_DESCRIPTION);
    }

    // --- project_define -------------------------------------------------------------

    /**
     * What is sent is what was typed, and what is shown is what came back.
     *
     * <p><b>The fixture types a relative workspace and the server answers an
     * absolute one</b>, which is what really happens — {@code ProjectStore}
     * resolves a workspace once, when the project is defined — and is what makes
     * this discriminating: a renderer echoing the request would print {@code
     * checkout} and never {@code /srv/checkout}. An earlier version of this test
     * had the stub answer with the same strings it was sent, so it passed for an
     * echo and a render alike and held nothing about either.
     *
     * <p>The project <em>name</em> is deliberately not pinned that way, and
     * cannot be: the server refuses a name it would have to change rather than
     * changing it, so echo and render are the same string for that field on every
     * path. {@code ProjectTools}' class javadoc says so rather than leaving the
     * claim to read as though a test held all three.
     */
    @Test
    void defining_a_project_sends_what_was_typed_and_shows_what_came_back() {
        server.projectIs(new ServerClient.ProjectView("payments", "/srv/checkout", List.of(),
                List.of("/etc/plowshare.yml", "/srv/checkout/keys")));

        String out = (String) tools.define(args(
                "project", "payments", "workspace", "checkout",
                "exclusions", List.of("keys")));

        assertEquals("payments", server.lastName);
        assertEquals("checkout", server.lastWorkspace);
        assertEquals(List.of("keys"), server.lastExclusions);
        assertTrue(out.contains("/srv/checkout"), out);
        assertTrue(out.contains("/srv/checkout/keys"), out);
    }

    /**
     * The answer shows every path the project cannot reach, the ones nobody may
     * override included.
     *
     * <p>This is the whole reason the tool renders the server's list rather than
     * echoing what was sent: the configuration file and the agents directory are
     * added server-side and a person who never sees them has no way to know the
     * leash is shorter than the directory they named. The fixture's list is
     * therefore longer than the argument, so a renderer echoing the request
     * would be a different answer rather than the same one.
     */
    @Test
    void the_answer_shows_the_paths_the_project_cannot_reach() {
        server.projectIs(new ServerClient.ProjectView("payments", "/srv/repo", List.of(),
                List.of("/etc/plowshare.yml", "/srv/agents")));

        String out = (String) tools.define(args(
                "project", "payments", "workspace", "/srv/repo"));

        assertTrue(out.contains("/etc/plowshare.yml"), out);
        assertTrue(out.contains("/srv/agents"), out);
    }

    /** No exclusions is an ordinary definition and not an error: the paths the
     *  server adds are never the caller's to send. */
    @Test
    void a_definition_with_no_exclusions_sends_none() {
        server.projectIs(new ServerClient.ProjectView(
                "payments", "/srv/repo", List.of(), List.of()));

        tools.define(args("project", "payments", "workspace", "/srv/repo"));

        assertEquals(List.of(), server.lastExclusions);
    }

    @Test
    void defining_without_a_workspace_names_the_argument() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> tools.define(args("project", "payments")))
                .getMessage().contains("workspace"));
    }

    @Test
    void defining_without_a_project_names_the_argument() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> tools.define(args("workspace", "/srv/repo")))
                .getMessage().contains("project"));
    }

    /** An exclusions value that is not a list is a caller's mistake with a
     *  correction, not a stack trace: a bare string is the natural mistype. */
    @Test
    void exclusions_that_are_not_a_list_are_refused_with_the_shape_to_send() {
        String message = assertThrows(IllegalArgumentException.class,
                        () -> tools.define(args("project", "payments",
                                "workspace", "/srv/repo", "exclusions", "/srv/repo/secrets")))
                .getMessage();

        assertTrue(message.contains("exclusions"), message);
        assertTrue(message.contains("list"), message);
    }

    /** A blank entry in the list is a mistype rather than a path, and letting it
     *  through would send the server a fence post naming nothing. */
    @Test
    void an_empty_entry_in_the_exclusions_is_refused() {
        String message = assertThrows(IllegalArgumentException.class,
                        () -> tools.define(args("project", "payments",
                                "workspace", "/srv/repo",
                                "exclusions", Arrays.asList("/srv/repo/secrets", "  "))))
                .getMessage();

        assertTrue(message.contains("exclusions"), message);
        assertTrue(message.contains("Nothing was written"), message);
    }

    /**
     * A project with nothing fenced off says so in words.
     *
     * <p>An empty list rendered as an empty list reads as a missing answer, and
     * the sentence after it — that the paths the server keeps for itself are
     * always covered, whether or not each is named above — would then be
     * describing a list the reader cannot see. This is the branch that keeps
     * the two halves consistent.
     */
    @Test
    void a_project_with_nothing_fenced_off_says_so_rather_than_showing_an_empty_list() {
        server.projectIs(new ServerClient.ProjectView(
                "payments", "/srv/repo", List.of(), List.of()));

        String out = (String) tools.define(args(
                "project", "payments", "workspace", "/srv/repo"));

        assertTrue(out.contains(ProjectTools.NOTHING_EXTRA), out);
    }

    // --- project_workspace_set ------------------------------------------------------

    /**
     * Moving is its own call, and the answer says the exclusions were kept.
     *
     * <p>The two tools take almost the same arguments, so the thing that tells
     * them apart is which server call they make: {@code define} replaces a
     * project's exclusions and this does not. A test that only read the rendered
     * text would pass for a tool that quietly redefined.
     */
    @Test
    void setting_a_workspace_moves_it_without_redefining_the_project() {
        server.projectIs(new ServerClient.ProjectView("payments", "/srv/moved", List.of(),
                List.of("/srv/repo/secrets")));

        String out = (String) tools.setWorkspace(args(
                "project", "payments", "workspace", "/srv/moved"));

        assertEquals("payments", server.lastMovedName);
        assertEquals("/srv/moved", server.lastMovedWorkspace);
        assertNullDefine();
        assertTrue(out.contains("/srv/moved"), out);
        assertTrue(out.contains("/srv/repo/secrets"), out);
        // The sentence about runs already going, asserted for what it must SAY
        // rather than for the absence of what it used to. It used to say a file
        // request landing after a move is refused, which is false in both halves
        // -- the router re-asks its seam on every call and the provider re-reads
        // the row, so the request is answered from the NEW tree. Nothing pinned
        // it, which is how it drifted; the behaviour itself is held by
        // LocalProviderTest.moving_a_project_s_workspace_moves_the_leash_of_a_
        // job_already_running.
        assertTrue(out.contains("next file request"), out);
        assertTrue(out.contains("reads the new directory from here on"), out);
    }

    @Test
    void setting_a_workspace_without_one_names_the_argument() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> tools.setWorkspace(args("project", "payments")))
                .getMessage().contains("workspace"));
    }

    // --- project_lend and project_unlend ---------------------------------------------

    /**
     * The roots go to the lending endpoint and the whole leash comes back.
     *
     * <p><b>Which endpoint is the assertion that cannot be made from the text.</b>
     * Lending and defining render almost the same paragraph, so a {@code lend}
     * that reached {@code defineProject} would print an answer a reader could
     * not tell from this one — while replacing the project's exclusions and
     * whatever else it was lent. {@link #assertNullDefine} is what says it did
     * not, and the workspace endpoint is checked for the same reason one door
     * along: lending must not move the project.
     *
     * <p><b>The fixture sends a relative root and the server answers an absolute
     * one</b>, on {@code defining_a_project_sends_what_was_typed_and_shows_what_
     * came_back}'s rule: a renderer echoing the request would print {@code
     * .github} and never {@code /srv/checkout/.github}, so this is the field
     * that separates a render from an echo for the lent list.
     */
    @Test
    void lending_sends_the_roots_and_shows_the_whole_leash_that_came_back() {
        server.projectIs(new ServerClient.ProjectView("payments", "/srv/checkout",
                List.of("/srv/checkout/.github", "/srv/shared"),
                List.of("/etc/plowshare.yml")));

        String out = (String) tools.lend(args(
                "project", "payments", "roots", List.of(".github", "/srv/shared")));

        assertEquals("payments", server.lastLentProject);
        assertEquals(List.of(".github", "/srv/shared"), server.lastLent,
                "the paths travel as they were typed: resolving one here would make it"
                        + " absolute against this machine rather than the server's");
        assertNullDefine();
        assertNull(server.lastMovedWorkspace,
                "and it never went near the workspace endpoint, which is what lending"
                        + " exists not to do");
        assertTrue(out.contains("/srv/checkout/.github"), out);
        assertTrue(out.contains("/srv/shared"), out);
        assertTrue(out.contains("/srv/checkout"), out);
        assertTrue(out.contains("/etc/plowshare.yml"),
                "the fence is shown alongside the reach, because a lent root does not"
                        + " lift the paths the server keeps for itself: " + out);
    }

    /**
     * Unlending reaches the other endpoint, and the answer is what is left
     * rather than what was taken.
     *
     * <p>The two tools take one schema and render one paragraph, so the only
     * thing that distinguishes them is the call — and a caller who read the text
     * alone could not tell an unlend that lent from one that did not.
     */
    @Test
    void unlending_sends_the_roots_and_shows_what_is_left() {
        server.projectIs(new ServerClient.ProjectView("payments", "/srv/checkout",
                List.of("/srv/shared"), List.of()));

        String out = (String) tools.unlend(args(
                "project", "payments", "roots", List.of("/srv/checkout/.github")));

        assertEquals("payments", server.lastUnlentProject);
        assertEquals(List.of("/srv/checkout/.github"), server.lastUnlent);
        assertNull(server.lastLent, "and it did not lend on its way past");
        assertNullDefine();
        assertTrue(out.contains("/srv/shared"), out);
        assertFalse(out.contains(".github"),
                "the answer is the leash as it now stands, so what was taken back is not"
                        + " in it: " + out);
    }

    /**
     * A project that lends nothing says so in words, exactly as one that fences
     * nothing does.
     *
     * <p>The fixture keeps an exclusion, so {@code (nothing)} can only have come
     * from the reach: with both lists empty the same assertion would pass on a
     * renderer that had lost the lent half altogether.
     */
    @Test
    void a_project_that_lends_nothing_says_so_rather_than_showing_an_empty_list() {
        server.projectIs(new ServerClient.ProjectView("payments", "/srv/checkout",
                List.of(), List.of("/etc/plowshare.yml")));

        String out = (String) tools.unlend(args(
                "project", "payments", "roots", List.of("/srv/shared")));

        assertTrue(out.contains(ProjectTools.NOTHING_EXTRA), out);
    }

    /**
     * A server that predates the {@code lent} field sends it not at all, and the
     * client is a separately installed binary.
     *
     * <p>Null and not merely empty is the state that matters here: the older
     * server really does have the workspace as its whole leash, so "(nothing)"
     * is the honest rendering — and the alternative to this branch is a {@link
     * NullPointerException} out of a tool a person is running to find out where
     * their project can reach.
     */
    @Test
    void a_server_that_predates_lending_renders_as_a_project_that_lends_nothing() {
        server.projectIs(new ServerClient.ProjectView("payments", "/srv/checkout",
                null, List.of("/etc/plowshare.yml")));

        String out = (String) tools.define(args(
                "project", "payments", "workspace", "/srv/checkout"));

        assertTrue(out.contains(ProjectTools.NOTHING_EXTRA), out);
    }

    /**
     * A lend naming no directory is refused here rather than sent.
     *
     * <p>The server refuses it too — {@code ProjectStore.lend} argues why at
     * length — but a call that changes nothing and answers "done" is the one
     * outcome nobody goes back and checks, and the likeliest cause is a caller
     * whose list came out empty. Refusing at the tool means the sentence names
     * the argument and the shape to send it in.
     */
    @Test
    void lending_nothing_is_refused_with_the_shape_to_send_and_is_not_sent() {
        String message = assertThrows(IllegalArgumentException.class,
                        () -> tools.lend(args("project", "payments", "roots", List.of())))
                .getMessage();

        assertTrue(message.contains("roots"), message);
        assertTrue(message.contains("at least one"), message);
        assertTrue(message.contains("Nothing was written"), message);
        assertNull(server.lastLentProject, "and the server was never asked");
    }

    /** An absent {@code roots} is the same refusal as an empty one: on a lend
     *  the two mean the same thing, which is what separates this argument from
     *  {@code exclusions}, where omitting it means "fence nothing extra". */
    @Test
    void lending_without_the_roots_argument_is_refused_the_same_way() {
        String message = assertThrows(IllegalArgumentException.class,
                        () -> tools.lend(args("project", "payments")))
                .getMessage();

        assertTrue(message.contains("roots"), message);
        assertTrue(message.contains("at least one"), message);
        assertNull(server.lastLentProject, "and the server was never asked");
    }

    /** Both directions, because the refusal is one helper and a tool that
     *  skipped it would be the one that quietly succeeds. */
    @Test
    void unlending_nothing_is_refused_rather_than_answered() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> tools.unlend(args("project", "payments", "roots", List.of())))
                .getMessage().contains("roots"));
        assertNull(server.lastUnlentProject, "and the server was never asked");
    }

    /** A blank entry is a mistype rather than a path, and a lend built out of
     *  one would send the server a root naming its own working directory. */
    @Test
    void an_empty_entry_in_the_roots_is_refused() {
        String message = assertThrows(IllegalArgumentException.class,
                        () -> tools.lend(args("project", "payments",
                                "roots", Arrays.asList("/srv/shared", "  "))))
                .getMessage();

        assertTrue(message.contains("roots"), message);
        assertTrue(message.contains("Nothing was written"), message);
        assertNull(server.lastLentProject, "and the server was never asked");
    }

    @Test
    void lending_without_a_project_names_the_argument() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> tools.lend(args("roots", List.of("/srv/shared"))))
                .getMessage().contains("project"));
    }

    @Test
    void unlending_without_a_project_names_the_argument() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> tools.unlend(args("roots", List.of("/srv/shared"))))
                .getMessage().contains("project"));
    }

    // --- project_move ---------------------------------------------------------------

    /**
     * The answer's whole job is to say what came with the project, because a
     * person reading "renamed" has no way to know whether an archive followed.
     *
     * <p>It did, and by construction: {@code memories} and {@code conversations}
     * reference the project's surrogate id, so a move is one row and one column
     * and nothing else is touched. That is asserted against a real database in
     * {@code ProjectStoreTest.a_moved_project_carries_its_memories_and_its_
     * conversations_with_it}; what is held here is that the sentence says so.
     */
    @Test
    void moving_a_project_says_its_memories_came_with_it() {
        String out = (String) tools.move(args("project", "payments", "to", "bench/srv/payments"));

        assertEquals("payments", server.lastMovedProject);
        assertEquals("bench/srv/payments", server.lastMovedTo);
        assertTrue(out.contains("payments") && out.contains("bench/srv/payments"), out);
        assertTrue(out.toLowerCase(Locale.ROOT).contains("memories"), out);
        assertTrue(out.toLowerCase(Locale.ROOT).contains("conversations"), out);
    }

    /** And that the files did not: this moves the project, not its workspace,
     *  which is the one confusion the pair of tools can produce. */
    @Test
    void moving_a_project_says_the_workspace_is_unchanged() {
        String out = (String) tools.move(args("project", "payments", "to", "moved"));

        assertTrue(out.toLowerCase(Locale.ROOT).contains("workspace"), out);
        assertNull(server.lastMovedWorkspace,
                "and it never went near the workspace endpoint");
    }

    @Test
    void moving_without_a_destination_names_the_argument() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> tools.move(args("project", "payments")))
                .getMessage().contains("to"));
    }

    @Test
    void moving_without_a_project_names_the_argument() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> tools.move(args("to", "moved")))
                .getMessage().contains("project"));
    }

    // --- project_forget -------------------------------------------------------------

    /** Dropping the leash is not dropping the memories, and the answer has to
     *  say so: a person reading "forgotten" would otherwise reasonably believe
     *  the project's whole archive had gone with it. */
    @Test
    void forgetting_a_project_says_its_memories_are_untouched() {
        String out = (String) tools.forget(args("project", "payments"));

        assertEquals("payments", server.lastForgotten);
        assertTrue(out.contains("payments"), out);
        assertTrue(out.toLowerCase(Locale.ROOT).contains("memories"), out);
    }

    @Test
    void forgetting_without_a_project_names_the_argument() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> tools.forget(args()))
                .getMessage().contains("project"));
    }

    // --- a server that is not there -------------------------------------------------

    /**
     * A dead server is said to be a dead server, and the sentence says nothing
     * was written.
     *
     * <p>{@code MemoryTools.ServerUnreachableException} exists so that "the
     * server could not be reached" is never rendered as "it did not work" — for
     * a write to the projects table that distinction is what stops a person
     * defining the same project twice, or believing a workspace they still have
     * is gone.
     */
    @Test
    void a_server_that_cannot_be_reached_is_said_to_be_unreachable() {
        server.failWith(new ConnectException("Connection refused"));

        String message = assertThrows(MemoryTools.ServerUnreachableException.class,
                        () -> tools.forget(args("project", "payments")))
                .getMessage();

        assertTrue(message.contains("http://localhost:9999"), message);
        assertTrue(message.contains("Connection refused"), message);
    }

    // --- scaffolding ----------------------------------------------------------------

    private void assertNullDefine() {
        assertFalse(server.defineCalled, "moving a workspace must not redefine the project");
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** A stand-in for the server, recording what it was asked and answering with
     *  whatever the test set — including by failing the way a dead one fails. */
    private static final class StubServerClient implements ServerClient {

        private IOException failure;
        private ProjectView project;

        boolean defineCalled;
        String lastName;
        String lastWorkspace;
        List<String> lastExclusions;
        String lastMovedName;
        String lastMovedWorkspace;
        String lastLentProject;
        List<String> lastLent;
        String lastUnlentProject;
        List<String> lastUnlent;
        String lastMovedProject;
        String lastMovedTo;
        String lastForgotten;

        void failWith(IOException e) {
            this.failure = e;
        }

        void projectIs(ProjectView project) {
            this.project = project;
        }

        @Override
        public String baseUrl() {
            return "http://localhost:9999";
        }

        @Override
        public ProjectView defineProject(
                String name, String workspace, List<String> exclusions) throws IOException {
            failIfAsked();
            this.defineCalled = true;
            this.lastName = name;
            this.lastWorkspace = workspace;
            this.lastExclusions = exclusions;
            return project;
        }

        @Override
        public ProjectView lendProject(String name, List<String> roots) throws IOException {
            failIfAsked();
            this.lastLentProject = name;
            this.lastLent = roots;
            return project;
        }

        @Override
        public ProjectView unlendProject(String name, List<String> roots) throws IOException {
            failIfAsked();
            this.lastUnlentProject = name;
            this.lastUnlent = roots;
            return project;
        }

        @Override
        public ProjectView setProjectWorkspace(String name, String workspace) throws IOException {
            failIfAsked();
            this.lastMovedName = name;
            this.lastMovedWorkspace = workspace;
            return project;
        }

        @Override
        public void moveProject(String name, String to) throws IOException {
            failIfAsked();
            this.lastMovedProject = name;
            this.lastMovedTo = to;
        }

        @Override
        public void forgetProject(String name) throws IOException {
            failIfAsked();
            this.lastForgotten = name;
        }

        @Override
        public Conversation openConversation(String project, Integer maxModelCalls) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public List<Seam> compactions(String conversationId) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public List<Conversation> conversations(String project) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public Entries chat(String conversationId, Integer offset, Integer limit) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public Entries trajectory(String conversationId, Integer offset, Integer limit) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public LogHits searchEntries(
                String project, String query, Integer offset, Integer limit) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public Context context(String conversationId, String agent) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        // --- everything else, which this class never uses -------------------------

        @Override
        public WriteResult write(String project, MemoryProposal proposal) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public UploadedImage uploadImage(String project, String filename, byte[] bytes) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public Recall recall(String project, String question, Integer limit) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public Memory read(String id) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public List<IndexEntry> index(String project) {
            throw new UnsupportedOperationException("not the subject of this test");
        }
        @Override
        public Citations citations(String conversationId, String documentId, Integer limit) {
            throw new UnsupportedOperationException("citations");
        }


        @Override
        public Ranking rankDocuments(String query, Integer limit) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public Stance documentStance(String documentId, String claim) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public Retrieved retrieve(String query, String documentId, Integer limit) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public DocumentPage listDocuments(String naming, Integer limit, Integer offset) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public DocumentOutline describeDocument(String documentId) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public DocumentSearch searchDocuments(String query, Integer limit) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public StartedJob run(String agent, String task, String project, String session,
                String conversation) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public StartedJob askDocument(String documentId, String question,
                Integer maxModelCalls) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public StartedJob curate(String project, Integer maxModelCalls) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public JobStatus job(String id) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public JobStatus cancelJob(String id) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public List<ProposalRow> proposals(String project) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public Resolution resolve(String id, boolean accept, String reason, String by) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        private void failIfAsked() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
