package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.information.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InformationToolTest {
    private static final UUID REVISION=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant INGESTED=Instant.parse("2026-10-02T07:59:00.123456Z");
    private final ObjectMapper json=new ObjectMapper();
    private final InformationAccess access=mock(InformationAccess.class);
    private final InformationJobs inputs=mock(InformationJobs.class);
    private final InformationCatalogue catalogue=mock(InformationCatalogue.class);
    private final RetrievalService retrieval=mock(RetrievalService.class);
    private final InformationContext context=new InformationContext("alice",InformationContext.Selection.personal());
    private final List<UUID> read=new ArrayList<>();

    private InformationTool reader() {
        when(access.forRun("alice",Home.global())).thenReturn(context);
        when(inputs.reads("cnv_reader",context)).thenReturn(read::add);
        when(retrieval.scoped(access,context)).thenReturn(retrieval);
        return new InformationTool(false,()->catalogue).withRetrieval(retrieval)
                .forRun(access,inputs,"alice","cnv_reader");
    }

    @Test void rank_serializes_real_document_timestamps_and_retains_input_dependencies() throws Exception {
        var document=new DocumentStore.StoredDocument(REVISION,"paper.pdf","A paper","hash","textHash",
                12,INGESTED,"alice","summary",null);
        when(retrieval.rank("UFO sightings",10)).thenReturn(new RetrievalService.Ranking(
                List.of(new DocumentStore.Ranked(document,.25)),new DocumentStore.Ranking(1,2)));
        String result=reader().run("{\"operation\":\"rank\",\"query\":\"UFO sightings\",\"limit\":10}",Home.global());
        assertTrue(result.startsWith("{"),result);
        var ranked=json.readTree(result);
        assertEquals(REVISION.toString(),ranked.path("documents").get(0).path("document").path("id").asText());
        assertEquals(INGESTED.toString(),ranked.path("documents").get(0).path("document").path("ingestedAt").asText());
        assertEquals(.25,ranked.path("documents").get(0).path("distance").asDouble());
        assertEquals(2,ranked.path("corpus").path("unranked").asInt());
        assertEquals(List.of(REVISION),read);verify(inputs,times(2)).requireLog("cnv_reader","alice");
    }

    @Test void catalogue_and_processing_status_serialize_temporal_values() throws Exception {
        when(catalogue.list(context,20,0)).thenReturn(List.of(Map.of("id",REVISION,"ingested_at",INGESTED)));
        when(catalogue.status(context,REVISION)).thenReturn(Map.of("revision",REVISION,"updated_at",INGESTED));
        var tool=reader();
        var listed=json.readTree(tool.run("{\"operation\":\"list\"}",Home.global()));
        assertEquals(INGESTED.toString(),listed.get(0).path("ingested_at").asText());
        var status=json.readTree(tool.run("{\"operation\":\"status\",\"revision\":\""+REVISION+"\"}",Home.global()));
        assertEquals(INGESTED.toString(),status.path("updated_at").asText());
        verify(catalogue).requireReadable(context,REVISION);assertEquals(List.of(REVISION,REVISION),read);
    }
}
