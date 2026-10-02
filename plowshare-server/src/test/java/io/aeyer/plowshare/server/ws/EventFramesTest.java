package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.Intake;
import io.aeyer.plowshare.server.events.ScheduleProposal;
import io.aeyer.plowshare.server.events.ScheduleReader;
import io.aeyer.plowshare.server.events.ScheduleRecord;
import io.aeyer.plowshare.server.events.ScheduleStore;
import io.aeyer.plowshare.server.events.TriggerRecord;
import io.aeyer.plowshare.server.events.TriggerStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;

class EventFramesTest {

    private ScheduleStore schedules;
    private TriggerStore triggers;
    private FiringStore firings;
    private Intake intake;
    private Callers callers;
    private ScheduleReader reader;
    private FrameRouter router;
    private static final Asking SIGNED_IN = new Asking("session-1", "enzo");

    /** The one instant every {@code schedule.define} call in this file is answered under, so a
     *  test can assert on it exactly rather than matching it with {@code any()}. */
    private static final Instant FIXED = Instant.parse("2026-09-14T00:00:00Z");

    @BeforeEach
    void setUp() {
        schedules = mock(ScheduleStore.class);
        triggers = mock(TriggerStore.class);
        firings = mock(FiringStore.class);
        intake = mock(Intake.class);
        callers = mock(Callers.class);
        reader = mock(ScheduleReader.class);
        router = new FrameRoutingConfig().frameRouter(List.of(
                new EventFrames(schedules, triggers, firings, intake, callers, reader, () -> FIXED)));
    }

    private Outcome route(String type, String payload, Asking asking) {
        return router.route(FrameParity.frame(type, payload), asking);
    }

    @Test
    void a_schedule_is_defined_by_the_signed_in_account() {
        when(schedules.define(eq("nine"), any(), eq("daily"), eq("enzo"), eq(FIXED))).thenReturn(
                new ScheduleRecord("nine", "0 0 9 * * *", "UTC", "daily", false,
                        Instant.parse("2026-09-14T09:00:00Z"), "enzo"));
        Outcome outcome = route(FrameTypes.SCHEDULE_DEFINE,
                "{\"schedule\":\"nine\",\"cron\":\"0 0 9 * * *\",\"zone\":\"UTC\",\"emits\":\"daily\"}",
                SIGNED_IN);
        assertEquals(Code.OK, outcome.code());
        verify(schedules).define(eq("nine"), any(), eq("daily"), eq("enzo"), eq(FIXED));
    }

    @Test
    void an_unreadable_cron_is_a_bad_request_and_nothing_is_stored() {
        Outcome outcome = route(FrameTypes.SCHEDULE_DEFINE,
                "{\"schedule\":\"nine\",\"cron\":\"every morning\",\"emits\":\"daily\"}", SIGNED_IN);
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(schedules, never()).define(any(), any(), any(), any(), any());
    }

    @Test
    void a_schedule_is_paused() {
        Outcome outcome = route(FrameTypes.SCHEDULE_PAUSE,
                "{\"schedule\":\"nine\",\"paused\":true}", SIGNED_IN);
        assertEquals(Code.NO_CONTENT, outcome.code());
        verify(schedules).pause("nine", true, "enzo");
    }

    @Test
    void pausing_a_schedule_with_no_direction_is_refused_and_nothing_is_changed() {
        Outcome outcome = route(FrameTypes.SCHEDULE_PAUSE, "{\"schedule\":\"nine\"}", SIGNED_IN);
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(schedules, never()).pause(any(), anyBoolean(), any());
    }

    @Test
    void a_schedule_is_forgotten() {
        Outcome outcome = route(FrameTypes.SCHEDULE_FORGET, "{\"schedule\":\"nine\"}", SIGNED_IN);
        assertEquals(Code.NO_CONTENT, outcome.code());
        verify(schedules).forget("nine", "enzo");
    }

    @Test
    void forgetting_a_schedule_nobody_defined_is_not_found() {
        doThrow(new ArchiveException("no schedule is named nine")).when(schedules).forget("nine", "enzo");
        Outcome outcome = route(FrameTypes.SCHEDULE_FORGET, "{\"schedule\":\"nine\"}", SIGNED_IN);
        assertEquals(Code.NOT_FOUND, outcome.code());
    }

