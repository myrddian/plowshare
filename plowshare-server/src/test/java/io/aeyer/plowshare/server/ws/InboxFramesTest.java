package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxItem;
import io.aeyer.plowshare.server.events.InboxStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InboxFramesTest {

    private Inbox inbox;
    private InboxStore store;
    private FrameRouter router;

    @BeforeEach
    void setUp() {
        inbox = mock(Inbox.class);
        store = mock(InboxStore.class);
        router = new FrameRoutingConfig().frameRouter(List.of(new InboxFrames(inbox, store)));
    }

    @Test
    void the_list_is_the_callers_own_with_its_unread_count() {
        InboxItem item = new InboxItem("inb_1", "enzo", "run", "fir_1", "cnv_1", "ANSWERED", "hi",
                Instant.parse("2026-09-13T09:00:00Z"), null);
        when(inbox.list("enzo", true, 0, 50)).thenReturn(List.of(item));
        when(store.unread("enzo")).thenReturn(1);
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_LIST, "{\"unread\":true}"),
                new Asking("s", "enzo"));
        assertEquals(Code.OK, outcome.code());
        assertEquals(new InboxListHandler.Page(List.of(item), 1), outcome.payload());
    }

    @Test
    void reading_marks_only_the_callers_items() {
        when(inbox.read("enzo", List.of("inb_1"))).thenReturn(1);
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_READ, "{\"items\":[\"inb_1\"]}"),
                new Asking("s", "enzo"));
        assertEquals(Code.OK, outcome.code());
        verify(inbox).read("enzo", List.of("inb_1"));
    }

    @Test
    void a_socket_with_no_account_has_no_inbox() {
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_LIST, "{}"), new Asking("s"));
        assertEquals(Code.BAD_REQUEST, outcome.code());
    }

    @Test
    void list_defaults_to_no_filter_and_the_first_fifty() {
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_LIST, "{}"),
                new Asking("s", "enzo"));
        assertEquals(Code.OK, outcome.code());
        verify(inbox).list("enzo", false, 0, 50);
    }

    @Test
    void list_clamps_a_limit_above_two_hundred_down_to_two_hundred() {
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_LIST, "{\"limit\":1000}"),
                new Asking("s", "enzo"));
        assertEquals(Code.OK, outcome.code());
        verify(inbox).list("enzo", false, 0, 200);
    }

    @Test
    void list_clamps_a_limit_below_one_up_to_one() {
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_LIST, "{\"limit\":0}"),
                new Asking("s", "enzo"));
        assertEquals(Code.OK, outcome.code());
        verify(inbox).list("enzo", false, 0, 1);
    }

    @Test
    void list_clamps_a_negative_offset_up_to_zero() {
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_LIST, "{\"offset\":-5}"),
                new Asking("s", "enzo"));
        assertEquals(Code.OK, outcome.code());
        verify(inbox).list("enzo", false, 0, 50);
    }

    @Test
    void reading_with_no_items_is_a_bad_request_and_nothing_is_marked() {
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_READ, "{}"),
                new Asking("s", "enzo"));
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(inbox, never()).read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reading_with_empty_items_is_a_bad_request_and_nothing_is_marked() {
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_READ, "{\"items\":[]}"),
                new Asking("s", "enzo"));
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(inbox, never()).read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void a_socket_with_no_account_cannot_read() {
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.INBOX_READ, "{\"items\":[\"inb_1\"]}"),
                new Asking("s"));
        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(inbox, never()).read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of() throws Exception {
        when(inbox.read("enzo", List.of("inb_1"))).thenReturn(1);
        java.util.Map<String, String> payloads = java.util.Map.of(
                FrameTypes.INBOX_LIST, "{}",
                FrameTypes.INBOX_READ, "{\"items\":[\"inb_1\"]}");
        for (java.util.Map.Entry<String, String> each : payloads.entrySet()) {
            FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
        }
    }

    @Test
    void the_production_routing_table_claims_every_inbox_type() {
        FrameRouter wired = FrameAreas.router();
        for (String type : List.of(FrameTypes.INBOX_LIST, FrameTypes.INBOX_READ)) {
            assertTrue(wired.types().contains(type), type);
        }
    }
}
