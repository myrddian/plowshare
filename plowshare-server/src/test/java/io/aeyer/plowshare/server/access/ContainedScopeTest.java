package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The contained scope's own invariants, and the one authority-substrate behaviour that must hold
 * before anything can create a child: a contained scope is not an issuance target.
 *
 * <p>Mocked throughout. The SQL text the repository locks with is the assertion, because the
 * predicate is the fence: a contained scope's access comes from its container, never from a grant
 * anyone issued against it. The real PostgreSQL CHECK constraints are asserted separately in {@code
 * ContainedScopeSchemaTest}.
 */
class ContainedScopeTest {
  private static ScopeId scope() {
    return new ScopeId(UUID.randomUUID());
  }

  @Test
  void containedScopeRequiresAContainer() {
    var id = scope();
    assertThrows(NullPointerException.class, () -> new AuthorityScope.Contained(id, 7L, null));
  }

  @Test
  void containedScopeRejectsItsOwnIdAsContainer() {
    var id = scope();
    var fault =
        assertThrows(
            IllegalArgumentException.class, () -> new AuthorityScope.Contained(id, 7L, id));
    assertTrue(fault.getMessage().contains("contain itself"), fault.getMessage());
  }

  @Test
  void containedScopeRequiresAnExistingProjectKey() {
    var id = scope();
    var container = scope();
    assertThrows(
        IllegalArgumentException.class, () -> new AuthorityScope.Contained(id, 0L, container));
    assertThrows(
        IllegalArgumentException.class, () -> new AuthorityScope.Contained(id, -1L, container));
  }

  @Test
  void containedScopeAcceptsAContainerThatIsItselfContained() {
    var grandparent = scope();
    var parent = new AuthorityScope.Contained(scope(), 7L, grandparent);
    var child = new AuthorityScope.Contained(scope(), 8L, parent.id());
    assertEquals(parent.id(), child.container());
  }

  /**
   * The containment row is resolved, not trusted. A caller-supplied {@code Contained} value is an
   * assertion about stored ancestry, and a sibling subtree's project carries the same reserved
   * prefix, so the predicate must bind all four facts at once: the row exists, it is CONTAINED, its
   * container is the one the caller named, and it maps to the project the caller named. The SQL
   * text and its bound parameters are the assertion here, as with the issuance lock above;
   * PostgreSQL's own constraints are asserted in {@code ContainedScopeSchemaTest}.
   */
  @Test
  void aContainedHomeResolvesTheStoredContainmentRow() {
    var jdbc = mock(JdbcTemplate.class);
    // No row matches, which is what a value naming a foreign project or container produces.
    when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn(List.of());
    var container = scope();
    var child = new AuthorityScope.Contained(scope(), 42L, container);

    var refusal =
        assertThrows(
            CallerFault.class,
            () -> new JdbcIdentityRepository(jdbc).containedHome(child, container));
    assertTrue(refusal.getMessage().contains("unavailable or restricted"), refusal.getMessage());

    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), eq(String.class), args.capture());
    assertTrue(sql.getValue().contains("authority_scopes"), sql.getValue());
    assertTrue(sql.getValue().contains("s.kind='CONTAINED'"), sql.getValue());
    assertTrue(sql.getValue().contains("s.container=?"), sql.getValue());
    assertTrue(sql.getValue().contains("s.project_id=?"), sql.getValue());
    assertTrue(sql.getValue().contains("p.name LIKE 'contained:%'"), sql.getValue());
    assertEquals(List.of(child.id().value(), container.value(), 42L), List.of(args.getValue()));
  }

  @Test
  void grantIssuanceExcludesContainedScopes() {
    var jdbc = mock(JdbcTemplate.class);
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    when(jdbc.getDataSource()).thenReturn(source);
    try {
      when(source.getConnection()).thenReturn(connection);
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
    // The scope row is simply not visible to the locking read, which is how a restricted scope
    // already behaves; the repository must refuse rather than issue against it.
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());
    when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(List.of());
    var grants = new JdbcScopedGrantRepository(jdbc);
    var target = scope();

    var refusal =
        assertThrows(
            CallerFault.class,
            () ->
                grants.issue(
                    new ScopedGrantRepository.Issue(
                        UUID.randomUUID(),
                        GrantRecipient.account(new AccountId(UUID.randomUUID())),
                        target,
                        StandardRole.READER,
                        GrantRecipient.account(new AccountId(UUID.randomUUID())),
                        UUID.randomUUID(),
                        Optional.empty(),
                        false)));
    assertTrue(refusal.getMessage().contains("unavailable or restricted"), refusal.getMessage());

    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(UUID.class), any(Object[].class));
    assertTrue(sql.getValue().contains("kind<>'RESTRICTED'"), sql.getValue());
    assertTrue(sql.getValue().contains("kind<>'CONTAINED'"), sql.getValue());
  }
}
