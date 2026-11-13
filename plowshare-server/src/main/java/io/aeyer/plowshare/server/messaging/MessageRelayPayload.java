package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.agents.Outcome;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Messaging-owned TEXT commands and bounded native snapshot pages; never Relay envelope fields. */
public final class MessageRelayPayload {
  private MessageRelayPayload() {}

  public static final String PUBLISHER = "messaging:ingress";
  public static final int CHUNK_CODEPOINTS = 16384;

  public sealed interface Command permits Receive, Cancel, Inspect {
    UUID requestId();

    String client();
  }

  public record Receive(
      String schema, UUID requestId, String client, UUID context, String body, String command)
      implements Command {
    public Receive {
      MessageRelayPayload.schema(schema, "plowshare-message-request/1");
      Objects.requireNonNull(requestId);
      client = MessageIngress.client(client);
      body = MessageIngress.body(body);
      command = MessageIngress.command(command);
    }
  }

  public record Cancel(String schema, UUID requestId, String client, UUID task) implements Command {
    public Cancel {
      MessageRelayPayload.schema(schema, "plowshare-message-cancel/1");
      Objects.requireNonNull(requestId);
      client = MessageIngress.client(client);
      Objects.requireNonNull(task);
    }
  }

  public record Inspect(
      String schema, UUID requestId, String client, UUID task, UUID originalRequest, String cursor)
      implements Command {
    public Inspect {
      MessageRelayPayload.schema(schema, "plowshare-message-inspect/1");
      Objects.requireNonNull(requestId);
      client = MessageIngress.client(client);
      if ((task == null) == (originalRequest == null))
        throw new IllegalArgumentException("Select one scoped task identity");
      if (cursor != null) Cursor.read(cursor);
    }
  }

  public record Reply(
      String id,
      String body,
      String offset,
      boolean complete,
      boolean finalReply,
      boolean generated,
      String ending,
      Instant postedAt) {
    public Reply {
      id = MessagePayload.identifier(id, "bdm_");
      Objects.requireNonNull(body);
      RelayPort.text("x" + body); // A valid fragment may contain whitespace only.
      if (body.codePointCount(0, body.length()) > CHUNK_CODEPOINTS
          || !offset.matches("0|[1-9][0-9]{0,9}")
          || Long.parseLong(offset) > Integer.MAX_VALUE)
        throw new IllegalArgumentException("Invalid native reply chunk");
      if (ending != null) Outcome.Ending.valueOf(ending);
      Objects.requireNonNull(postedAt);
    }
  }

  public record Task(
      UUID id,
      UUID context,
      UUID requestId,
      String agent,
      String message,
      String state,
      String ending,
      List<Reply> replies,
      Instant createdAt) {
    public Task {
      Objects.requireNonNull(id);
      Objects.requireNonNull(context);
      Objects.requireNonNull(requestId);
      agent = RelayPort.identity(agent);
      message = MessagePayload.identifier(message, "bdm_");
      if (!List.of("SUBMITTED", "WORKING", "INPUT_REQUIRED", "COMPLETED", "FAILED", "CANCELED")
          .contains(state)) throw new IllegalArgumentException("Invalid native task state");
      if (ending != null) Outcome.Ending.valueOf(ending);
      Objects.requireNonNull(createdAt);
      if (state.equals("COMPLETED") && !"ANSWERED".equals(ending))
        throw new IllegalArgumentException("Completed native task requires an answered outcome");
      replies = List.copyOf(replies);
      if (replies.size() > 2) throw new IllegalArgumentException("Oversized native snapshot page");
    }
  }

  public record Snapshot(
      String schema,
      UUID requestId,
      String sourceEvent,
      String client,
      String revision,
      Task task,
      String refusal,
      String next,
      String resume) {
    public Snapshot {
      MessageRelayPayload.schema(schema, "plowshare-message-task/1");
      Objects.requireNonNull(requestId);
      MessageIngress.client(client);
      sourceEvent = RelayPort.identity(sourceEvent);
      if ((task == null) != (refusal != null) || (task == null) != (revision == null))
        throw new IllegalArgumentException("Invalid native snapshot disposition");
      if (refusal != null
          && !List.of("NOT_ACCESSIBLE", "REQUEST_CONFLICT", "COMMAND_REFUSED", "INVALID_REQUEST")
              .contains(refusal)) throw new IllegalArgumentException("Invalid native refusal code");
      if (revision != null
          && (!revision.matches("[1-9][0-9]{0,18}") || Long.parseLong(revision) < 1))
        throw new IllegalArgumentException("Invalid native revision");
      if (next != null) {
        if (task == null || resume != null)
          throw new IllegalArgumentException("Invalid native history continuation");
        Cursor.read(next);
      }
      if (resume != null) {
        if (task == null || next != null || !Cursor.read(resume).follow())
          throw new IllegalArgumentException("Invalid native follow cursor");
      }
      if (task != null && next == null && resume == null)
        throw new IllegalArgumentException("Completed native page requires a resume cursor");
    }
  }

  /** Opaque progress only. Every use rechecks scoped task ownership; a cursor grants no access. */
  public record Cursor(UUID task, long through, long sequence, int offset, boolean follow) {
    public Cursor {
      Objects.requireNonNull(task);
      if (through < 0
          || sequence < 0
          || offset < 0
          || (!follow && sequence > through)
          || (follow && (through != 0 || offset != 0)))
        throw new IllegalArgumentException("Invalid native history cursor");
    }

    public String encode() {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              (task + ":" + through + ":" + sequence + ":" + offset + ":" + (follow ? "f" : "p"))
                  .getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor read(String value) {
      try {
        if (value.length() > 160) throw new IllegalArgumentException();
        String[] parts =
            new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", -1);
        if (parts.length != 5 || !List.of("f", "p").contains(parts[4]))
          throw new IllegalArgumentException();
        var cursor =
            new Cursor(
                UUID.fromString(parts[0]),
                Long.parseLong(parts[1]),
                Long.parseLong(parts[2]),
                Integer.parseInt(parts[3]),
                parts[4].equals("f"));
        if (!cursor.encode().equals(value)) throw new IllegalArgumentException();
        return cursor;
      } catch (RuntimeException invalid) {
        throw new IllegalArgumentException("Invalid native history cursor");
      }
    }
  }

  private static void schema(String actual, String expected) {
    if (!expected.equals(actual))
      throw new IllegalArgumentException("Unsupported native messaging schema");
  }
}
