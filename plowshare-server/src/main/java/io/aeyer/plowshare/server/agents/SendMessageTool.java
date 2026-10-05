package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A per-run sender. Identity, scope and return address are supplied by the harness. */
public final class SendMessageTool implements AgentTool {
  public static final String NAME = "send_message";

  public record Request(
      String to,
      String body,
      String replyTo,
      boolean replyExpected,
      boolean finalReply,
      String lifetime,
      String callId,
      Long timeoutSeconds,
      String toProject,
      String route) {
    public Request(
        String to,
        String body,
        String replyTo,
        boolean replyExpected,
        boolean finalReply,
        String lifetime,
        String callId,
        Long timeoutSeconds) {
      this(
          to,
          body,
          replyTo,
          replyExpected,
          finalReply,
          lifetime,
          callId,
          timeoutSeconds,
          null,
          null);
    }

    public Request(
        String to,
        String body,
        String replyTo,
        boolean replyExpected,
        boolean finalReply,
        String lifetime,
        String callId) {
      this(to, body, replyTo, replyExpected, finalReply, lifetime, callId, null);
    }
  }

  @FunctionalInterface
  public interface Sender {
    String send(Request request, Home home);
  }

  private final Sender sender;
  private String callId;

  public SendMessageTool(Sender sender) {
    this.sender = Objects.requireNonNull(sender);
  }

  public void calledAs(String id) {
    callId = id;
  }

  @Override
  public ToolSchema schema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "to",
        ToolArguments.string(
            "Instance address, or a definition name for its project default. Omit for replies."));
    properties.put(
        "to_project",
        ToolArguments.string(
            "Destination project or Personal:<account-name> for a new message. Defaults to this project. Personal routing follows the server's account-owned defaults; ordinary cross-project routes require both projects' permission."));
    properties.put(
        "route",
        ToolArguments.string(
            "Named route from this project's route files. Supplies destination project and agent; omit to and to_project."));
    properties.put("body", ToolArguments.string("The message to deliver."));
    properties.put(
        "reply_to",
        ToolArguments.string("Incoming message ID; the harness supplies its return address."));
    properties.put(
        "reply_expected",
        ToolArguments.flag(
            "Default false. If true, an outcome reply is generated when the handling turn ends without a final reply."));
    properties.put(
        "final",
        ToolArguments.flag(
            "For replies: default true. Set false for questions or progress that do not complete the request."));
    properties.put(
        "lifetime",
        Map.of(
            "type",
            "string",
            "enum",
            List.of("persistent", "task"),
            "description",
            "For a definition-name destination: persistent selects its default; task creates a fresh bounded instance."));
    properties.put(
        "timeout_seconds",
        Map.of(
            "type",
            "integer",
            "minimum",
            1,
            "maximum",
            604800,
            "description",
            "Optional handling deadline, including approval waits, in seconds. Expiry cancels handling and supplies an expected terminal reply."));
    return ToolSchema.from(
        NAME,
        "Send a message to an agent or bot instance in this project or an explicitly permitted destination project. Sending durably queues a wake and returns a receipt; it does not wait for an answer. Incoming messages include an ID and return address. Reply with reply_to. A final reply satisfies an expected response; progress does not. Only the harness supplies sender identity and scope.",
        ToolArguments.object(properties, List.of("body")));
  }

  @Override
  public String run(String json, Home home) {
    String opening = callId;
    callId = null;
    try {
      var args =
          ToolArguments.parse(
              json, NAME, "{\"to\":\"reviewer\",\"body\":\"Review this\",\"reply_expected\":true}");
      if (args.has("from")
          || args.has("return_address")
          || args.has("project")
          || args.has("home")) {
        return "The harness supplies sender identity, return address and project; omit those fields.";
      }
      String body = ToolArguments.requireText(args, "body", NAME, "the message");
      String to =
          ToolArguments.optionalText(
              args,
              "to",
              v ->
                  new ToolArguments.BadArguments(
                      "to must be an instance address or definition name"));
      String reply =
          ToolArguments.optionalText(
              args,
              "reply_to",
              v -> new ToolArguments.BadArguments("reply_to must be an incoming message ID"));
      String lifetime =
          ToolArguments.optionalText(
              args,
              "lifetime",
              v -> new ToolArguments.BadArguments("lifetime must be persistent or task"));
      String toProject =
          ToolArguments.optionalText(
              args,
              "to_project",
              v -> new ToolArguments.BadArguments("to_project must be a project name"));
      String route =
          ToolArguments.optionalText(
              args,
              "route",
              v -> new ToolArguments.BadArguments("route must be a configured route name"));
      if (to == null && reply == null && route == null)
        return "send_message needs to, route or reply_to.";
      if (route != null && (to != null || toProject != null || reply != null))
        return "A named route supplies its destination; omit to, to_project and reply_to.";
      if (reply != null && toProject != null)
        return "A reply uses its existing return address; omit to_project.";
      if (lifetime != null && !List.of("persistent", "task").contains(lifetime))
        return "lifetime must be persistent or task.";
      for (String flag : List.of("reply_expected", "final")) {
        if (args.has(flag) && !args.get(flag).isBoolean()) return flag + " must be true or false.";
      }
      boolean expected =
          ToolArguments.optionalFlag(
              args,
              "reply_expected",
              false,
              v -> new ToolArguments.BadArguments("reply_expected must be true or false"));
      boolean last =
          ToolArguments.optionalFlag(
              args,
              "final",
              reply != null,
              v -> new ToolArguments.BadArguments("final must be true or false"));
      if (last && reply == null) return "final is only meaningful with reply_to.";
      if (reply != null && lifetime != null)
        return "A reply uses its existing return address; omit lifetime.";
      if (to != null && to.startsWith("ins_") && lifetime != null)
        return "An instance address already has a lifetime; omit lifetime.";
      Long timeout = null;
      if (args.has("timeout_seconds")) {
        var value = args.get("timeout_seconds");
        if (!value.isIntegralNumber()
            || !value.canConvertToLong()
            || value.asLong() < 1
            || value.asLong() > 604800) {
          return "timeout_seconds must be an integer from 1 to 604800.";
        }
        timeout = value.asLong();
      }
      return sender.send(
          new Request(
              to,
              body,
              reply,
              expected,
              last,
              lifetime == null ? "persistent" : lifetime,
              opening,
              timeout,
              toProject,
              route),
          home);
    } catch (ToolArguments.BadArguments bad) {
      return bad.getMessage();
    }
  }
}
