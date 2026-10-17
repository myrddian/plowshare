package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Business admission uses current effective roles; persistence still owns mandatory fences. */
class KnowledgeWriteAuthorizationTest {
  final ProjectMembers members = mock(ProjectMembers.class);
  final InformationCatalogueRepository records = mock(InformationCatalogueRepository.class);
  final InformationAccess access = new InformationAccess(new EffectiveProjectPermissions(members));
  final InformationCatalogue catalogue =
      new InformationCatalogue(records, UnitOfWork.NONE, access, mock(InformationLogAccess.class));
  final InformationContext context =
      new InformationContext("writer", InformationContext.Selection.project("one"));
  final UUID revision = UUID.randomUUID(), resource = UUID.randomUUID();

  void resource() {
    when(records.lockResource(context, revision, false))
        .thenReturn(
            new InformationCatalogueRepository.Resource(
                resource,
                "project:one",
                "shared.md",
                "creator",
                1L,
                "one",
                new ScopeId(UUID.randomUUID()),
                Optional.of(new AccountId(UUID.randomUUID()))));
    when(records.row(revision))
        .thenReturn(
            new InformationCatalogueRepository.Snapshot(
                revision, resource, "document", "markdown", "active", "text", "creator", 1, 0));
  }

  @Test
  void contributor_can_retire_another_creators_resource_without_changing_its_access() {
    when(members.role("one", "writer")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    resource();
    catalogue.availability(context, revision, "withdrawn");
    verify(records).availability(context, revision, "withdrawn");
    verify(records, never()).share(any(), any(), anyBoolean());
  }

  @Test
  void content_writer_including_creator_cannot_publish_or_unpublish() {
    when(members.role("one", "writer")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    for (boolean shared : List.of(true, false))
      assertThrows(CallerFault.class, () -> catalogue.share(context, revision, shared));
    verifyNoInteractions(records);
  }

  @Test
  void reader_and_unrelated_project_refuse_before_resource_mutation() {
    for (var role : List.of(Optional.of(ProjectRole.VIEWER), Optional.<ProjectRole>empty())) {
      when(members.role("one", "writer")).thenReturn(role);
      assertThrows(CallerFault.class, () -> catalogue.availability(context, revision, "withdrawn"));
    }
    verifyNoInteractions(records);
  }

  @Test
  void repository_mandatory_refusal_is_not_overridden_by_manager() {
    when(members.role("one", "writer")).thenReturn(Optional.of(ProjectRole.MANAGER));
    doThrow(new NotFoundFault("unavailable")).when(records).lockResource(context, revision, false);
    assertThrows(NotFoundFault.class, () -> catalogue.availability(context, revision, "deleted"));
    verify(records, never()).availability(any(), any(), anyString());
  }

  @Test
  void authority_is_rechecked_after_preparation() {
    when(members.role("one", "writer")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    resource();
    catalogue.withGates(
        new InformationLifecycle.Gates() {
          @Override
          public io.aeyer.plowshare.server.hooks.Gate before(InformationLifecycle.Lease lease) {
            when(members.role("one", "writer")).thenReturn(Optional.of(ProjectRole.VIEWER));
            return io.aeyer.plowshare.server.hooks.Gate.NOTHING;
          }
        });
    assertThrows(CallerFault.class, () -> catalogue.allowance(context, revision, 10));
    verify(records, never()).allowance(any(), any(), anyInt());
  }
}
