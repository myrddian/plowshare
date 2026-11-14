package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.embedding.QueryEmbeddings;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Reader;
import io.aeyer.plowshare.server.information.InformationReadSelection;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class SelectedPassagesTest {
  @Test
  void selected_reader_copies_cannot_become_projection_writers() {
    var jdbc = mock(JdbcTemplate.class);
    var selected =
        new InformationReadSelection(
            new Reader("reader", null, null), List.of(UUID.randomUUID().toString()));
    var reader =
        new DocumentStore(jdbc, UnitOfWork.NONE).selected(selected, null).audited(id -> {});
    assertThrows(IllegalStateException.class, () -> reader.fenced(() -> {}));
    assertThrows(
        IllegalStateException.class,
        () -> reader.attachDocumentSummary(UUID.randomUUID(), "changed"));
    verifyNoInteractions(jdbc);
  }

  @Test
  void scoped_source_selection_keeps_inference_admission_in_the_retained_execution_home() {
    var store = mock(DocumentStore.class);
    var selectedStore = mock(DocumentStore.class);
    var embeddings = mock(EmbeddingClient.class);
    var queries = mock(QueryEmbeddings.class);
    var read = mock(QueryEmbeddings.Read.class);
    UUID document = UUID.randomUUID();
    var selected =
        new InformationReadSelection(
            new Reader("reader", null, null), List.of(UUID.randomUUID().toString()));
    var home = new InformationContext("reader", InformationContext.Selection.project("execution"));
    var origin = QueryEmbeddings.Origin.request(() -> {});
    when(store.selected(selected, document)).thenReturn(selectedStore);
    when(queries.open(eq(home), any(), eq(origin))).thenReturn(read);
    when(embeddings.forOperation(read)).thenReturn(embeddings);
    when(read.disclose(any())).thenAnswer(call -> call.getArgument(0, Supplier.class).get());
    when(selectedStore.hasRetrievable(document)).thenReturn(true);
    float[] vector = {1, 0};
    when(embeddings.embed(eq("question"), any(UsageAttribution.class))).thenReturn(vector);
    when(selectedStore.retrieve(document, vector, 5)).thenReturn(List.of());
    var service = new RetrievalService(store, embeddings, "fixture", 2);
    service.useQueries(queries);
    assertTrue(service.retrieve(selected, home, origin, "question", document, 5).isEmpty());
    var query = ArgumentCaptor.forClass(QueryEmbeddings.Query.class);
    verify(queries).open(eq(home), query.capture(), eq(origin));
    assertNull(
        query.getValue().revision(),
        "The source revision is fenced by selected source authority, not misattributed to the execution home");
    assertTrue(query.getValue().mode().contains(document.toString()));
    assertTrue(query.getValue().mode().contains(selected.scopes().getFirst()));
    verify(selectedStore).retrieve(document, vector, 5);
    verify(read).close();
  }
}
