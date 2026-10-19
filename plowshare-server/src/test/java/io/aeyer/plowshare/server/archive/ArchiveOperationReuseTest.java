package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationDeployment.Release;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.events.ScheduleDefinitionStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Per-operation query budgets. PostgreSQL tests own RETURNING, locks and rollback semantics. */
class ArchiveOperationReuseTest {
  @TempDir Path temporary;
  private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

  @Test
  void deploymentDecodesTheLockedActiveReleaseOnceAndPassesThatSamePlacementToItsOwner()
      throws Exception {
    var jdbc = transactionalJdbc();
    var projects = mock(ServerProjects.class);
    var previousId = UUID.randomUUID();
    var nextId = UUID.randomUUID();
    var destination = new FileStoreReference("sources", "application");
    var previousPlacement =
        new ApplicationPlacement(
            new FileStoreReference("sources", "application/revisions/" + previousId), List.of());
    var nextPlacement =
        new ApplicationPlacement(
            new FileStoreReference("sources", "application/revisions/" + nextId), List.of());
    var previous =
        new ApplicationDeploymentStore.Retained(
            new Release(previousId, "a".repeat(64), 1), destination, previousPlacement);
    var next =
        new ApplicationDeploymentStore.Retained(
            new Release(nextId, "b".repeat(64), 1), destination, nextPlacement);
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class)))
        .thenReturn(List.of(previousId));
    var releaseReads = new ArrayList<String>();
    doAnswer(
            call -> {
              String sql = call.getArgument(0);
              if (sql.contains("WHERE p.name = ? AND s.revision = ?")) {
                releaseReads.add(sql);
                return List.of(previous);
              }
              return List.of();
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    var store =
        new JdbcApplicationDeploymentStore(
            jdbc, UnitOfWork.NONE, projects, mock(ScheduleDefinitionStore.class));
    var request =
        new ApplicationDeploymentStore.Mutation(
            "application",
            UUID.randomUUID(),
            previousId,
            "c".repeat(64),
            next,
            temporary.toAbsolutePath(),
            true);
    assertEquals(next.release(), store.commit("alice", request).release());
    assertEquals(1, releaseReads.size());
    assertFalse(releaseReads.getFirst().contains("SELECT s.*"));
    verify(projects)
        .activateDeployment(
            "application", nextPlacement, previousPlacement, temporary.toAbsolutePath(), "alice");
  }

  @Test
  void accessResolvesManagementAndOrdinaryRoleAtMostOnceWithinTheOperation() throws Exception {
    var jdbc = transactionalJdbc();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
        .thenReturn(false);
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(jdbc.queryForList(
            contains("authority_project_role"), eq(String.class), any(Object[].class)))
        .thenReturn(List.of("VIEWER"));
    var members = new JdbcProjectMembers(jdbc);
    assertEquals(ProjectRole.VIEWER, members.access("research", "alice").role());
    verify(jdbc)
        .queryForObject(
            contains("authority_project_management_grant"),
            eq(Boolean.class),
            eq("research"),
            eq("alice"));
    verify(jdbc)
        .queryForList(
            contains("authority_project_role"), eq(String.class), eq("research"), eq("alice"));
    clearInvocations(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    assertEquals(List.of("manage"), members.access("research", "alice").permissions());
    verify(jdbc)
        .queryForObject(
            contains("authority_project_management_grant"),
            eq(Boolean.class),
            eq("research"),
            eq("alice"));
    verify(jdbc, never())
        .queryForList(contains("authority_project_role"), eq(String.class), any(Object[].class));
  }

  @Test
  void proposalSuccessReturnsTheWinningProjectionWhileNoteAndReleaseKeepTheirPriorReads()
      throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var state = new AtomicReference<>("pending");
    var row = proposalRow(state);
    var statements = new ArrayList<String>();
    var emptyWrite = new AtomicBoolean();
    doAnswer(
            call -> {
              String sql = call.getArgument(0);
              statements.add(sql);
              if (sql.contains("WITH written AS") && emptyWrite.get()) return List.of();
              RowMapper<Proposal> mapper = call.getArgument(1);
              return List.of(mapper.mapRow(row, 0));
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Proposal>>any(),
            any(Object[].class));
    var proposals = new ProposalStore(jdbc, () -> NOW, () -> "prp_one");
    assertEquals(
        Home.of("research"), proposals.propose("mem_one", "promote", "reason", "alice").home());
    assertEquals(1, statements.size());
    assertTrue(statements.getFirst().contains("RETURNING *"));
    assertTrue(statements.getFirst().contains("FROM written p JOIN memories m"));
    state.set("accepted");
    proposals.resolve("prp_one", true, null, "alice");
    assertEquals(2, statements.size());
    proposals.note("prp_one", "done");
    assertEquals(4, statements.size()); // Current pre-transition snapshot, then winning write.
    proposals.release("prp_one");
    assertEquals(6, statements.size());
    assertTrue(statements.getLast().contains("WHERE id = ? AND state <> ?"));
    emptyWrite.set(true);
    assertThrows(ArchiveException.class, () -> proposals.resolve("prp_one", true, null, "alice"));
    assertEquals(
        8, statements.size()); // Failed UPDATE still reads to explain unknown/settled state.
  }

  @Test
  void attachClientReturnsItsUpdateProjectionWithoutASecondReadOfTheSameRow() throws Exception {
    var jdbc = transactionalJdbc();
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    String key = ClientProjects.key("workspace", "session", "alice");
    var updated =
        new ProjectRecord(
            key, temporary, List.of(), List.of(), "DISJOINT", List.of("."), null, List.of());
    var statements = new ArrayList<String>();
    doAnswer(
            call -> {
              statements.add(call.getArgument(0));
              return List.of(updated);
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<ProjectRecord>>any(),
            any(Object[].class));
    var projects =
        new ProjectStore(
            jdbc,
            temporary.resolve("configuration"),
            temporary.resolve("sampling"),
            temporary.resolve("credential"),
            temporary.resolve("exports"),
            temporary.resolve("data"));
    assertSame(
        updated,
        projects.attachClient(
            "workspace", temporary.toString(), "client-machine", "session", "alice"));
    assertEquals(1, statements.size());
    assertTrue(statements.getFirst().startsWith("UPDATE projects"));
    assertTrue(statements.getFirst().contains("RETURNING name, workspace"));
    verify(jdbc, never())
        .queryForObject(
            startsWith("SELECT name, workspace"),
            org.mockito.ArgumentMatchers.<RowMapper<ProjectRecord>>any(),
            any(Object[].class));
  }

  private ResultSet proposalRow(AtomicReference<String> state) throws Exception {
    var row = mock(ResultSet.class);
    when(row.getString("id")).thenReturn("prp_one");
    when(row.getString("memory_id")).thenReturn("mem_one");
    when(row.getString("project")).thenReturn("research");
    when(row.getString("action")).thenReturn("promote");
    when(row.getString("reason")).thenReturn("reason");
    when(row.getString("state")).thenAnswer(ignored -> state.get());
    when(row.getObject("created_at", OffsetDateTime.class))
        .thenReturn(NOW.atOffset(ZoneOffset.UTC));
    when(row.getObject("resolved_at", OffsetDateTime.class))
        .thenReturn(NOW.atOffset(ZoneOffset.UTC));
    when(row.getString("resolved_by")).thenReturn("alice");
    return row;
  }

  private JdbcTemplate transactionalJdbc() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var dataSource = mock(DataSource.class);
    when(jdbc.getDataSource()).thenReturn(dataSource);
    when(dataSource.getConnection()).thenReturn(mock(Connection.class));
    return jdbc;
  }
}
