package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Source release and destination administration are independent of model/provenance labels. */
class ScopedPromotionAccessTest {
  PromotionSourceRepository sources = mock(PromotionSourceRepository.class);
  ProjectMembers members = mock(ProjectMembers.class);
  IdentityRepository identities = mock(IdentityRepository.class);
  ScopedAuthority authority = mock(ScopedAuthority.class);
  ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  ScopedGrantRepository.Binding binding = mock(ScopedGrantRepository.Binding.class);
  UUID grant = UUID.randomUUID();
  AccountId actor = new AccountId(UUID.randomUUID());
  AuthorityScope source = new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);
  AuthorityScope global =
      new AuthorityScope.AccountKnowledge(new ScopeId(UUID.randomUUID()), BuiltInIdentities.GLOBAL);
  ScopedPromotionAccess access =
      new ScopedPromotionAccess(sources, members, identities, authority, grants);

  @BeforeEach
  void setup() {
    when(identities.account("human"))
        .thenReturn(
            new IdentityRepository.Account(
                actor, "human", IdentityRepository.AccountKind.USER, false));
    when(identities.project("one")).thenReturn(source);
    when(identities.knowledge(BuiltInIdentities.GLOBAL))
        .thenReturn((AuthorityScope.AccountKnowledge) global);
    when(sources.source("proposal"))
        .thenReturn(Optional.of(new PromotionSourceRepository.Source(source.id(), "one", true)));
    when(grants.live(GrantRecipient.account(actor))).thenReturn(List.of(binding));
    when(binding.id()).thenReturn(grant);
    when(binding.scope()).thenReturn(global.id());
    when(binding.role()).thenReturn(StandardRole.ADMIN);
    when(binding.coverage()).thenReturn(ScopedGrantRepository.Coverage.SELF);
    when(members.role("one", "human")).thenReturn(Optional.of(ProjectRole.MANAGER));
  }

  @Test
  void source_manager_alone_cannot_copy_into_global() {
    assertThrows(CallerFault.class, () -> access.require("human", "proposal"));
    when(authority.allowsUsing(GrantRecipient.account(actor), global, AuthorityAction.WRITE, grant))
        .thenReturn(true);
    assertThrows(CallerFault.class, () -> access.require("human", "proposal"));
    when(authority.allowsUsing(
            GrantRecipient.account(actor), global, AuthorityAction.MANAGE_SCOPE, grant))
        .thenReturn(true);
    assertDoesNotThrow(() -> access.require("human", "proposal"));
    when(binding.role()).thenReturn(StandardRole.MANAGER);
    assertThrows(CallerFault.class, () -> access.require("human", "proposal"));
    when(binding.role()).thenReturn(StandardRole.ADMIN);
    when(members.role("one", "human")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    assertThrows(CallerFault.class, () -> access.require("human", "proposal"));
  }

  @Test
  void absent_private_misbound_and_nonhuman_sources_fail_before_the_copy() {
    assertThrows(CallerFault.class, () -> access.require(null, "proposal"));
    when(sources.source("proposal")).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> access.require("human", "proposal"));
    when(sources.source("proposal"))
        .thenReturn(Optional.of(new PromotionSourceRepository.Source(source.id(), "one", false)));
    assertThrows(CallerFault.class, () -> access.require("human", "proposal"));
    when(sources.source("proposal"))
        .thenReturn(
            Optional.of(
                new PromotionSourceRepository.Source(new ScopeId(UUID.randomUUID()), "one", true)));
    assertThrows(CallerFault.class, () -> access.require("human", "proposal"));
    when(identities.account("app"))
        .thenReturn(
            new IdentityRepository.Account(
                actor, "app", IdentityRepository.AccountKind.SERVICE, false));
    assertThrows(CallerFault.class, () -> access.require("app", "proposal"));
    verifyNoInteractions(authority);
  }
}
