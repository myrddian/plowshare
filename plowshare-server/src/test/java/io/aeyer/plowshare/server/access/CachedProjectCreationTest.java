package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.Binding;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.Coverage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CachedProjectCreationTest {
  private static final Instant AT = Instant.parse("2026-11-14T00:00:00Z");
  private static final UUID ROOT = new UUID(0, 100);
  private static final UUID SAM = new UUID(0, 200);
  private static final UUID MIA = new UUID(0, 201);
  private static final UUID ADMIN = new UUID(0, 202);

  private final ProjectCreatorGrants stored = mock(ProjectCreatorGrants.class);

  private static Binding binding(
      UUID id,
      UUID subject,
      ScopeId scope,
      StandardRole role,
      Coverage coverage,
      UUID issuer,
      Optional<UUID> authority,
      String provenance,
      String handle) {
    return new Binding(
        id,
        GrantRecipient.account(new AccountId(subject)),
        scope,
        role,
        role,
        coverage,
        GrantRecipient.account(new AccountId(issuer)),
        authority,
        Optional.empty(),
        1,
        1,
        false,
        provenance,
        OffsetDateTime.ofInstant(AT.minusSeconds(60), ZoneOffset.UTC),
        Optional.empty(),
        Optional.empty(),
        Set.of(),
        handle,
        Optional.empty());
  }

  /** The SYSTEM root and one SYSTEM-rooted creator grant to sam, issued by admin. */
  private static AuthoritySnapshot snapshot() {
    UUID system = BuiltInIdentities.SYSTEM.value();
    var root =
        binding(
            ROOT,
            system,
            BuiltInIdentities.ADMINISTRATION,
            StandardRole.SYSTEM,
            Coverage.ALL_SCOPES,
            system,
            Optional.empty(),
            "ROOT_POLICY",
            system.toString());
    var creator =
        binding(
            new UUID(0, 101),
            SAM,
            BuiltInIdentities.PROJECT_CREATION,
            StandardRole.PROJECT_CREATOR,
            Coverage.SELF,
            ADMIN,
            Optional.of(ROOT),
            "ROLE",
            "sam");
    return new AuthoritySnapshot(
        new AuthoritySnapshot.Facts(
            AT,
            List.of(
                new AuthoritySnapshot.Account(system.toString(), "BUILTIN", false, false, false),
                new AuthoritySnapshot.Account("sam", "USER", true, false, false),
                new AuthoritySnapshot.Account("mia", "USER", true, false, false),
                new AuthoritySnapshot.Account("admin", "USER", true, false, true)),
            Map.of(system, system.toString(), SAM, "sam", MIA, "mia", ADMIN, "admin"),
            List.of(
                new AuthoritySnapshot.Role(
                    BuiltInIdentities.SYSTEM_ROLE.value(), "SYSTEM", Optional.of(system), false),
                new AuthoritySnapshot.Role(
                    BuiltInIdentities.PROJECT_CREATOR.value(),
                    "PROJECT_CREATOR",
                    Optional.empty(),
                    true)),
            List.of(
                new AuthoritySnapshot.Scope(
                    BuiltInIdentities.ADMINISTRATION.value(),
                    "ADMINISTRATION",
                    Optional.of(system),
                    Optional.empty(),
                    Optional.of("SYSTEM_ADMINISTRATION")),
                new AuthoritySnapshot.Scope(
                    BuiltInIdentities.PROJECT_CREATION.value(),
                    "PROJECT_CREATION",
                    Optional.of(system),
                    Optional.empty(),
                    Optional.of("PROJECT_CREATION"))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new AuthoritySnapshot.Grant(root, BuiltInIdentities.SYSTEM_ROLE.value()),
                new AuthoritySnapshot.Grant(creator, BuiltInIdentities.PROJECT_CREATOR.value()))));
  }

  @Test
  void answers_from_snapshot_when_available() {
    var snapshot = snapshot();
    var creation = new CachedProjectCreation(stored, () -> Optional.of(snapshot));
    assertTrue(creation.mayCreate("sam"));
    assertFalse(creation.mayCreate("mia"));
    assertFalse(creation.mayCreate("admin"));
    assertFalse(creation.mayCreate("nobody"));
    verifyNoInteractions(stored);
  }

  @Test
  void falls_back_to_repository_without_snapshot() {
    when(stored.holds("sam")).thenReturn(true);
    var creation = new CachedProjectCreation(stored, AuthoritySnapshots.none());
    assertTrue(creation.mayCreate("sam"));
    assertFalse(creation.mayCreate("mia"));
    verify(stored).holds("sam");
    verify(stored).holds("mia");
  }

  @Test
  void null_or_blank_handle_is_false() {
    var snapshot = snapshot();
    var cached = new CachedProjectCreation(stored, () -> Optional.of(snapshot));
    var fallback = new CachedProjectCreation(stored, AuthoritySnapshots.none());
    for (var creation : List.of(cached, fallback)) {
      assertFalse(creation.mayCreate(null));
      assertFalse(creation.mayCreate(""));
      assertFalse(creation.mayCreate("  "));
    }
    verifyNoInteractions(stored);
  }
}
