package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** A durable acquisition receipt, including explicit decisions from its preparation hooks. */
public record InformationAcquisition(
    UUID id,
    String url,
    @JsonProperty("source_name") String sourceName,
    String corpus,
    String state,
    @JsonProperty("revision_id") UUID revisionId,
    int attempt,
    String error,
    @JsonProperty("allowance_total") int allowanceTotal,
    @JsonProperty("created_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
    @JsonProperty("pre_gate") Gate preGate,
    @JsonProperty("post_gate") Gate postGate) {
  public InformationAcquisition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(createdAt, "createdAt");
    url = ContractValues.identity(url, "url", 8192);
    URI source = URI.create(url);
    if (!Set.of("http", "https").contains(source.getScheme())
        || source.getHost() == null
        || source.getRawUserInfo() != null)
      throw new IllegalArgumentException("invalid acquisition URL");
    sourceName = ContractValues.text(sourceName, "sourceName", 1024, true);
    if (!Set.of("documents", "code").contains(corpus)
        || !Set.of("queued", "running", "failed", "blocked", "succeeded").contains(state)
        || attempt < 0
        || allowanceTotal < 1) throw new IllegalArgumentException("invalid acquisition state");
    error = ContractValues.text(error, "error", 32768, false);
    if ("succeeded".equals(state) && revisionId == null)
      throw new IllegalArgumentException("successful acquisition requires a revision");
  }

  public record Gate(String denied, List<String> notes, List<Decision> records) {
    public Gate {
      denied = ContractValues.text(denied, "denied", 32768, false);
      notes =
          ContractValues.list(notes, "notes", 10000).stream()
              .map(note -> ContractValues.text(note, "note", 32768, false))
              .toList();
      records = ContractValues.list(records, "decisions", 10000);
    }
  }

  public record Decision(
      String hook,
      String file,
      String tier,
      String stage,
      String tool,
      String decision,
      String reason,
      String added,
      String original,
      long tookMs) {
    public Decision {
      hook = ContractValues.identity(hook, "hook", 1024);
      file = ContractValues.text(file, "file", 8192, false);
      tool = ContractValues.optionalIdentity(tool, "tool", 256);
      if (!Set.of("HARNESS", "PERSONAL", "PROJECT", "LOCAL").contains(tier)
          || !Set.of(
                  "PROMPT_PRE",
                  "PROMPT_POST",
                  "TOOL_PRE",
                  "TOOL_POST",
                  "STEP_POST",
                  "LOG_OPEN",
                  "LOG_CLOSE",
                  "STAGE_PRE",
                  "STAGE_POST",
                  "APPROVAL_PRE",
                  "APPROVAL_POST",
                  "FOLD_POST",
                  "DELIVERY_PRE",
                  "DELIVERY_POST")
              .contains(stage)
          || !Set.of(
                  "allow",
                  "deny",
                  "rewrite",
                  "ask",
                  "redact",
                  "note",
                  "add",
                  "keep",
                  "notify",
                  "failed",
                  "unused",
                  "swallowed")
              .contains(decision)
          || tookMs < 0) throw new IllegalArgumentException("invalid hook decision");
      reason = ContractValues.text(reason, "reason", 1048576, false);
      added = ContractValues.text(added, "added", 1048576, false);
      original = ContractValues.text(original, "original", 1048576, false);
    }
  }
}
