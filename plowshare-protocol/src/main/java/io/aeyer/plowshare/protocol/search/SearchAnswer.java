package io.aeyer.plowshare.protocol.search;

import java.util.List;

/** What one provider said, and how long it took to say it. */
public record SearchAnswer(
        String requestId, String providerKey, AnswerStatus status,
        List<Hit> hits, long elapsedMs, String message) {

    public SearchAnswer {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public static SearchAnswer success(String requestId, String providerKey, List<Hit> hits, long elapsedMs) {
        return new SearchAnswer(requestId, providerKey, AnswerStatus.SUCCESS, hits, elapsedMs, null);
    }

    public static SearchAnswer failed(String requestId, String providerKey, String message, long elapsedMs) {
        return new SearchAnswer(requestId, providerKey, AnswerStatus.FAILED, List.of(), elapsedMs, message);
    }
}
