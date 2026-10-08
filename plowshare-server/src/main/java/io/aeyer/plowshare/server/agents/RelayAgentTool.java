package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.relay.tools.RelayToolCodec;
import io.aeyer.plowshare.server.relay.tools.RelayToolDefinition;
import io.aeyer.plowshare.server.relay.tools.RelayToolInvocations;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Normal named model tool. Project, provider and reply routing never come from model arguments. */
public final class RelayAgentTool implements AgentTool {
  private final List<RelayToolDefinition> bindings;
  private final RelayToolInvocations invocations;
  private final BooleanSupplier cancelled;
  private String call;

  public RelayAgentTool(List<RelayToolDefinition> bindings, RelayToolInvocations invocations) {
    this(bindings, invocations, () -> false);
  }

  private RelayAgentTool(
      List<RelayToolDefinition> bindings,
      RelayToolInvocations invocations,
      BooleanSupplier cancelled) {
    this.bindings = List.copyOf(bindings);
    if (bindings.isEmpty()) throw new IllegalArgumentException("A Relay tool needs a binding");
    this.invocations = Objects.requireNonNull(invocations);
    this.cancelled = Objects.requireNonNull(cancelled);
  }

  /** Call identity is per run, never mutable state shared by concurrent jobs. */
  public RelayAgentTool forRun(BooleanSupplier cancelled) {
    return new RelayAgentTool(bindings, invocations, cancelled);
  }

  @Override
  public ToolSchema schema() {
    return bindings.getFirst().schema();
  }

  @Override
  public void calledAs(String id) {
    call = id;
  }

  @Override
  public String run(String arguments, Home home) {
    return "Relay tools require an authenticated run owner.";
  }

  @Override
  public String run(String arguments, Home home, UsageAttribution owner) {
    Objects.requireNonNull(arguments);
    Objects.requireNonNull(home);
    if (owner == null || call == null)
      return "Relay tools require an authenticated execution and model call identity.";
    var binding = bindings.stream().filter(b -> b.project().equals(home.project())).findFirst();
    if (binding.isEmpty()) return "This tool has no provider binding in the run's project.";
    try {
      var result =
          invocations.invoke(
              binding.get(), RelayToolCodec.arguments(arguments), owner, call, cancelled);
      return "Invocation " + result.id() + ": " + result.state() + "\n" + result.text();
    } catch (CallerFault | IllegalArgumentException refused) {
      return "Relay tool refused: " + refused.getMessage();
    }
  }
}
