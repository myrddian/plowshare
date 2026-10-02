package io.aeyer.plowshare.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The one surface a project's workspace can be managed from, and no agent has
 * it.
 *
 * <p>Pure MVC against a mocked {@link ProjectStore}, matching {@code
 * MemoryControllerTest}: what the store does with a row is {@code
 * ProjectStoreTest}'s subject against a real database, and this class's job is
 * narrower — does each endpoint call the right method with the right arguments,
 * and does each refusal become the right status.
 *
 * <p><b>What is not here, and is the point of the whole endpoint.</b> The three
 * verbs are reachable from a person's MCP session and from nowhere else: {@code
 * JobRuntime.knownTools()} never names {@code project_define}, {@code
 * project_workspace_set} or {@code project_forget}, so {@code
 * AgentRegistry.load} refuses at boot any definition that does — which is
 * asserted in {@code
 * AgentsConfigTest.no_boot_binds_a_workspace_management_tool}, because it is a
 * fact about the tool layer rather than about this controller.
 *
 * <p><b>The listing is out of reach for a different reason, and deliberately.</b>
 * It has no MCP tool at all, so there is no tool name for that boot-time refusal
 * to catch: it is reached by the console over HTTP and by nothing else. That is
 * a choice rather than an omission — the answer carries every project's
 * workspace and every path fenced off around it — and {@code ProjectController}
 * records it, since a missing tool is not a thing any test in this file can
 * observe.
 */
class ProjectControllerTest {

    private static final Path REPO = Path.of("/srv/repo");
    private static final Path MOVED = Path.of("/srv/moved");

