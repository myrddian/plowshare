package io.aeyer.plowshare.server.llm.tokens;

import java.nio.file.Path;

/** A local tokenizer artifact pinned to the served model; runtime never downloads model files. */
public record TokenizerFile(Path file, String sha256) {
  public TokenizerFile {
    if (file == null || !file.isAbsolute())
      throw new IllegalArgumentException("tokenizer file must be an explicit absolute path");
    if (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException(
          "tokenizer sha256 must be 64 lowercase hexadecimal digits");
  }
}
