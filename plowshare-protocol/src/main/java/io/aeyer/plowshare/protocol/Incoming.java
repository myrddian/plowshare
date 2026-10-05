package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable adapter ingress. Client identities are supplied by the authenticated adapter. */
public final class Incoming {
  private Incoming() {}

  public record Receive(
      String project,
      String client,
      String agent,
      UUID requestId,
      UUID context,
      String body,
      String command,
      Source source) {
    public Receive {
      project = Home.of(project).project();
      client = identity(client, "client", 64);
      if (!client.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("invalid client");
      agent = identity(agent, "agent", 256);
      Objects.requireNonNull(requestId, "requestId");
      body = text(body, "body", 65536);
      if (command != null
          && !command.matches(
              "/(skill|orchestration):[a-zA-Z0-9_.-]+(?: --mode=(INHERITED|SUMMARISED|NEW|DIRECT))?"))
        throw new IllegalArgumentException("invalid incoming command");
      if (source != null) {
        if (!body.equals(String.join("\n", source.parts().stream().map(TextPart::text).toList()))
            || !Objects.equals(
                command, source.metadata() == null ? null : source.metadata().plowshareCommand())
            || !Objects.equals(context, source.contextId())
            || source.taskId() != null)
          throw new IllegalArgumentException("incoming request differs from its source provenance");
      }
    }
  }

  public record Id(String project, String client, UUID id) {
    public Id {
      project = Home.of(project).project();
      client = identity(client, "client", 64);
      if (!client.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("invalid client");
      Objects.requireNonNull(id, "id");
    }
  }

  /** Supported ingress provenance. Additional protocol extensions require an explicit contract. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Source(
      String messageId,
      String role,
      UUID taskId,
      UUID contextId,
      List<TextPart> parts,
      Metadata metadata,
      List<String> referenceTaskIds,
      List<String> extensions) {
    public Source {
      messageId = identity(messageId, "messageId", 256);
      if (!"ROLE_USER".equals(role))
        throw new IllegalArgumentException("source role must be ROLE_USER");
      parts = List.copyOf(parts);
      if (parts.isEmpty() || parts.size() > 256)
        throw new IllegalArgumentException("source needs 1..256 parts");
      if (parts.stream().mapToInt(part -> part.text().getBytes(StandardCharsets.UTF_8).length).sum()
          > 65536) throw new IllegalArgumentException("source text exceeds 64 KiB");
      if (referenceTaskIds != null)
        referenceTaskIds = identities(referenceTaskIds, "referenceTaskIds");
      if (extensions != null) extensions = identities(extensions, "extensions");
    }

    public Source(String messageId, List<TextPart> parts, Metadata metadata) {
      this(messageId, "ROLE_USER", null, null, parts, metadata, null, null);
    }

    public Source withTask(UUID task, UUID context) {
      return new Source(
          messageId, role, task, context, parts, metadata, referenceTaskIds, extensions);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TextPart(String text, String mediaType) {
    public TextPart {
      text = ContractValues.text(text, "part text", 65536, false);
      Objects.requireNonNull(text, "part text");
      if (text.getBytes(StandardCharsets.UTF_8).length > 65536)
        throw new IllegalArgumentException("part text exceeds 64 KiB");
      if (mediaType != null && !"text/plain".equals(mediaType))
        throw new IllegalArgumentException("only text/plain ingress is supported");
    }

    public TextPart(String text) {
      this(text, null);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Metadata(String plowshareCommand) {
    public Metadata {
      if (plowshareCommand != null
          && !plowshareCommand.matches(
              "/(skill|orchestration):[a-zA-Z0-9_.-]+(?: --mode=(INHERITED|SUMMARISED|NEW|DIRECT))?"))
        throw new IllegalArgumentException("invalid source command");
    }
  }

  /** Receiving agent discovery in the configured project; grants remain enforced at receive. */
  public record Catalog(String name, String description, boolean served, List<Command> commands) {
    public Catalog {
      name = identity(name, "name", 256);
      description = ContractValues.text(description, "description", 32768, false);
      commands = ContractValues.list(commands, "commands", 10000);
    }
  }

  public record CatalogQuery(String project, String agent) {
    public CatalogQuery {
      project = Home.of(project).project();
      agent = identity(agent, "agent", 256);
    }
  }

  public record Command(
      String command,
      List<String> aliases,
      String kind,
      String name,
      String description,
      String argumentHint,
      String executor,
      String mode,
      String tier,
      String hash,
      boolean agentVisible) {
    public Command {
      if (!List.of("skill", "orchestration").contains(kind))
        throw new IllegalArgumentException("invalid command kind");
      name = identity(name, "name", 256);
      if (!name.matches("[a-zA-Z0-9_.-]+") || command == null)
        throw new IllegalArgumentException("invalid command identity");
      if (!command.equals("/" + kind + ":" + name))
        throw new IllegalArgumentException("command identity differs from its kind and name");
      aliases =
          ContractValues.list(aliases, "aliases", 32).stream()
              .map(value -> identity(value, "alias", 256))
              .toList();
      description = ContractValues.text(description, "description", 32768, false);
      argumentHint = ContractValues.text(argumentHint, "argumentHint", 4096, false);
      executor = ContractValues.optionalIdentity(executor, "executor", 256);
      if (mode != null && !List.of("INHERITED", "SUMMARISED", "NEW", "DIRECT").contains(mode))
        throw new IllegalArgumentException("invalid command mode");
      if (!List.of("GLOBAL", "SESSION", "PROJECT", "PERSONAL", "SHIPPED").contains(tier))
        throw new IllegalArgumentException("invalid command tier");
      if (hash == null || !hash.matches("sha256:[a-f0-9]{64}"))
        throw new IllegalArgumentException("invalid command hash");
    }
  }

  public record Reply(
      String id,
      String body,
      boolean finalReply,
      boolean generated,
      String ending,
      Instant postedAt) {
    public Reply {
      id = identity(id, "reply id", 1024);
      body = ContractValues.text(body, "reply body", 1048576, false);
      ending = Incoming.ending(ending);
      Objects.requireNonNull(postedAt, "postedAt");
    }
  }

  public record Task(
      UUID id,
      UUID context,
      String agent,
      String message,
      String state,
      String ending,
      Source source,
      List<Reply> replies,
      Instant createdAt) {
    public Task {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(context, "context");
      agent = identity(agent, "agent", 256);
      message = identity(message, "message", 1024);
      if (!List.of("SUBMITTED", "INPUT_REQUIRED", "CANCELED", "COMPLETED", "FAILED", "WORKING")
          .contains(state)) throw new IllegalArgumentException("invalid incoming state");
      ending = Incoming.ending(ending);
      if (state.equals("COMPLETED") && !"ANSWERED".equals(ending))
        throw new IllegalArgumentException("completed incoming task requires an answered outcome");
      replies = List.copyOf(replies);
      if (replies.stream().map(Reply::id).distinct().count() != replies.size())
        throw new IllegalArgumentException("duplicate incoming reply identity");
      if (replies.size() > 200) throw new IllegalArgumentException("too many replies");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }

  private static String ending(String value) {
    if (value != null
        && !java.util.Set.of(
                "ANSWERED",
                "TURN_CAP",
                "CALL_BUDGET",
                "CANCELLED",
                "STUCK",
                "UNAVAILABLE",
                "SUB_AGENT_FAILED",
                "SESSION_GONE",
                "AWAITING")
            .contains(value)) throw new IllegalArgumentException("invalid incoming outcome");
    return value;
  }

  private static List<String> identities(List<String> values, String name) {
    if (values.size() > 256) throw new IllegalArgumentException(name + " exceeds 256 entries");
    return values.stream().map(value -> identity(value, name, 1024)).toList();
  }

  private static String identity(String value, String name, int limit) {
    if (value == null
        || value.isBlank()
        || value.length() > limit
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException(name + " must be bounded nonblank identity");
    return value.strip();
  }

  private static String text(String value, String name, int limit) {
    if (value == null
        || value.isBlank()
        || value.indexOf('\0') >= 0
        || value.getBytes(StandardCharsets.UTF_8).length > limit)
      throw new IllegalArgumentException(name + " must be bounded nonblank text");
    return value;
  }
}
