package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.tokens.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingChunkingTest {
  private static EmbeddingProperties.Model model(String id, String prefix, int limit)
      throws Exception {
    return new EmbeddingProperties.Model(
        id,
        "weights",
        3,
        "",
        prefix,
        "cls",
        EmbeddingSpace.Normalization.NONE,
        "none",
        limit,
        "exact",
        EmbeddingSearchPolicy.Distance.COSINE,
        PinnedTokenizerTest.fixture());
  }

  @Test
  void denseProseAndCodeSlicesFitBothCompleteInputsAndRetainTheSource() throws Exception {
    var code = model("code", "", 32);
    var prose = model("prose", "search_document: ", 24);
    try (var tokenizers =
        new ConfiguredEmbeddingTokenizers(
            new EmbeddingProperties(true, code, prose, false, List.of()), null)) {
      var bounds = new Chunking(tokenizers.documents(), 12, tokenizers.documentLimit(1536));
      String dense = "a:2 ".repeat(2000);
      var chunks = Chunker.chunk(dense, bounds);
      assertTrue(chunks.size() > 1);
      assertEquals(
          dense.trim(), String.join(" ", chunks.stream().map(Chunker.Chunk::text).toList()));
      var slices =
          CodeDerivation.derive(
                  new Extracted("fixture.java", "hash", dense, List.of(), "none"), bounds)
              .paragraphs()
              .getFirst()
              .chunks();
      assertEquals(dense, String.join("", slices.stream().map(Chunker.Chunk::text).toList()));
      for (var entry : List.of(chunks, slices))
        for (var chunk : entry) {
          assertTrue(
              tokenizers.model(code.definition()).count(chunk.text()).tokens()
                  <= code.maxInputTokens());
          assertTrue(
              tokenizers
                      .model(prose.definition())
                      .count(prose.documentPrefix() + chunk.text())
                      .tokens()
                  <= prose.maxInputTokens());
        }
    }
  }

  @Test
  void unconfiguredAndEstimatedCountersCannotProduceChunksOrEmbeddings() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Chunker.chunk("a:2 ".repeat(2000), new Chunking(new RatioTokenizer(4), 400, 1536)));
    try (var tokenizers = new ConfiguredEmbeddingTokenizers(null, null)) {
      assertThrows(EmbeddingException.class, () -> tokenizers.legacy().count("text"));
    }
  }

  @Test
  void encoderRevisionChangesRequireAnExplicitCounterAndOldServingCountersMayBeRetained()
      throws Exception {
    var code = model("code", "", 32);
    var prose = model("prose", "", 32);
    var old = model("old", "", 32);
    var properties =
        new EmbeddingProperties(
            true,
            code,
            prose,
            false,
            List.of(
                new EmbeddingProperties.TokenizerBinding(
                    old.modelId(), old.modelRevision(), old.tokenizer())));
    try (var tokenizers = new ConfiguredEmbeddingTokenizers(properties, null)) {
      assertEquals(3, tokenizers.model(old.definition()).count("hello").tokens());
      assertThrows(
          EmbeddingException.class, () -> tokenizers.model(model("unknown", "", 32).definition()));
    }
  }

  @Test
  void preprocessingThatLeavesNoRoomForOneCharacterIsRefused() throws Exception {
    var code = model("code", "", 2);
    var prose = model("prose", "search_document: ", 2);
    try (var tokenizers =
        new ConfiguredEmbeddingTokenizers(
            new EmbeddingProperties(true, code, prose, false, List.of()), null)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> Chunker.chunk("a", new Chunking(tokenizers.documents(), 2, 2)));
    }
  }
}
