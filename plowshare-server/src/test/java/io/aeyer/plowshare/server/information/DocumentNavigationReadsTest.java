package io.aeyer.plowshare.server.information;

import static io.aeyer.plowshare.protocol.InformationNavigation.METADATA_PROJECTION;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.InformationNavigation.*;
import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Service admission stays live even when the repository can share a projection. */
class DocumentNavigationReadsTest {
  final InformationNavigationRepository repository = mock(InformationNavigationRepository.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final DocumentNavigation service =
      new DocumentNavigation(
          repository,
          new InformationAccess(new EffectiveProjectPermissions(members)),
          UnitOfWork.NONE,
          true);
  final InformationRequest.Selection project =
      new InformationRequest.Selection(
          new InformationRequest.Scope("project", "research", false), "documents", null);
  final InformationContext context =
      new InformationContext(
              "reader",
              new InformationContext.Selection(InformationContext.Scope.PROJECT, "research", false))
          .withView(true);

  @Test
  void projectedPageUsesOneRepositoryCapabilityAndRechecksMembershipOnEveryRead() {
    when(members.role("research", "reader"))
        .thenReturn(Optional.of(ProjectRole.VIEWER))
        .thenReturn(Optional.empty());
    var request =
        new InformationRequest.DocumentList(
            new InformationRequest.Selection(
                project.scope(), "documents", null, "content-types-v1"),
            null,
            null,
            null,
            20,
            0,
            METADATA_PROJECTION);
    when(repository.documentsWithMetadata(context, null, null, null, 20, 0))
        .thenReturn(new Documents(List.of(), 20, 0, METADATA_PROJECTION));
    var result =
        assertInstanceOf(
            io.aeyer.plowshare.protocol.InformationResponse.NavigationDocuments.class,
            service.execute("reader", request));
    assertEquals(METADATA_PROJECTION, result.value().projection());
    assertThrows(CallerFault.class, () -> service.execute("reader", request));
    verify(repository, times(2)).snapshotSingleProjection();
    verify(repository, times(2)).requireActive("reader");
    verify(members, times(2)).role("research", "reader");
    verify(repository).documentsWithMetadata(context, null, null, null, 20, 0);
    verifyNoMoreInteractions(repository, members);
  }

  @Test
  void listsAndFacetsChooseSingleProjectionBoundaryBeforeLiveAdmission() {
    when(members.role("research", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
    when(repository.documents(context, null, null, null, 20, 0))
        .thenReturn(new Documents(List.of(), 20, 0));
    var counts = new HashMap<String, List<Information.FacetCount>>();
    var more = new HashMap<String, Boolean>();
    for (String key :
        List.of(
            "kind", "author", "documentAuthor", "tagGroup", "subtype", "when", "tags", "autoTag")) {
      counts.put(key, List.of());
      more.put(key, false);
    }
    when(repository.facets(context, null, null, null))
        .thenReturn(
            new Information.Facets(0, counts, more, new Information.TagGraph(List.of(), false)));

    service.execute(
        "reader", new InformationRequest.DocumentList(project, null, null, null, 20, 0));
    service.execute("reader", new InformationRequest.DocumentFacets(project, null, null, null));

    var order = inOrder(repository, members);
    order.verify(repository).snapshotSingleProjection();
    order.verify(repository).requireActive("reader");
    order.verify(members).role("research", "reader");
    order.verify(repository).documents(context, null, null, null, 20, 0);
    order.verify(repository).snapshotSingleProjection();
    order.verify(repository).requireActive("reader");
    order.verify(members).role("research", "reader");
    order.verify(repository).facets(context, null, null, null);
    verify(repository, never()).snapshot();
    verifyNoMoreInteractions(repository, members);
  }

  @Test
  void relationshipPagesUseTheSingleProjectionBoundaryAndLiveSelectionAdmission() {
    UUID resource = UUID.randomUUID();
    when(members.role("research", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
    when(repository.relationships(context, resource, "outgoing", "cites", null, 20, 0))
        .thenReturn(new Connections(List.of(), 20, 0));

    service.execute(
        "reader",
        new InformationRequest.RelationshipList(
            project, resource, "outgoing", "cites", null, 20, 0));

    var order = inOrder(repository, members);
    order.verify(repository).snapshotSingleProjection();
    order.verify(repository).requireActive("reader");
    order.verify(members).role("research", "reader");
    order.verify(repository).relationships(context, resource, "outgoing", "cites", null, 20, 0);
    verifyNoMoreInteractions(repository, members);
  }

  @Test
  void singleProjectionResourceDenialAfterAdmissionPropagatesWithoutReplay() {
    when(members.role("research", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
    var denied = new NotFoundFault("authority changed before projection");
    when(repository.documents(context, null, null, null, 20, 0)).thenThrow(denied);

    assertSame(
        denied,
        assertThrows(
            NotFoundFault.class,
            () ->
                service.execute(
                    "reader",
                    new InformationRequest.DocumentList(project, null, null, null, 20, 0))));

    verify(repository).snapshotSingleProjection();
    verify(repository).requireActive("reader");
    verify(members).role("research", "reader");
    verify(repository).documents(context, null, null, null, 20, 0);
    verifyNoMoreInteractions(repository, members);
  }

  @Test
  void multiStepSelectionKeepsHistoricalSnapshotAndPropagatesResourceDenial() {
    var target = new Target(UUID.randomUUID(), null);
    var denied = new NotFoundFault("resource no longer readable");
    when(members.role("research", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
    when(repository.select(context, target)).thenThrow(denied);

    assertSame(
        denied,
        assertThrows(
            NotFoundFault.class,
            () -> service.execute("reader", new InformationRequest.DocumentGet(project, target))));

    var order = inOrder(repository, members);
    order.verify(repository).snapshot();
    order.verify(repository).requireActive("reader");
    order.verify(members).role("research", "reader");
    order.verify(repository).select(context, target);
    verifyNoMoreInteractions(repository, members);
  }

  @Test
  void aPriorProjectionNeverAdmitsTheNextOperationAfterRevocation() {
    when(members.role("research", "reader"))
        .thenReturn(Optional.of(ProjectRole.VIEWER))
        .thenReturn(Optional.empty());
    when(repository.documents(context, null, null, null, 20, 0))
        .thenReturn(new Documents(List.of(), 20, 0));
    var request = new InformationRequest.DocumentList(project, null, null, null, 20, 0);

    service.execute("reader", request);
    assertThrows(CallerFault.class, () -> service.execute("reader", request));

    verify(members, times(2)).role("research", "reader");
    verify(repository, times(2)).snapshotSingleProjection();
    verify(repository, times(2)).requireActive("reader");
    verify(repository).documents(context, null, null, null, 20, 0);
    verifyNoMoreInteractions(repository, members);
  }

  @Test
  void savedViewRechecksScopeAfterLoadingBecauseApplicationAuthorityCanChange() {
    UUID id = UUID.randomUUID();
    when(members.role("research", "reader"))
        .thenReturn(Optional.of(ProjectRole.VIEWER))
        .thenReturn(Optional.empty());
    when(repository.view("reader", id))
        .thenReturn(
            new SavedView(
                id, "Research", new Path(List.of(new ScopeStep(project.scope()))), 1, false));

    assertThrows(
        CallerFault.class,
        () -> service.execute("reader", new InformationRequest.NavigationViewGet(project, id)));

    var order = inOrder(repository, members);
    order.verify(repository).snapshot();
    order.verify(repository).requireActive("reader");
    order.verify(members).role("research", "reader");
    order.verify(repository).view("reader", id);
    order.verify(members).role("research", "reader");
    verifyNoMoreInteractions(repository, members);
  }
}
