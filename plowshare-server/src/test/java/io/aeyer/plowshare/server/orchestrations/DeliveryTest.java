package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.agents.RecordingLogStages;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration;
import io.aeyer.plowshare.server.todos.StageRules;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Delivery, against a real Postgres: a question or an ending reaches the caller's idle TURN
 * conversation, waits for a busy one's drain, falls back to the inbox, and is delivered once —
 * with the caller's voice and the inbox replaced by fakes that record what reached them.
 */
@Testcontainers
class DeliveryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Instant T0 = Instant.parse("2026-09-15T09:00:00Z");

    private static final List<StageRules.Stage> STAGES =
            List.of(new StageRules.Stage("goal", List.of()));

    private static JdbcTemplate jdbc;
    private static UnitOfWork work;

    private OrchestrationStore store;
    private ConversationStore conversations;
    private FakeCaller caller;
    private FakeInbox inbox;
    private FakeParents parents;
    private Set<String> conductorsSpeaking;
    private Delivery delivery;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        // ONE DataSource under the JdbcTemplate and the transaction manager both, as
        // OrchestrationsTest builds its UnitOfWork.
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(ds));
        work = new UnitOfWork() {
            @Override
            public <T> T inTransaction(Supplier<T> body) {
                return template.execute(status -> body.get());
            }
        };
    }

    @BeforeEach
    void fresh() {
        jdbc.execute("TRUNCATE TABLE orchestration_messages, orchestrations, entries, turns,"
                + " conversations, admins CASCADE");
        jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
        AtomicLong tick = new AtomicLong();
        store = new OrchestrationStore(jdbc, () -> T0.plusMillis(tick.incrementAndGet()), work);
        conversations = new ConversationStore(jdbc, () -> T0, null);
        caller = new FakeCaller();
        inbox = new FakeInbox();
        parents = new FakeParents(store);
        conductorsSpeaking = new HashSet<>();
        delivery = new Delivery(store, conversations, caller, inbox, conductorsSpeaking::contains,
                parents);
    }

    private String callerConversation() {
        return conversations.open(Home.of("story"), Budget.of(40)).id();
    }

    private OrchestrationRecord run(String callerConversation, String callerHandle) {
        String conductor = conversations.log(Origin.ORCHESTRATION, Home.of("story"),
                "code_implementation", null, Budget.of(40)).id();
        return store.insert(new NewOrchestration("code_implementation", Tier.PROJECT,
                "sha256:x", "src", "test", STAGES, 1, "story", conductor, callerConversation,
                "interlocutor", callerHandle, null, null, 0));
    }

    /**
     * A child of {@code parent}, as {@code startNested} inserts one: its caller conversation is the
     * parent's own conductor conversation, and it inherits the parent's account.
     */
    private OrchestrationRecord nested(OrchestrationRecord parent) {
        String conductor = conversations.log(Origin.ORCHESTRATION, Home.of("story"),
                "code_implementation", null, Budget.of(40)).id();
        return store.insert(new NewOrchestration("code_implementation", Tier.PROJECT,
                "sha256:x", "src", "test", STAGES, 1, "story", conductor,
                parent.conductorConversation(), "code_implementation", parent.callerHandle(), null,
                parent.id(), parent.depth() + 1));
    }

    private OrchestrationMessage asked(OrchestrationRecord run) {
        return store.ask(run.id(), "Which database?", "code_implementation").orElseThrow();
    }

    private OrchestrationRecord finished(OrchestrationRecord run) {
        assertTrue(store.finish(run.id(), "the page is built"));
        return store.find(run.id()).orElseThrow();
    }

    private Instant deliveredAt(OrchestrationMessage message) {
        return store.messages(message.orchestration()).stream()
                .filter(m -> m.id().equals(message.id())).findFirst().orElseThrow().deliveredAt();
    }

    // --- tests ------------------------------------------------------------------------------

    @Test
    void a_question_is_spoken_into_the_caller_s_idle_conversation_and_marked_delivered() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = asked(run);

        delivery.questionAsked(run, question);

        assertEquals(1, caller.spoken.size());
        FakeCaller.Spoken spoken = caller.spoken.get(0);
        assertEquals(conv, spoken.conversation());
        assertEquals("interlocutor", spoken.agent());
        assertEquals(Utterances.questionForCaller(run, question), spoken.utterance());
        assertEquals(Speaker.orchestration(run.id()), spoken.speaker());
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_question_for_a_busy_caller_waits_for_its_drain() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = asked(run);
        caller.speaking.add(conv);

        delivery.questionAsked(run, question);

        assertTrue(caller.spoken.isEmpty());
        assertTrue(inbox.notices.isEmpty());
        assertNull(deliveredAt(question));

        caller.speaking.remove(conv);
        delivery.drainCaller(conv);

        assertEquals(1, caller.spoken.size());
        assertEquals(conv, caller.spoken.get(0).conversation());
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_caller_that_refuses_for_any_other_reason_is_told_in_its_inbox() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = asked(run);
        caller.refusal = "this conversation has spent all 40 of its model calls";

        delivery.questionAsked(run, question);

        assertTrue(caller.spoken.isEmpty());
        assertEquals(List.of(new FakeInbox.Notice("enzo", "orchestration",
                Utterances.questionForCaller(run, question))), inbox.notices);
        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_caller_with_no_conversation_is_told_in_its_inbox() {
        OrchestrationRecord run = finished(run(null, "enzo"));

        delivery.runEnded(run);

        assertTrue(caller.spoken.isEmpty());
        assertEquals(List.of(new FakeInbox.Notice("enzo", "orchestration",
                Utterances.endingForCaller(run))), inbox.notices);
        assertNotNull(store.find(run.id()).orElseThrow().resultDeliveredAt());
    }

    @Test
    void a_caller_whose_conversation_is_not_a_turn_is_told_in_its_inbox() {
        String notTurn = conversations.log(Origin.ORCHESTRATION, Home.of("story"), "outer",
                null, Budget.of(40)).id();
        OrchestrationRecord run = finished(run(notTurn, "enzo"));

        delivery.runEnded(run);

        assertTrue(caller.spoken.isEmpty());
        assertEquals(1, inbox.notices.size());
        assertNotNull(store.find(run.id()).orElseThrow().resultDeliveredAt());
    }

    @Test
    void a_caller_with_no_conversation_and_no_account_is_marked_delivered_and_logged() {
        OrchestrationRecord run = finished(run(null, null));

        delivery.runEnded(run);

        assertTrue(caller.spoken.isEmpty());
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(store.find(run.id()).orElseThrow().resultDeliveredAt());
    }

    @Test
    void a_run_s_ending_is_delivered_once_even_when_it_is_asked_twice() {
        String conv = callerConversation();
        OrchestrationRecord run = finished(run(conv, "enzo"));

        delivery.runEnded(run);
        delivery.runEnded(run);

        assertEquals(1, caller.spoken.size());
        assertEquals(Utterances.endingForCaller(run), caller.spoken.get(0).utterance());
        assertEquals(Speaker.orchestration(run.id()), caller.spoken.get(0).speaker());
        assertTrue(inbox.notices.isEmpty());
    }

    @Test
    void a_re_entrant_drain_during_delivery_does_nothing() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = asked(run);
        caller.duringSpeak = () -> delivery.drainCaller(conv);

        delivery.questionAsked(run, question);

        assertEquals(1, caller.spoken.size());
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void drain_all_delivers_every_undelivered_question_and_ending() {
        String conv = callerConversation();
        OrchestrationMessage first = asked(run(conv, "enzo"));
        OrchestrationMessage second = asked(run(null, "enzo"));
        OrchestrationRecord ended = finished(run(conv, "enzo"));
        // An answer is the conductor's to hear, through the engine; Delivery never delivers one.
        OrchestrationRecord answered = run(conv, "enzo");
        asked(answered);
        assertTrue(store.answer(answered.id(), "Postgres", "enzo"));
        jdbc.update("UPDATE orchestration_messages SET delivered_at = now()"
                + " WHERE orchestration = ? AND kind = 'question'", answered.id());

        delivery.drainAll();

        assertNotNull(deliveredAt(first));
        assertNotNull(deliveredAt(second));
        assertNotNull(store.find(ended.id()).orElseThrow().resultDeliveredAt());
        assertEquals(2, caller.spoken.size());
        assertEquals(1, inbox.notices.size());
        OrchestrationMessage answer = store.messages(answered.id()).stream()
                .filter(m -> m.kind() == OrchestrationMessage.Kind.ANSWER).findFirst()
                .orElseThrow();
        assertNull(answer.deliveredAt());
        assertTrue(store.undeliveredMessages().stream().allMatch(
                m -> m.kind() == OrchestrationMessage.Kind.ANSWER));
    }

    @Test
    void nothing_delivery_calls_throws_out_of_it() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = asked(run);
        caller.failure = new IllegalStateException("the voice broke");

        delivery.questionAsked(run, question);
        delivery.drainCaller(conv);
        delivery.drainAll();

        assertNull(deliveredAt(question));
        assertTrue(inbox.notices.isEmpty());

        caller.failure = null;
        delivery.drainCaller(conv);

        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_question_is_not_delivered_while_its_conductor_is_still_in_its_turn() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        // orchestration_ask committed inside the conductor's tool; the turn has not ended yet.
        OrchestrationMessage question = asked(run);
        conductorsSpeaking.add(run.conductorConversation());

        delivery.drainCaller(conv);
        delivery.questionAsked(run, question);

        assertTrue(caller.spoken.isEmpty());
        assertTrue(inbox.notices.isEmpty());
        assertNull(deliveredAt(question));

        conductorsSpeaking.remove(run.conductorConversation());
        delivery.drainCaller(conv);

        assertEquals(1, caller.spoken.size());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void an_item_whose_drain_was_skipped_mid_attempt_is_retried_once_the_caller_is_free() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = asked(run);
        // Busy when this attempt looks, and freed before it lets go of the item: the free's drain
        // runs while the item is still in flight, and skips it.
        caller.scripted.addAll(List.of(true, false));
        caller.duringSpeakingCheck = () -> delivery.drainCaller(conv);

        delivery.questionAsked(run, question);

        assertEquals(1, caller.spoken.size());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void an_item_is_retried_at_most_once() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = asked(run);
        // Busy, free, busy again for the retry, free again: a second retry would deliver it.
        caller.scripted.addAll(List.of(true, false, true, false));

        delivery.questionAsked(run, question);

        assertTrue(caller.spoken.isEmpty());
        assertNull(deliveredAt(question));
        assertEquals(List.of(false), List.copyOf(caller.scripted), "no second retry looked");
    }

    // --- the parent route -------------------------------------------------------------------

    // --- a cap question goes to both (spec 2026-09-29 §2) -------------------------------------

    private OrchestrationMessage askedAboutTurnCap(OrchestrationRecord run) {
        return store.askCap(run.id(), Orchestrations.TURN_CAP, "The orchestration"
                + " 'code_implementation' (" + run.id() + "): the conductor stopped at its turn"
                + " cap; answer `yes` to continue with a fresh turn, or `no` to stop it here.")
                .orElseThrow();
    }

    @Test
    void a_question_settled_before_its_copy_was_delivered_is_withdrawn_not_spoken() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = askedAboutTurnCap(child);
        // The person answered from their inbox before the parent could hear it.
        assertTrue(store.answer(child.id(), "yes", "enzo"));

        delivery.questionAsked(store.find(child.id()).orElseThrow(), question);
        delivery.drainAll();

        assertTrue(parents.spoken.isEmpty(), "nothing is spoken for a question nobody is"
                + " waiting on");
        assertTrue(caller.spoken.isEmpty());
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(deliveredAt(question), "and it is not tried again");
    }

    /**
     * Task 13's review: the case the check that withdraws a copy exists for, which the test above
     * cannot reach — an answer marks the question delivered in its own transaction, but a stop
     * does not. A run cancelled while its cap question was still undelivered, and whose turn's
     * ending then delivers it (the ending raced the cancel), has it withdrawn: nobody is spoken
     * to about a run that has ended. The drain never lists it — it skips ended runs.
     */
    @Test
    void a_stopped_run_s_undelivered_question_is_withdrawn_not_spoken() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = askedAboutTurnCap(child);
        OrchestrationRecord asking = store.find(child.id()).orElseThrow();
        assertTrue(store.stop(child.id(), OrchestrationState.CANCELLED, "cancelled by enzo"));
        assertNull(deliveredAt(question), "a stop leaves the question undelivered");

        delivery.questionAsked(asking, question);

        // Its ending may still be told — that is what a stop leaves to tell — but never its
        // question.
        assertTrue(parents.spoken.stream().noneMatch(spoken -> spoken.toString().contains(
                "turn cap")), "the parent is not asked about an ended run: " + parents.spoken);
        assertTrue(caller.spoken.stream().noneMatch(spoken -> spoken.utterance().contains(
                "turn cap")), "nor is the caller");
        assertTrue(inbox.notices.stream().noneMatch(notice -> notice.text().contains(
                "turn cap")), "nor is anyone else asked it");
        assertNotNull(deliveredAt(question), "withdrawn, so nothing tries it again");
    }

    @Test
    void the_person_s_copy_goes_to_their_inbox() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = askedAboutTurnCap(child);

        delivery.toThePerson(store.find(child.id()).orElseThrow(), question);

        assertEquals(1, inbox.notices.size());
        FakeInbox.Notice notice = inbox.notices.get(0);
        assertEquals("enzo", notice.handle());
        assertEquals(Delivery.INBOX_KIND, notice.kind());
        assertTrue(notice.text().contains("/answer " + child.id() + " yes"), notice.text());
        assertTrue(notice.text().contains("parent conductor was asked too"), notice.text());
        assertTrue(parents.spoken.isEmpty(), "the person's copy is not the model's");
        assertNull(deliveredAt(question), "the model's copy is still its own to deliver");
    }

    @Test
    void a_root_s_person_copy_says_its_caller_was_asked_too() {
        OrchestrationRecord run = run(callerConversation(), "enzo");
        OrchestrationMessage question = askedAboutTurnCap(run);

        delivery.toThePerson(store.find(run.id()).orElseThrow(), question);

        assertEquals(1, inbox.notices.size());
        assertTrue(inbox.notices.get(0).text().contains("Its caller was asked too"),
                inbox.notices.get(0).text());
    }

    /**
     * A run with no model to hear its question — no live parent, no caller conversation a turn is
     * spoken into — has its one copy land in the same inbox already: a second would be the person
     * told the same thing twice.
     */
    @Test
    void a_run_whose_only_route_is_the_inbox_gets_no_second_copy() {
        OrchestrationRecord run = run(null, "enzo");
        OrchestrationMessage question = askedAboutTurnCap(run);

        delivery.questionAsked(store.find(run.id()).orElseThrow(), question);
        delivery.toThePerson(store.find(run.id()).orElseThrow(), question);

        assertEquals(1, inbox.notices.size(), inbox.notices.toString());
    }

    @Test
    void a_run_with_no_account_has_no_person_to_copy() {
        OrchestrationRecord parent = run(callerConversation(), null);
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = askedAboutTurnCap(child);

        delivery.toThePerson(store.find(child.id()).orElseThrow(), question);

        assertTrue(inbox.notices.isEmpty());
    }

    // --- a question only the person may answer (spec 2026-09-28, "stuck") ---------------------

    private OrchestrationMessage askedAboutStuck(OrchestrationRecord run) {
        return store.askCap(run.id(), Orchestrations.STUCK, "`" + run.id() + "` ended 3 turns in"
                + " a row without making progress. `/answer " + run.id() + " go on` gives it 3"
                + " more tries; `/cancel " + run.id() + "` stops it.").orElseThrow();
    }

    /**
     * Measured 2026-09-28, {@code orc_3187D648AC346812}: the bot that started a run told only
     * {@code stuck} invented a reason for it. Whether a run that has stopped making progress goes
     * on is the person's to decide, so the question goes to their inbox, and the idle caller
     * conversation a model would answer from is never spoken into.
     */
    @Test
    void a_stuck_question_goes_to_the_person_s_inbox_not_the_caller_s_conversation() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = askedAboutStuck(run);

        delivery.questionAsked(store.find(run.id()).orElseThrow(), question);

        assertTrue(caller.spoken.isEmpty(), "no model is asked");
        assertEquals(List.of(new FakeInbox.Notice("enzo", "orchestration", question.text())),
                inbox.notices);
        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_child_s_stuck_question_goes_to_the_person_not_its_parent() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = askedAboutStuck(child);

        delivery.questionAsked(store.find(child.id()).orElseThrow(), question);

        assertTrue(parents.spoken.isEmpty(), "a parent's conductor cannot judge it");
        assertTrue(caller.spoken.isEmpty());
        assertEquals(List.of(new FakeInbox.Notice("enzo", "orchestration", question.text())),
                inbox.notices);
        assertNotNull(deliveredAt(question));
    }

    /** V65: the question about the verifier's findings is the person's too, so it goes where the
     *  stuck question goes — their inbox — and never to the model that started the run. */
    @Test
    void the_verifier_s_question_goes_to_the_person_s_inbox_not_the_caller_s_conversation() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = store.askUncovered(run.id(), "d".repeat(64),
                "does spec.md stand? `/answer " + run.id() + " accept`").orElseThrow();

        delivery.questionAsked(store.find(run.id()).orElseThrow(), question);

        assertTrue(caller.spoken.isEmpty(), "no model is asked");
        assertEquals(List.of(new FakeInbox.Notice("enzo", "orchestration", question.text())),
                inbox.notices);
        assertNotNull(deliveredAt(question));
    }

    /** V69: a phase whose check kept failing asks the person, never its parent conductor. */
    @Test
    void a_child_s_failing_check_question_goes_to_the_person_not_its_parent() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = store.askCap(child.id(), Orchestrations.CHECK_FAILURES,
                "`" + child.id() + "`'s check `make check` has failed 5 times. Go on, or stop?")
                .orElseThrow();

        delivery.questionAsked(store.find(child.id()).orElseThrow(), question);

        assertTrue(parents.spoken.isEmpty(), "a parent's conductor cannot judge it");
        assertTrue(caller.spoken.isEmpty());
        assertEquals(List.of(new FakeInbox.Notice("enzo", "orchestration", question.text())),
                inbox.notices);
        assertNotNull(deliveredAt(question));
    }

    /** V69: a time cap is a cap — the parent model is asked, and the person beside it. */
    @Test
    void a_child_s_time_cap_question_goes_to_its_parent_and_to_the_person() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = store.askCap(child.id(), Orchestrations.TIME_CAP,
                "`" + child.id() + "` has run 91 minutes, past its time cap of 90. Go on?")
                .orElseThrow();
        OrchestrationRecord asking = store.find(child.id()).orElseThrow();

        delivery.questionAsked(asking, question);
        delivery.toThePerson(asking, question);

        assertEquals(List.of(new FakeParents.Spoken(parent.id(),
                Utterances.questionForCaller(asking, question))), parents.spoken,
                "the parent model is asked");
        assertTrue(parents.spoken.get(0).toString().contains("The person was asked this too"),
                parents.spoken.toString());
        assertEquals(1, inbox.notices.size(), "and the person beside it");
        assertTrue(inbox.notices.get(0).text().contains("`/answer " + child.id() + " yes`"),
                inbox.notices.get(0).text());
    }

    /** A drain reads the row fresh, so a stuck question it finds goes the same way. */
    @Test
    void a_drained_stuck_question_goes_to_the_inbox_too() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = askedAboutStuck(run);

        delivery.drainAll();

        assertTrue(caller.spoken.isEmpty());
        assertEquals(1, inbox.notices.size());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_childs_question_is_spoken_into_its_parents_conversation() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = asked(child);

        delivery.questionAsked(child, question);

        assertEquals(List.of(new FakeParents.Spoken(parent.id(),
                Utterances.questionForCaller(child, question))), parents.spoken);
        assertTrue(caller.spoken.isEmpty(), "the person's route was never used");
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_childs_ending_reaches_its_parent_the_same_way() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = finished(nested(parent));

        delivery.runEnded(child);

        assertEquals(List.of(new FakeParents.Spoken(parent.id(), Utterances.endingForCaller(child))),
                parents.spoken);
        assertTrue(caller.spoken.isEmpty());
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(store.find(child.id()).orElseThrow().resultDeliveredAt());
    }

    @Test
    void a_child_whose_parent_has_ended_falls_back_to_the_person_route() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = finished(nested(parent));
        assertTrue(store.stop(parent.id(), OrchestrationState.FAILED, "the pool went away"));

        delivery.runEnded(child);

        assertTrue(parents.spoken.isEmpty(), "an ended parent is not a live parent");
        // The child's caller conversation is its dead parent's conductor conversation, which is no
        // TURN: the account the whole tree was started by is what is left to tell.
        assertTrue(caller.spoken.isEmpty());
        assertEquals(List.of(new FakeInbox.Notice("enzo", "orchestration",
                Utterances.endingForCaller(child))), inbox.notices);
        assertNotNull(store.find(child.id()).orElseThrow().resultDeliveredAt());
    }

    @Test
    void a_question_waits_while_its_parent_is_speaking_and_is_drained_when_it_frees() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = asked(child);
        parents.speaking.add(parent.id());

        delivery.questionAsked(child, question);

        assertTrue(parents.spoken.isEmpty());
        assertTrue(inbox.notices.isEmpty());
        assertNull(deliveredAt(question));

        parents.speaking.remove(parent.id());
        delivery.drainCaller(parent.conductorConversation());

        assertEquals(List.of(new FakeParents.Spoken(parent.id(),
                Utterances.questionForCaller(child, question))), parents.spoken);
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_parent_that_refuses_the_speak_leaves_the_item_undelivered() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = asked(child);
        // The refusal a report meets most: the parent's own turn took the wait and is still in it.
        parents.refusal = "orchestration " + parent.id() + " already has a turn in flight";

        delivery.questionAsked(child, question);

        assertTrue(parents.spoken.isEmpty());
        assertTrue(caller.spoken.isEmpty(), "a live parent is still where the report belongs");
        assertTrue(inbox.notices.isEmpty());
        assertNull(deliveredAt(question));
        assertEquals(1, parents.attempts, "a parent that is not speaking is not spoken to twice:"
                + " the refusal it gave is one a second speak would only meet again");

        parents.refusal = null;
        delivery.drainCaller(parent.conductorConversation());

        assertEquals(1, parents.spoken.size());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_report_refused_as_in_flight_is_retried_once_that_turn_frees() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = asked(child);
        // Not speaking when this attempt looks; the parent takes a turn in that window, refuses the
        // report as in flight, and frees before the attempt lets go of the item — so the free's
        // drain runs while the item is still in flight, and skips it.
        parents.scripted.addAll(List.of(false, true, false));
        parents.refusal = "orchestration " + parent.id() + " already has a turn in flight";
        parents.refusals = 1;
        parents.duringRefusal = () -> delivery.drainCaller(parent.conductorConversation());

        delivery.questionAsked(child, question);

        assertEquals(2, parents.attempts, "the refusal, then the retry once the turn freed");
        assertEquals(List.of(new FakeParents.Spoken(parent.id(),
                Utterances.questionForCaller(child, question))), parents.spoken);
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(deliveredAt(question));
    }

    @Test
    void a_report_to_a_busy_parent_is_retried_at_most_once() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage question = asked(child);
        // Busy, free, busy again for the retry, free again: a second retry would deliver it.
        parents.scripted.addAll(List.of(true, false, true, false));

        delivery.questionAsked(child, question);

        assertEquals(0, parents.attempts, "a busy parent is never spoken to");
        assertTrue(parents.spoken.isEmpty());
        assertTrue(inbox.notices.isEmpty());
        assertNull(deliveredAt(question));
        assertEquals(List.of(false), List.copyOf(parents.scripted), "no second retry looked");
    }

    @Test
    void a_refusal_that_ended_the_parent_sends_the_report_down_the_person_route() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = finished(nested(parent));
        // A refusal no retry gets past fails the parent, as speakToParent does: from there nothing
        // would ever free that conversation to drain the report again.
        parents.refusal = "the model 'reasoning' is served by no pool";
        parents.duringRefusal = () -> assertTrue(store.stop(parent.id(),
                OrchestrationState.FAILED, "the model 'reasoning' is served by no pool"));

        delivery.runEnded(child);

        assertTrue(parents.spoken.isEmpty());
        assertEquals(List.of(new FakeInbox.Notice("enzo", "orchestration",
                Utterances.endingForCaller(child))), inbox.notices);
        assertNotNull(store.find(child.id()).orElseThrow().resultDeliveredAt());
    }

    @Test
    void a_root_run_is_unaffected() {
        String conv = callerConversation();
        OrchestrationRecord run = run(conv, "enzo");
        OrchestrationMessage question = asked(run);

        delivery.questionAsked(run, question);

        assertTrue(parents.asked.isEmpty(), "a root run has no parent to ask about");
        assertEquals(1, caller.spoken.size());
        assertEquals(conv, caller.spoken.get(0).conversation());
        assertNotNull(deliveredAt(question));
    }

    // --- fakes ------------------------------------------------------------------------------

    static final class FakeCaller implements CallerVoice {

        record Spoken(String conversation, String agent, String utterance, Speaker speaker) {}

        final List<Spoken> spoken = new ArrayList<>();
        final Set<String> speaking = new HashSet<>();
        /** Answers to {@link #isSpeaking}, in order, before {@link #speaking} is consulted. */
        final Deque<Boolean> scripted = new ArrayDeque<>();
        /** Run once, inside the first scripted {@link #isSpeaking}. */
        Runnable duringSpeakingCheck;
        String refusal;
        RuntimeException failure;
        Runnable duringSpeak;

        @Override
        public boolean isSpeaking(String conversation) {
            if (!scripted.isEmpty()) {
                Runnable during = duringSpeakingCheck;
                duringSpeakingCheck = null;
                if (during != null) {
                    during.run();
                }
                return scripted.removeFirst();
            }
            return speaking.contains(conversation);
        }

        @Override
        public void speak(String conversation, String callerAgent, String utterance,
                Speaker speaker) {
            if (refusal != null) {
                throw new Turn.Refused(refusal);
            }
            if (failure != null) {
                throw failure;
            }
            spoken.add(new Spoken(conversation, callerAgent, utterance, speaker));
            if (duringSpeak != null) {
                duringSpeak.run();
            }
        }
    }

    /** The parent door, with the store answering what is live — {@code
     *  OrchestrationsConfig.parentVoice}'s own rule, which its own test covers. */
    static final class FakeParents implements ParentVoice {

        record Spoken(String parent, String utterance) {}

        private final OrchestrationStore store;
        final List<Spoken> spoken = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        final Set<String> speaking = new HashSet<>();
        /** Answers to {@link #isSpeaking}, in order, before {@link #speaking} is consulted. */
        final Deque<Boolean> scripted = new ArrayDeque<>();
        /** Run once, inside the first scripted {@link #isSpeaking}. */
        Runnable duringSpeakingCheck;
        String refusal;
        /** How many speaks {@link #refusal} refuses before it is cleared; negative for all. */
        int refusals = -1;
        /** Speaks attempted, refused ones included. */
        int attempts;
        /** Run once, inside the next refused speak: how a parent a refusal settled stops being
         *  live, or takes a turn of its own. */
        Runnable duringRefusal;

        FakeParents(OrchestrationStore store) {
            this.store = store;
        }

        @Override
        public boolean isLiveParent(String orchestration) {
            asked.add(orchestration);
            return store.find(orchestration).filter(run -> !run.state().terminal()).isPresent();
        }

        @Override
        public boolean isSpeaking(String parentOrchestration) {
            if (!scripted.isEmpty()) {
                Runnable during = duringSpeakingCheck;
                duringSpeakingCheck = null;
                if (during != null) {
                    during.run();
                }
                return scripted.removeFirst();
            }
            return speaking.contains(parentOrchestration);
        }

        @Override
        public void speak(String parentOrchestration, String utterance) {
            attempts++;
            if (refusal != null) {
                String refused = refusal;
                if (refusals > 0 && --refusals == 0) {
                    refusal = null;
                }
                Runnable during = duringRefusal;
                duringRefusal = null;
                if (during != null) {
                    during.run();
                }
                throw new Turn.Refused(refused);
            }
            spoken.add(new Spoken(parentOrchestration, utterance));
        }
    }

    static final class FakeInbox implements InboxPort {

        record Notice(String handle, String kind, String text) {}

        final List<Notice> notices = new ArrayList<>();
        /** What each notice in {@link #notices} asked about, in the same order; null for news. */
        final List<String> abouts = new ArrayList<>();
        final List<String> settled = new ArrayList<>();
        /** Run once, as the next notice lands — a person answering while it was on its way. */
        Runnable duringNotify;

        @Override
        public void notify(String handle, String kind, String text) {
            notify(handle, kind, text, null);
        }

        @Override
        public void notify(String handle, String kind, String text, String about) {
            Runnable during = duringNotify;
            duringNotify = null;
            if (during != null) {
                during.run();
            }
            notices.add(new Notice(handle, kind, text));
            abouts.add(about);
        }

        @Override
        public void settle(String about) {
            settled.add(about);
        }
    }

    // --- a question's notice settles with it (V68) ---------------------------------------------

    @Test
    void a_question_s_notice_names_its_question_and_an_ending_names_none() {
        OrchestrationRecord run = run(null, "enzo");
        OrchestrationMessage question = asked(run);

        delivery.questionAsked(store.find(run.id()).orElseThrow(), question);
        assertTrue(store.answer(run.id(), "Postgres", "enzo"));
        OrchestrationRecord ended = finished(run);
        delivery.runEnded(ended);

        assertEquals(2, inbox.notices.size(), inbox.notices.toString());
        assertEquals(Arrays.asList(Delivery.about(question), null), inbox.abouts);
    }

    @Test
    void the_person_s_own_questions_name_theirs() {
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = nested(parent);
        OrchestrationMessage cap = askedAboutTurnCap(child);
        delivery.toThePerson(store.find(child.id()).orElseThrow(), cap);
        OrchestrationRecord other = run(callerConversation(), "enzo");
        OrchestrationMessage stuck = askedAboutStuck(other);
        delivery.questionAsked(store.find(other.id()).orElseThrow(), stuck);

        assertEquals(List.of(Delivery.about(cap), Delivery.about(stuck)), inbox.abouts);
    }

    /** Answered, by anyone: the run is running again, so none of its questions is open. */
    @Test
    void an_answered_question_is_settled_and_one_still_asked_is_not() {
        OrchestrationRecord run = run(callerConversation(), "enzo");
        OrchestrationMessage question = askedAboutStuck(run);

        delivery.questionsSettled(store.find(run.id()).orElseThrow());
        assertEquals(List.of(), inbox.settled, "still asked");

        assertTrue(store.answer(run.id(), "go on", "enzo"));
        delivery.questionsSettled(store.find(run.id()).orElseThrow());

        assertEquals(List.of(Delivery.about(question)), inbox.settled);
    }

    /** Asking again, only the question it asks now stays: every earlier one was settled. */
    @Test
    void a_run_asking_again_keeps_only_its_new_question_open() {
        OrchestrationRecord run = run(callerConversation(), "enzo");
        OrchestrationMessage first = asked(run);
        assertTrue(store.answer(run.id(), "Postgres", "interlocutor"));
        OrchestrationMessage second = asked(run);

        delivery.questionsSettled(store.find(run.id()).orElseThrow());

        assertEquals(List.of(Delivery.about(first)), inbox.settled);
        assertTrue(!inbox.settled.contains(Delivery.about(second)));
    }

    @Test
    void an_ended_run_s_question_is_settled() {
        OrchestrationRecord run = run(callerConversation(), "enzo");
        OrchestrationMessage question = askedAboutStuck(run);
        assertTrue(store.stop(run.id(), OrchestrationState.CANCELLED, "cancelled by enzo"));

        delivery.questionsSettled(store.find(run.id()).orElseThrow());

        assertEquals(List.of(Delivery.about(question)), inbox.settled);
    }

    /**
     * The person answered between the question being read as open and its notice landing: the
     * engine's settle for that answer found no notice, and nothing else would come for it — so it
     * is settled as it lands.
     */
    @Test
    void a_question_answered_while_its_notice_was_on_its_way_is_settled_as_it_lands() {
        OrchestrationRecord run = run(callerConversation(), "enzo");
        OrchestrationMessage question = askedAboutStuck(run);
        inbox.duringNotify = () -> assertTrue(store.answer(run.id(), "go on", "enzo"));

        delivery.questionAsked(store.find(run.id()).orElseThrow(), question);

        assertEquals(1, inbox.notices.size());
        assertEquals(List.of(Delivery.about(question)), inbox.settled);
    }

    @Test
    void a_question_still_open_as_its_notice_lands_is_not_settled() {
        OrchestrationRecord run = run(callerConversation(), "enzo");
        OrchestrationMessage question = askedAboutStuck(run);

        delivery.questionAsked(store.find(run.id()).orElseThrow(), question);

        assertEquals(List.of(Delivery.about(question)), inbox.abouts);
        assertEquals(List.of(), inbox.settled);
    }

    /** Spec 2026-09-28-hooks-reach-the-log §3: a note reaches the text; the SOURCE log records. */
    @Test
    void an_ending_is_noted_by_delivery_pre_and_told_to_delivery_post_from_its_source_log() {
        RecordingLogStages told = new RecordingLogStages();
        told.note = "checked by a hook";
        delivery.useLogStages(told);
        OrchestrationRecord run = finished(run(null, "enzo"));

        delivery.runEnded(run);

        String said = Utterances.endingForCaller(run) + "\n\nchecked by a hook";
        assertEquals(List.of(new FakeInbox.Notice("enzo", "orchestration", said)), inbox.notices);
        assertEquals(List.of("pre " + run.conductorConversation() + " inbox",
                "post " + run.conductorConversation() + " inbox " + said), told.lines);
    }

    @Test
    void an_ending_spoken_into_a_person_s_conversation_names_that_destination() {
        RecordingLogStages told = new RecordingLogStages();
        delivery.useLogStages(told);
        OrchestrationRecord run = finished(run(callerConversation(), "enzo"));

        delivery.runEnded(run);

        assertEquals(List.of("pre " + run.conductorConversation() + " conversation",
                "post " + run.conductorConversation() + " conversation "
                        + Utterances.endingForCaller(run)), told.lines);
    }

    /** A child's ending is told from the child's own log, never its parent's (decision 7). */
    @Test
    void a_child_s_ending_names_its_parent_and_is_told_from_the_child_s_log() {
        RecordingLogStages told = new RecordingLogStages();
        told.note = "checked";
        delivery.useLogStages(told);
        OrchestrationRecord parent = run(callerConversation(), "enzo");
        OrchestrationRecord child = finished(nested(parent));

        delivery.runEnded(child);

        String said = Utterances.endingForCaller(child) + "\n\nchecked";
        assertEquals(List.of(new FakeParents.Spoken(parent.id(), said)), parents.spoken);
        assertEquals(List.of("pre " + child.conductorConversation() + " parent",
                "post " + child.conductorConversation() + " parent " + said), told.lines);
    }

    @Test
    void a_question_passes_no_delivery_stage() {
        RecordingLogStages told = new RecordingLogStages();
        delivery.useLogStages(told);
        OrchestrationRecord run = run(null, "enzo");

        delivery.questionAsked(run, asked(run));

        assertEquals(List.of(), told.lines);
    }

    /** Plan choice 8: a busy caller's attempt is told to pre, not post; its retry is told again. */
    @Test
    void an_ending_for_a_busy_caller_is_told_pre_each_attempt_and_post_once_delivered() {
        RecordingLogStages told = new RecordingLogStages();
        delivery.useLogStages(told);
        String conv = callerConversation();
        OrchestrationRecord run = finished(run(conv, "enzo"));
        caller.speaking.add(conv);

        delivery.runEnded(run);

        assertEquals(List.of("pre " + run.conductorConversation() + " conversation"), told.lines);

        caller.speaking.remove(conv);
        delivery.drainCaller(conv);

        assertEquals(List.of("pre " + run.conductorConversation() + " conversation",
                "pre " + run.conductorConversation() + " conversation",
                "post " + run.conductorConversation() + " conversation "
                        + Utterances.endingForCaller(run)), told.lines);
    }

    @Test
    void an_ending_with_nowhere_to_go_is_told_pre_as_nowhere_and_never_post() {
        RecordingLogStages told = new RecordingLogStages();
        delivery.useLogStages(told);
        OrchestrationRecord run = finished(run(null, null));

        delivery.runEnded(run);

        assertEquals(List.of("pre " + run.conductorConversation() + " nowhere"), told.lines);
        assertTrue(inbox.notices.isEmpty());
        assertNotNull(store.find(run.id()).orElseThrow().resultDeliveredAt(),
                "marked delivered, so it is not tried again");
    }
}
