package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Pure execution-envelope rules shared by job admission and synchronous delegation. */
public final class OperationExecution {
  private OperationExecution() {}

  /** Actions needed by the definition and its declared child capability, never management. */
  public static Set<AuthorityAction> actions(AgentDefinition definition) {
    var actions = EnumSet.of(AuthorityAction.READ, AuthorityAction.WRITE);
    boolean children =
        !definition.calls().isEmpty()
            || !definition.skills().isEmpty()
            || !definition.orchestrations().isEmpty()
            || definition.reviewWith() != null
            || definition.tools().contains(AgentRunTool.NAME);
    if (!definition.nativeOnly()) actions.add(AuthorityAction.RUN_MODEL);
    if (children) actions.add(AuthorityAction.CONVERSATION_CREATE);
    return Set.copyOf(actions);
  }

  static Set<AuthorityAction> childActions(
      OperationAuthority.Admission parent, AgentDefinition definition) {
    var actions = EnumSet.copyOf(actions(definition));
    // A native intermediary makes no inference itself. It can pass on an already-retained
    // inference ceiling to its declared descendants, never acquire one from ambient grants.
    if (actions.contains(AuthorityAction.CONVERSATION_CREATE)
        && parent.actions().contains(AuthorityAction.RUN_MODEL))
      actions.add(AuthorityAction.RUN_MODEL);
    return Set.copyOf(actions);
  }

  static OperationAuthority.Admission admit(
      OperationAuthority operations,
      String executor,
      Home home,
      AgentDefinition definition,
      String conversation,
      Origin origin,
      Budget budget) {
    var retained =
        conversation == null
            ? Optional.<OperationAuthority.Conversation>empty()
            : operations.conversation(conversation);
    if (retained.isEmpty()) {
      if (origin == Origin.ORCHESTRATION || origin == Origin.DELEGATION)
        throw new CallerFault("This continuation has no retained authority lineage");
      return operations.admit(executor, home, actions(definition));
    }
    var bound = retained.get();
    requireBinding(bound, executor, home, definition, budget);
    var required = actions(definition);
    if (!bound.admission().actions().containsAll(required))
      throw new CallerFault("The continuation exceeds its retained action ceiling");
    // A conductor's harness extras can create children even when not named on its definition.
    return operations.derive(bound.admission(), bound.admission().actions());
  }

  static void requireBinding(
      OperationAuthority.Conversation bound,
      String executor,
      Home home,
      AgentDefinition definition,
      Budget budget) {
    if (!bound.admission().executor().equals(executor)
        || !bound.admission().home().equals(home)
        || !bound.agent().equals(definition.name())
        || !bound.capabilities().equals(fingerprint(definition))
        || !bound.budget().equals(budget.reference()))
      throw new CallerFault("Execution cannot replace its retained account, agent, home or budget");
  }

  /** Retains existing capability declarations; this does not define account-based tool RBAC. */
  public static String fingerprint(AgentDefinition definition) {
    var values = new ArrayList<String>();
    values.add("operation-capabilities-v1");
    values.add(definition.name());
    values.add(Objects.toString(definition.model(), ""));
    values.add(Objects.toString(definition.fallback().model(), ""));
    values.add(Integer.toString(definition.fallback().maxAttempts()));
    values.add(Objects.toString(definition.reviewWith(), ""));
    for (var items :
        List.of(
            definition.tools(),
            definition.calls(),
            definition.skills(),
            definition.orchestrations(),
            definition.scopes().stream()
                .map(io.aeyer.plowshare.server.files.Grant::declaration)
                .toList(),
            definition.fallback().on().stream()
                .map(AgentDefinition.Fallback.Trigger::key)
                .toList())) {
      values.add(Integer.toString(items.size()));
      values.addAll(items.stream().sorted().toList());
    }
    values.add(Boolean.toString(definition.vision()));
    values.add(Boolean.toString(definition.bot()));
    values.add(Boolean.toString(definition.board()));
    values.add(Boolean.toString(definition.dynamic()));
    values.add(Boolean.toString(definition.announcesInbox()));
    values.add(Boolean.toString(definition.isSystem()));
    values.add(Boolean.toString(definition.delegable()));
    values.add(Boolean.toString(definition.exported()));
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      // Length framing avoids aliases between adjacent arbitrary declaration values.
      for (String value : values) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required SHA-256 provider missing", impossible);
    }
  }
}
