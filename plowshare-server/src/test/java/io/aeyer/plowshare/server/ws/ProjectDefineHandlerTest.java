package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ApiExceptionHandler;
import io.aeyer.plowshare.server.api.ProjectController;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The write pilot, measured as a parity test: {@code POST /v1/projects} and the
 * {@code project.define} frame are driven through the same request text, and
 * each assertion below is that the two surfaces answered the same thing.
 *
 * <h2>Why every test drives both surfaces rather than asserting the frame alone</h2>
 *
 * <p>A frame test that asserted only what the handler answers would be green on
 * the day the two surfaces stopped agreeing, which is the one failure this
 * slice exists to prevent — §4's strangler leaves both surfaces live for as
 * long as a client needs HTTP, and a caller that gets a different answer
 * depending on which one it asked is worse than a caller that has only one.
 * <b>The refusals are where that drift would actually happen</b>: a happy path
 * agrees by construction, because both surfaces hand the same arguments to the
 * same store method and render the same {@code ProjectView} from what it
 * returns. A refusal agrees only if the exception really does travel out of the
 * shared code untouched and get classified in one place, so the refusals are
 * asserted down to the sentence rather than to the status.
 *
 * <p><b>One mocked {@link ProjectStore}, shared by both surfaces</b>, matching
 * {@code ProjectControllerTest}: what the store does with a row is {@code
 * ProjectStoreTest}'s subject against a real database. What is under test here
 * is which method each surface calls, with which arguments, and what each does
 * with what comes back — including what it does with what is thrown.
 *
 * <p><b>No Spring context and no socket.</b> {@link FrameRouter} needs neither,
 * and {@code EventChannelTest} already measures everything between a socket and
 * an {@link Outcome}; this file is about which {@link Outcome}.
 */
class ProjectDefineHandlerTest {

    private static final Path REPO = Path.of("/srv/repo");
    private static final Path SHARED = Path.of("/srv/shared");

