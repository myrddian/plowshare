package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.cache.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RelayProjectionCacheTest {
  @Test
  void warmTopicsAndPagesReuseTrackedDtosWithoutAnyOwnerProjectionQuery() throws Exception {
    try (var fixture = new Fixture()) {
      var topics = fixture.store.topics(new Relay.ProjectScope(7), 10);
      assertSame(topics, fixture.store.topics(new Relay.ProjectScope(7), 10));
      assertEquals(1, fixture.queries.size());
      fixture.queries.clear();
      var page = fixture.store.read(fixture.key, 0, 10, "reader");
      assertSame(page, fixture.store.read(fixture.key, 0, 10, "reader"));
      assertEquals(5, fixture.queries.size());
      String branch =
          fixture.queries.stream()
              .filter(sql -> sql.contains("FROM relay_deliveries"))
              .findFirst()
              .orElseThrow();
      assertFalse(branch.contains("SELECT d.*"));
      assertFalse(branch.contains("handler_source"));
      assertTrue(branch.contains("d.subscriber,d.id LIMIT"));
    }
  }

  @Test
  void eachAccountScopeTopicAfterAndLimitOwnsItsSnapshotIdentity() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.store.read(fixture.key, 0, 10, "reader");
      fixture.store.read(fixture.key, 0, 10, "other-account");
      fixture.store.read(new Relay.TopicKey(8, fixture.key.name()), 0, 10, "reader");
      fixture.store.read(new Relay.TopicKey(7, "another.topic"), 0, 10, "reader");
      fixture.store.read(fixture.key, 1, 10, "reader");
      fixture.store.read(fixture.key, 0, 9, "reader");
      assertEquals(30, fixture.queries.size());
      fixture.store.read(fixture.key, 0, 10, "reader");
      assertEquals(30, fixture.queries.size());
    }
  }

  @Test
  void aWarmEntryCannotBypassCurrentMembershipOrPersonalOwnership() throws Exception {
    try (var fixture = new Fixture()) {
      var members = mock(ProjectMembers.class);
      var projects = mock(ProjectWorkspaces.class);
      when(members.mayUse("project", "reader")).thenReturn(true);
      when(projects.id("project")).thenReturn(7L);
      var inspection = new ScopedRelayInspection(members, projects, fixture.store);
      var query = new RelayLog.Query("project", false, fixture.key.name(), "0", 10);
      assertEquals(1, inspection.log("reader", query).events().size());
      int queries = fixture.queries.size();
      when(members.mayUse("project", "reader")).thenReturn(false);
      assertThrows(CallerFault.class, () -> inspection.log("reader", query));
      when(members.mayUse("project", "reader")).thenReturn(true);
      when(projects.personalOwner("project")).thenReturn(Optional.of("other-account"));
      assertThrows(CallerFault.class, () -> inspection.log("reader", query));
      assertEquals(queries, fixture.queries.size());
    }
  }

  @Test
  void oversizedRowsRefuseBeforeHydrationAndFallbackKeepsTheCompletePage() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.oversized.set(true);
      var result = fixture.store.read(fixture.key, 0, 10, "reader");
      assertEquals(
          "complete text",
          ((RelayPayload.Text) result.events().getFirst().event().payload()).text());
      assertEquals(
          7,
          fixture.queries.size(),
          "Bounded topic/events refusal, then all five ordinary projections");
      verify(fixture.refused, never()).getString(anyString());
      verify(fixture.refused, never()).getObject(anyString());
      fixture.store.read(fixture.key, 0, 10, "reader");
      assertEquals(
          14,
          fixture.queries.size(),
          "An oversize refusal cannot leave a partial resident snapshot");
    }
  }

  static ProjectionCacheProperties properties() {
    return new ProjectionCacheProperties(
        32,
        4_000_000,
        2,
        4_000_000,
        16,
        8,
        256,
        1_000_000,
        Duration.ofSeconds(5),
        Duration.ofMillis(10),
        Duration.ofMillis(10),
        128,
        8,
        2);
  }

  private static final class Fixture implements AutoCloseable {
    final Relay.TopicKey key = new Relay.TopicKey(7, "notices.status");
    final CopyOnWriteArrayList<String> queries = new CopyOnWriteArrayList<>();
    final AtomicBoolean oversized = new AtomicBoolean();
    final ResultSet refused = mock(ResultSet.class);
    final ProjectionCaches caches;
    final JdbcRelayLogRepository store;

    Fixture() throws Exception {
      var versions = mock(ProjectionVersions.class);
      var token =
          new ProjectionVersions.Token(
              new UUID(0, 1),
              Map.of(ProjectionVersions.Domain.RELAY, 1L, ProjectionVersions.Domain.AUTHORITY, 1L),
              List.of(),
              "999999",
              null,
              null);
      when(versions.databaseIdentity()).thenReturn(token.database());
      when(versions.capture(anySet())).thenReturn(Optional.of(token));
      when(versions.current(any())).thenReturn(true);
      when(versions.canShare()).thenReturn(true);
      var signals = mock(ProjectionChangeSignals.class);
      when(signals.subscribe(anySet(), any()))
          .thenAnswer(
              call -> {
                ProjectionChangeSignals.Observer observer = call.getArgument(1);
                observer.stateChanged(new ProjectionChangeSignals.State(1, true));
                ProjectionChangeSignals.Subscription subscription = () -> {};
                return subscription;
              });
      caches = new ProjectionCaches(versions, signals, properties());
      var jdbc = mock(JdbcTemplate.class);
      var topic = mock(ResultSet.class);
      when(topic.getString("payload_kind")).thenReturn("TEXT");
      when(topic.getLong("retention_seconds")).thenReturn(86400L);
      when(topic.getLong("last_position")).thenReturn(1L);
      when(topic.getObject("generation", UUID.class)).thenReturn(new UUID(0, 2));
      when(topic.getLong("projection_rows")).thenReturn(1L);
      var event = mock(ResultSet.class);
      when(event.getLong("projection_rows")).thenReturn(1L);
      when(event.getLong("position")).thenReturn(1L);
      when(event.getString("event_id")).thenReturn("event-one");
      when(event.getString("publisher")).thenReturn("publisher");
      when(event.getInt("schema_version")).thenReturn(1);
      when(event.getString("payload")).thenReturn("{\"text\":\"complete text\"}");
      when(event.getObject(anyString(), eq(OffsetDateTime.class)))
          .thenReturn(Instant.EPOCH.atOffset(ZoneOffset.UTC));
      when(refused.getLong("projection_bytes")).thenReturn(10_000_000L);
      doAnswer(
              call -> {
                String sql = call.getArgument(0);
                queries.add(sql);
                Object[] args = (Object[]) call.getRawArguments()[2];
                RowMapper<?> mapper = call.getArgument(1);
                if (sql.contains("FROM relay_topics")) {
                  when(topic.getString("scope_key")).thenReturn((String) args[0]);
                  when(topic.getString("name"))
                      .thenReturn(
                          args.length > 1 && args[1] instanceof String name ? name : key.name());
                  return List.of(Objects.requireNonNull(mapper.mapRow(topic, 0)));
                }
                if (sql.contains("FROM relay_publications"))
                  return List.of(
                      Objects.requireNonNull(
                          mapper.mapRow(
                              oversized.get() && sql.contains("projection_bytes") ? refused : event,
                              0)));
                return List.of();
              })
          .when(jdbc)
          .query(
              anyString(),
              org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
              any(Object[].class));
      var domains = Set.of(ProjectionVersions.Domain.RELAY, ProjectionVersions.Domain.AUTHORITY);
      store =
          new JdbcRelayLogRepository(
              jdbc,
              UnitOfWork.NONE,
              caches.create("fixture-topics", domains),
              caches.create("fixture-pages", domains));
    }

    public void close() {
      caches.close();
    }
  }
}
