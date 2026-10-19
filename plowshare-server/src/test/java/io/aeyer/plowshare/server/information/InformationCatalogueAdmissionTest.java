package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.documents.CodeProjection;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Immediate typed-context reuse removes duplicate decisions without retaining authority. */
class InformationCatalogueAdmissionTest {
  final InformationCatalogueRepository repository = mock(InformationCatalogueRepository.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final InformationAccess access = new InformationAccess(new EffectiveProjectPermissions(members));
  final InformationContext context =
      new InformationContext(
          "reader",
          new InformationContext.Selection(InformationContext.Scope.PROJECT, "research", false),
          InformationContext.Corpus.CODE);
  final UUID revision = UUID.randomUUID();

  InformationCatalogue catalogue(UnitOfWork work) {
    return new InformationCatalogue(repository, work, access, mock(InformationLogAccess.class));
  }

  @Test
  void syntaxPagesResolveSelectionOncePerCallAndDoNotRetainThatDecision() {
    when(members.role("research", "reader"))
        .thenReturn(Optional.of(ProjectRole.VIEWER))
        .thenReturn(Optional.of(ProjectRole.VIEWER))
        .thenReturn(Optional.empty());
    var outline =
        new Information.Outline(
            revision, "java", null, null, null, 0, "ready", null, "hash", "parser", 0, List.of(),
            false);
    var symbols = new Information.Symbols("read", 0, false, List.of(), null, null, null);
    when(repository.outline(context, revision, 0, 20)).thenReturn(outline);
    when(repository.symbols(context, "read", revision, 0, 20)).thenReturn(symbols);
    var catalogue = catalogue(UnitOfWork.NONE);

    assertSame(outline, catalogue.outline(context, revision, 0, 20));
    assertSame(symbols, catalogue.symbols(context, "read", revision, 0, 20));
    assertThrows(CallerFault.class, () -> catalogue.outline(context, revision, 0, 20));

    verify(members, times(3)).role("research", "reader");
    verify(repository).outline(context, revision, 0, 20);
    verify(repository).symbols(context, "read", revision, 0, 20);
    verifyNoMoreInteractions(repository, members);
  }

  @Test
  void codeProjectionAdmitsOnceInsideItsTransactionAndRevocationStopsTheNextCall() {
    var active = new AtomicBoolean();
    var work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> action) {
            assertFalse(active.getAndSet(true));
            try {
              return action.get();
            } finally {
              active.set(false);
            }
          }
        };
    when(members.role("research", "reader"))
        .thenAnswer(
            invocation -> {
              assertTrue(active.get(), "admit at actual transactional execution");
              return Optional.of(ProjectRole.VIEWER);
            });
    var value = new CodeProjection(revision, "class Example {}", null);
    when(repository.codeProjection(context, revision, "hash"))
        .thenAnswer(
            invocation -> {
              assertTrue(active.get());
              return value;
            });
    var catalogue = catalogue(work);

    assertSame(value, catalogue.codeProjection(context, revision, "hash"));
    assertFalse(active.get());
    doReturn(Optional.empty()).when(members).role("research", "reader");
    assertThrows(CallerFault.class, () -> catalogue.codeProjection(context, revision, "hash"));
    assertFalse(active.get());

    verify(members, times(2)).role("research", "reader");
    verify(repository).codeProjection(context, revision, "hash");
    verifyNoMoreInteractions(repository, members);
  }

  @Test
  void corpusValidationStillPrecedesSyntaxRepositoryWork() {
    when(members.role("research", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
    var documents = context.withCorpus(InformationContext.Corpus.DOCUMENTS);
    var catalogue = catalogue(UnitOfWork.NONE);

    assertThrows(CallerFault.class, () -> catalogue.outline(documents, revision, 0, 20));
    assertThrows(CallerFault.class, () -> catalogue.symbols(documents, "read", revision, 0, 20));
    assertThrows(CallerFault.class, () -> catalogue.codeProjection(documents, revision, "hash"));

    verify(members, times(3)).role("research", "reader");
    verifyNoInteractions(repository);
  }
}
