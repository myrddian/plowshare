package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.api.ApiExceptionHandler;
import io.aeyer.plowshare.server.api.ConversationController;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The read pilot, measured the way {@code ProjectDefineHandlerTest} measures the
 * write one: {@code GET /v1/conversations/&#123;id&#125;/turns} and the {@code
 * conversation.turns} frame are driven off one conversation id, and every
 * assertion is that the two surfaces answered the same thing.
 *
 * <h2>The refusal is the reason this file exists in this commit and not the last
 * one</h2>
 *
 * <p>This pilot was reported blocked rather than written, because {@code
 * ConversationController.turns} checked the conversation's existence inline with
 * a sentence {@code Conversations.requireExists} could not build, and a handler
 * calling the service would have refused the same request in different words. A
 * happy path would never have shown it: both surfaces read the same list from
 * the same store and render the same {@code TurnView}s, so they agree on that by
 * construction. <b>{@link
 * #a_conversation_nothing_opened_is_the_same_404_in_the_same_words} is the
 * assertion that finding was about</b>, and it pins the sentence rather than the
 * status — a 404 on both surfaces saying two different things is the drift, and
 * only the sentence sees it.
 *
 * <p><b>One mocked {@link TurnStore} and one mocked {@link ConversationStore},
 * shared by both surfaces</b>, with the real {@link Conversations} over them:
 * what a store does with a row is its own Testcontainers test's subject. What is
 * under test here is which method each surface calls and what each does with
 * what comes back — including with what is thrown.
 */
class ConversationTurnsHandlerTest {

    private static final Instant OPENED_AT = Instant.parse("2026-09-11T00:00:00Z");

    private ConversationStore conversations;
    private TurnStore turns;
    private MockMvc mvc;
    private FrameRouter router;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationStore.class);
        turns = mock(TurnStore.class);
        Conversations rules = new Conversations(conversations, turns);
        // Null for every dependency this one read does not touch, and not a
        // mock: a mock would answer quietly if the handler or the controller
        // ever started reaching for one, and the claim under test is that this
        // read is the two stores and the rules and nothing else.
        mvc = MockMvcBuilders
                .standaloneSetup(new ConversationController(conversations, null, turns,
                        null, null, null, null, null, null, null, rules, LogStages.NONE, org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        // Through the area rather than through a literal map: this is the
        // registration a breadth task adds to, and a sibling area adding a type
        // of its own changes nothing about this line.
        //
        // The area now takes the whole of ConversationController's own service
        // list, because it registers the whole of that controller's endpoints;
        // this read touches two of them, and the rest are mocked rather than
        // null because ConversationFrames refuses to be built without them --
        // it builds every handler it registers, not only the one being asked
        // for. The claim under test is unchanged: the handler reached below is
        // built over these two real collaborators.
        router = new FrameRoutingConfig().frameRouter(List.of(new ConversationFrames(
                rules, conversations, mock(CompactionStore.class), turns,
                mock(EntryStore.class), mock(JobRuntime.class), mock(Turn.class),
                unusedAgents(), new ConversationsProperties(), mock(Compaction.class),
                mock(Tokenizer.class), mock(Callers.class), LogStages.NONE, org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class))));
    }

    // --- the read itself -----------------------------------------------------

    /**
     * Both surfaces read the same history back and render it the same way.
     *
     * <p><b>The store call is verified twice</b>, which is the half of the
     * parity a body comparison cannot see: the mock answers the same list to
     * whoever asks, so two surfaces could agree on the JSON while one of them
     * had asked about a different conversation.
     */
    @Test
    void both_surfaces_read_back_the_same_history() throws Exception {
        exists("cnv_1");
        when(turns.forConversation("cnv_1")).thenReturn(List.of(
                spoken(1, "interlocutor"), spoken(2, "scribe")));

        MockHttpServletResponse http = over("cnv_1");
        Outcome outcome = router.route(frame("cnv_1"), FrameParity.ASKING);

        assertEquals(Code.OK, outcome.code());
        assertNotNull(outcome.payload(), "a read answers with what it read");
        FrameParity.assertSameAnswer(http, outcome);
        verify(turns, times(2)).forConversation("cnv_1");
    }

    /**
     * A conversation nobody has spoken into is an empty history on both surfaces
     * — the ordinary answer, and the one the refusal below must stay distinct
     * from.
     */
    @Test
    void a_conversation_nobody_has_spoken_into_is_an_empty_history_on_both_surfaces()
            throws Exception {
        exists("cnv_1");
        when(turns.forConversation("cnv_1")).thenReturn(List.of());

        MockHttpServletResponse http = over("cnv_1");
        Outcome outcome = router.route(frame("cnv_1"), FrameParity.ASKING);

        assertEquals(Code.OK, outcome.code());
        assertEquals("[]", http.getContentAsString());
        FrameParity.assertSameAnswer(http, outcome);
        verify(turns, times(2)).forConversation("cnv_1");
    }

    // --- the refusal, which is where two surfaces drift ----------------------

    /**
     * An id nothing opened is a 404 on both surfaces, in the same words, and
     * neither reads the turns.
     *
     * <p><b>This is the test the blocked pilot was blocked on.</b> The sentence
     * belongs to {@code Conversations.requireExistsOrThereIsNo} now, so there is
     * one place it is written; before that the controller spelled it inline and
     * anything else calling the service got {@code "... so there is no history
     * to read"} — a 404 with the same status and different words, which is
     * exactly the drift a parity test that asserted only the status would have
     * passed straight through.
     */
    @Test
    void a_conversation_nothing_opened_is_the_same_404_in_the_same_words() throws Exception {
        when(conversations.find("cnv_nope")).thenReturn(Optional.empty());

        MockHttpServletResponse http = over("cnv_nope");
        Outcome outcome = router.route(frame("cnv_nope"), FrameParity.ASKING);

        assertEquals(Code.NOT_FOUND, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        assertEquals("no conversation has the id cnv_nope, so there is no history to read back",
                outcome.said(),
                "and those words are the ones the endpoint has always said, character for"
                        + " character -- a client reads this sentence");
        verify(turns, never()).forConversation(any());
    }

    /**
     * A payload naming no conversation is the caller's mistake and says so.
     *
     * <p><b>Only the frame is driven here, and that is a fact about the two
     * surfaces rather than a gap in the test.</b> The endpoint takes its id from
     * the path, so "no conversation named" is not a request it can receive —
     * there is no such URL to call. The frame can receive it, so it needs an
     * answer of its own, and a {@code CallerFault} is that answer: {@code
     * Faults} is still the one table deciding it is a 400, exactly as it decides
     * the same class is a 400 for a controller.
     */
    @Test
    void a_payload_naming_no_conversation_is_a_caller_fault() {
        Outcome outcome = router.route(frame(null), FrameParity.ASKING);

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertNotNull(outcome.said(), "and says what was missing");
        verify(conversations, never()).find(any());
        verify(turns, never()).forConversation(any());
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
        exists("cnv_1");
        when(turns.forConversation("cnv_1")).thenReturn(List.of());

        FrameParity.assertUnknownFieldsAreIgnored(router, FrameTypes.CONVERSATION_TURNS,
                "{\"conversation\":\"cnv_1\"}");
    }

    // --- the wiring ----------------------------------------------------------

    /**
     * The production routing table really does claim this type.
     *
     * <p>Built from every {@link FrameArea} Spring would collect, for the
     * reason its sibling test gives: an unregistered type is answered with a
     * perfectly well-formed {@code NOT_FOUND}, so a pilot nobody wired would
     * look from the outside exactly like a pilot nobody wrote.
     *
     * <p><b>The refusal asserted is the empty payload's and not the missing
     * conversation's</b>, because the discovered table holds this area's
     * handler over mocked stores — a mocked {@code Conversations} refuses
     * nothing. What this measures is that the type reaches a handler at all;
     * that the handler is the right one, over real rules, is every other test
     * in this file.
     */
    @Test
    void the_production_routing_table_claims_conversation_turns() {
        FrameRouter wired = FrameAreas.router();

        Outcome outcome = wired.route(frame(null), FrameParity.ASKING);

        assertEquals(Code.BAD_REQUEST, outcome.code(),
                "a registered type reaches its handler and is refused by it, rather than"
                        + " answering the NOT_FOUND an unregistered type would");
    }

    // --- driving the two surfaces off one conversation id --------------------

    /** The registry no type this file drives resolves a name through; the area
     *  takes one because two of its other handlers do. */
    private static ObjectProvider<AgentRegistry> unusedAgents() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRegistry> provider = mock(ObjectProvider.class);
        return provider;
    }

    /** The store answers with a row for {@code id}, so the existence rule passes. */
    private void exists(String id) {
        when(conversations.find(id)).thenReturn(Optional.of(new ConversationRecord(
                id, Home.global(), Origin.TURN, ConversationLifecycle.ACTIVE, null, null,
                OPENED_AT, null, null, null, null)));
    }

    private static TurnRecord spoken(int ordinal, String agent) {
        return new TurnRecord("cnv_1", ordinal, "asked", "answered",
                io.aeyer.plowshare.server.agents.Outcome.Ending.ANSWERED, 10, agent, null);
    }

    /** The read over HTTP. */
    private MockHttpServletResponse over(String id) throws Exception {
        return mvc.perform(get("/v1/conversations/" + id + "/turns"))
                .andReturn().getResponse();
    }

    /** The same read as a frame, through the builder every parity test shares
     *  — one id string drives both surfaces, so the two are answering one
     *  question and not two spellings of it. */
    private static String frame(String conversation) {
        String payload = conversation == null ? "{}"
                : "{\"conversation\":\"" + conversation + "\"}";
        return FrameParity.frame(FrameTypes.CONVERSATION_TURNS, payload);
    }
}