    private ProjectStore projects;
    private MockMvc mvc;
    private FrameRouter router;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectStore.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ProjectController(projects, new PresenceRegistry()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        // Through the area rather than through a literal map: this is the
        // registration a breadth task adds to, and a sibling area adding a type
        // of its own changes nothing about this line.
        router = new FrameRoutingConfig()
                .frameRouter(List.of(new ProjectFrames(projects, new PresenceRegistry(), org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.ProjectMembers.class), new io.aeyer.plowshare.server.auth.AuthProperties())));
    }

    // --- the write itself ----------------------------------------------------

    /**
     * Both surfaces write the same row and answer the same view.
     *
     * <p><b>The store call is verified twice with the same arguments</b>, and
     * that is the half of the parity a body comparison cannot see: two surfaces
     * could answer identical JSON while one of them lent a directory the other
     * did not, since the answer is rendered from what the store returns and the
     * store here is a mock that returns the same row either way.
     */
    @Test
    void both_surfaces_write_the_same_row_and_answer_the_same_view() throws Exception {
        String asked = """
                {"name": "payments", "workspace": "/srv/repo", "lent": ["/srv/shared"]}""";
        ProjectRecord row = new ProjectRecord("payments", REPO, List.of(SHARED), List.of());
        when(projects.defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenReturn(row);
        when(projects.effectiveExclusions(row)).thenReturn(List.of(Path.of("/opt/plowshare")));

        MockHttpServletResponse http = over(asked);
        Outcome outcome = router.route(frame(asked), FrameParity.ASKING);

        assertEquals(Code.OK, outcome.code());
        assertNotNull(outcome.payload(), "a define answers with the leash it just set");
        FrameParity.assertSameAnswer(http, outcome);
        verify(projects, times(2))
                .defineLending("payments", REPO, List.of(SHARED), List.of(), null);
    }

    /**
     * An omitted {@code exclusions} means "fence off nothing extra" on the
     * frame exactly as it does on the endpoint — both reach {@code
     * RequestedPaths.each}, which is where an absent list becomes an empty one
     * rather than the null the store refuses.
     */
    @Test
    void an_omitted_list_is_the_empty_list_on_both_surfaces() throws Exception {
        String asked = """
                {"name": "payments", "workspace": "/srv/repo"}""";
        ProjectRecord row = new ProjectRecord("payments", REPO, List.of(), List.of());
        when(projects.defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenReturn(row);
        when(projects.effectiveExclusions(row)).thenReturn(List.of());

        MockHttpServletResponse http = over(asked);
        Outcome outcome = router.route(frame(asked), FrameParity.ASKING);

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(projects, times(2)).defineLending("payments", REPO, List.of(), List.of(), null);
    }

    // --- the refusals, which is where two surfaces drift ---------------------

    /**
     * A definition with no workspace is refused in the same words on both
     * surfaces, and nothing is written on either.
     *
     * <p>The refusal itself is {@code RequestedPaths.workspace}'s, which lives
     * in {@code requests/} precisely so that a dispatcher reaching it off a
     * socket makes the same refusal as the controller rather than a second one
     * shaped like it.
     */
    @Test
    void a_definition_with_no_workspace_is_refused_in_the_same_words_on_both_surfaces()
            throws Exception {
        String asked = """
                {"name": "payments"}""";

        MockHttpServletResponse http = over(asked);
        Outcome outcome = router.route(frame(asked), FrameParity.ASKING);

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        verify(projects, never()).defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * A workspace the store refuses is the same 422 with the same sentence on
     * both surfaces — the domain rule behind this endpoint, travelling as the
     * exception it is.
     *
     * <p><b>This is the assertion that {@code Faults} really is the one
     * mapping.</b> {@code ValidationException} is not a status the handler
     * chose: it is thrown below both surfaces and classified in the one table,
     * so a handler that had caught it and picked a code of its own would fail
     * here rather than in whatever the second mapping first disagreed about.
     */
    @Test
    void a_workspace_the_store_refuses_is_the_same_refusal_on_both_surfaces() throws Exception {
        String asked = """
                {"name": "payments", "workspace": "/srv/repo"}""";
        when(projects.defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenThrow(
                new ValidationException("project 'payments' cannot take workspace /srv/repo:"
                        + " it is not a directory"));

        MockHttpServletResponse http = over(asked);
        Outcome outcome = router.route(frame(asked), FrameParity.ASKING);

        assertEquals(Code.VALIDATION_FAILED, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
    }

    /**
     * A payload whose {@code lent} is a string rather than a list is a caller
     * fault on both surfaces, and not a 500 on either.
     *
     * <p><b>The status is asserted and the sentence is not, and that is a
     * property of the two surfaces rather than a weaker test.</b> A body that
     * cannot be bound at all never reaches {@code ApiExceptionHandler}'s own
     * rows — Spring answers it from {@code ResponseEntityExceptionHandler} with
     * a problem-detail body that has no {@code detail} of this surface's making
     * — so there is no server sentence on the HTTP side to compare one to. What
     * both surfaces do agree on is that a payload of the wrong shape is the
     * caller's mistake: the frame's own decode failure is translated to a
     * {@code CallerFault} for exactly that reason, rather than being left as
     * the {@code IllegalArgumentException} that {@code Faults} would have had
     * to call an internal error.
     */
    @Test
    void a_payload_of_the_wrong_shape_is_a_caller_fault_on_both_surfaces() throws Exception {
        String asked = """
                {"name": "payments", "workspace": "/srv/repo", "lent": "/srv/shared"}""";

        MockHttpServletResponse http = over(asked);
        Outcome outcome = router.route(frame(asked), FrameParity.ASKING);

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals(outcome.code().httpStatus(), http.getStatus());
        assertNotNull(outcome.said(), "and says what was wrong with it");
        verify(projects, never()).defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * A payload carrying a field this build has never heard of is answered
     * exactly as one without it — spec §3.2's "the payload is tolerant",
     * asserted for this type.
     *
     * <p>One line, from {@code FrameParity}, because it is a line every one of
     * the breadth plan's parity tests should carry: the endpoint is never sent
     * the extra field, so no two-surface comparison can see a handler that
     * refuses it.
     */
    @Test
    void a_payload_field_this_build_has_never_heard_of_is_ignored() throws Exception {
        ProjectRecord row = new ProjectRecord("payments", REPO, List.of(), List.of());
        when(projects.defineLending(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenReturn(row);
        when(projects.effectiveExclusions(row)).thenReturn(List.of());

        FrameParity.assertUnknownFieldsAreIgnored(router, FrameTypes.PROJECT_DEFINE, """
                {"name": "payments", "workspace": "/srv/repo"}""");
    }

    // --- the wiring ----------------------------------------------------------

    /**
     * The production routing table really does claim this type.
     *
     * <p>Built here from {@link FrameRoutingConfig} itself rather than from a
     * literal map, because the handler being correct says nothing about whether
     * anything registered it: the surface's answer to an unregistered type is a
     * perfectly well-formed {@code NOT_FOUND}, so a pilot that was never wired
     * would look from the outside exactly like a pilot that was never written.
     *
     * <p><b>What this cannot reach is the component scan.</b> That {@link
     * FrameRoutingConfig} is found at boot at all is only measured by the
     * end-to-end suites, which need a database; this measures the table it
     * builds once found.
     */
    @Test
    void the_production_routing_table_claims_project_define() {
        // Every area Spring would collect, built over mocks by FrameAreas --
        // so a sibling task adding an area of its own neither breaks this line
        // nor has to be named in it. The refusal below is RequestedPaths',
        // reached before the mocked store behind the handler is touched.
        FrameRouter wired = FrameAreas.router();

        Outcome outcome = wired.route(frame("""
                {"name": "payments"}"""), FrameParity.ASKING);

        assertEquals(Code.BAD_REQUEST, outcome.code(),
                "a registered type reaches its handler and is refused by it, rather than"
                        + " answering the NOT_FOUND an unregistered type would");
    }

    // --- driving the two surfaces through one request text -------------------

    /** The request over HTTP. */
    private MockHttpServletResponse over(String body) throws Exception {
        return mvc.perform(post("/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    /** The same request as a frame, through the builder every parity test
     *  shares — the body text is dropped in unchanged, so the two surfaces are
     *  driven by one string and not by two spellings of one intent. */
    private static String frame(String payload) {
        return FrameParity.frame(FrameTypes.PROJECT_DEFINE, payload);
    }
}
