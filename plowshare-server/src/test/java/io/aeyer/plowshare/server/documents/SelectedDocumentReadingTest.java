package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.embedding.QueryEmbeddings.Origin;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Reader;
import io.aeyer.plowshare.server.information.InformationReadSelection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelectedDocumentReadingTest {
  @Test
  void results_are_batch_retained_after_inference_and_revocation_refuses_disclosure() {
    var repository = mock(SelectedDocumentRepository.class);
    var passages = mock(SelectedPassages.class);
    var service = new SelectedDocumentReading(repository, passages);
    UUID one = UUID.randomUUID(), two = UUID.randomUUID();
    var selected =
        new InformationReadSelection(
            new Reader("reader", null, null), List.of(UUID.randomUUID().toString()));
    var home = new InformationContext("reader", InformationContext.Selection.project("execution"));
    var valid = new AtomicInteger();
    var origin = new Origin("invocation", valid::incrementAndGet);
    when(passages.retrieve(eq(selected), eq(home), any(), eq("q"), isNull(), eq(5)))
        .thenAnswer(
            call -> {
              var checked = call.getArgument(2, Origin.class);
              assertEquals(origin.invocation(), checked.invocation());
              checked.validity().run();
              return List.of(hit(one), hit(two), hit(one));
            });
    var result = service.retrieve(selected, home, origin, "q", null, 5);
    assertEquals(3, result.hits().size());
    verify(repository, times(2)).require(selected, null);
    verify(repository).retain(selected, List.of(one, two));
    assertEquals(2, valid.get());
    doThrow(new NotFoundFault("revoked")).when(repository).retain(selected, List.of(one, two));
    assertThrows(NotFoundFault.class, () -> service.retrieve(selected, home, origin, "q", null, 5));
  }

  private static DocumentStore.Retrieved hit(UUID document) {
    return new DocumentStore.Retrieved(
        new DocumentStore.ChunkWithAncestors(
            UUID.randomUUID(),
            "text",
            UUID.randomUUID(),
            1,
            null,
            new DocumentStore.Placement.InNoSection(),
            document,
            "source",
            "title",
            null),
        .1);
  }
}
