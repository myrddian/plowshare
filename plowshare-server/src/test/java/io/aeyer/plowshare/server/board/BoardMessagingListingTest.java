package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.messaging.JdbcMessagingRepository;
import io.aeyer.plowshare.server.messaging.MessageInboxes;
import io.aeyer.plowshare.server.messaging.MessageRefused;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.messaging.MessagingRepository;
import io.aeyer.plowshare.server.messaging.MessagingRepository.InstanceListing;
import io.aeyer.plowshare.server.messaging.MessagingRepository.InstanceState;
import io.aeyer.plowshare.server.messaging.RelayMessaging;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class BoardMessagingListingTest {
  private final MessagingRepository repository = mock(MessagingRepository.class);
  private final MessageService.Voice voice = mock(MessageService.Voice.class);
  private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
  private final RelayMessaging service =
      new RelayMessaging(
          repository,
          mock(AllowanceRepository.class),
          null,
          null,
          mock(MessageInboxes.class),
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
    when(voice.busy(listed("voice", true, false, false, 2, null).instance())).thenReturn(true);
    var result = service.listing("alice", "research", true, 0, 10);
    assertEquals(
        List.of("archived", "stopped", "awaiting", "running", "running", "queued", "idle"),
        result.stream().map(MessageService.InstanceView::state).toList());
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
    var checked = new ArrayList<String>();
    service.useLogVisibility(
        (conversation, account) -> {
          assertEquals("alice", account);
          checked.add(conversation);
          return conversation.equals("cnv_visible");
        });
    var page = service.listingPage("alice", "research", false, 7, 2);
    assertTrue(page.more());
    assertEquals(7, page.offset());
    assertEquals(
        List.of("ins_visible"),
        page.instances().stream().map(MessageService.InstanceView::id).toList());
    assertEquals(List.of("cnv_hidden", "cnv_visible"), checked);
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
    when(row.getString("id")).thenReturn("ins_fixture");
    when(row.getString("conversation")).thenReturn("cnv_fixture");
    when(row.getString("lifetime")).thenReturn("persistent");
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
            anyString(), ArgumentMatchers.<RowMapper<InstanceListing>>any(), any(Object[].class));
    var jdbcRepository = new JdbcMessagingRepository(jdbc, mock(AllowanceRepository.class));
    var page = jdbcRepository.listing("alice", "research", false, 7, 3);
    assertEquals(List.of("alice", "research", false, 7, 3), bindings.get());
    assertEquals(3, page.getFirst().pending());
    assertTrue(page.getFirst().awaiting());
    assertEquals(Optional.of("job"), page.getFirst().job());
    assertTrue(page.getFirst().state().defaultInstance());
    assertEquals(now, page.getFirst().state().createdAt());
    assertTrue(sql.get().contains("instance_page AS MATERIALIZED"));
    assertTrue(sql.get().contains("WHERE r.handled_at IS NULL GROUP BY r.recipient"));
    assertTrue(sql.get().contains("ORDER BY f.message_instance,f.started_at DESC,f.id"));
    verify(jdbc)
        .query(
            anyString(), ArgumentMatchers.<RowMapper<InstanceListing>>any(), any(Object[].class));
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
    var rows = new ArrayList<MessagingRepository.DeliveryListing>();
    rows.add(delivery("terminated", sender, recipient, false, true, "cancelled", true));
    rows.add(delivery("handled", sender, recipient, true, true, null, true));
    rows.add(delivery("awaiting", sender, recipient, false, true, null, true));
    rows.add(delivery("running", sender, recipient, false, false, null, true));
    rows.add(delivery("queued", sender, recipient, false, false, null, false));
    when(repository.deliveryPage("sender", 7, 5)).thenReturn(rows);
    var checked = new ArrayList<String>();
    service.useLogVisibility(
        (conversation, account) -> {
          assertEquals("alice", account);
          checked.add(conversation);
          return true;
        });
    var page = service.deliveries("sender", "alice", 7, 5);
    assertEquals(
        List.of("cancelled", "handled", "awaiting", "running", "queued"),
        page.stream().map(MessageService.Delivery::state).toList());
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
        new MessageService.Instance(
            "ins_recipient", "bob", "research", "agent", "cnv_foreign", "persistent", true);
    when(repository.instance("sender")).thenReturn(Optional.of(sender));
    when(repository.deliveryPage("sender", 0, 1))
        .thenReturn(List.of(delivery("message", sender, foreign, false, false, null, false)));
    assertThrows(MessageRefused.class, () -> service.deliveries("sender", "alice", 0, 1));
    var recipient = listed("recipient", true, false, false, 0, null).instance();
    when(repository.deliveryPage("sender", 0, 1))
        .thenReturn(List.of(delivery("message", sender, recipient, false, false, null, false)));
    service.useLogVisibility((conversation, account) -> !conversation.equals("cnv_recipient"));
    assertThrows(MessageRefused.class, () -> service.deliveries("sender", "alice", 0, 1));
  }

  private MessagingRepository.DeliveryListing delivery(
      String id,
      MessageService.Instance sender,
      MessageService.Instance recipient,
      boolean handled,
      boolean awaiting,
      String termination,
      boolean running) {
    return new MessagingRepository.DeliveryListing(
        new MessageService.Route(
            "bdm_" + id, sender.id(), recipient.id(), null, true, false, false, null, handled),
        sender,
        recipient,
        new MessagingRepository.DeliveryState(awaiting, termination, now),
        Optional.of("job"),
        running,
        Optional.of("final-reply"),
        now,
        "body");
  }

  private InstanceListing listed(
      String id, boolean active, boolean archived, boolean awaiting, int pending, String job) {
    return new InstanceListing(
        new MessageService.Instance(
            "ins_" + id, "alice", "research", "agent", "cnv_" + id, "persistent", active),
        new InstanceState(false, archived, now),
        awaiting,
        pending,
        Optional.ofNullable(job));
  }
}
