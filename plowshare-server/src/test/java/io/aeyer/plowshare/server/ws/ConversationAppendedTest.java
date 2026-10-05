package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.archive.EntryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The push that says a followed log grew (2026-09-28-the-log-is-the-source §3). */
class ConversationAppendedTest {

  private Watchers watchers;
  private EntryStore entries;
  private Map<String, List<Object>> told;
  private ConversationAppended appended;

  @BeforeEach
  void setUp() {
    watchers = new Watchers();
    entries = mock(EntryStore.class);
    told = new ConcurrentHashMap<>();
    appended =
        new ConversationAppended(
            watchers,
            entries,
            (session, body) -> told.computeIfAbsent(session, s -> new ArrayList<>()).add(body));
  }

  @Test
  void a_session_following_the_conversation_is_told_how_far_its_log_now_reaches() {
    when(entries.through("cnv_x")).thenReturn(12);
    watchers.follows("s-x", "cnv_x");
    watchers.follows("s-y", "cnv_y");

    appended.appended("cnv_x");

    assertEquals(
        Map.of("s-x", List.of(new io.aeyer.plowshare.protocol.ConversationGrowth("cnv_x", 12))),
        told);
  }

  @Test
  void a_session_that_moved_on_is_not_told_about_the_conversation_it_left() {
    when(entries.through("cnv_x")).thenReturn(3);
    watchers.follows("s-x", "cnv_x");
    watchers.follows("s-x", "cnv_z");

    appended.appended("cnv_x");

    assertEquals(Map.of(), told);
  }

  @Test
  void multiple_logs_reach_their_followers_once_each_without_displacing_other_views() {
    when(entries.through("cnv_x")).thenReturn(3);
    when(entries.through("cnv_y")).thenReturn(8);
    watchers.follows("desktop", Set.of("cnv_x", "cnv_y"));
    watchers.follows("terminal", "cnv_x");
    watchers.follows("unrelated", "cnv_z");

    appended.appended("cnv_x");
    appended.appended("cnv_y");

    Object x = new io.aeyer.plowshare.protocol.ConversationGrowth("cnv_x", 3);
    Object y = new io.aeyer.plowshare.protocol.ConversationGrowth("cnv_y", 8);
    assertEquals(Map.of("desktop", List.of(x, y), "terminal", List.of(x)), told);
  }

  @Test
  void replacing_views_stops_notifications_for_closed_logs_but_keeps_remaining_views() {
    when(entries.through("cnv_y")).thenReturn(8);
    watchers.follows("desktop", Set.of("cnv_x", "cnv_y"));
    watchers.follows("desktop", Set.of("cnv_y"));

    appended.appended("cnv_x");
    appended.appended("cnv_y");

    verify(entries, never()).through("cnv_x");
    assertEquals(
        Map.of("desktop", List.of(new io.aeyer.plowshare.protocol.ConversationGrowth("cnv_y", 8))),
        told);
  }

  @Test
  void a_closed_session_is_told_nothing() {
    watchers.follows("s-x", Set.of("cnv_x", "cnv_y"));
    watchers.forget("s-x");

    appended.appended("cnv_x");
    appended.appended("cnv_y");

    assertEquals(Map.of(), told);
  }

  @Test
  void a_conversation_nobody_follows_is_not_even_read() {
    appended.appended("cnv_x");

    verify(entries, never()).through(any());
  }

  @Test
  void a_log_that_cannot_be_read_is_not_the_turn_s_failure() {
    watchers.follows("s-x", "cnv_x");
    when(entries.through("cnv_x")).thenThrow(new IllegalStateException("database gone"));

    assertDoesNotThrow(() -> appended.appended("cnv_x"));
    assertEquals(Map.of(), told);
  }
}
