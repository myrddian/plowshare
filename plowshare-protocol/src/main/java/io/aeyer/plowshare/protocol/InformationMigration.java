package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/**
 * Operator-only quarantine inspection; transport authentication controls access to these values.
 */
public final class InformationMigration {
  private InformationMigration() {}

  public record Document(
      UUID id, @JsonProperty("source_name") String sourceName, String title, String visibility) {
    public Document {
      Objects.requireNonNull(id, "id");
      sourceName = ContractValues.text(sourceName, "sourceName", 1024, true);
      title = ContractValues.text(title, "title", 32768, false);
      if (visibility != null
          && !Set.of("personal", "project", "shared", "quarantined").contains(visibility))
        throw new IllegalArgumentException("invalid visibility");
    }
  }

  public record Payload(@JsonProperty("payload_id") String payloadId, String reason) {
    public Payload {
      payloadId = ContractValues.identity(payloadId, "payload", 1024);
      reason = ContractValues.text(reason, "reason", 32768, false);
    }
  }

  public record Inventory(List<Document> documents, List<Payload> payloads) {
    public Inventory {
      documents = ContractValues.list(documents, "documents", 100);
      payloads = ContractValues.list(payloads, "payloads", 100);
    }
  }

  public record Log(String id, @JsonProperty("owner_handle") String ownerHandle, String agent) {
    public Log {
      id = ContractValues.identity(id, "log", 1024);
      ownerHandle = ContractValues.optionalIdentity(ownerHandle, "ownerHandle", 256);
      agent = ContractValues.optionalIdentity(agent, "agent", 256);
    }
  }

  public record Entry(
      int ordinal,
      String kind,
      String content,
      @JsonProperty("tool_calls") List<ToolCall> toolCalls) {
    public Entry {
      if (ordinal < 0) throw new IllegalArgumentException("invalid entry position");
      kind = ContractValues.identity(kind, "kind", 64);
      content = ContractValues.text(content, "content", 8388608, false);
      if (toolCalls != null) toolCalls = ContractValues.list(toolCalls, "toolCalls", 1024);
    }
  }

  public record Job(String id, String agent, String ending) {
    public Job {
      id = ContractValues.identity(id, "job", 1024);
      agent = ContractValues.optionalIdentity(agent, "agent", 256);
      ending = ContractValues.optionalIdentity(ending, "ending", 64);
    }
  }

  public record Inspection(List<Log> log, List<Entry> entries, List<Job> job) {
    public Inspection {
      log = ContractValues.list(log, "log", 100);
      entries = ContractValues.list(entries, "entries", 100);
      job = ContractValues.list(job, "job", 100);
    }
  }
}
