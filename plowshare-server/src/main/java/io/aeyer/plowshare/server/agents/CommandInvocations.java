package io.aeyer.plowshare.server.agents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Account-owned durable command bindings. Claims never authorize replay after uncertain delivery.
 */
public interface CommandInvocations {
  public record Bound(
      String account,
      UUID id,
      String conversation,
      String sourceRun,
      String caller,
      String command,
      String kind,
      String name,
      String hash,
      String arguments,
      String mode,
      String state,
      String result) {
    public Bound {
      for (String value :
          new String[] {account, conversation, sourceRun, caller, command, name, hash})
        InvocationValues.identity(value);
      java.util.Objects.requireNonNull(id, "id");
      if (!java.util.Set.of("skill", "orchestration").contains(kind))
        throw new IllegalArgumentException("invalid bound command kind");
      if (mode != null) SkillDefinition.Mode.valueOf(mode);
      if (!java.util.Set.of("bound", "dispatching", "finished", "failed").contains(state))
        throw new IllegalArgumentException("invalid command state");
      InvocationValues.text(arguments, false);
      InvocationValues.text(result, true);
    }
  }

  /** Idempotent binding; a reused run with changed arguments or definition is refused. */
  Bound bind(
      String account,
      String conversation,
      String source,
      String caller,
      CommandCatalog.Entry command,
      String arguments,
      String mode);

  Optional<Bound> find(String account, String conversation, String caller, UUID id);

  List<Bound> pending(String account, String conversation, String caller);

  /** Only the bound-to-dispatching CAS grants execution authority. */
  boolean claim(Bound bound);

  /** Completes exactly the claimed invocation or reports a stale receipt. */
  void ended(Bound bound, String state, String result);
}
