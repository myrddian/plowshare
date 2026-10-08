package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.IntegrationPayload;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.relay.tools.RelayToolCodec;
import io.aeyer.plowshare.server.relay.tools.RelayToolInvocations;
import java.util.Map;
import java.util.UUID;

/** Reconciles the owning invocation; cannot publish or start another external operation. */
public final class RelayInvocationReadTool implements AgentTool {
  public static final String NAME = "relay_tool_read";
  private final RelayToolInvocations invocations;

  public RelayInvocationReadTool(RelayToolInvocations invocations) {
    this.invocations = java.util.Objects.requireNonNull(invocations);
  }

  @Override
  public ToolSchema schema() {
    return ToolSchema.from(
        NAME,
        "Read your project's retained external tool invocation. UNKNOWN must be reconciled, never resubmitted with a new call identity.",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("id", Map.of("type", "string", "format", "uuid")),
            "required",
            java.util.List.of("id"),
            "additionalProperties",
            false));
  }

  @Override
  public String run(String arguments, Home home) {
    return "Relay invocation reads require an authenticated owner.";
  }

  @Override
  public String run(String arguments, Home home, UsageAttribution owner) {
    if (owner == null
        || owner.accountHandle() == null
        || home.project() == null
        || owner.status() != UsageAttribution.Status.ATTRIBUTED
        || owner.scope() != UsageAttribution.Scope.PROJECT)
      return "Relay invocation reads require an authenticated owner.";
    try {
      var values = RelayToolCodec.arguments(arguments).values();
      if (values.size() != 1
          || !(values.get("id") instanceof IntegrationPayload.TextParameter text))
        return "relay_tool_read requires only an invocation UUID id.";
      io.aeyer.plowshare.protocol.RelayPort.uuid(text.value());
      var result =
          invocations.read(home.project(), owner.accountHandle(), UUID.fromString(text.value()));
      return "Invocation " + result.id() + ": " + result.state() + "\n" + result.text();
    } catch (CallerFault | IllegalArgumentException invalid) {
      return "Relay invocation read refused: " + invalid.getMessage();
    }
  }
}
