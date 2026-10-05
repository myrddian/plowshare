package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** A tracking observation, or an explicit reason why no durable observation is available. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeTrackingStatus(
    String state,
    String reason,
    String workspace,
    Long generation,
    @JsonProperty("checked_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant checkedAt,
    @JsonProperty("next_poll") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant nextPoll,
    @JsonProperty("interval_seconds") Long intervalSeconds,
    List<String> issues) {
  public CodeTrackingStatus {
    if (!Set.of(
            "checking",
            "observed",
            "partial",
            "dirty",
            "unavailable",
            "disabled",
            "inactive",
            "background",
            "limited")
        .contains(state)) throw new IllegalArgumentException("invalid tracking state");
    reason = ContractValues.optionalIdentity(reason, "reason", 256);
    if (workspace != null) {
      if (!workspace.matches("[a-f0-9]{64}")
          || generation == null
          || generation < 0
          || nextPoll == null
          || intervalSeconds == null
          || intervalSeconds < 5
          || intervalSeconds > 3600)
        throw new IllegalArgumentException("invalid durable tracking observation");
      issues =
          ContractValues.list(issues, "issues", 10000).stream()
              .map(issue -> ContractValues.identity(issue, "issue", 4096))
              .toList();
    } else if (generation != null
        || checkedAt != null
        || nextPoll != null
        || intervalSeconds != null
        || issues != null) {
      throw new IllegalArgumentException("tracking details require a workspace");
    }
  }

  public static CodeTrackingStatus state(String state) {
    return failure(state, null);
  }

  public static CodeTrackingStatus failure(String state, String reason) {
    return new CodeTrackingStatus(state, reason, null, null, null, null, null, null);
  }
}