    private ProjectStore projects;
    private PresenceRegistry presences;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectStore.class);
        presences = new PresenceRegistry();
        mvc = MockMvcBuilders.standaloneSetup(new ProjectController(projects, presences))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // --- listing -------------------------------------------------------------------

    /**
     * Each listed project carries its <em>effective</em> exclusions, asked of the
     * store per row.
     *
     * <p>Same reason the three verbs answer that way, and it matters more here:
     * the console's projects screen is where an operator reads what their leash
     * actually is, and a listing built from {@code ProjectRecord.exclusions()}
     * would show every project shorter than it is.
     *
     * <p><b>{@code beta} is here to make that assertion mean something.</b> With
     * one project, the mandatory path appearing in the answer is also what a
     * store that ignored its argument would produce, and what a row that happened
     * to carry that path itself would produce. Two projects whose rows differ and
     * whose effective lists share only the mandatory path can be produced no
     * other way than by asking per row.
     *
     * <p><b>Which is why both rows' exclusions are asserted whole.</b> Asserting
     * {@code alpha}'s alone left the claim above untrue of this test: a {@code
     * list} that called {@code effectiveExclusions} once and reused the answer
     * for every row satisfied {@code alpha} and was never asked about {@code
     * beta}. The two lists here have different lengths and different contents,
     * so one shared call cannot be both.
     */
    @Test
    void listing_projects_answers_each_ones_effective_exclusions_and_not_its_row()
            throws Exception {
        ProjectRecord alpha = new ProjectRecord("alpha", Path.of("/w/alpha"), List.of(),
                List.of(Path.of("/w/alpha/secret")));
        ProjectRecord beta =
                new ProjectRecord("beta", Path.of("/w/beta"), List.of(), List.of());
        when(projects.all()).thenReturn(List.of(alpha, beta));
        when(projects.effectiveExclusions(alpha))
                .thenReturn(List.of(Path.of("/w/alpha/secret"), Path.of("/cfg/application.yml")));
        when(projects.effectiveExclusions(beta))
                .thenReturn(List.of(Path.of("/cfg/application.yml")));

        mvc.perform(get("/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("alpha"))
                .andExpect(jsonPath("$[0].workspace").value("/w/alpha"))
                .andExpect(jsonPath("$[0].exclusions.length()").value(2))
                .andExpect(jsonPath("$[0].exclusions[0]").value("/w/alpha/secret"))
                .andExpect(jsonPath("$[0].exclusions[1]").value("/cfg/application.yml"))
                .andExpect(jsonPath("$[1].name").value("beta"))
                .andExpect(jsonPath("$[1].workspace").value("/w/beta"))
                // beta's list is asserted whole and is a different list: alpha's
                // own path must NOT be here. A single shared call would put it
                // here, and nothing else in this test would notice.
                .andExpect(jsonPath("$[1].exclusions.length()").value(1))
                .andExpect(jsonPath("$[1].exclusions[0]").value("/cfg/application.yml"));
    }

    /**
     * A store that could not be reached is 503, and the listing is not the one
     * endpoint where that goes unsaid.
     *
     * <p>Every other verb here has an arm for the store refusing; without this
     * one, a dead database on the console's own screen reached {@code
     * ApiExceptionHandler} on a path nothing had ever driven. 503 rather than
     * 404 for the reason {@code ArchiveUnavailableException} is an unrelated
     * type: an empty listing means this server has no projects, and a console
     * that showed one for a database nobody could reach would be reporting an
     * answer nobody gave.
     */
    @Test
    void a_listing_the_archive_could_not_be_reached_for_is_503() throws Exception {
        when(projects.all()).thenThrow(new ArchiveUnavailableException(
                "the archive could not be reached to list the projects",
                new IllegalStateException("Connection to localhost:5432 refused")));

        mvc.perform(get("/v1/projects"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("archive_unavailable"))
                .andExpect(jsonPath("$.detail").value(containsString("list the projects")));
    }

    // --- defining ------------------------------------------------------------------

    @Test
    void defining_a_project_names_its_workspace_and_its_exclusions() throws Exception {
        ProjectRecord row = new ProjectRecord("payments", REPO,
                List.of(REPO.resolve(".github")), List.of(REPO.resolve("secrets")));
        when(projects.defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenReturn(row);
        when(projects.effectiveExclusions(row))
                .thenReturn(List.of(Path.of("/etc/plowshare.yml"), REPO.resolve("secrets")));

        mvc.perform(post("/v1/projects").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "payments", "workspace": "/srv/repo",
                         "lent": ["/srv/repo/.github"],
                         "exclusions": ["/srv/repo/secrets"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("payments"))
                .andExpect(jsonPath("$.workspace").value("/srv/repo"))
                .andExpect(jsonPath("$.lent[0]").value("/srv/repo/.github"))
                .andExpect(jsonPath("$.exclusions[0]").value("/etc/plowshare.yml"))
                .andExpect(jsonPath("$.exclusions[1]").value("/srv/repo/secrets"));

        // Both lists captured, in one verify, because the hazard this pins is
        // that they are two adjacent List<Path> and a transposed call would read
        // a fence as a grant -- which grows a leash rather than failing.
        // Asserting only the exclusions would leave the transposition green.
        ArgumentCaptor<List<Path>> lent = ArgumentCaptor.captor();
        ArgumentCaptor<List<Path>> excluded = ArgumentCaptor.captor();
        verify(projects).defineLending(
                eq("payments"), eq(REPO), lent.capture(), excluded.capture(), org.mockito.ArgumentMatchers.isNull());
        assertEquals(List.of(REPO.resolve(".github")), lent.getValue());
        assertEquals(List.of(REPO.resolve("secrets")), excluded.getValue());
    }

    /**
     * The answer carries the lent roots, because a view without them is a leash
     * with a hole a person cannot see.
     *
     * <p>The mirror of {@code the_answer_names_the_exclusions_no_project_may_
     * override}, and the failure runs the other way: that one is about showing a
     * <em>shorter</em> leash than the row has by hiding what the server adds to
     * the fence; this is about showing a shorter one by hiding what the project
     * itself was granted.
     */
    @Test
    void the_answer_carries_the_directories_the_project_is_lent() throws Exception {
        ProjectRecord row = new ProjectRecord("payments", REPO,
                List.of(REPO.resolve(".github"), Path.of("/srv/shared")), List.of());
        when(projects.lend(anyString(), any())).thenReturn(row);
        when(projects.effectiveExclusions(row)).thenReturn(List.of(Path.of("/opt/plowshare")));

        mvc.perform(post("/v1/projects/payments/lend")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"roots": ["/srv/repo/.github", "/srv/shared"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace").value("/srv/repo"))
                .andExpect(jsonPath("$.lent[0]").value("/srv/repo/.github"))
                .andExpect(jsonPath("$.lent[1]").value("/srv/shared"))
                .andExpect(jsonPath("$.exclusions[0]").value("/opt/plowshare"));

        verify(projects).lend("payments",
                List.of(REPO.resolve(".github"), Path.of("/srv/shared")));
    }

    /** The other direction, on its own route: one endpoint taking a direction
     *  would make "lend this and unlend it" a request with no defensible
     *  answer, and a caller who filled in the wrong field would do the opposite
     *  of what they meant with nothing to say so. */
    @Test
    void unlending_is_its_own_route_and_answers_with_what_is_left() throws Exception {
        ProjectRecord row = new ProjectRecord("payments", REPO, List.of(), List.of());
        when(projects.unlend(anyString(), any())).thenReturn(row);
        when(projects.effectiveExclusions(row)).thenReturn(List.of());

        mvc.perform(post("/v1/projects/payments/unlend")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"roots": ["/srv/shared"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lent").isEmpty());

        verify(projects).unlend("payments", List.of(Path.of("/srv/shared")));
        verify(projects, never()).lend(anyString(), any());
    }

    /** A lend that names nothing is refused here rather than passed on. The
     *  store refuses an empty list too, and this is not that check: {@code null}
     *  is what an omitted key arrives as, and is a shape the store never sees. */
    @Test
    void a_lend_that_names_no_directory_is_refused_before_anything_is_written()
            throws Exception {
        mvc.perform(post("/v1/projects/payments/lend")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("roots")));

        mvc.perform(post("/v1/projects/payments/unlend")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"roots": []}"""))
                .andExpect(status().isBadRequest());

        verify(projects, never()).lend(anyString(), any());
        verify(projects, never()).unlend(anyString(), any());
    }

    /**
     * The answer carries the <em>effective</em> exclusions, which is more than
     * the caller sent.
     *
     * <p>{@code ProjectRecord.exclusions()} is the row and is explicitly not the
     * containment set: the server's configuration and the sampling directory
     * are added by {@link ProjectStore#effectiveExclusions} so that no row can drop
     * them. Answering with the row would show an operator a shorter leash than
     * the one they have, which is the one thing a person setting a workspace is
     * reading this answer to learn.
     */
    @Test
    void the_answer_names_the_exclusions_no_project_may_override() throws Exception {
        ProjectRecord row = new ProjectRecord("payments", REPO, List.of(), List.of());
        when(projects.defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenReturn(row);
        when(projects.effectiveExclusions(row))
                .thenReturn(List.of(Path.of("/etc/plowshare.yml"), Path.of("/srv/agents")));

        mvc.perform(post("/v1/projects").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "payments", "workspace": "/srv/repo"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions[0]").value("/etc/plowshare.yml"))
                .andExpect(jsonPath("$.exclusions[1]").value("/srv/agents"));
    }

    /** An omitted list means none, and is not the same thing as a null the store
     *  refuses: leaving the key out is how a caller says "fence off nothing
     *  extra", and 422 for it would be a refusal about a field they never sent. */
    @Test
    void a_definition_with_no_exclusions_fences_off_nothing_extra() throws Exception {
        ProjectRecord row = new ProjectRecord("payments", REPO, List.of(), List.of());
        when(projects.defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenReturn(row);
        when(projects.effectiveExclusions(row)).thenReturn(List.of());

        mvc.perform(post("/v1/projects").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "payments", "workspace": "/srv/repo"}"""))
                .andExpect(status().isOk());

        // And an omitted `lent` is the same "none", turned into the empty list
        // one line before the store sees it -- so the two absent keys take the
        // same path and neither arrives as the null the store refuses.
        verify(projects).defineLending("payments", REPO, List.of(), List.of(), null);
    }

    /** A workspace is a path on the server's disk and the store is the only
     *  thing that may say whether it is one; nothing is invented here for it. */
    @Test
    void a_definition_with_no_workspace_is_refused_before_anything_is_written()
            throws Exception {
        mvc.perform(post("/v1/projects").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "payments"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("workspace")));

        verify(projects, never()).defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void a_workspace_the_store_refuses_is_a_422() throws Exception {
        when(projects.defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenThrow(
                new ValidationException("project 'payments' cannot take workspace /srv/repo:"
                        + " it is not a directory"));

        mvc.perform(post("/v1/projects").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "payments", "workspace": "/srv/repo"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("not a directory")));
    }

    /**
     * A blank workspace is refused here rather than passed on.
     *
     * <p>Not the same refusal as a missing one, though the message is: {@code
     * Path.of("  ")} is a perfectly valid <em>relative</em> path, so a blank
     * string that reached the store would be resolved against the server's
     * working directory and refused as "does not exist" — a 422 about a
     * directory the caller never named, instead of a 400 about the field they
     * left empty. It is the shape an unset field sends, which is why it is
     * worth its own arm.
     */
    @Test
    void a_blank_workspace_is_refused_as_a_missing_one_and_not_resolved() throws Exception {
        mvc.perform(post("/v1/projects").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "payments", "workspace": "   "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("workspace")));

        verify(projects, never()).defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * A string that is not a path at all is the caller's mistake and not a 500.
     *
     * <p>Measured on JDK 21: {@code Path.of("a\0b")} raises {@code
     * InvalidPathException}, which is unchecked — so without the translation it
     * leaves this controller as an unclassified failure about a typo. {@code
     * FileTools} makes the same translation at the other end of the system, for
     * the same reason.
     */
    @Test
    void a_workspace_that_is_not_a_path_at_all_is_a_400() throws Exception {
        mvc.perform(post("/v1/projects").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"name\": \"payments\", \"workspace\": \"/srv/re\\u0000po\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("cannot be read as a path")));

        verify(projects, never()).defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any());
    }

    // --- moving --------------------------------------------------------------------

    /**
     * Moving is {@code moveWorkspace} and never {@code define}, which is the
     * whole reason there are two tools rather than one.
     *
     * <p>{@code define} replaces a row's exclusions, so a person moving a project
     * onto a new checkout through it would silently drop every path they had
     * fenced off. This asserts the method as well as the answer: they take the
     * same two arguments, so nothing else in this test could tell them apart.
     */
    @Test
    void moving_a_workspace_keeps_the_exclusions_by_not_going_through_define()
            throws Exception {
        ProjectRecord row = new ProjectRecord(
                "payments", MOVED, List.of(), List.of(REPO.resolve("secrets")));
        when(projects.moveWorkspace(anyString(), any())).thenReturn(row);
        when(projects.effectiveExclusions(row)).thenReturn(List.of(REPO.resolve("secrets")));

        mvc.perform(post("/v1/projects/payments/workspace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspace": "/srv/moved"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace").value("/srv/moved"))
                .andExpect(jsonPath("$.exclusions[0]").value("/srv/repo/secrets"));

        verify(projects).moveWorkspace("payments", MOVED);
        verify(projects, never()).defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void moving_the_workspace_of_a_project_that_has_none_is_a_404() throws Exception {
        when(projects.moveWorkspace(anyString(), any()))
                .thenThrow(new ArchiveException("no project named payments has a workspace"));

        mvc.perform(post("/v1/projects/payments/workspace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspace": "/srv/moved"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("payments")));
    }

    @Test
    void a_move_with_no_workspace_is_refused_before_anything_is_written() throws Exception {
        mvc.perform(post("/v1/projects/payments/workspace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("workspace")));

        verify(projects, never()).moveWorkspace(anyString(), any());
    }

    // --- moving a project ----------------------------------------------------------

    /**
     * A move is a rename, and the server answers with nothing because there is
     * nothing new to describe: the workspace, the exclusions and the whole
     * archive are what they were, under a different name. <b>Saying that in words
     * is the client tool's job</b>, as it is for {@code forget}.
     */
    @Test
    void moving_a_project_renames_it_and_answers_with_no_content() throws Exception {
        mvc.perform(post("/v1/projects/payments/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to": "bench.local/srv/payments/payments"}"""))
                .andExpect(status().isNoContent());

        verify(projects).rename("payments", "bench.local/srv/payments/payments");
    }

    /**
     * The refusal the uniqueness constraint would otherwise report as {@code
     * projects_name_is_unique}. 409 and not 404: the row is there and the archive
     * will not do this to it.
     */
    @Test
    void moving_a_project_onto_a_name_that_is_taken_is_a_409() throws Exception {
        doThrow(new ArchiveRefusedException("a project is already called ledger"))
                .when(projects).rename(anyString(), anyString());

        mvc.perform(post("/v1/projects/payments/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to": "ledger"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("ledger")));
    }

    @Test
    void moving_a_project_that_does_not_exist_is_a_404() throws Exception {
        doThrow(new ArchiveException("no project is called payments"))
                .when(projects).rename(anyString(), anyString());

        mvc.perform(post("/v1/projects/payments/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to": "moved"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("payments")));
    }

    @Test
    void a_move_with_no_destination_is_refused_before_anything_is_written() throws Exception {
        mvc.perform(post("/v1/projects/payments/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("to")));

        verify(projects, never()).rename(anyString(), anyString());
    }

    /**
     * <b>A project a live session roots is not moved out from under it.</b>
     *
     * <p>A presence declares {@code project=} on its file channel, and the
     * registry keys on that name — so a rename that succeeded would leave the
     * session rooting a name no row holds, every run in the new name finding no
     * presence, and the client re-declaring the old name at its next reconnect,
     * undoing the move for the registry alone. Two authorities, no shared
     * transaction, and nothing that could reconcile them.
     *
     * <p>Refused before the store is asked at all, so the answer is exactly true:
     * nothing was written.
     */
    @Test
    void moving_a_project_a_live_presence_roots_is_refused_and_names_the_machine()
            throws Exception {
        presences.declare(new Presence("bench", "bench.local", "/srv/payments", "payments"));

        mvc.perform(post("/v1/projects/payments/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to": "moved"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("bench.local")));

        verify(projects, never()).rename(anyString(), anyString());
    }

    /**
     * And the destination too, which the uniqueness constraint cannot catch: a
     * project rooted by a live session need have no {@code projects} row at all,
     * so moving another project onto its name would hand one project's archive to
     * another machine's files with nothing refusing.
     */
    @Test
    void moving_a_project_onto_a_name_a_live_presence_roots_is_refused_too() throws Exception {
        presences.declare(new Presence("bench", "bench.local", "/srv/ledger", "ledger"));

        mvc.perform(post("/v1/projects/payments/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to": "ledger"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("ledger")));

        verify(projects, never()).rename(anyString(), anyString());
    }

    // --- forgetting ----------------------------------------------------------------

    /** Dropping a workspace leaves the project's memories alone -- there is no
     *  foreign key -- and this endpoint carries that by answering 204 with no
     *  body at all: there is no leash left to describe, and a body describing
     *  one would be the thing that implied a delete. <b>Saying so in words is
     *  the client tool's job</b>, since only a person reads it;
     *  {@code ProjectToolsTest.forgetting_a_project_says_its_memories_are_untouched}
     *  is where that sentence is held. */
    @Test
    void forgetting_a_project_drops_its_workspace_and_answers_with_no_content()
            throws Exception {
        mvc.perform(delete("/v1/projects/payments"))
                .andExpect(status().isNoContent());

        verify(projects).forget("payments");
    }

    @Test
    void forgetting_a_project_that_has_no_workspace_is_a_404() throws Exception {
        doThrow(new ArchiveException("no project named payments has a"
                        + " workspace"))
                .when(projects).forget(anyString());

        mvc.perform(delete("/v1/projects/payments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("payments")));
    }
}
