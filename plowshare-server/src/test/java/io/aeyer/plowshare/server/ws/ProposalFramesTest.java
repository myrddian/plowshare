package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.api.ProposalController;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.archive.Proposal;
import io.aeyer.plowshare.server.archive.ProposalState;
import io.aeyer.plowshare.server.archive.ProposalStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * All three endpoints {@code ProposalController} answers, driven twice — once over HTTP and once as
 * a frame — off one request, asserting that the two surfaces said the same thing.
 *
 * <h2>One mocked {@link PromotionQueue}, shared by both surfaces</h2>
 *
 * <p>Matching {@code ProposalControllerTest}: what approving really does — the claim taken before
 * the promotion, the claim released when the promotion is refused, the unique index that stops two
 * passes promoting one memory twice — is {@code PromotionQueueTest}'s subject against a real
 * Postgres. What is under test here is which method each surface calls, with which arguments, and
 * what each does with what comes back, including with what is thrown.
 *
 * <p><b>That mock is also why this controller's two refusals went to {@code
 * requests.RequestedResolution} rather than into the queue.</b> A rule inside a mocked method is a
 * rule neither surface runs, so the comparison below would have been two surfaces agreeing about a
 * sentence nothing said. In {@code requests} both surfaces really refuse, and {@link
 * FrameParity#assertSameRefusal} compares the words.
 *
 * <p><b>Nothing in this controller's constructor is runtime state.</b> It takes one queue, which
 * holds a store and an archive and no session table — the check {@code ProjectController}'s {@code
 * PresenceRegistry} made compulsory.
 */
class ProposalFramesTest {

  private static final Instant WHEN = Instant.parse("2026-08-29T12:00:00Z");

  private PromotionQueue queue;
  private MockMvc mvc;
  private FrameRouter router;

  @BeforeEach
  void setUp() {
    queue = mock(PromotionQueue.class);
    // FrameParity.endpointsOf and not a bare standaloneSetup: a proposal
    // carries a createdAt, and the bare harness renders an Instant as a
    // number where a deployed server sends an ISO string.
    mvc = FrameParity.endpointsOf(new ProposalController(queue));
    router = new FrameRoutingConfig().frameRouter(List.of(new ProposalFrames(queue)));
  }

  // --- proposal.list -------------------------------------------------------

