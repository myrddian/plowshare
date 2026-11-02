package io.aeyer.plowshare.server.llm;

import java.time.Duration;
import okhttp3.HttpUrl;

/** Explicit same-origin counters; existing vLLM text tokenization remains the default. */
public class CountingProperties {
  /** Wire protocol for complete chat prompts; embedding tokenization remains vLLM-only. */
  public enum ChatProtocol {
    VLLM_TOKENIZE("VLLM_TOKENIZE_0_15_1", "count"),
    LLAMA_CPP_INPUT_TOKENS("LLAMA_CPP_CHAT_INPUT_TOKENS", "input_tokens"),
    LMSTUDIO_MLX_INPUT_TOKENS("LMSTUDIO_MLX_CHAT_INPUT_TOKENS", "input_tokens");

    private final String source;
    private final String countField;

    ChatProtocol(String source, String countField) {
      this.source = source;
      this.countField = countField;
    }

    public String source() {
      return source;
    }

    public String countField() {
      return countField;
    }
  }

  private ChatProtocol chatProtocol = ChatProtocol.VLLM_TOKENIZE;

  public ChatProtocol getChatProtocol() {
    return chatProtocol;
  }

  public void setChatProtocol(ChatProtocol value) {
    chatProtocol = java.util.Objects.requireNonNull(value, "chatProtocol");
  }

  private String url;
  private String revision;
  private Duration timeout = Duration.ofSeconds(2);
  private Duration cacheTtl = Duration.ofSeconds(60);
  private int cacheEntries = 1000;
  private boolean automatic;
  private boolean imageTokensIncluded;

  /**
   * Operator assertion: the pinned counter includes image encoder context in the complete count.
   */
  public boolean isImageTokensIncluded() {
    return imageTokensIncluded;
  }

  public void setImageTokensIncluded(boolean value) {
    imageTokensIncluded = value;
  }

  private boolean embeddings;
  private boolean embeddingSpecialTokens = true;

  public String getUrl() {
    return url;
  }

  public void setUrl(String value) {
    url = value;
  }

  public String getRevision() {
    return revision;
  }

  public void setRevision(String value) {
    revision = value == null || value.isBlank() ? null : value;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration value) {
    timeout = value;
  }

  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public void setCacheTtl(Duration value) {
    cacheTtl = value;
  }

  public int getCacheEntries() {
    return cacheEntries;
  }

  public void setCacheEntries(int value) {
    cacheEntries = value;
  }

  public boolean isAutomatic() {
    return automatic;
  }

  public void setAutomatic(boolean value) {
    automatic = value;
  }

  public boolean isEmbeddings() {
    return embeddings;
  }

  public void setEmbeddings(boolean value) {
    embeddings = value;
  }

  public boolean isEmbeddingSpecialTokens() {
    return embeddingSpecialTokens;
  }

  public void setEmbeddingSpecialTokens(boolean value) {
    embeddingSpecialTokens = value;
  }

  public void validate(String base) {
    if (timeout == null
        || timeout.toMillis() < 1
        || timeout.compareTo(Duration.ofSeconds(30)) > 0
        || cacheTtl == null
        || cacheTtl.isNegative()
        || cacheTtl.compareTo(Duration.ofSeconds(60)) > 0
        || cacheEntries < 0
        || cacheEntries > 10000
        || revision != null && !revision.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new IllegalArgumentException("invalid pool counting bounds or revision");
    if (chatProtocol != ChatProtocol.VLLM_TOKENIZE
        && (url == null || revision == null || embeddings))
      throw new IllegalArgumentException(
          "Full-chat counting needs an explicit url, verified revision and no embedding counting");
    // LM Studio settings can change without changing its model alias. This slice validates
    // the live binding on every exchange; a cached count would bypass that check.
    if (chatProtocol == ChatProtocol.LMSTUDIO_MLX_INPUT_TOKENS
        && (cacheEntries != 0 || !revision.matches("[a-f0-9]{64}")))
      throw new IllegalArgumentException(
          "MLX counting needs cache-entries: 0 and the sidecar's SHA-256 revision");
    if (imageTokensIncluded && (url == null || revision == null))
      throw new IllegalArgumentException(
          "Image counting needs an explicit url and verified revision");
    if (url == null) {
      if (automatic || embeddings)
        throw new IllegalArgumentException("pool counting needs an explicit url");
      return;
    }
    HttpUrl target = HttpUrl.parse(url), origin = HttpUrl.parse(base);
    if (target == null
        || origin == null
        || !target.scheme().equals(origin.scheme())
        || !target.host().equals(origin.host())
        || target.port() != origin.port()
        || !target.username().isEmpty()
        || !target.password().isEmpty()
        || target.query() != null
        || target.fragment() != null
        || !url.equals(url.trim()))
      throw new IllegalArgumentException(
          "pool counting url must use the inference origin without credentials or query");
    if (chatProtocol != ChatProtocol.VLLM_TOKENIZE
        && !target.encodedPath().endsWith("/chat/completions/input_tokens"))
      throw new IllegalArgumentException(
          "Full-chat counting url must name its chat-completion input-token endpoint");
  }

  public String fingerprint() {
    return java.util.List.of(
            chatProtocol.name(),
            String.valueOf(url),
            String.valueOf(revision),
            timeout.toString(),
            cacheTtl.toString(),
            Integer.toString(cacheEntries),
            Boolean.toString(embeddings),
            Boolean.toString(imageTokensIncluded),
            Boolean.toString(embeddingSpecialTokens))
        .toString();
  }
}
