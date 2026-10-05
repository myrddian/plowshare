package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.llm.LlmProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Checkpoint identity, excluding allowances and batch sizes which do not change output meaning. */
public final class InformationConfiguration {
  private InformationConfiguration() {}

  public static String embedding(String model, int width) {
    return hash("embedding-v1:" + model + ":" + width);
  }

  public static Map<String, String> fingerprints(
      LlmProperties llm, DocumentsProperties documents, AgentRegistry agents) {
    String summaries = "summary-cascade-v1:" + documents.getSpanSize();
    if (agents != null)
      for (String name :
          List.of(
              "paragraph_summariser",
              "span_summariser",
              "section_summariser",
              "chapter_summariser",
              "document_summariser"))
        if (agents.names().contains(name)) {
          var definition = agents.get(name);
          summaries +=
              ":"
                  + definition.name()
                  + ":"
                  + definition.model()
                  + ":"
                  + definition.intent()
                  + ":"
                  + definition.sampling()
                  + ":"
                  + definition.prompt();
        }
    String tags = "automatic-topic-tags-v1:12000";
    if (agents != null && agents.names().contains("information_tagger")) {
      var definition = agents.get("information_tagger");
      tags +=
          ":"
              + definition.model()
              + ":"
              + definition.intent()
              + ":"
              + definition.sampling()
              + ":"
              + definition.prompt();
    }
    String groups = "tag-categories-v1";
    if (agents != null && agents.names().contains("information_tag_grouper")) {
      var definition = agents.get("information_tag_grouper");
      groups +=
          ":"
              + definition.model()
              + ":"
              + definition.intent()
              + ":"
              + definition.sampling()
              + ":"
              + definition.prompt();
    }
    return Map.of(
        "tagGroups",
        hash(groups),
        "autoTag",
        hash(tags),
        "extract",
        hash("retained-extraction-v1"),
        "derive",
        hash(
            "anchor-derivation-v1:"
                + documents.getChunkTargetTokens()
                + ":"
                + llm.getEmbeddingMaxInputTokens()),
        "embed",
        embedding(llm.getEmbeddingModel(), llm.getEmbeddingDim()),
        "summarise",
        hash(summaries),
        "summary_embed",
        embedding(llm.getEmbeddingModel(), llm.getEmbeddingDim()));
  }

  public static Map<String, String> fingerprints(
      LlmProperties llm,
      DocumentsProperties documents,
      AgentRegistry agents,
      io.aeyer.plowshare.server.embedding.DualEmbeddings dual) {
    var result = new HashMap<>(fingerprints(llm, documents, agents));
    if (dual != null) {
      String spaces = hash(dual.fingerprint());
      result.put("embed", spaces);
      result.put("summary_embed", spaces);
    }
    return Map.copyOf(result);
  }

  /**
   * Chunk derivation must be invalidated when either encoder's tokenizer or input contract changes.
   */
  public static Map<String, String> fingerprints(
      LlmProperties llm,
      DocumentsProperties documents,
      AgentRegistry agents,
      io.aeyer.plowshare.server.embedding.DualEmbeddings dual,
      io.aeyer.plowshare.server.embedding.EmbeddingTokenizers tokenizers) {
    var result = new HashMap<>(fingerprints(llm, documents, agents, dual));
    if (tokenizers != null)
      result.put("derive", hash(result.get("derive") + ":" + tokenizers.fingerprint()));
    return Map.copyOf(result);
  }

  private static String hash(String value) {
    return InformationCatalogue.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
