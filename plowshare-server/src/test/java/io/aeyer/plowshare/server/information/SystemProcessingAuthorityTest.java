package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The worker's poll receives the SYSTEM decision as one of three fixed literals, and a snapshot
 * without the intrinsic grant decides "not authorized" for every scope rather than deferring.
 */
class SystemProcessingAuthorityTest {
  @Test
  void theDecisionReachesSqlOnlyAsAFixedLiteral() {
    assertTrue(
        InformationQueueSql.processingAuthority(Optional.of(true))
            .contains("information_system_processing_revisions(TRUE::boolean)"));
    assertTrue(
        InformationQueueSql.processingAuthority(Optional.of(false))
            .contains("information_system_processing_revisions(FALSE::boolean)"));
    assertTrue(
        InformationQueueSql.processingAuthority(Optional.empty())
            .contains("information_system_processing_revisions(NULL::boolean)"));
  }

  @Test
  void withoutTheIntrinsicGrantNoScopeIsValid() {
    var snapshot =
        new AuthoritySnapshot(
            new AuthoritySnapshot.Facts(
                Instant.parse("2026-11-14T00:00:00Z"),
                List.of(),
                Map.of(),
                List.of(),
                List.of(
                    new AuthoritySnapshot.Scope(
                        new java.util.UUID(0, 1),
                        "KNOWLEDGE",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("GLOBAL_KNOWLEDGE"))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    assertFalse(snapshot.systemProcessingAuthorized());
    assertFalse(snapshot.systemScopeValid(null), "A scope alone does not authorize processing");
    assertFalse(snapshot.systemScopeValid("anything"));
  }

  @Test
  void sqlDecidesWhenNoSnapshotIsAvailable() {
    var sql = SystemProcessingAuthority.sql();
    assertTrue(sql.processing().isEmpty());
    assertTrue(sql.scopeValid("one").isEmpty());
  }
}
