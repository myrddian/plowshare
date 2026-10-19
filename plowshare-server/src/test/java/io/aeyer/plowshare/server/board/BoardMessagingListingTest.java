package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.board.BoardMessagingRepository.InstanceListing;
import io.aeyer.plowshare.server.board.BoardMessagingRepository.InstanceState;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class BoardMessagingListingTest {
  private final BoardMessagingRepository repository = mock(BoardMessagingRepository.class);
  private final BoardMessaging.Voice voice = mock(BoardMessaging.Voice.class);
  private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
  private final BoardMessaging service =
      new BoardMessaging(
          repository,
          null,
          null,
          null,
          null,
          UnitOfWork.NONE,
          voice,
          null,
          ignored -> {},
          () -> now);

  @Test
  void listingConsumesOneRepositorySnapshotAndPreservesStatePrecedenceWithoutPerRowQueries() {
    when(repository.listing("alice", "research", true, 0, 10))
        .thenReturn(
            List.of(
                listed("archived", true, true, true, 2, "job"),
                listed("stopped", false, false, true, 2, "job"),
                listed("awaiting", true, false, true, 2, "job"),
                listed("job", true, false, false, 2, "job"),
                listed("voice", true, false, false, 2, null),
                listed("queued", true, false, false, 2, null),
                listed("idle", true, false, false, 0, null)));
    when(voice.busy("voice")).thenReturn(true);
    var result = service.listing("alice", "research", true, 0, 10);
    assertEquals(
        List.of("archived", "stopped", "awaiting", "running", "running", "queued", "idle"),
        result.stream().map(BoardMessaging.InstanceView::state).toList());
    verify(repository).listing("alice", "research", true, 0, 10);
    verifyNoMoreInteractions(repository);
    verify(voice, never()).busy("job");
    assertEquals("job", result.get(3).job());
  }

  @Test
  void rawLookaheadAndFreshVisibilityArePreservedEvenWhenPageRowsAreWithheld() {
    when(repository.listing("alice", "research", false, 7, 3))
        .thenReturn(
            List.of(
                listed("hidden", true, false, false, 0, null),
                listed("visible", true, false, false, 0, null),
                listed("lookahead", true, false, false, 0, null)));
    var checked = new java.util.ArrayList<String>();
    service.useLogVisibility(
        (conversation, account) -> {
          assertEquals("alice", account);
          checked.add(conversation);
          return conversation.equals("visible");
        });
    var page = service.listingPage("alice", "research", false, 7, 2);
    assertTrue(page.more());
    assertEquals(7, page.offset());
    assertEquals(
        List.of("visible"),
        page.instances().stream().map(BoardMessaging.InstanceView::id).toList());
    assertEquals(List.of("hidden", "visible"), checked);
    service.listingPage("alice", "research", false, 7, 2);
    assertEquals(4, checked.size()); // Permission decisions are current on every public call.
    verify(repository, times(2)).listing("alice", "research", false, 7, 3);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void jdbcUsesOneAccountProjectBoundPageBeforeRouteAndJobAggregation() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var sql = new AtomicReference<String>();
    var bindings = new AtomicReference<List<Object>>();
    var row = mock(ResultSet.class);
    for (String field : List.of("id", "agent", "conversation", "topic", "lifetime"))
      when(row.getString(field)).thenReturn(field);
    when(row.getString("account")).thenReturn("alice");
    when(row.getString("project")).thenReturn("research");
    when(row.getString("job_id")).thenReturn("job");
    when(row.getBoolean("active")).thenReturn(true);
    when(row.getBoolean("is_default")).thenReturn(true);
    when(row.getBoolean("awaiting")).thenReturn(true);
    when(row.getInt("pending")).thenReturn(3);
    when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    doAnswer(
            call -> {
              sql.set(call.getArgument(0));
              bindings.set(Arrays.asList(((Object[]) call.getRawArguments()[2]).clone()));
              RowMapper<InstanceListing> mapper = call.getArgument(1);
              return List.of(mapper.mapRow(row, 0));
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<InstanceListing>>any(),
            any(Object[].class));
    var jdbcRepository = new JdbcBoardMessagingRepository(jdbc, mock(AllowanceRepository.class));
    var page = jdbcRepository.listing("alice", "research", false, 7, 3);
    assertEquals(List.of("alice", "research", false, 7, 3), bindings.get());
    assertEquals(3, page.getFirst().pending());
    assertTrue(page.getFirst().awaiting());
    assertEquals(Optional.of("job"), page.getFirst().job());
    assertTrue(page.getFirst().state().defaultInstance());
    assertEquals(now, page.getFirst().state().createdAt());
    assertTrue(sql.get().contains("instance_page AS MATERIALIZED"));
    assertTrue(sql.get().contains("WHERE r.handled_at IS NULL GROUP BY r.recipient"));
    assertTrue(sql.get().contains("ORDER BY f.topic,f.started_at DESC,f.id"));
    verify(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<InstanceListing>>any(),
            any(Object[].class));
    verifyNoMoreInteractions(jdbc);
    assertThrows(
        IllegalArgumentException.class,
        () -> jdbcRepository.listing("alice", "research", false, -1, 3));
  }

  @Test
  void deliveryPageUsesBatchedMetadataAndRetainsLiveInputChecksWithoutPerMessageRehydration() {
    var sender = listed("sender", true, false, false, 0, null).instance();
    var recipient = listed("recipient", true, false, false, 0, null).instance();
    when(repository.instance("sender")).thenReturn(Optional.of(sender));
    var rows = new java.util.ArrayList<BoardMessagingRepository.DeliveryListing>();
    rows.add(delivery("terminated", sender, recipient, false, true, "cancelled", true));
    rows.add(delivery("handled", sender, recipient, true, true, null, true));
    rows.add(delivery("awaiting", sender, recipient, false, true, null, true));
    rows.add(delivery("running", sender, recipient, false, false, null, true));
    rows.add(delivery("queued", sender, recipient, false, false, null, false));
    when(repository.deliveryPage("sender", 7, 5)).thenReturn(rows);
    var checked = new java.util.ArrayList<String>();
    service.useLogVisibility(
        (conversation, account) -> {
          assertEquals("alice", account);
          checked.add(conversation);
          return true;
        });
    var page = service.deliveries("sender", "alice", 7, 5);
    assertEquals(
        List.of("cancelled", "handled", "awaiting", "running", "queued"),
        page.stream().map(BoardMessaging.Delivery::state).toList());
    assertEquals(11, checked.size()); // Initial participant plus both current inputs per row.
    assertEquals("body", page.getFirst().body());
    assertEquals("final-reply", page.getFirst().reply());
    assertEquals("job", page.getFirst().job());
    verify(repository).instance("sender");
    verify(repository).deliveryPage("sender", 7, 5);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void deliveryPageNeverDisclosesForeignOrCurrentlyWithheldParticipants() {
    var sender = listed("sender", true, false, false, 0, null).instance();
    var foreign =
        new BoardMessaging.Instance(
            "recipient", "bob", "research", "agent", "foreign-log", "topic", "persistent", true);
    when(repository.instance("sender")).thenReturn(Optional.of(sender));
    when(repository.deliveryPage("sender", 0, 1))
        .thenReturn(List.of(delivery("message", sender, foreign, false, false, null, false)));
    assertThrows(Board.Refused.class, () -> service.deliveries("sender", "alice", 0, 1));
    var recipient = listed("recipient", true, false, false, 0, null).instance();
    when(repository.deliveryPage("sender", 0, 1))
        .thenReturn(List.of(delivery("message", sender, recipient, false, false, null, false)));
    service.useLogVisibility((conversation, account) -> !conversation.equals("recipient"));
    assertThrows(Board.Refused.class, () -> service.deliveries("sender", "alice", 0, 1));
  }

  private BoardMessagingRepository.DeliveryListing delivery(
      String id,
      BoardMessaging.Instance sender,
      BoardMessaging.Instance recipient,
      boolean handled,
      boolean awaiting,
      String termination,
      boolean running) {
    return new BoardMessagingRepository.DeliveryListing(
        new BoardMessaging.Route(
            id, sender.id(), recipient.id(), null, true, false, false, null, handled),
        sender,
        recipient,
        new BoardMessagingRepository.DeliveryState(awaiting, termination, now),
        Optional.of("job"),
        running,
        Optional.of("final-reply"),
        now,
        "body");
  }

  private InstanceListing listed(
      String id, boolean active, boolean archived, boolean awaiting, int pending, String job) {
    return new InstanceListing(
        new BoardMessaging.Instance(
            id, "alice", "research", "agent", id, "topic-" + id, "persistent", active),
        new InstanceState(false, archived, now),
        awaiting,
        pending,
        Optional.ofNullable(job));
  }
}
