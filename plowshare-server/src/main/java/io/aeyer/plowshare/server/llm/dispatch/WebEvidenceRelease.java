package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.files.FileContents;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/** Server-issued receipt for a single released tool result and a single local injection rule. */
public record WebEvidenceRelease(
    String call,
    String digest,
    String rule,
    Verb verb,
    String policy,
    Instant expiresAt,
    String heightenedRun) {
  public WebEvidenceRelease(
      String call, String digest, String rule, Verb verb, String policy, Instant expiresAt) {
    this(call, digest, rule, verb, policy, expiresAt, null);
  }

  public WebEvidenceRelease {
    Objects.requireNonNull(call);
    Objects.requireNonNull(digest);
    Objects.requireNonNull(rule);
    Objects.requireNonNull(verb);
    Objects.requireNonNull(policy);
    Objects.requireNonNull(expiresAt);
    if (call.isBlank()
        || call.length() > 256
        || !digest.matches("[0-9a-f]{64}")
        || !rule.matches("prompt_injection_[a-z_]+"))
      throw new IllegalArgumentException("Invalid web evidence receipt");
  }

  public boolean covers(String call, String text, String rule, Instant now) {
    return this.call.equals(call)
        && this.rule.equals(rule)
        && now.isBefore(expiresAt)
        && digest.equals(digest(text));
  }

  public static String digest(String text) {
    return FileContents.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
