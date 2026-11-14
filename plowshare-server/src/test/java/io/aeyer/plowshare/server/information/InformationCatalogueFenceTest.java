package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.server.access.AuthorityWithdrawnException;
import io.aeyer.plowshare.server.access.OperationFence;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The run's {@link OperationFence} runs inside the transaction that commits an information write,
 * after that transaction's locks and before its first durable write, so a revocation it observes
 * rolls the write back. Database behaviour is mocked: the assertions are about ordering.
 */
class InformationCatalogueFenceTest {
  private final InformationCatalogueRepository repository =
      mock(InformationCatalogueRepository.class);
  private final InformationAccess access = mock(InformationAccess.class);
  private final InformationContentAuthority authority = mock(InformationContentAuthority.class);
  private final InformationProjectionRepository projections =
      mock(InformationProjectionRepository.class);
  private final InformationTransitions gates = mock(InformationTransitions.class);
  private final UnitOfWork transactions = mock(UnitOfWork.class);
  private final UnitOfWork gateWork = mock(UnitOfWork.class);
  private final OperationFence fence = mock(OperationFence.class);
  private final List<String> events = new ArrayList<>();
  private final InformationContext context =
      new InformationContext("author", InformationContext.Selection.personal());
  private final UUID expected = UUID.randomUUID();
  private final UUID request = UUID.randomUUID();
  private final UUID resource = UUID.randomUUID();
  private final InformationCatalogue.Admission admitted =
      new InformationCatalogue.Admission(UUID.randomUUID(), UUID.randomUUID(), true);
  private InformationCatalogue catalogue;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void composed() {
    recording(transactions, "tx");
    recording(gateWork, "gate");
    // The write gate's committing transaction, reduced to its shape: prepare outside, then run
    // the prepared transition inside one unit of work.
    when(gates.executePrepared(any(), any(), anyString(), any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              Supplier<InformationTransitions.Prepared<InformationGateResult>> prepare =
                  call.getArgument(7);
              var prepared = prepare.get();
              return gateWork.inTransaction(() -> prepared.transition().get());
            });
    when(gates.execute(any(), any(), anyString(), any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              Supplier<InformationGateResult> transition = call.getArgument(7);
              return gateWork.inTransaction(transition);
            });
    doAnswer(
            call -> {
              events.add("fence");
              return null;
            })
        .when(fence)
        .require();
    when(repository.lockResource(context, expected, false))
        .thenAnswer(
            call -> {
              events.add("lock");
              return new InformationCatalogueRepository.Resource(
                  resource,
                  "account:author",
                  "notes.md",
                  "author",
                  null,
                  null,
                  null,
                  Optional.empty());
            });
    when(repository.row(expected))
        .thenReturn(
            new InformationCatalogueRepository.Snapshot(
                expected, resource, "document", "text", "active", "quoted text", "author", 1, 0));
    when(repository.readable(context, expected)).thenReturn(true);
    when(repository.inputs(expected)).thenReturn(List.of());
    when(authority.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());
    catalogue =
        new InformationCatalogue(
            repository,
            transactions,
            access,
            mock(InformationLogAccess.class),
            authority,
            mock(InformationContentRepository.class),
            projections,
            gates);
  }

  private void recording(UnitOfWork work, String name) {
    when(work.inTransaction(any()))
        .thenAnswer(
            call -> {
              Supplier<?> body = call.getArgument(0);
              events.add(name + ":begin");
              try {
                Object result = body.get();
                events.add(name + ":commit");
                return result;
              } catch (RuntimeException failed) {
                events.add(name + ":rollback");
                throw failed;
              }
            });
  }

  private void preparedRevision() {
    var manifest =
        new InformationProjectionRepository.Manifest(
            expected, resource, 1, "a".repeat(64), Map.of(), List.of());
    when(projections.prepare(eq(context), eq(request), eq(expected), any(), any()))
        .thenReturn(new InformationProjectionRepository.Prepared(manifest, null));
    when(projections.copy(eq(context), eq(request), any(), any()))
        .thenAnswer(
            call -> {
              events.add("copy");
              return admitted;
            });
  }

  @Test
  void informationReviseRunsFenceInsideTransaction() {
    preparedRevision();

    var result =
        catalogue.reviseMetadata(
            context,
            request,
            expected,
            new InformationContent.Typing("clear", null, null),
            "session",
            fence);

    assertEquals(admitted, result);
    var order = inOrder(gateWork, repository, fence, projections);
    order.verify(gateWork).inTransaction(any());
    order.verify(repository).lockResource(context, expected, false);
    order.verify(fence).require();
    order.verify(projections).copy(eq(context), eq(request), any(), any());
    order.verify(repository).event(eq(admitted.revision()), eq(1L), any(), any(), any(), any());
    int begin = events.lastIndexOf("gate:begin");
    assertEquals(
        List.of("gate:begin", "lock", "fence", "copy", "gate:commit"),
        events.subList(begin, events.size()));
  }

  @Test
  void informationReviseRefusedByTheFenceRollsBackBeforeCopying() {
    preparedRevision();
    doAnswer(
            call -> {
              events.add("fence");
              throw new AuthorityWithdrawnException("revoked");
            })
        .when(fence)
        .require();

    assertThrows(
        AuthorityWithdrawnException.class,
        () ->
            catalogue.reviseMetadata(
                context,
                request,
                expected,
                new InformationContent.Typing("clear", null, null),
                "session",
                fence));

    verify(projections, never()).copy(any(), any(), any(), any());
    assertEquals("gate:rollback", events.getLast());
  }

  @Test
  void uploadRunsFenceInsideAdmissionBeforeTheRevisionIsWritten() {
    when(repository.admit(eq(context), eq(request), any()))
        .thenAnswer(
            call -> {
              events.add("admit");
              return admitted;
            });

    catalogue.admitForSession(
        context,
        request,
        "notes.md",
        "body".getBytes(StandardCharsets.UTF_8),
        "text/plain",
        null,
        "session",
        null,
        null,
        fence);

    var order = inOrder(transactions, fence, repository);
    order.verify(transactions).inTransaction(any());
    order.verify(fence).require();
    order.verify(repository).admit(eq(context), eq(request), any());
    int begin = events.lastIndexOf("tx:begin");
    assertEquals(
        List.of("tx:begin", "fence", "admit", "tx:commit"), events.subList(begin, begin + 4));
  }

  @Test
  void evidenceRunsFenceInsideItsRecordingTransaction() {
    var text = mock(InformationCatalogueRepository.AuthorizedText.class);
    when(text.text()).thenReturn("quoted text");
    when(repository.authorizedText(any(), eq(expected))).thenReturn(Optional.of(text));
    UUID recorded = UUID.randomUUID();
    when(repository.recordEvidence(
            eq(context), eq(expected), anyInt(), anyInt(), anyString(), anyString(), eq(request)))
        .thenAnswer(
            call -> {
              events.add("record");
              return recorded;
            });

    assertEquals(
        recorded,
        catalogue.evidenceForSession(
            context, expected, 0, 6, "quoted", "extracted-text:utf16", request, "session", fence));

    int begin = events.lastIndexOf("tx:begin");
    assertEquals(
        List.of("tx:begin", "fence", "record", "tx:commit"), events.subList(begin, begin + 4));
  }

  @Test
  void callersWithoutARunKeepTheUnfencedOverloads() {
    preparedRevision();
    catalogue.reviseMetadata(
        context, request, expected, new InformationContent.Typing("clear", null, null), "session");
    verify(fence, never()).require();
  }
}