    @Test
    void a_socket_with_no_account_cannot_define_a_trigger() {
        Outcome outcome = route(FrameTypes.TRIGGER_DEFINE,
                "{\"trigger\":\"t\",\"event\":\"daily\",\"agent\":\"bard\",\"task\":\"go\"}",
                new Asking("session-1"));
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(triggers, never()).define(any());
    }

    @Test
    void a_trigger_naming_both_a_project_and_a_conversation_is_refused() {
        Outcome outcome = route(FrameTypes.TRIGGER_DEFINE,
                "{\"trigger\":\"t\",\"event\":\"daily\",\"agent\":\"bard\",\"task\":\"go\","
                        + "\"project\":\"p\",\"conversation\":\"cnv_1\"}", SIGNED_IN);
        assertEquals(Code.BAD_REQUEST, outcome.code());
    }

    @Test
    void a_trigger_aimed_at_a_conversation_cannot_carry_its_own_limits() {
        for (String limit : List.of("\"maxModelCalls\":40", "\"maxTurns\":3")) {
            Outcome outcome = route(FrameTypes.TRIGGER_DEFINE,
                    "{\"trigger\":\"t\",\"event\":\"daily\",\"agent\":\"bard\",\"task\":\"go\","
                            + "\"conversation\":\"cnv_1\"," + limit + "}", SIGNED_IN);
            assertEquals(Code.BAD_REQUEST, outcome.code(), limit);
            assertTrue(outcome.said().contains("conversation"), outcome.said());
        }
        verify(triggers, never()).define(any());
    }

    @Test
    void a_trigger_is_stored_with_its_definer_and_the_agent_is_checked_first() {
        when(triggers.define(any())).thenAnswer(call -> call.getArgument(0));
        Outcome outcome = route(FrameTypes.TRIGGER_DEFINE,
                "{\"trigger\":\"t\",\"event\":\"daily\",\"agent\":\"bard\",\"task\":\"go\"}", SIGNED_IN);
        assertEquals(Code.OK, outcome.code());
        verify(callers).requireAgent(eq("bard"), any());
        verify(triggers).define(new TriggerRecord("t", "daily", null, null, "bard", "go",
                null, null, 1, false, "enzo"));
    }

    @Test
    void a_trigger_is_paused() {
        Outcome outcome = route(FrameTypes.TRIGGER_PAUSE,
                "{\"trigger\":\"t\",\"paused\":true}", SIGNED_IN);
        assertEquals(Code.NO_CONTENT, outcome.code());
        verify(triggers).pause("t", true, "enzo");
    }

    @Test
    void pausing_a_trigger_refuses_what_it_had_waiting_after_its_ownership_is_confirmed() {
        route(FrameTypes.TRIGGER_PAUSE, "{\"trigger\":\"t\",\"paused\":true}", SIGNED_IN);
        InOrder order = inOrder(triggers, firings);
        order.verify(triggers).pause("t", true, "enzo");
        order.verify(firings).refuseWaiting("t", "trigger paused");
    }

    @Test
    void unpausing_a_trigger_refuses_nothing() {
        route(FrameTypes.TRIGGER_PAUSE, "{\"trigger\":\"t\",\"paused\":false}", SIGNED_IN);
        verify(triggers).pause("t", false, "enzo");
        verify(firings, never()).refuseWaiting(any(), any());
    }

    @Test
    void pausing_another_accounts_trigger_is_not_found_and_refuses_nothing_it_had_waiting() {
        doThrow(new ArchiveException("no trigger named t is yours"))
                .when(triggers).pause("t", true, "enzo");
        Outcome outcome = route(FrameTypes.TRIGGER_PAUSE, "{\"trigger\":\"t\",\"paused\":true}",
                SIGNED_IN);
        assertEquals(Code.NOT_FOUND, outcome.code());
        verify(firings, never()).refuseWaiting(any(), any());
    }

    @Test
    void pausing_a_trigger_with_no_direction_is_refused_and_nothing_is_changed() {
        Outcome outcome = route(FrameTypes.TRIGGER_PAUSE, "{\"trigger\":\"t\"}", SIGNED_IN);
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(triggers, never()).pause(any(), anyBoolean(), any());
    }