  /** Both surfaces list one tier's queue, and both ask for that tier. */
  @Test
  void both_surfaces_list_the_same_tiers_queue() throws Exception {
    when(queue.waiting(Home.of("payments")))
        .thenReturn(List.of(waiting("prp_000001", "mem_000001")));

    MockHttpServletResponse http = read("payments");
    Outcome outcome = route(FrameTypes.PROPOSAL_LIST, "{\"project\":\"payments\"}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(queue, times(2)).waiting(Home.of("payments"));
  }

  /**
   * No project is the global queue on both surfaces, and that is the boundary of the refusal below
   * rather than an oversight.
   */
  @Test
  void a_listing_that_names_no_tier_is_the_global_queue_on_both_surfaces() throws Exception {
    when(queue.waiting(Home.global())).thenReturn(List.of());

    MockHttpServletResponse http = read(null);
    Outcome outcome = route(FrameTypes.PROPOSAL_LIST, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(queue, times(2)).waiting(Home.global());
  }

  /**
   * A blank project is refused in the same words rather than read as global, and the queue is never
   * asked on either surface.
   */
  @Test
  void a_blank_tier_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = read("  ");
    Outcome outcome = route(FrameTypes.PROPOSAL_LIST, "{\"project\":\"  \"}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(queue, never()).waiting(any());
  }

  // --- proposal.reconsider -------------------------------------------------

  /**
   * Both surfaces re-open the curator's own keeps, in one tier, by the same two constants.
   *
   * <p>Verified as well as compared: the predicate is what makes this the escape hatch for a wrong
   * {@code keep} rather than a re-open of everything a person already settled, and it is not
   * visible in either answer.
   */
  @Test
  void both_surfaces_reopen_the_curators_own_rulings() throws Exception {
    when(queue.reconsider(Home.of("payments"), Curator.BY, Curator.KEPT))
        .thenReturn(new PromotionQueue.Reconsidered(List.of("prp_000001"), List.of("prp_000002")));

    MockHttpServletResponse http = reconsidered("payments");
    Outcome outcome = route(FrameTypes.PROPOSAL_RECONSIDER, "{\"project\":\"payments\"}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(queue, times(2)).reconsider(Home.of("payments"), Curator.BY, Curator.KEPT);
  }

  @Test
  void reconsidering_a_blank_tier_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = reconsidered(" ");
    Outcome outcome = route(FrameTypes.PROPOSAL_RECONSIDER, "{\"project\":\" \"}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(queue, never()).reconsider(any(), anyString(), anyString());
  }

  // --- proposal.resolve ----------------------------------------------------

  /**
   * Accepting promotes on both surfaces, and both answer with the global record the claim now lives
   * in and what fell out to make room.
   */
  @Test
  void both_surfaces_promote_and_name_the_global_record() throws Exception {
    when(queue.approve("prp_000001", "enzo"))
        .thenReturn(
            new PromotionQueue.Approval(
                settled("prp_000001", ProposalState.ACCEPTED, "enzo"),
                new Archive.Promotion(global("mem_000009"), List.of("mem_000002"))));

    MockHttpServletResponse http =
        posted(
            "/v1/proposals/prp_000001/resolve",
            """
                {"accept": true, "by": "enzo"}""");
    Outcome outcome =
        route(
            FrameTypes.PROPOSAL_RESOLVE,
            """
                {"proposal": "prp_000001", "accept": true, "by": "enzo"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(queue, times(2)).approve("prp_000001", "enzo");
  }

  /**
   * Rejecting promotes nothing on either surface, and both say so the same way — a null {@code
   * promotedId} and an empty {@code demoted} rather than an absent one.
   *
   * <p>The branch this endpoint's body really is: two service methods and two answer shapes, chosen
   * by a field the caller sent. It stayed a branch when this controller's throws came down, and
   * these two tests are why that is safe — a handler that took the wrong arm fails one of them
   * rather than drifting in silence.
   */
  @Test
  void both_surfaces_record_the_refusal_and_promote_nothing() throws Exception {
    when(queue.reject("prp_000001", "too niche for global", "enzo"))
        .thenReturn(settled("prp_000001", ProposalState.REJECTED, "enzo"));

    MockHttpServletResponse http =
        posted(
            "/v1/proposals/prp_000001/resolve",
            """
                {"accept": false, "reason": "too niche for global", "by": "enzo"}""");
    Outcome outcome =
        route(
            FrameTypes.PROPOSAL_RESOLVE,
            """
                {"proposal": "prp_000001", "accept": false,
                 "reason": "too niche for global", "by": "enzo"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(queue, never()).approve(anyString(), anyString());
  }

  /**
   * A settlement that says neither way is the same refusal on both surfaces, and nothing is settled
   * on either.
   */
  @Test
  void a_settlement_that_names_no_direction_is_the_same_refusal_on_both_surfaces()
      throws Exception {
    MockHttpServletResponse http =
        posted(
            "/v1/proposals/prp_000001/resolve",
            """
                {"by": "enzo"}""");
    Outcome outcome =
        route(
            FrameTypes.PROPOSAL_RESOLVE,
            """
                {"proposal": "prp_000001", "by": "enzo"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(outcome.said().contains("never re-opened"), outcome.said());
    verify(queue, never()).approve(anyString(), anyString());
    verify(queue, never()).reject(anyString(), any(), anyString());
  }

  /** A settlement nobody signed is the same refusal on both surfaces. */
  @Test
  void a_settlement_nobody_signed_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http =
        posted(
            "/v1/proposals/prp_000001/resolve",
            """
                {"accept": true, "by": "  "}""");
    Outcome outcome =
        route(
            FrameTypes.PROPOSAL_RESOLVE,
            """
                {"proposal": "prp_000001", "accept": true, "by": "  "}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(outcome.said().contains("who decided"), outcome.said());
    verify(queue, never()).approve(anyString(), anyString());
  }

  /**
   * A proposal the archive will not settle is the same 409 in the same words on both surfaces.
   *
   * <p>The refusal that has to reach the person who clicked accept: between proposal and resolution
   * the memory may have been superseded, and promoting a fact that stopped being true is the worst
   * thing this queue could do, because global is the tier every project reads.
   */
  @Test
  void a_proposal_the_archive_refuses_is_the_same_conflict_on_both_surfaces() throws Exception {
    when(queue.approve("prp_000001", "enzo"))
        .thenThrow(
            new ArchiveRefusedException("memory mem_000001 is superseded and cannot be promoted"));

    MockHttpServletResponse http =
        posted(
            "/v1/proposals/prp_000001/resolve",
            """
                {"accept": true, "by": "enzo"}""");
    Outcome outcome =
        route(
            FrameTypes.PROPOSAL_RESOLVE,
            """
                {"proposal": "prp_000001", "accept": true, "by": "enzo"}""");

    assertEquals(Code.CONFLICT, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  /**
   * A frame that names no proposal is refused by this surface alone, and says which field to send.
   *
   * <p>A URL naming no proposal is a different URL and Spring answers it before a controller is
   * reached, so there is nothing on the HTTP side to compare this to — which is exactly why the
   * sentence has to be good.
   */
  @Test
  void a_frame_that_names_no_proposal_says_which_field_to_send() {
    Outcome outcome =
        route(
            FrameTypes.PROPOSAL_RESOLVE,
            """
                {"accept": true, "by": "enzo"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertTrue(
        outcome.said().contains("proposal.resolve needs its payload to say which"), outcome.said());
    verify(queue, never()).approve(anyString(), anyString());
  }

  // --- the surface's own two rules, for all three types ---------------------

  @Test
  void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of() throws Exception {
    when(queue.waiting(any())).thenReturn(List.of());
    when(queue.reconsider(any(), anyString(), anyString()))
        .thenReturn(new PromotionQueue.Reconsidered(List.of(), List.of()));
    when(queue.reject(anyString(), any(), anyString()))
        .thenReturn(settled("prp_000001", ProposalState.REJECTED, "enzo"));

    for (Map.Entry<String, String> each : representativePayloads().entrySet()) {
      FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
    }
  }

  @Test
  void the_production_routing_table_claims_every_proposal_type() {
    FrameRouter wired = FrameAreas.router();

    for (String type : representativePayloads().keySet()) {
      assertTrue(
          wired.types().contains(type), type + " is not in the production table: " + wired.types());
    }
  }

  /** One payload per type this area claims, good enough to reach the handler. */
  private static Map<String, String> representativePayloads() {
    return Map.of(
        FrameTypes.PROPOSAL_LIST,
        "{\"project\":\"payments\"}",
        FrameTypes.PROPOSAL_RECONSIDER,
        "{\"project\":\"payments\"}",
        FrameTypes.PROPOSAL_RESOLVE,
        "{\"proposal\":\"prp_000001\",\"accept\":false,\"by\":\"enzo\"}");
  }

  // --- fixtures ------------------------------------------------------------

  private static Proposal waiting(String id, String memoryId) {
    return new Proposal(
        id,
        memoryId,
        Home.of("payments"),
        ProposalStore.PROMOTE,
        "it holds for every project",
        ProposalState.PENDING,
        WHEN,
        "a-colleague",
        null,
        null,
        null);
  }

  private static Proposal settled(String id, ProposalState state, String by) {
    return new Proposal(
        id,
        "mem_000001",
        Home.of("payments"),
        ProposalStore.PROMOTE,
        "it holds for every project",
        state,
        WHEN,
        "a-colleague",
        WHEN,
        by,
        "settled");
  }

  private static Memory global(String id) {
    return Memory.formed(
        id,
        "The retry budget is 4 attempts",
        "calling payments",
        "Four attempts since the timeout change.",
        new Provenance(WHEN, "curator", "promoted from project 'payments'"),
        Home.global());
  }

  // --- driving the two surfaces off one request ----------------------------

  private Outcome route(String type, String payload) {
    return router.route(FrameParity.frame(type, payload), FrameParity.ASKING);
  }

  /**
   * A read of the queue, with the tier as a real request parameter.
   *
   * <p><b>{@code .param} and not a query string in the path</b>: the standalone builder does not
   * percent-decode one, so a blank tier written as {@code ?project=%20} would reach the controller
   * as the literal three characters and prove nothing about blankness.
   */
  private MockHttpServletResponse read(String project) throws Exception {
    MockHttpServletRequestBuilder asked = get("/v1/proposals");
    if (project != null) {
      asked.param("project", project);
    }
    return mvc.perform(asked).andReturn().getResponse();
  }

  /** The same, for the re-open, which also takes its tier as a parameter. */
  private MockHttpServletResponse reconsidered(String project) throws Exception {
    MockHttpServletRequestBuilder asked = post("/v1/proposals/reconsider");
    if (project != null) {
      asked.param("project", project);
    }
    return mvc.perform(asked).andReturn().getResponse();
  }

  private MockHttpServletResponse posted(String path, String body) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }
}
