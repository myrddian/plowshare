package io.aeyer.plowshare.protocol.search;

import io.aeyer.plowshare.protocol.WebContractValues;
import java.util.List;
import java.util.Objects;

/** What one provider said, and how long it took to say it. */
public record SearchAnswer(
    String requestId,
    String providerKey,
    AnswerStatus status,
    List<Hit> hits,
    long elapsedMs,
    String message) {

  public SearchAnswer {
    requestId = SearchValues.identity(requestId, "requestId", 1024);
    providerKey = SearchValues.identity(providerKey, "providerKey", 256);
    Objects.requireNonNull(status, "status");
    hits = hits == null ? List.of() : List.copyOf(hits);
    if (elapsedMs < 0 || hits.size() > 10000 || status != AnswerStatus.SUCCESS && !hits.isEmpty())
      throw new IllegalArgumentException("invalid search answer timing or result set");
    message = WebContractValues.text(message, "provider message", 32768, false);
  }

  public static SearchAnswer success(
      String requestId, String providerKey, List<Hit> hits, long elapsedMs) {
    return new SearchAnswer(requestId, providerKey, AnswerStatus.SUCCESS, hits, elapsedMs, null);
  }

  public static SearchAnswer failed(
      String requestId, String providerKey, String message, long elapsedMs) {
    return new SearchAnswer(
        requestId, providerKey, AnswerStatus.FAILED, List.of(), elapsedMs, message);
  }
}