    @Test
    void forgetting_a_trigger_refuses_what_it_had_waiting() {
        Outcome outcome = route(FrameTypes.TRIGGER_FORGET, "{\"trigger\":\"t\"}", SIGNED_IN);
        assertEquals(Code.NO_CONTENT, outcome.code());
        verify(firings).refuseWaiting("t", "trigger forgotten");
        verify(triggers).forget("t", "enzo");
    }

    @Test
    void a_socket_with_no_account_cannot_pause_or_forget_a_schedule_or_a_trigger() {
        Asking anonymous = new Asking("session-1");
        Map<String, String> payloads = Map.of(
                FrameTypes.SCHEDULE_PAUSE, "{\"schedule\":\"nine\",\"paused\":true}",
                FrameTypes.SCHEDULE_FORGET, "{\"schedule\":\"nine\"}",
                FrameTypes.TRIGGER_PAUSE, "{\"trigger\":\"t\",\"paused\":true}",
                FrameTypes.TRIGGER_FORGET, "{\"trigger\":\"t\"}");
        for (Map.Entry<String, String> each : payloads.entrySet()) {
            assertEquals(Code.BAD_REQUEST, route(each.getKey(), each.getValue(), anonymous).code(),
                    each.getKey());
        }
        verify(schedules, never()).pause(any(), anyBoolean(), any());
        verify(schedules, never()).forget(any(), any());
        verify(triggers, never()).pause(any(), anyBoolean(), any());
        verify(triggers, never()).forget(any(), any());
        verify(firings, never()).refuseWaiting(any(), any());
    }

    @Test
    void forgetting_another_accounts_trigger_is_not_found_and_refuses_nothing_it_had_waiting() {
        doThrow(new ArchiveException("no trigger named t is yours")).when(triggers).forget("t", "enzo");
        Outcome outcome = route(FrameTypes.TRIGGER_FORGET, "{\"trigger\":\"t\"}", SIGNED_IN);
        assertEquals(Code.NOT_FOUND, outcome.code());
        verify(firings, never()).refuseWaiting(any(), any());
    }

    @Test
    void firing_an_event_goes_through_the_same_intake_as_a_tick() {
        when(intake.emit(eq("daily"), eq(Map.of("n", 1)))).thenReturn(List.of());
        Outcome outcome = route(FrameTypes.EVENT_FIRE, "{\"event\":\"daily\",\"data\":{\"n\":1}}", SIGNED_IN);
        assertEquals(Code.OK, outcome.code());
        verify(intake).emit("daily", Map.of("n", 1));
    }

    @Test
    void a_socket_with_no_account_cannot_fire_an_event() {
        Outcome outcome = route(FrameTypes.EVENT_FIRE, "{\"event\":\"daily\",\"data\":{\"n\":1}}",
                new Asking("session-1"));
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(intake, never()).emit(any(), any());
    }

    @Test
    void firing_list_defaults_to_no_filter_and_the_first_fifty() {
        Outcome outcome = route(FrameTypes.FIRING_LIST, "{}", SIGNED_IN);
        assertEquals(Code.OK, outcome.code());
        verify(firings).list(isNull(), isNull(), eq(0), eq(50));
    }

    @Test
    void firing_list_clamps_a_limit_above_two_hundred_down_to_two_hundred() {
        Outcome outcome = route(FrameTypes.FIRING_LIST, "{\"limit\":1000}", SIGNED_IN);
        assertEquals(Code.OK, outcome.code());
        verify(firings).list(isNull(), isNull(), eq(0), eq(200));
    }

    @Test
    void firing_list_clamps_a_limit_below_one_up_to_one() {
        Outcome outcome = route(FrameTypes.FIRING_LIST, "{\"limit\":0}", SIGNED_IN);
        assertEquals(Code.OK, outcome.code());
        verify(firings).list(isNull(), isNull(), eq(0), eq(1));
    }

    @Test
    void firing_list_clamps_a_negative_offset_up_to_zero() {
        Outcome outcome = route(FrameTypes.FIRING_LIST, "{\"offset\":-5}", SIGNED_IN);
        assertEquals(Code.OK, outcome.code());
        verify(firings).list(isNull(), isNull(), eq(0), eq(50));
    }

