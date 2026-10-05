package io.aeyer.plowshare.server.llm.tokens;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Counts complete inputs with a pinned Hugging Face tokenizer, including its special tokens.
 * Padding and truncation are disabled even when the artifact enables them. The verified bytes,
 * rather than a second file read, initialize the native tokenizer. Closing belongs to composition.
 */
public final class PinnedTokenizer implements Tokenizer, AutoCloseable {
  private final HuggingFaceTokenizer delegate;
  private final String identity;

  public PinnedTokenizer(TokenizerFile artifact) {
    Objects.requireNonNull(artifact, "tokenizer artifact");
    try {
      if (Files.size(artifact.file()) > 64 * 1024 * 1024)
        throw new IllegalArgumentException("tokenizer artifact exceeds 64 MiB");
      byte[] bytes = Files.readAllBytes(artifact.file());
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      if (!actual.equals(artifact.sha256()))
        throw new IllegalArgumentException(
            "tokenizer artifact checksum does not match configuration");
      // Counting is local; disable the dependency's optional cloud telemetry as well.
      System.setProperty("OPT_OUT_TRACKING", "true");
      delegate =
          HuggingFaceTokenizer.newInstance(
              new ByteArrayInputStream(bytes),
              Map.of("truncation", "false", "padding", "false", "addSpecialTokens", "true"));
      identity = "huggingface-untruncated-v1:" + actual;
    } catch (IOException failure) {
      throw new IllegalArgumentException("cannot read the configured tokenizer artifact", failure);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  @Override
  public TokenCount count(String text) {
    Objects.requireNonNull(text, "tokenizer input");
    return TokenCount.measured(delegate.encode(text).getIds().length, identity);
  }

  @Override
  public String describe() {
    return identity;
  }

  @Override
  public void close() {
    delegate.close();
  }
}
