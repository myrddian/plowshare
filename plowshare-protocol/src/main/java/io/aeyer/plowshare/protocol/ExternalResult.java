package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validated observations of remote work. Each registered result family owns its fields. These
 * values describe remote evidence and confer neither local execution authority nor permission to
 * replay an uncertain request.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
  @JsonSubTypes.Type(ExternalResult.TaskResult.class),
  @JsonSubTypes.Type(ExternalResult.MessageResult.class),
  @JsonSubTypes.Type(ExternalResult.IntegrationResult.class)
})
public sealed interface ExternalResult
    permits ExternalResult.TaskResult,
        ExternalResult.MessageResult,
        ExternalResult.IntegrationResult {
  record TaskResult(Task task) implements ExternalResult {
    public TaskResult {
      Objects.requireNonNull(task, "task");
    }
  }

  record MessageResult(ExternalMessage message) implements ExternalResult {
    public MessageResult {
      Objects.requireNonNull(message, "message");
      checkedResponse(message);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Task(
      String id,
      String contextId,
      Status status,
      List<Artifact> artifacts,
      List<ExternalMessage> history,
      ExternalMessage.Metadata metadata) {
    public Task {
      id = ContractValues.identity(id, "remote task id", 1024);
      contextId = ContractValues.identity(contextId, "remote context id", 1024);
      Objects.requireNonNull(status, "status");
      if (artifacts != null) {
        artifacts = ContractValues.list(artifacts, "artifacts", 256);
        if (artifacts.stream().map(Artifact::artifactId).distinct().count() != artifacts.size())
          throw new IllegalArgumentException("duplicate remote artifacts");
      }
      if (history != null) {
        history = ContractValues.list(history, "history", 256);
        for (ExternalMessage message : history) {
          if (message.messageId() == null
              || message.role() == null
              || message.taskId() != null && !id.equals(message.taskId())
              || message.contextId() != null && !contextId.equals(message.contextId()))
            throw new IllegalArgumentException("foreign or incomplete task history");
        }
      }
      if (status.message() != null
          && (status.message().taskId() != null && !id.equals(status.message().taskId())
              || status.message().contextId() != null
                  && !contextId.equals(status.message().contextId())))
        throw new IllegalArgumentException("foreign remote task status message");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Status(String state, ExternalMessage message, Instant timestamp) {
    public Status {
      if (!Set.of(
              "TASK_STATE_SUBMITTED",
              "TASK_STATE_WORKING",
              "TASK_STATE_INPUT_REQUIRED",
              "TASK_STATE_AUTH_REQUIRED",
              "TASK_STATE_COMPLETED",
              "TASK_STATE_FAILED",
              "TASK_STATE_CANCELED",
              "TASK_STATE_REJECTED")
          .contains(state == null ? "" : state))
        throw new IllegalArgumentException("invalid remote task state");
      if (message != null) checkedResponse(message);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Artifact(
      String artifactId,
      String name,
      String description,
      List<ExternalMessage.Part> parts,
      List<String> extensions,
      ExternalMessage.Metadata metadata) {
    public Artifact {
      artifactId = ContractValues.identity(artifactId, "artifactId", 1024);
      name = ContractValues.text(name, "artifact name", 1024, false);
      description = ContractValues.text(description, "artifact description", 32768, false);
      parts = ContractValues.list(parts, "artifact parts", 256);
      if (parts.isEmpty()) throw new IllegalArgumentException("artifact parts required");
      if (extensions != null) {
        extensions = ContractValues.list(extensions, "artifact extensions", 32);
        for (String extension : extensions) {
          ContractValues.identity(extension, "artifact extension", 8192);
          if (!java.net.URI.create(extension).isAbsolute())
            throw new IllegalArgumentException("absolute extension URI required");
        }
      }
    }
  }

  /** Integration result families are disjoint: selected states, action receipt or diagnostic. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record IntegrationResult(
      Map<String, IntegrationPayload.Reading> states,
      Boolean acknowledged,
      String verification,
      IntegrationPayload.ActionContext context,
      String diagnostic)
      implements ExternalResult {
    public IntegrationResult {
      if (states != null) {
        states = Map.copyOf(states);
        if (states.size() > 256
            || acknowledged != null
            || verification != null
            || context != null
            || diagnostic != null)
          throw new IllegalArgumentException("invalid integration state result");
        for (var entry : states.entrySet())
          if (!entry.getKey().equals(entry.getValue().alias()))
            throw new IllegalArgumentException("foreign integration reading alias");
      } else if (acknowledged != null) {
        if (verification != null
            && !Set.of("unconfirmed", "not_observed", "observed").contains(verification))
          throw new IllegalArgumentException("invalid action verification");
        if (!acknowledged && context != null)
          throw new IllegalArgumentException("unacknowledged action cannot carry context");
      } else if (diagnostic == null || verification != null || context != null)
        throw new IllegalArgumentException(
            "integration result requires states, action receipt or diagnostic");
      diagnostic = ContractValues.text(diagnostic, "integration diagnostic", 32768, false);
    }
  }

  private static void checkedResponse(ExternalMessage message) {
    if (message.messageId() == null || !"ROLE_AGENT".equals(message.role()))
      throw new IllegalArgumentException("response message requires agent role and identity");
  }
}
