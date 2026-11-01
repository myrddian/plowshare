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
public final class RelayAgentTool implements AgentTool.ContentTool {
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
  public boolean typedResults(Home home) {
    return bindings.stream()
        .anyMatch(
            binding ->
                binding.project().equals(home.project())
                    && io.aeyer.plowshare.protocol.ToolContent.PROFILE.equals(
                        binding.resultProfile()));
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
    return execute(arguments, home, owner, false).text();
  }

  @Override
  public Result runResult(String arguments, Home home, UsageAttribution owner) {
    return execute(arguments, home, owner, true);
  }

  private Result execute(String arguments, Home home, UsageAttribution owner, boolean typed) {
    Objects.requireNonNull(arguments);
    Objects.requireNonNull(home);
    if (owner == null || call == null)
      return new Result(
          "Relay tools require an authenticated execution and model call identity.", null);
    var binding = bindings.stream().filter(b -> b.project().equals(home.project())).findFirst();
    if (binding.isEmpty())
      return new Result("This tool has no provider binding in the run's project.", null);
    try {
      var result =
          invocations.invoke(
              binding.get(), RelayToolCodec.arguments(arguments), owner, call, cancelled);
      String prefix = "Invocation " + result.id() + ": " + result.state() + "\n";
      if (!typed || result.content() == null || result.content().images().isEmpty())
        return new Result(prefix + result.displayText(), null);
      String descriptors =
          result.content().images().stream()
              .map(image -> "Image " + image.imageId() + " (" + image.use() + ")")
              .collect(java.util.stream.Collectors.joining("\n"));
      return new Result(
          prefix + result.text() + "\n" + descriptors,
          new ToolObservation(result.id(), result.content(), result.content()));
    } catch (CallerFault | IllegalArgumentException refused) {
      return new Result("Relay tool refused: " + refused.getMessage(), null);
    }
  }
}
