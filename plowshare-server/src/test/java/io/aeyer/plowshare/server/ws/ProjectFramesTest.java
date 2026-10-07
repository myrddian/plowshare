package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ProjectController;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The six endpoints {@code ProjectController} answers besides {@code project.define}, driven twice
 * — once over HTTP and once as a frame — off one request, asserting that the two surfaces said the
 * same thing.
 *
 * <h2>Seven endpoints, and the seventh has a file of its own</h2>
 *
 * <p>{@code project.define} landed as the write pilot and its parity is measured in {@code
 * ProjectDefineHandlerTest}, which was written before this area had a breadth task. It is not
 * repeated here: two files asserting one type would be two places to weaken. What this file adds is
 * the other six, plus the two surface-wide rules — tolerance and registration — asserted over
 * <b>all seven</b>, since those are the assertions that have to count the area rather than the
 * endpoint.
 *
 * <h2>What every section asserts</h2>
 *
 * <p>The happy path and <b>at least one refusal</b>. A happy path agrees by construction — both
 * surfaces hand the same arguments to the same {@link ProjectStore} method and render the same
 * {@code ProjectView} from what comes back — so the value of this file is in the refusals, which
 * this controller has more of than most: {@code requests.RequestedPaths} holds four with exact
 * sentences, the store answers 404, 409 and 422 in its own words, and {@code archive.Projects}
 * refuses a move at both ends with a sentence naming the machine to go and close a client on.
 * {@link FrameParity#assertSameRefusal} compares the words and not merely the status.
 *
 * <p><b>One mocked {@link ProjectStore}, shared by both surfaces</b>, matching {@code
 * ProjectControllerTest}: what the store does with a row is {@code ProjectStoreTest}'s subject
 * against a real database. What is under test here is which method each surface calls, with which
 * arguments, and what each does with what comes back — including with what is thrown.
 *
 * <p><b>The {@link PresenceRegistry} is real and not mocked</b>, because it is the one collaborator
 * here that is state rather than a service: {@code move}'s refusal is a lookup in it, both surfaces
 * build a {@code Projects} over the same instance, and a mock would make the refusal a stub rather
 * than the rule.
 */
class ProjectFramesTest {

  private static final Path REPO = Path.of("/srv/repo");
  private static final Path SHARED = Path.of("/srv/shared");
  private static final Path MOVED = Path.of("/srv/moved");

  private ProjectStore projects;
  private PresenceRegistry presences;
  private MockMvc mvc;
  private FrameRouter router;

  @BeforeEach
  void setUp() {
    projects = mock(ProjectStore.class);
    presences = new PresenceRegistry();

    // FrameParity.endpointsOf and not a bare standaloneSetup: what the
    // deployed server's converters do is what a frame is being compared
    // against, and that class's javadoc has the argument.
    mvc =
        FrameParity.endpointsOf(
            new ProjectController(
                projects, presences, io.aeyer.plowshare.server.ws.TestProjectMembers.allowed()));
    // Through the area rather than through a literal map: this is the
    // registration the handlers are reached by in production, and a test
    // that built its own map would pass for a type nobody wired.
    router =
        new FrameRoutingConfig()
            .frameRouter(
                List.of(
                    new ProjectFrames(
                        projects,
                        presences,
                        TestProjectMembers.allowed(),
                        new io.aeyer.plowshare.server.auth.AuthProperties())));
  }

  // --- project.list --------------------------------------------------------

  /**
   * Both surfaces list every project, each carrying its <em>effective</em> exclusions rather than
   * its row's own.
   *
   * <p>That distinction is the whole of what this listing is for — a console showing the row's list
   * would show an operator a shorter leash than the one enforced — and it is exactly the kind of
   * thing a second surface drops silently, since answering the row instead would still be a
   * well-formed list of well-formed projects.
   */
  @Test
  void both_surfaces_list_every_project_with_its_effective_exclusions() throws Exception {
    ProjectRecord alpha = new ProjectRecord("alpha", REPO, List.of(SHARED), List.of());
    ProjectRecord beta = new ProjectRecord("beta", MOVED, List.of(), List.of());
    when(projects.allForSession(
            org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenAnswer(invocation -> projects.allFor(invocation.getArgument(0)));
    when(projects.allFor(org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenReturn(List.of(alpha, beta));
    when(projects.effectiveExclusions(alpha)).thenReturn(List.of(Path.of("/opt/plowshare")));
    when(projects.effectiveExclusions(beta)).thenReturn(List.of(Path.of("/opt/plowshare")));

    MockHttpServletResponse http = read("/v1/projects");
    Outcome outcome = route(FrameTypes.PROJECT_LIST, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    assertTrue(
        outcome.payload().toString().contains("/opt/plowshare"),
        "and it is the effective list that was rendered: " + outcome.payload());
  }

  /**
   * A listing says where each project's files are, because a terminal started inside one has to be
   * able to recognise it — and a path alone cannot: the same string on a laptop and a build box
   * names two different trees.
   */
  @Test
  void both_surfaces_say_which_machine_roots_each_listed_project() throws Exception {
    ProjectRecord ledger =
        new ProjectRecord("ledger", Path.of("/Users/someone/ledger"), List.of(), List.of());
    ProjectRecord notes = new ProjectRecord("notes", Path.of("/srv/notes"), List.of(), List.of());
    when(projects.allForSession(
            org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenAnswer(invocation -> projects.allFor(invocation.getArgument(0)));
    when(projects.allFor(org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenReturn(List.of(ledger, notes));
    when(projects.effectiveExclusions(ledger)).thenReturn(List.of());
    when(projects.effectiveExclusions(notes)).thenReturn(List.of());
    when(projects.rootedElsewhere("ledger")).thenReturn(Optional.of("bench.local"));
    when(projects.rootedElsewhere("notes")).thenReturn(Optional.empty());

    MockHttpServletResponse http = read("/v1/projects");
    Outcome outcome = route(FrameTypes.PROJECT_LIST, "{}");

    FrameParity.assertSameAnswer(http, outcome);
    JsonNode rows = FrameJson.answering().readTree(http.getContentAsString());
    assertEquals("bench.local", rows.get(0).get("machine").asText());
    assertTrue(rows.get(1).get("machine").isNull());
  }

  /** An archive neither surface can reach is the same 503 in the same words. */
  @Test
  void a_listing_the_archive_could_not_be_reached_for_is_the_same_503_on_both_surfaces()
      throws Exception {
    when(projects.allForSession(
            org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenAnswer(invocation -> projects.allFor(invocation.getArgument(0)));
    when(projects.allFor(org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenThrow(
            new ArchiveUnavailableException(
                "the archive could not be reached to list the projects",
                new IllegalStateException("no connection")));

    MockHttpServletResponse http = read("/v1/projects");
    Outcome outcome = route(FrameTypes.PROJECT_LIST, "{}");

    assertEquals(Code.ARCHIVE_UNAVAILABLE, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- project.lend --------------------------------------------------------

  /**
   * Both surfaces lend the same roots to the same project and answer with the whole resulting
   * leash, not with what was added.
   */
  @Test
  void both_surfaces_lend_the_same_roots_and_answer_the_whole_leash() throws Exception {
    ProjectRecord row = new ProjectRecord("payments", REPO, List.of(SHARED), List.of());
    when(projects.lend(anyString(), any())).thenReturn(row);
    when(projects.effectiveExclusions(row)).thenReturn(List.of(Path.of("/opt/plowshare")));

    MockHttpServletResponse http =
        posted(
            "/v1/projects/payments/lend",
            """
                {"roots": ["/srv/shared"]}""");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_LEND,
            """
                {"project": "payments", "roots": ["/srv/shared"]}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    // Verified as well as compared: two surfaces could answer identical JSON
    // while one of them lent a directory the other did not, since the answer
    // is rendered from what the mocked store returns either way.
    verify(projects, times(2)).lend("payments", List.of(SHARED));
  }

  /**
   * A lend that names no directory is refused in the same words, and nothing is written on either
   * surface.
   */
  @Test
  void a_lend_that_names_no_directory_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = posted("/v1/projects/payments/lend", "{}");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_LEND,
            """
                {"project": "payments"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(projects, never()).lend(anyString(), any());
  }

  /**
   * A root that is not a path at all is the same 400 in the same words.
   *
   * <p>{@code RequestedPaths.one} is what makes it one: a NUL byte raises an unchecked {@code
   * InvalidPathException} that would otherwise leave either surface as a 500 about a caller's typo.
   */
  @Test
  void a_root_that_is_not_a_path_at_all_is_the_same_400_on_both_surfaces() throws Exception {
    MockHttpServletResponse http =
        posted("/v1/projects/payments/lend", "{\"roots\": [\"/srv/sh\\u0000ared\"]}");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_LEND,
            "{\"project\": \"payments\", \"roots\": [\"/srv/sh\\u0000ared\"]}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(projects, never()).lend(anyString(), any());
  }

  /**
   * A frame that names no project is refused before the store is touched, and this is the one
   * refusal on this area with no HTTP half to compare.
   *
   * <p><b>Not a gap in the parity.</b> A URL that names no project is a different URL — Spring
   * answers it with its own 404 before {@code ProjectController} is reached — so "you named none"
   * is a request only this surface can receive. {@code Payloads.required} is where it is answered,
   * so that the code still comes out of {@code Faults} rather than being a status this handler
   * picked.
   */
  @Test
  void a_lend_frame_that_names_no_project_is_refused_before_anything_is_written() {
    Outcome outcome =
        route(
            FrameTypes.PROJECT_LEND,
            """
                {"roots": ["/srv/shared"]}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertTrue(
        outcome.said().contains("project"),
        "and the sentence names the field that was left out: " + outcome.said());
    verify(projects, never()).lend(anyString(), any());
  }

  // --- project.unlend ------------------------------------------------------

  /** Both surfaces take the same roots back and answer with what is left. */
  @Test
  void both_surfaces_unlend_the_same_roots_and_answer_what_is_left() throws Exception {
    ProjectRecord row = new ProjectRecord("payments", REPO, List.of(), List.of());
    when(projects.unlend(anyString(), any())).thenReturn(row);
    when(projects.effectiveExclusions(row)).thenReturn(List.of(Path.of("/opt/plowshare")));

    MockHttpServletResponse http =
        posted(
            "/v1/projects/payments/unlend",
            """
                {"roots": ["/srv/shared"]}""");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_UNLEND,
            """
                {"project": "payments", "roots": ["/srv/shared"]}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(projects, times(2)).unlend("payments", List.of(SHARED));
  }

  /**
   * An unlend that names no directory is refused in the same words as a lend that does, on both
   * surfaces.
   *
   * <p>The two verbs share {@code RequestedPaths.roots} and therefore share one sentence — which is
   * worth measuring on the second surface too, since a handler that had written its own would have
   * been the place the two spellings appeared.
   */
  @Test
  void an_unlend_that_names_no_directory_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = posted("/v1/projects/payments/unlend", "{}");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_UNLEND,
            """
                {"project": "payments"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(projects, never()).unlend(anyString(), any());
  }

  // --- project.workspace ---------------------------------------------------

  /**
   * Both surfaces point the project at the same directory, through the one store call that keeps
   * the exclusions it already had.
   */
  @Test
  void both_surfaces_move_the_workspace_through_the_same_store_call() throws Exception {
    ProjectRecord row = new ProjectRecord("payments", MOVED, List.of(), List.of());
    when(projects.moveWorkspace(anyString(), any())).thenReturn(row);
    when(projects.effectiveExclusions(row)).thenReturn(List.of(Path.of("/opt/plowshare")));

    MockHttpServletResponse http =
        posted(
            "/v1/projects/payments/workspace",
            """
                {"workspace": "/srv/moved"}""");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_WORKSPACE,
            """
                {"project": "payments", "workspace": "/srv/moved"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(projects, times(2)).moveWorkspace("payments", MOVED);
  }

  /**
   * A request with no workspace is refused in the same words, and nothing is written on either
   * surface.
   */
  @Test
  void a_workspace_move_with_no_workspace_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = posted("/v1/projects/payments/workspace", "{}");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_WORKSPACE,
            """
                {"project": "payments"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(projects, never()).moveWorkspace(anyString(), any());
  }

  /**
   * A project that has no workspace is the same 404 in the same words — this endpoint does not
   * upsert, and does not on either surface.
   */
  @Test
  void moving_the_workspace_of_a_project_that_has_none_is_the_same_404_on_both_surfaces()
      throws Exception {
    when(projects.moveWorkspace(anyString(), any()))
        .thenThrow(new ArchiveException("no project named payments has a workspace"));

    MockHttpServletResponse http =
        posted(
            "/v1/projects/payments/workspace",
            """
                {"workspace": "/srv/moved"}""");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_WORKSPACE,
            """
                {"project": "payments", "workspace": "/srv/moved"}""");

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- project.move --------------------------------------------------------

  /**
   * Both surfaces rename the same project to the same name, and both answer with no content at all.
   *
   * <p><b>204 and not 200</b>, which is the thing a second surface is likeliest to round off:
   * {@code Outcome.ok()} would have been a well-formed answer carrying a different promise, since
   * nothing about the project is new except what it is called.
   */
  @Test
  void both_surfaces_rename_the_project_and_answer_with_no_content() throws Exception {
    MockHttpServletResponse http =
        posted(
            "/v1/projects/payments/move",
            """
                {"to": "bench.local/srv/payments/payments"}""");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_MOVE,
            """
                {"project": "payments", "to": "bench.local/srv/payments/payments"}""");

    assertEquals(Code.NO_CONTENT, outcome.code());
    FrameParity.assertSameEmptyAnswer(http, outcome);
    verify(projects, times(2)).rename("payments", "bench.local/srv/payments/payments");
  }

  /**
   * A move that names no destination is refused in the same words, and nothing is renamed on either
   * surface.
   */
  @Test
  void a_move_with_no_destination_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = posted("/v1/projects/payments/move", "{}");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_MOVE,
            """
                {"project": "payments"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(projects, never()).rename(anyString(), anyString());
  }

  /**
   * A project a live session roots is refused on both surfaces, in a sentence naming the machine to
   * go and close a client on.
   *
   * <p><b>The refusal a frame surface could most easily have lost.</b> It is not the store's and
   * not a request shape's: it is {@code archive.Projects.refuseIfRooted}, reached from the
   * controller's own constructor-built wrapper, and a handler that had simply called {@code
   * ProjectStore.rename} would have moved a project out from under a live session with nothing
   * failing anywhere.
   */
  @Test
  void moving_a_project_a_live_presence_roots_is_the_same_refusal_on_both_surfaces()
      throws Exception {
    presences.declare(new Presence("bench", "bench.local", "/srv/payments", "payments"));

    MockHttpServletResponse http =
        posted(
            "/v1/projects/payments/move",
            """
                {"to": "moved"}""");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_MOVE,
            """
                {"project": "payments", "to": "moved"}""");

    assertEquals(Code.CONFLICT, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(
        outcome.said().contains("bench.local"),
        "and names the machine to close: " + outcome.said());
    verify(projects, never()).rename(anyString(), anyString());
  }

  /**
   * And the destination end too, which the uniqueness constraint cannot catch — a project a live
   * session roots need have no row at all.
   */
  @Test
  void moving_a_project_onto_a_name_a_live_presence_roots_is_refused_on_both_surfaces()
      throws Exception {
    presences.declare(new Presence("bench", "bench.local", "/srv/ledger", "ledger"));

    MockHttpServletResponse http =
        posted(
            "/v1/projects/payments/move",
            """
                {"to": "ledger"}""");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_MOVE,
            """
                {"project": "payments", "to": "ledger"}""");

    assertEquals(Code.CONFLICT, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(projects, never()).rename(anyString(), anyString());
  }

  /**
   * A name another project already holds is the same 409 in the same words — the store's own
   * refusal, travelling as the exception it is.
   */
  @Test
  void moving_a_project_onto_a_name_that_is_taken_is_the_same_409_on_both_surfaces()
      throws Exception {
    doThrow(new ArchiveRefusedException("a project is already called ledger"))
        .when(projects)
        .rename(anyString(), anyString());

    MockHttpServletResponse http =
        posted(
            "/v1/projects/payments/move",
            """
                {"to": "ledger"}""");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_MOVE,
            """
                {"project": "payments", "to": "ledger"}""");

    assertEquals(Code.CONFLICT, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- project.forget ------------------------------------------------------

  /**
   * Both surfaces drop the same project's workspace and answer with no content: there is no leash
   * left to describe.
   */
  @Test
  void both_surfaces_forget_the_project_and_answer_with_no_content() throws Exception {
    MockHttpServletResponse http = dropped("/v1/projects/payments");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_FORGET,
            """
                {"project": "payments"}""");

    assertEquals(Code.NO_CONTENT, outcome.code());
    FrameParity.assertSameEmptyAnswer(http, outcome);
    verify(projects, times(2)).forget("payments");
  }

  /**
   * A project that has no workspace is the same 404 in the same words, so that a mistyped name
   * cannot read as a workspace successfully removed on either surface.
   */
  @Test
  void forgetting_a_project_that_has_no_workspace_is_the_same_404_on_both_surfaces()
      throws Exception {
    doThrow(new ArchiveException("no project named payments has a workspace"))
        .when(projects)
        .forget(anyString());

    MockHttpServletResponse http = dropped("/v1/projects/payments");
    Outcome outcome =
        route(
            FrameTypes.PROJECT_FORGET,
            """
                {"project": "payments"}""");

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- the surface's own two rules, for all seven types ---------------------

  /**
   * Every type this area claims ignores a field this build has never heard of — spec §3.2's "the
   * payload is tolerant", asserted once per type.
   *
   * <p>Over all seven and not the six this file adds: the rule is the area's, and a list that
   * stopped at the endpoints one task happened to write would be a list that quietly excused the
   * pilot.
   */
  @Test
  void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of() throws Exception {
    ProjectRecord row = new ProjectRecord("payments", REPO, List.of(), List.of());
    when(projects.defineLending(
            anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(row);
    when(projects.lend(anyString(), any())).thenReturn(row);
    when(projects.unlend(anyString(), any())).thenReturn(row);
    when(projects.moveWorkspace(anyString(), any())).thenReturn(row);

    for (Map.Entry<String, String> each : representativePayloads().entrySet()) {
      FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
    }
  }

  /**
   * The production routing table really does claim all seven of this controller's types.
   *
   * <p>Built from every {@link FrameArea} Spring would collect: an unregistered type is answered
   * with a perfectly well-formed {@code NOT_FOUND}, so a handler nobody wired looks from the
   * outside exactly like a handler nobody wrote.
   */
  @Test
  void the_production_routing_table_claims_every_project_type() {
    FrameRouter wired = FrameAreas.router();

    for (String type : representativePayloads().keySet()) {
      assertTrue(
          wired.types().contains(type), type + " is not in the production table: " + wired.types());
    }
  }

  /** One payload per type this area claims, good enough to reach the handler. */
  private static Map<String, String> representativePayloads() {
    return Map.ofEntries(
        Map.entry(FrameTypes.PROJECT_LIST, "{}"),
        Map.entry(FrameTypes.PROJECT_DEFINE, "{\"name\":\"payments\",\"workspace\":\"/srv/repo\"}"),
        Map.entry(
            FrameTypes.PROJECT_LEND, "{\"project\":\"payments\",\"roots\":[\"/srv/shared\"]}"),
        Map.entry(
            FrameTypes.PROJECT_UNLEND, "{\"project\":\"payments\",\"roots\":[\"/srv/shared\"]}"),
        Map.entry(
            FrameTypes.PROJECT_WORKSPACE,
            "{\"project\":\"payments\",\"workspace\":\"/srv/moved\"}"),
        Map.entry(FrameTypes.PROJECT_MOVE, "{\"project\":\"payments\",\"to\":\"ledger\"}"),
        Map.entry(FrameTypes.PROJECT_FORGET, "{\"project\":\"payments\"}"));
  }

  // --- driving the two surfaces off one request ----------------------------

  private Outcome route(String type, String payload) {
    return router.route(FrameParity.frame(type, payload), FrameParity.ASKING);
  }

  private MockHttpServletResponse read(String path) throws Exception {
    return mvc.perform(get(path)).andReturn().getResponse();
  }

  private MockHttpServletResponse posted(String path, String body) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse dropped(String path) throws Exception {
    return mvc.perform(delete(path)).andReturn().getResponse();
  }
}
