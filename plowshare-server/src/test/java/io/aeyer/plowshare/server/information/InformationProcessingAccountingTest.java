package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class InformationProcessingAccountingTest {
    @ParameterizedTest
    @ValueSource(strings = {"embed", "summary_embed"})
    void processing_embeddings_use_the_retained_owned_log(String stage) {
        UUID revision = UUID.randomUUID();
        var catalogue = mock(InformationCatalogue.class);
        var store = mock(DocumentStore.class);
        var embeddings = mock(EmbeddingClient.class);
        when(catalogue.row(revision)).thenReturn(Map.of("processing_log", "pipeline-log"));
        when(store.fenced(any())).thenReturn(store);
        var owner = UsageAttribution.project("alice", "project-id", UsageAttribution.Operation.EMBEDDING_WRITE)
                .withExecution(UsageLineage.root("pipeline-log"),
                        UsageLineage.NONE, UsageLineage.NONE, "document_pipeline", 0L, null);
        UsageOwners owners = new UsageOwners() {
            @Override public UsageAttribution in(io.aeyer.plowshare.protocol.Home home, String account, UsageAttribution.Operation operation) {
                throw new AssertionError("processing must retain its conversation");
            }
            @Override public UsageAttribution conversation(String log, int turn, UsageAttribution.Operation operation) {
                assertEquals("pipeline-log", log);
                assertEquals(UsageAttribution.Operation.EMBEDDING_WRITE, operation);
                return owner;
            }
        };
        float[] vector = {1, 0};
        UUID chunk = UUID.randomUUID();
        when(store.unembedded(revision)).thenReturn(List.of(new DocumentStore.UnembeddedChunk(chunk, "passage")));
        when(embeddings.embedAll(List.of("passage"), owner)).thenReturn(List.of(vector));
        when(store.documentSummary(revision)).thenReturn("summary");
        when(store.summariesAwaitingAVector()).thenReturn(List.of(new DocumentStore.UnembeddedSummary(revision, "summary")));
        when(embeddings.embed("summary", owner)).thenReturn(vector);
        var processor = InformationLifecycle.processing(mock(JdbcTemplate.class), mock(UnitOfWork.class), catalogue,
                store, embeddings, null, 10, 2, () -> null, new DocumentsProperties(), null, owners);
        processor.process(new InformationLifecycle.Lease(revision, UUID.randomUUID(), 1, stage, 1,
                UUID.randomUUID(), "alice", 1L), () -> false, () -> {});
        if (stage.equals("embed")) {
            verify(embeddings).embedAll(List.of("passage"), owner);
            verify(store).attach(chunk, vector);
        } else {
            verify(embeddings).embed("summary", owner);
            verify(store).attachSummaryEmbedding(revision, vector);
        }
        verify(embeddings, never()).embedAll(any());
        verify(embeddings, never()).embed(any());
    }
}
