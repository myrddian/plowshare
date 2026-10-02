package io.aeyer.plowshare.server.llm;

import java.time.Duration;
import java.util.Map;
import okhttp3.HttpUrl;

/** Opt-in vLLM 0.15.1 tokenization contract; URL is explicit, never inferred from /v1. */
public class CountingProperties {
    private String url;
    private String revision;
    private Duration timeout = Duration.ofSeconds(2);
    private Duration cacheTtl = Duration.ofSeconds(60);
    private int cacheEntries = 1000;
    private boolean automatic;
    private boolean embeddings;
    private boolean embeddingSpecialTokens = true;
    public String getUrl() { return url; }
    public void setUrl(String value) { url = value; }
    public String getRevision() { return revision; }
    public void setRevision(String value) { revision = value == null || value.isBlank() ? null : value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public Duration getCacheTtl() { return cacheTtl; }
    public void setCacheTtl(Duration value) { cacheTtl = value; }
    public int getCacheEntries() { return cacheEntries; }
    public void setCacheEntries(int value) { cacheEntries = value; }
    public boolean isAutomatic() { return automatic; }
    public void setAutomatic(boolean value) { automatic = value; }
    public boolean isEmbeddings() { return embeddings; }
    public void setEmbeddings(boolean value) { embeddings = value; }
    public boolean isEmbeddingSpecialTokens() { return embeddingSpecialTokens; }
    public void setEmbeddingSpecialTokens(boolean value) { embeddingSpecialTokens = value; }
    public void validate(String base) {
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(30)) > 0
                || cacheTtl == null || cacheTtl.isNegative() || cacheTtl.compareTo(Duration.ofSeconds(60)) > 0
                || cacheEntries < 0 || cacheEntries > 10000
                || revision != null && !revision.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("invalid pool counting bounds or revision");
        if (url == null) {
            if (automatic || embeddings) throw new IllegalArgumentException("pool counting needs an explicit url");
            return;
        }
        HttpUrl target = HttpUrl.parse(url), origin = HttpUrl.parse(base);
        if (target == null || origin == null || !target.scheme().equals(origin.scheme())
                || !target.host().equals(origin.host()) || target.port() != origin.port()
                || !target.username().isEmpty() || !target.password().isEmpty()
                || target.query() != null || target.fragment() != null || !url.equals(url.trim()))
            throw new IllegalArgumentException("pool counting url must use the inference origin without credentials or query");
    }
    public String fingerprint() {
        return java.util.List.of(String.valueOf(url), String.valueOf(revision), timeout.toString(),
                cacheTtl.toString(), Integer.toString(cacheEntries), Boolean.toString(embeddings),
                Boolean.toString(embeddingSpecialTokens)).toString();
    }
}
