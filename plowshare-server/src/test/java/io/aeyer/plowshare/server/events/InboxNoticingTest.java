package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.AgentTool;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InboxNoticingTest {

    private final InboxStore store = mock(InboxStore.class);
    private final Inbox inbox = mock(Inbox.class);
    private final SpeakerHandles speakers = session -> "s-enzo".equals(session)
            ? Optional.of("enzo") : Optional.empty();
    private final InboxNoticing noticing = new InboxNoticing(store, inbox, speakers);

    @Test
    void new_unread_arrivals_since_the_last_turn_are_noticed_with_when_and_how_many() {
        when(store.unreadSinceLastTurn("enzo", "cnv_talk")).thenReturn(2);
        when(store.newestUnreadSinceLastTurn("enzo", "cnv_talk"))
                .thenReturn(Optional.of(Instant.parse("2026-09-13T09:02:00Z")));
        String notice = noticing.noticeFor(null, "s-enzo", "cnv_talk").orElseThrow();
        assertTrue(notice.contains("2 new items"), notice);
        assertTrue(notice.contains("2026-09-13T09:02:00Z"), notice);
        assertTrue(notice.contains("inbox_read"), notice);
    }

    @Test
    void nothing_new_is_no_notice() {
        when(store.unreadSinceLastTurn("enzo", "cnv_talk")).thenReturn(0);
        assertTrue(noticing.noticeFor(null, "s-enzo", "cnv_talk").isEmpty());
    }

    @Test
    void a_turn_with_no_speaker_or_no_conversation_is_never_noticed() {
        assertTrue(noticing.noticeFor(null, null, "cnv_talk").isEmpty());
        assertTrue(noticing.noticeFor(null, "s-nobody", "cnv_talk").isEmpty());
        assertTrue(noticing.noticeFor(null, "s-enzo", null).isEmpty());
        assertTrue(noticing.inboxToolFor(null, null).isEmpty());
    }

    @Test
    void the_tool_reads_and_marks_the_speakers_own_unread_items() {
        InboxItem item = new InboxItem("inb_1", "enzo", "run", "fir_1", "cnv_1", "ANSWERED", "three PRs",
                Instant.parse("2026-09-13T09:02:00Z"), null);
        when(inbox.list("enzo", true, 0, InboxTool.SHOWN + 1)).thenReturn(List.of(item));
        AgentTool tool = noticing.inboxToolFor(null, "s-enzo").orElseThrow();
        assertEquals("inbox_read", tool.schema().name());
        String answer = tool.run("{}", null);
        assertTrue(answer.contains("three PRs"), answer);
        verify(inbox).read("enzo", List.of("inb_1"));
    }
}
