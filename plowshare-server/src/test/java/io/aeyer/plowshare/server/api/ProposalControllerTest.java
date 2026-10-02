package io.aeyer.plowshare.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.archive.Proposal;
import io.aeyer.plowshare.server.archive.ProposalState;
import io.aeyer.plowshare.server.archive.ProposalStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The queue a person answers, over HTTP.
 *
 * <p>A pure MVC test against a mocked {@link PromotionQueue}. What approving
 * <em>does</em> — the claim taken before the promotion, the claim released when
 * the promotion is refused, the unique index that stops two passes promoting one
 * memory twice — is {@code PromotionQueueTest}'s and {@code ProposalStoreTest}'s
 * subject, against a real Postgres. This class asks the narrower question: does
 * each endpoint reach the queue with what the caller sent, and does what comes
 * back tell a person what happened.
 */
class ProposalControllerTest {

    private static final Instant WHEN = Instant.parse("2026-08-29T12:00:00Z");

    private PromotionQueue queue;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queue = mock(PromotionQueue.class);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new ProposalController(queue))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    // --- GET /v1/proposals --------------------------------------------------------

    /**
     * One tier at a time, like every other read in this archive: a person
     * settling one project's queue must not be handed another project's.
     */
    @Test
    void the_queue_is_listed_for_one_tier_at_a_time() throws Exception {
        when(queue.waiting(Home.of("payments"))).thenReturn(List.of(
                waiting("prp_000001", "mem_000001", "it holds for every project")));

        mvc.perform(get("/v1/proposals").param("project", "payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("prp_000001"))
                .andExpect(jsonPath("$[0].memoryId").value("mem_000001"))
                .andExpect(jsonPath("$[0].project").value("payments"))
                .andExpect(jsonPath("$[0].state").value("pending"))
                .andExpect(jsonPath("$[0].reason").value("it holds for every project"));
    }

    /**
     * Who asked travels to the wire, beside who answered.
     *
     * <p>The two are adjacent nullable strings on the row and on {@link
     * ProposalView}, so the fixture names the proposer something that appears
     * nowhere else in it: an assertion that passed on {@code resolvedBy} would
     * be measuring the wrong column, and a waiting row's {@code resolvedBy} is
     * null, which is what a mis-wired view would hand back here.
     */
    @Test
    void the_queue_says_who_asked_as_well_as_who_answered() throws Exception {
        when(queue.waiting(Home.of("payments"))).thenReturn(List.of(
                waiting("prp_000001", "mem_000001", "it holds for every project")));

        mvc.perform(get("/v1/proposals").param("project", "payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].proposedBy").value("a-colleague"))
                .andExpect(jsonPath("$[0].resolvedBy").doesNotExist());
    }

    /** No project means global, and the tier is flattened to a string rather
     *  than nesting Home's single-field record — matching the `project`
     *  parameter every other endpoint takes. */
    @Test
    void the_global_queue_is_the_one_with_no_project() throws Exception {
        when(queue.waiting(Home.global())).thenReturn(List.of());

        mvc.perform(get("/v1/proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(queue).waiting(Home.global());
    }

    @Test
    void a_blank_project_is_refused_rather_than_read_as_global() throws Exception {
        mvc.perform(get("/v1/proposals").param("project", "  "))
                .andExpect(status().isBadRequest());

        verify(queue, never()).waiting(any());
    }

    // --- POST /v1/proposals/reconsider ---------------------------------------------

    /**
     * The endpoint asks for the curator's own keeps, by name, in one tier.
     *
     * <p><b>This is the whole answer to "who may pull it".</b> {@link
     * PromotionQueue#reconsider} takes the settler and the account as arguments
     * and never learns what a curator is, so the pair {@code Curator.BY} /
     * {@code Curator.KEPT} is named here and nowhere else — which is what makes
     * the operation an operator's rather than something an agent could reach.
     * The captor asserts both constants rather than {@code anyString()}: a
     * controller that passed a settler and a blank account would re-open every
     * unsigned rejection in the tier, and {@code anyString()} would pass it.
     */
    @Test
    void reconsidering_asks_for_the_curators_own_rulings_in_one_tier() throws Exception {
        when(queue.reconsider(Home.of("payments"), Curator.BY, Curator.KEPT))
                .thenReturn(new PromotionQueue.Reconsidered(List.of("prp_000001"),
                        List.of("prp_000002 (the waiting place was taken)")));

        mvc.perform(post("/v1/proposals/reconsider").param("project", "payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reopened[0]").value("prp_000001"))
                .andExpect(jsonPath("$.refused[0]")
                        .value(containsString("the waiting place was taken")));

        verify(queue).reconsider(Home.of("payments"), Curator.BY, Curator.KEPT);
    }

    /** A blank project is refused rather than read as global, exactly as the
     *  listing above refuses one: this operation writes, so reading a mistyped
     *  tier as the one every project shares is worse here than there. */
    @Test
    void reconsidering_a_blank_project_is_refused_rather_than_read_as_global()
            throws Exception {
        mvc.perform(post("/v1/proposals/reconsider").param("project", "  "))
                .andExpect(status().isBadRequest());

        verify(queue, never()).reconsider(any(), anyString(), anyString());
    }

    // --- POST /v1/proposals/{id}/resolve -------------------------------------------

    /**
     * Accepting promotes, and the answer names the record that now holds the
     * claim for everyone.
     */
    @Test
    void accepting_promotes_and_names_the_global_record() throws Exception {
        when(queue.approve("prp_000001", "enzo")).thenReturn(new PromotionQueue.Approval(
                settled("prp_000001", "mem_000001", ProposalState.ACCEPTED, "enzo"),
                new Archive.Promotion(global("mem_000009"), List.of())));

        mvc.perform(post("/v1/proposals/prp_000001/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accept": true, "by": "enzo"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.state").value("accepted"))
                .andExpect(jsonPath("$.proposal.resolvedBy").value("enzo"))
                .andExpect(jsonPath("$.promotedId").value("mem_000009"))
                .andExpect(jsonPath("$.demoted").isEmpty());
    }

    /**
     * What the promotion pushed out of the global index is surfaced, not
     * silent.
     *
     * <p>The reason {@code WriteResult.demoted} exists, one operation over:
     * approving adds to the tier <em>every</em> project reads, so a promotion
     * that quietly demoted other memories would make that index shrink for a
     * reason no caller could see. Task 6 recorded the gap when {@code
     * Archive.promote} returned a bare {@code Memory} and called it debt worse
     * than {@code applyVerdict}'s; this is where it reaches a person.
     */
    @Test
    void accepting_says_what_fell_out_of_the_global_index_to_make_room() throws Exception {
        when(queue.approve("prp_000001", "enzo")).thenReturn(new PromotionQueue.Approval(
                settled("prp_000001", "mem_000001", ProposalState.ACCEPTED, "enzo"),
                new Archive.Promotion(global("mem_000009"),
                        List.of("mem_000002", "mem_000003"))));

        mvc.perform(post("/v1/proposals/prp_000001/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accept": true, "by": "enzo"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoted[0]").value("mem_000002"))
                .andExpect(jsonPath("$.demoted[1]").value("mem_000003"));
    }

    /**
     * Rejecting promotes nothing, and the reason is the point.
     *
     * <p>The archive's own principle one layer up: a tombstone carrying why a
     * claim was refused is what stops the curator asking again next week, and
     * the week after.
     */
    @Test
    void rejecting_records_the_refusal_and_promotes_nothing() throws Exception {
        when(queue.reject("prp_000001", "too niche for global", "enzo"))
                .thenReturn(settled("prp_000001", "mem_000001", ProposalState.REJECTED, "enzo"));

        mvc.perform(post("/v1/proposals/prp_000001/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accept": false, "reason": "too niche for global", "by": "enzo"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.state").value("rejected"))
                .andExpect(jsonPath("$.promotedId").doesNotExist())
                .andExpect(jsonPath("$.demoted").isEmpty());

        verify(queue, never()).approve(anyString(), anyString());
    }

    /**
     * No default for {@code accept}, and the refusal says why.
     *
     * <p>A settled proposal is never re-opened, so a guess here would be
     * permanent. Jackson binds a missing boolean to null on a record, which is
     * what makes the distinction reachable at all — a primitive would have
     * silently defaulted to "reject".
     */
    @Test
    void resolving_without_saying_which_way_is_refused() throws Exception {
        mvc.perform(post("/v1/proposals/prp_000001/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"by": "enzo"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("never re-opened")));

        verify(queue, never()).approve(anyString(), anyString());
        verify(queue, never()).reject(anyString(), any(), anyString());
    }

    /** {@code by} is what tells a person's decision from the curator's own on a
     *  row read months later, and there is nothing sensible to default it to. */
    @Test
    void resolving_without_saying_who_decided_is_refused() throws Exception {
        mvc.perform(post("/v1/proposals/prp_000001/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accept": true, "by": "  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("who decided")));

        verify(queue, never()).approve(anyString(), anyString());
    }

    /**
     * A stale proposal is refused, not applied.
     *
     * <p>Between proposal and resolution the memory may have been superseded or
     * invalidated. {@code Archive.promote} refuses a retired record naming its
     * state, and that refusal has to reach the person who clicked accept —
     * promoting a fact that stopped being true would be the worst thing this
     * queue could do, because global is the tier every project reads.
     */
    @Test
    void a_proposal_whose_memory_has_since_been_retired_is_refused_naming_the_state()
            throws Exception {
        when(queue.approve("prp_000001", "enzo")).thenThrow(new ArchiveRefusedException(
                "memory mem_000001 is superseded and cannot be promoted"));

        mvc.perform(post("/v1/proposals/prp_000001/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accept": true, "by": "enzo"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("superseded")));
    }

    /**
     * And so is a second settlement of one that is already answered.
     *
     * <p><b>The 404 this test used to assert was the wart, and it is closed.</b>
     * Every {@code ArchiveException} answered 404, which reads as "there is no
     * such proposal" — and there is, it is just already settled. This test was
     * left here by 3a's task 10 "so that whoever makes it can see what depended
     * on the old shape", and what depended on it was this line: {@code
     * ProposalStore} raises {@link ArchiveRefusedException} for it now, and the
     * status is 409.
     */
    @Test
    void settling_a_proposal_twice_is_refused_and_says_who_answered_it_first() throws Exception {
        when(queue.approve("prp_000001", "enzo")).thenThrow(new ArchiveRefusedException(
                "proposal prp_000001 was already rejected by curator; a settled proposal is not"
                        + " re-opened by settling it again"));

        mvc.perform(post("/v1/proposals/prp_000001/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accept": true, "by": "enzo"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already rejected")))
                .andExpect(jsonPath("$.detail").value(containsString("curator")));
    }

    /**
     * The refusal is a conflict, and the pair below is what makes that mean
     * something.
     *
     * <p>{@link ArchiveRefusedException} <em>extends</em> {@link
     * ArchiveException}, so both of {@code ApiExceptionHandler}'s handlers match
     * and only Spring's most-specific rule decides which runs. That is a library
     * behaviour, so it is measured here rather than assumed — and measured from
     * both directions, because a handler set that always answered 409 would pass
     * this test alone and a set that always answered 404 would pass the next one
     * alone.
     */
    @Test
    void an_archive_refusal_is_a_conflict_and_not_a_missing_row() throws Exception {
        when(queue.approve("prp_000001", "enzo")).thenThrow(
                new ArchiveRefusedException("proposal prp_000001 was already accepted by enzo"));

        mvc.perform(post("/v1/proposals/prp_000001/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accept": true, "by": "enzo"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    /**
     * And the fallback still holds: an archive failure nobody classified answers
     * exactly as it did before the split existed.
     *
     * <p>This is the whole of why the subclass narrows <em>this</em> way round.
     * {@code ApiExceptionHandler} names the supertype in the list that decides
     * the status, so a site that is never reclassified — or one added later by
     * somebody who has not read {@code ArchiveRefusedException} — behaves as it
     * always did rather than newly reporting a conflict about a row that may not
     * exist.
     */
    @Test
    void an_unclassified_archive_failure_is_still_a_missing_row() throws Exception {
        when(queue.approve("prp_000001", "enzo")).thenThrow(
                new ArchiveException("no proposal with id prp_000001"));

        mvc.perform(post("/v1/proposals/prp_000001/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accept": true, "by": "enzo"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    // --- helpers --------------------------------------------------------------------

    /** Asked by a name that appears nowhere else in the row, so an assertion
     *  about {@code proposedBy} cannot be satisfied by {@code resolvedBy},
     *  {@code reason} or the memory's provenance. */
    private static Proposal waiting(String id, String memoryId, String reason) {
        return new Proposal(id, memoryId, Home.of("payments"), ProposalStore.PROMOTE, reason,
                ProposalState.PENDING, WHEN, "a-colleague", null, null, null);
    }

    private static Proposal settled(String id, String memoryId, ProposalState state, String by) {
        return new Proposal(id, memoryId, Home.of("payments"), ProposalStore.PROMOTE,
                "it holds for every project", state, WHEN, "a-colleague", WHEN, by, "settled");
    }

    private static Memory global(String id) {
        return Memory.formed(id, "The retry budget is 4 attempts", "calling payments",
                "Four attempts since the timeout change.",
                new Provenance(WHEN, "curator", "promoted from project 'payments'"),
                Home.global());
    }
}
