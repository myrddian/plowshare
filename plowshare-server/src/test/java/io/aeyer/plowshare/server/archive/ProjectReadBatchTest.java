package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.information.InformationJson;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ProjectReadBatchTest {
  @Test
  void candidate_membership_uses_one_bound_query_and_retains_no_authorization_cache() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
    var members = new JdbcProjectMembers(jdbc);
    clearInvocations(jdbc);
    when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn(List.of("allowed"))
        .thenReturn(List.of());
    var names = java.util.stream.IntStream.range(0, 1000).mapToObj(index -> "p" + index).toList();
    var candidates = new java.util.ArrayList<>(names);
    candidates.add("allowed");
    candidates.add("allowed");
    var admitted = members.readableProjects(candidates, "reader");
    assertEquals(Set.of("allowed"), admitted);
    assertThrows(UnsupportedOperationException.class, () -> admitted.add("other"));
    assertTrue(members.readableProjects(candidates, "reader").isEmpty());
    var bound = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, times(2)).queryForList(anyString(), eq(String.class), bound.capture());
    var arguments = bound.getValue();
    assertEquals("reader", arguments[0]);
    assertEquals(io.aeyer.plowshare.server.personal.PersonalSpaces.name("reader"), arguments[1]);
    assertEquals(false, arguments[2]);
    assertEquals(
        InformationJson.json(candidates.stream().distinct().sorted().toList()), arguments[3]);
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void service_identity_and_candidate_names_remain_bound_values() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
    var members = new JdbcProjectMembers(jdbc);
    clearInvocations(jdbc);
    when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn(List.of());
    assertTrue(members.readableProjects(List.of("project'quoted"), "@service/fixture").isEmpty());
    var bound = ArgumentCaptor.forClass(Object[].class);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(String.class), bound.capture());
    assertEquals(true, bound.getValue()[2]);
    assertFalse(sql.getValue().contains("project'quoted"));
    assertTrue(sql.getValue().contains("service_project_role"));
    assertTrue(sql.getValue().contains("authority_project_role"));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void empty_or_invalid_candidates_never_execute_sql() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
    var members = new JdbcProjectMembers(jdbc);
    clearInvocations(jdbc);
    assertTrue(members.readableProjects(List.of(), "reader").isEmpty());
    for (String account : Arrays.asList(null, "", " reader", "reader\n", "x".repeat(257)))
      assertThrows(
          IllegalArgumentException.class, () -> members.readableProjects(List.of("p"), account));
    for (String name : Arrays.asList(null, "", " p", "p\n", "x".repeat(1025)))
      assertThrows(
          IllegalArgumentException.class,
          () -> members.readableProjects(Arrays.asList(name), "reader"));
    assertThrows(IllegalArgumentException.class, () -> members.readableProjects(null, "reader"));
    verifyNoInteractions(jdbc);
  }

  @Test
  void default_capability_keeps_effective_application_and_lineage_checks() {
    ProjectMembers members = mock(ProjectMembers.class, CALLS_REAL_METHODS);
    when(members.mayUse("allowed", "reader")).thenReturn(true);
    when(members.mayUse("denied", "reader")).thenReturn(false);
    assertEquals(
        Set.of("allowed"),
        members.readableProjects(List.of("allowed", "allowed", "denied"), "reader"));
    verify(members).mayUse("allowed", "reader");
    verify(members).mayUse("denied", "reader");
  }
}
