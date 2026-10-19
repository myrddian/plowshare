package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.cache.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Typed owner budgets and pre-deserialization guards; PostgreSQL proves SQL/transaction fences. */
class InformationContentTypeCacheTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final Recorded<InformationContent.Versions> lists = new Recorded<>();
  final Recorded<Optional<InformationContent.Version>> versions = new Recorded<>();
  final Recorded<Optional<InformationContent.Contract>> attached = new Recorded<>();
  final JdbcInformationContentTypeRepository repository =
      new JdbcInformationContentTypeRepository(jdbc, lists, versions, attached);
  final UUID type = UUID.randomUUID(), version = UUID.randomUUID(), revision = UUID.randomUUID();
  final SchemaDefinition schema = SchemaDefinition.fromWire(Map.of("type", "object"));
  final SchemaValue.ObjectValue presentation = DocumentMetadata.empty();
  final List<String> sql = new ArrayList<>();
  final List<List<Object>> arguments = new ArrayList<>();
  final Instant created = Instant.parse("2026-10-01T12:00:00Z");
  ResultSet row, oversized, control, lifecycle, lookahead;
  boolean empty, tooLarge, fail;
  int count = 1;

  @BeforeEach
  void setup() throws Exception {
    row = mock(ResultSet.class);
    when(row.getObject("type_id", UUID.class)).thenReturn(type);
    when(row.getObject("id", UUID.class)).thenReturn(version);
    when(row.getObject(1, UUID.class)).thenReturn(type);
    when(row.getInt("ordinal")).thenReturn(3);
    when(row.getString("slug")).thenReturn("incident");
    when(row.getString("label")).thenReturn("Incident");
    when(row.getString("description")).thenReturn("");
    when(row.getString("profile")).thenReturn(DocumentMetadata.PROFILE);
    when(row.getString("definition_hash"))
        .thenReturn(
            SchemaCodec.documentFingerprint(schema, DocumentMetadata.PROFILE, presentation));
    when(row.getString("schema_definition")).thenReturn(SchemaCodec.compactJson(schema));
    when(row.getString("presentation")).thenReturn("{}");
    when(row.getString("state")).thenReturn("draft");
    when(row.getLong("control_revision")).thenReturn(5L);
    when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(created));
    when(row.getLong("cache_rows")).thenAnswer(invocation -> empty ? 0L : (long) count);
    when(row.getLong("cache_bytes")).thenReturn(1000L);
    oversized = mock(ResultSet.class);
    when(oversized.getLong("cache_bytes")).thenReturn(Long.MAX_VALUE);
    when(oversized.getString(anyString()))
        .thenThrow(new AssertionError("Oversized schema must never cross JDBC"));
    lookahead = mock(ResultSet.class);
    when(lookahead.getLong("cache_rows")).thenReturn(2L);
    when(lookahead.getLong("cache_bytes")).thenReturn(1000L);
    when(lookahead.getString(anyString()))
        .thenThrow(new AssertionError("Lookahead proves cardinality without decoding its schema"));
    control = mock(ResultSet.class);
    when(control.getInt(1)).thenReturn(3);
    when(control.getLong(2)).thenReturn(5L);
    when(control.getString(3)).thenReturn("incident");
    when(control.getString(4)).thenReturn("Incident");
    when(control.getString(5)).thenReturn("");
    lifecycle = mock(ResultSet.class);
    when(lifecycle.getString("state")).thenReturn("active");
    when(lifecycle.getTimestamp("created_at")).thenReturn(Timestamp.from(created));
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String statement = invocation.getArgument(0);
              record(statement, (Object[]) invocation.getRawArguments()[2]);
              if (fail) throw new DataAccessResourceFailureException("fixture unavailable");
              RowMapper<Object> mapper = invocation.getArgument(1);
              if (statement.startsWith("SELECT next_version"))
                return List.of(mapper.mapRow(control, 0));
              if (statement.startsWith("SELECT t.id")) return List.of(mapper.mapRow(row, 0));
              if (empty && !statement.startsWith("WITH registry_rows")) return List.of();
              if (tooLarge && statement.startsWith("WITH registry_rows"))
                return List.of(mapper.mapRow(oversized, 0));
              var values = new ArrayList<Object>();
              values.add(mapper.mapRow(row, 0));
              if (count == 2 && !empty) values.add(mapper.mapRow(lookahead, 1));
              return values;
            });
    when(jdbc.queryForObject(
            anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              record(invocation.getArgument(0), (Object[]) invocation.getRawArguments()[2]);
              RowMapper<Object> mapper = invocation.getArgument(1);
              return mapper.mapRow(lifecycle, 0);
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              record(invocation.getArgument(0), (Object[]) invocation.getRawArguments()[2]);
              return 6L;
            });
    when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
        .thenAnswer(
            invocation -> {
              record(invocation.getArgument(0), new Object[] {});
              return true;
            });
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              record(invocation.getArgument(0), (Object[]) invocation.getRawArguments()[1]);
              return 1;
            });
  }

  private void record(String statement, Object[] bound) {
    sql.add(statement);
    arguments.add(Arrays.asList(bound.clone()));
  }

  @Test
  void warmTypedReadsSkipAllRegistryHydrationAndReturnDeeplyImmutableValues() {
    var page = repository.list("account:reader", true, 10, 0);
    var retained = repository.version("account:reader", version, false);
    var contract = repository.attached(revision, version);
    assertSame(page, repository.list("account:reader", true, 10, 0));
    assertSame(retained, repository.version("account:reader", version, false));
    assertSame(contract, repository.attached(revision, version));
    assertEquals(3, sql.size());
    assertEquals(retained.orElseThrow().contract(), contract.orElseThrow());
    assertThrows(UnsupportedOperationException.class, () -> page.results().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> contract.orElseThrow().presentation().values().clear());
    assertTrue(sql.stream().allMatch(statement -> statement.startsWith("WITH registry_rows")));
    assertFalse(
        sql.getLast().contains("t.control_revision"),
        "Attached contracts omit mutable lifecycle projection");
  }

  @Test
  void exactKeysSeparateNamespaceDraftsPageVersionAndAttachedRevision() {
    repository.list("account:reader", true, 10, 0);
    repository.list("account:reader", false, 10, 0);
    repository.list("account:other", true, 10, 0);
    repository.list("account:reader", true, 11, 0);
    repository.list("account:reader", true, 10, 1);
    repository.version("account:reader", version, false);
    repository.version("account:other", version, false);
    repository.version("account:reader", UUID.randomUUID(), false);
    repository.attached(revision, version);
    repository.attached(UUID.randomUUID(), version);
    repository.attached(revision, UUID.randomUUID());
    assertEquals(5, lists.values.size());
    assertEquals(3, versions.values.size());
    assertEquals(3, attached.values.size());
    assertEquals(List.of("account:reader", 0, 11, 1_000_000L), arguments.getFirst());
    assertTrue(sql.getFirst().contains("ORDER BY t.slug,v.ordinal DESC OFFSET ? LIMIT ?"));
    assertTrue(sql.getFirst().endsWith("ORDER BY registry_rows.slug,registry_rows.ordinal DESC"));
  }

  @Test
  void lockReadsAlwaysTakePolicyThenTypeThenVersionAndNeverUseACachedSnapshot() {
    repository.version("account:reader", version, false);
    sql.clear();
    repository.version("account:reader", version, true);
    repository.version("account:reader", version, true);
    assertEquals(6, sql.size());
    assertTrue(sql.get(0).contains("pg_advisory_xact_lock_shared"));
    assertTrue(sql.get(1).endsWith("FOR UPDATE OF t"));
    assertTrue(sql.get(2).endsWith("FOR UPDATE OF v"));
    assertTrue(sql.stream().noneMatch(statement -> statement.startsWith("WITH registry_rows")));
    assertEquals(1, versions.reads);
  }

  @Test
  void oversizedSentinelsRefuseBeforeJsonHydrationAndFallbackExactlyOncePerRead() {
    tooLarge = true;
    assertEquals(1, repository.list("account:reader", true, 10, 0).results().size());
    assertTrue(repository.version("account:reader", version, false).isPresent());
    assertTrue(repository.attached(revision, version).isPresent());
    assertEquals(6, sql.size());
    assertEquals(1, lists.fallbacks);
    assertEquals(1, versions.fallbacks);
    assertEquals(1, attached.fallbacks);
    assertTrue(lists.values.isEmpty() && versions.values.isEmpty() && attached.values.isEmpty());
  }

  @Test
  void absentRowsAndEmptyPagesAreBoundedTypedSnapshotsWithoutFallbackOrRefetch() {
    empty = true;
    assertTrue(repository.list("account:reader", true, 10, 0).results().isEmpty());
    assertFalse(repository.list("account:reader", true, 10, 0).hasMore());
    assertTrue(repository.version("account:reader", version, false).isEmpty());
    assertTrue(repository.version("account:reader", version, false).isEmpty());
    assertTrue(repository.attached(revision, version).isEmpty());
    assertTrue(repository.attached(revision, version).isEmpty());
    assertEquals(3, sql.size());
    assertEquals(0, lists.fallbacks + versions.fallbacks + attached.fallbacks);
  }

  @Test
  void lookaheadRowIsNotSchemaDecodedInEitherDirectOrConfiguredReads() {
    count = 2;
    var bounded = repository.list("account:reader", true, 1, 0);
    var direct = new JdbcInformationContentTypeRepository(jdbc).list("account:reader", true, 1, 0);
    assertEquals(1, bounded.results().size());
    assertEquals(bounded, direct);
    assertTrue(bounded.hasMore());
    assertEquals(2, sql.size());
  }

  @Test
  void malformedInputsAndRealDatabaseFailuresAreNotHiddenOrReplayed() {
    assertThrows(
        IllegalArgumentException.class, () -> repository.list("account:reader", false, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> repository.list("account:reader", false, 1, -1));
    assertThrows(IllegalArgumentException.class, () -> repository.version("", version, false));
    assertThrows(NullPointerException.class, () -> repository.attached(null, version));
    assertTrue(sql.isEmpty());
    fail = true;
    assertThrows(
        DataAccessResourceFailureException.class,
        () -> repository.version("account:reader", version, false));
    assertEquals(1, sql.size());
    assertEquals(0, versions.fallbacks);
  }

  @Test
  void createReusesValidatedDefinitionAndReturnsActualLifecycleAndControlWithoutAFullReread() {
    var result =
        repository.create(
            "account:reader", "reader", type, 5L, null, null, null, schema, presentation);
    assertSame(schema, result.contract().schema());
    assertEquals(type, result.contract().type());
    assertEquals("Incident", result.contract().label());
    assertEquals(3, result.contract().ordinal());
    assertEquals(6, result.controlRevision());
    assertEquals(
        "active", result.state(), "RETURNING is authoritative, including trigger-supplied facts");
    assertEquals(created.toString(), result.createdAt());
    assertEquals(3, sql.size());
    assertTrue(sql.getFirst().endsWith("FOR UPDATE"));
    assertTrue(sql.get(1).endsWith("RETURNING state,created_at"));
    assertTrue(sql.get(2).endsWith("RETURNING control_revision"));
    assertTrue(versions.values.isEmpty());
  }

  @Test
  void transitionDecodesOnlyTheLockedContractAndUsesReturningRatherThanPostwriteFullRead() {
    var result = repository.transition("account:reader", version, 5L, true);
    assertEquals("active", result.state());
    assertEquals(6, result.controlRevision());
    assertEquals(created.toString(), result.createdAt());
    assertEquals(6, sql.size());
    assertEquals(
        1, sql.stream().filter(statement -> statement.contains("schema_definition")).count());
    assertTrue(sql.get(2).endsWith("FOR UPDATE OF v"));
    assertTrue(sql.getLast().endsWith("RETURNING control_revision"));
    assertEquals(0, versions.reads);
    assertThrows(
        CallerFault.class, () -> repository.transition("account:reader", version, 4L, true));
    assertEquals(9, sql.size(), "Conflict adds only three fresh lock/read statements");
  }

  @Test
  void attachedCacheHitsStillRequireCurrentOwnerAdmissionBeforeEveryDisclosure() {
    var context =
        new InformationContext("reader", InformationContext.Selection.personal()).withView(true);
    var reads = mock(InformationReadAccess.class);
    var service =
        new InformationContentTypeService(
            repository,
            new InformationAccess(mock(io.aeyer.plowshare.server.access.ScopedPermissions.class)),
            mock(io.aeyer.plowshare.server.archive.ProjectMembers.class),
            reads,
            mock(io.aeyer.plowshare.server.archive.UnitOfWork.class),
            mock(InformationTransitions.class));
    assertEquals(service.get(context, version, revision), service.get(context, version, revision));
    verify(reads, times(2)).requireReadable(context, revision);
    doThrow(new CallerFault("revoked")).when(reads).requireReadable(context, revision);
    assertThrows(CallerFault.class, () -> service.get(context, version, revision));
    assertEquals(2, attached.reads, "Refused current access must not reach the retained contract");
    assertEquals(1, sql.size());
  }

  private static final class Recorded<T> implements ProjectionCache<T> {
    final ConcurrentMap<SnapshotCache.Key, T> values = new ConcurrentHashMap<>();
    int reads, fallbacks;

    public boolean enabled() {
      return true;
    }

    public long maxWeight() {
      return 1_000_000;
    }

    public T read(
        SnapshotCache.Key key,
        boolean cacheable,
        Supplier<T> loader,
        Supplier<T> fallback,
        ToLongFunction<T> weight) {
      reads++;
      if (!cacheable) {
        fallbacks++;
        return fallback.get();
      }
      T previous = values.get(key);
      if (previous != null) return previous;
      T value;
      try {
        value = loader.get();
      } catch (ProjectionCache.Bypass refused) {
        fallbacks++;
        return fallback.get();
      }
      long charged = weight.applyAsLong(value);
      assertTrue(charged > 0);
      if (charged <= maxWeight()) values.put(key, value);
      return value;
    }
  }
}