    @Test
    void firing_list_passes_its_trigger_and_status_filters_through_unchanged() {
        Outcome outcome = route(FrameTypes.FIRING_LIST,
                "{\"trigger\":\"t\",\"status\":\"queued\",\"offset\":10,\"limit\":25}", SIGNED_IN);
        assertEquals(Code.OK, outcome.code());
        verify(firings).list("t", "queued", 10, 25);
    }

    @Test
    void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of() throws Exception {
        Map<String, String> payloads = Map.of(
                FrameTypes.SCHEDULE_LIST, "{}",
                FrameTypes.TRIGGER_LIST, "{}",
                FrameTypes.FIRING_LIST, "{}");
        for (Map.Entry<String, String> each : payloads.entrySet()) {
            FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
        }
    }

    @Test
    void the_production_routing_table_claims_every_event_type() {
        FrameRouter wired = FrameAreas.router();
        for (String type : List.of(FrameTypes.SCHEDULE_DEFINE, FrameTypes.SCHEDULE_LIST,
                FrameTypes.SCHEDULE_PAUSE, FrameTypes.SCHEDULE_FORGET, FrameTypes.TRIGGER_DEFINE,
                FrameTypes.TRIGGER_LIST, FrameTypes.TRIGGER_PAUSE, FrameTypes.TRIGGER_FORGET,
                FrameTypes.EVENT_FIRE, FrameTypes.FIRING_LIST, FrameTypes.SCHEDULE_READ)) {
            assertTrue(wired.types().contains(type), type);
        }
    }

    @Test
    void a_socket_with_no_account_cannot_have_a_sentence_read() {
        Outcome outcome = route(FrameTypes.SCHEDULE_READ, "{\"text\":\"every day at nine\"}",
                new Asking("session-1"));
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(reader, never()).read(any());
    }

    /** Unlike {@code trigger.define}: a client knows its tier and its conversation both, and
     *  which one a reading uses is not known until the sentence is read. */
    @Test
    void a_sentence_read_for_both_a_project_and_a_conversation_hands_both_to_the_reader() {
        ScheduleReader.Request asked = new ScheduleReader.Request("every day at nine", null, "p",
                "cnv_1");
        ScheduleProposal proposal = new ScheduleProposal("0 0 9 * * *", "UTC", "every day",
                "interlocutor", "Summarise.", true, null, "cnv_1", List.of(),
                new ScheduleProposal.Names("summarise-3f2a", "summarise-3f2a", "summarise-3f2a"));
        when(reader.read(asked)).thenReturn(proposal);
        Outcome outcome = route(FrameTypes.SCHEDULE_READ,
                "{\"text\":\"every day at nine\",\"project\":\"p\",\"conversation\":\"cnv_1\"}",
                SIGNED_IN);
        assertEquals(Code.OK, outcome.code());
        assertEquals(proposal, outcome.payload());
    }

    @Test
    void a_request_with_no_sentence_is_refused_before_the_model() {
        Outcome outcome = route(FrameTypes.SCHEDULE_READ, "{\"zone\":\"UTC\"}", SIGNED_IN);
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(reader, never()).read(any());
    }

    @Test
    void a_sentence_is_handed_to_the_reader_and_its_proposal_is_the_answer() {
        ScheduleProposal proposal = new ScheduleProposal("0 0 9 * * *", "Europe/London",
                "every day at 09:00", "interlocutor", "Summarise.", false, "payments", null,
                List.of(Instant.parse("2026-09-14T08:00:00Z")),
                new ScheduleProposal.Names("summarise-3f2a", "summarise-3f2a", "summarise-3f2a"));
        ScheduleReader.Request asked = new ScheduleReader.Request("every day at nine",
                "Europe/London", "payments", null);
        when(reader.read(asked)).thenReturn(proposal);

        Outcome outcome = route(FrameTypes.SCHEDULE_READ,
                "{\"text\":\"every day at nine\",\"zone\":\"Europe/London\",\"project\":\"payments\"}",
                SIGNED_IN);

        assertEquals(Code.OK, outcome.code());
        assertEquals(proposal, outcome.payload());
        verify(schedules, never()).define(any(), any(), any(), any(), any());
        verify(triggers, never()).define(any());
    }
}
