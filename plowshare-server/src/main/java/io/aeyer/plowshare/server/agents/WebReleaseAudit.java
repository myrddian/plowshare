package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.llm.dispatch.WebEvidenceRelease;
import java.time.Instant;
import java.util.Optional;

/** Codec for compact server-authored release receipts in roleless conversation entries. */
public final class WebReleaseAudit {
  public static final String PREFIX = "Web evidence release v1 ";
  private static final ObjectMapper JSON = new ObjectMapper();

  private WebReleaseAudit() {}

  static String encode(WebEvidenceRelease release) {
    try {
      return PREFIX
          + JSON.writeValueAsString(
              new Stored(
                  release.call(),
                  release.digest(),
                  release.rule(),
                  release.verb(),
                  release.policy(),
                  release.expiresAt().toEpochMilli(),
                  release.heightenedRun()));
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Web release could not be recorded", invalid);
    }
  }

  static Optional<WebEvidenceRelease> decode(String content) {
    if (content == null || !content.startsWith(PREFIX) || content.length() > 1024)
      return Optional.empty();
    try {
      var stored = JSON.readValue(content.substring(PREFIX.length()), Stored.class);
      return Optional.of(
          new WebEvidenceRelease(
              stored.call(),
              stored.digest(),
              stored.rule(),
              stored.verb(),
              stored.policy(),
              Instant.ofEpochMilli(stored.expires()),
              stored.heightenedRun()));
    } catch (IllegalArgumentException | NullPointerException | JsonProcessingException invalid) {
      return Optional.empty();
    }
  }

  private record Stored(
      String call,
      String digest,
      String rule,
      Verb verb,
      String policy,
      long expires,
      String heightenedRun) {}
}
