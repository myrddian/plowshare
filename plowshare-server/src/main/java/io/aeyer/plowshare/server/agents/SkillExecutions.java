package io.aeyer.plowshare.server.agents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Account-owned durable skill execution receipts. Claims never authorize replay after uncertain
 * delivery.
 */
public interface SkillExecutions {
  public record Execution(
      String account,
      UUID invocation,
      String payload,
      String parent,
      String conversation,
      String executor,
      SkillDefinition skill,
      SkillDefinition.Mode mode,
      String state,
      String result) {
    public Execution {
      InvocationValues.identity(account);
      InvocationValues.identity(executor);
      java.util.Objects.requireNonNull(invocation, "invocation");
      java.util.Objects.requireNonNull(skill, "skill");
      java.util.Objects.requireNonNull(mode, "mode");
      if (parent != null) InvocationValues.identity(parent);
      if (conversation != null) InvocationValues.identity(conversation);
      InvocationValues.text(payload, false);
      InvocationValues.text(result, true);
      if (!java.util.Set.of("claimed", "running", "awaiting", "finished", "failed").contains(state))
        throw new IllegalArgumentException("invalid skill state");
    }
  }

  Optional<Execution> find(String account, UUID invocation);

  /** False leaves all child creation and paid work with the existing claimant. */
  boolean claim(
      String account,
      UUID id,
      String payload,
      String parent,
      String executor,
      SkillDefinition skill,
      SkillDefinition.Mode mode);

  void running(String account, UUID id, String conversation);

  List<Execution> active(String conversation);

  void closed(String conversation, Outcome outcome);

  void failed(String account, UUID id, String reason);

  /** Pins a complete immutable log snapshot once, before starting delegated work. */
  void context(String account, UUID id, SkillContextLog snapshot, String prompt, int through);

  /** Only handles captured in this child's account-owned snapshot may be redeemed. */
  Optional<String> contextResult(String account, String conversation, UUID handle);
}
