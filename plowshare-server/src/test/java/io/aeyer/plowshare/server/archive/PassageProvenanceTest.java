package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.agents.InputProvenance;
import io.aeyer.plowshare.server.documents.Chunking;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.tokens.FixtureTokenizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class PassageProvenanceTest {
  @Test
  void chunksNeverCrossSuppliedContextBoundariesEvenInsideASentence() {
    var index =
        new PassageIndex(
            mock(PassageRepository.class),
            mock(EmbeddingClient.class),
            new Chunking(new FixtureTokenizer(4), 80, 100),
            "fixture",
            768);
    String prefix = "Investigate 🌱 ";
    String copied = "orchard capacity documentation";
    String suffix = " and report the actual failure.";
    String text = prefix + copied + suffix;
    int start = prefix.codePointCount(0, prefix.length());
    var source =
        new PassageIndex.Source(
            "entry",
            "conversation:1",
            "hash",
            text,
            0,
            new InputProvenance(start, start + copied.length(), List.of()));
    var passages = index.projectedPassages(source);
    assertEquals(3, passages.size());
    assertFalse(passages.get(0).suppliedContext());
    assertTrue(passages.get(1).suppliedContext());
    assertEquals(copied, passages.get(1).text());
    assertFalse(passages.get(2).suppliedContext());
  }
}
