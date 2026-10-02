package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.llm.LlmProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Checkpoint identity, excluding allowances and batch sizes which do not change output meaning. */
public final class InformationConfiguration {
    private InformationConfiguration() {}
    public static String embedding(String model,int width){return hash("embedding-v1:"+model+":"+width);}
    public static Map<String,String> fingerprints(LlmProperties llm,DocumentsProperties documents,AgentRegistry agents){
        String summaries="summary-cascade-v1:"+documents.getSpanSize();
        if(agents!=null)for(String name:List.of("paragraph_summariser","span_summariser","section_summariser","chapter_summariser","document_summariser"))
            if(agents.names().contains(name)){var definition=agents.get(name);summaries+=":"+definition.name()+":"+definition.model()+":"+definition.intent()+":"+definition.sampling()+":"+definition.prompt();}
        return Map.of("extract",hash("retained-extraction-v1"),
            "derive",hash("anchor-derivation-v1:"+documents.getChunkTargetTokens()+":"+llm.getEmbeddingMaxInputTokens()),
            "embed",embedding(llm.getEmbeddingModel(),llm.getEmbeddingDim()),
            "summarise",hash(summaries),"summary_embed",embedding(llm.getEmbeddingModel(),llm.getEmbeddingDim()));
    }
    private static String hash(String value){return InformationCatalogue.sha256(value.getBytes(StandardCharsets.UTF_8));}
}
