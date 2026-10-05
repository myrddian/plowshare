package io.aeyer.plowshare.server.llm.tokens;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

public class PinnedTokenizerTest {
  public static TokenizerFile fixture() throws Exception {
    Path file = Path.of(PinnedTokenizerTest.class.getResource("/tokenizers/fixture.json").toURI());
    return new TokenizerFile(
        file,
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
  }

  @Test
  void countsAllTokensIncludingSpecialsDespiteArtifactTruncationSetting() throws Exception {
    try (var tokenizer = new PinnedTokenizer(fixture())) {
      assertEquals(3, tokenizer.count("hello").tokens());
      assertEquals(3002, tokenizer.count("a:2 ".repeat(1000)).tokens());
      assertEquals(10002, tokenizer.count("hello ".repeat(10000)).tokens());
      assertTrue(tokenizer.count("hello").isMeasured());
    }
  }

  @Test
  void mismatchedArtifactsAndInvalidConfigurationAreRefused() throws Exception {
    var configured = fixture();
    assertThrows(
        IllegalArgumentException.class,
        () -> new PinnedTokenizer(new TokenizerFile(configured.file(), "0".repeat(64))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenizerFile(Path.of("relative.json"), configured.sha256()));
    assertThrows(
        IllegalArgumentException.class, () -> new TokenizerFile(configured.file(), "unpinned"));
  }
}
