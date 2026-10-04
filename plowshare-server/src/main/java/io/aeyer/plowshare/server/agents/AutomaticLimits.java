package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Project-authorized finite grants. Orchestration caps remain the engine's decision. */
public final class AutomaticLimits {
  @FunctionalInterface
  interface Grant {
    void commit(String conversation, int total, int spent);
  }

  public static final AutomaticLimits NONE =
      new AutomaticLimits(
          (project, session) -> ProjectCaps.NONE, id -> Optional.empty(), (id, total, spent) -> {});
  private final BiFunction<String, String, ProjectCaps> caps;
  private final Function<String, Optional<ConversationRecord>> conversations;
  private final Grant grant;

  public AutomaticLimits(Environments environments, ConversationStore conversations) {
    this(environments::caps, conversations::find, conversations::increaseBudget);
  }

  AutomaticLimits(
      BiFunction<String, String, ProjectCaps> caps,
      Function<String, Optional<ConversationRecord>> conversations,
      Grant grant) {
    this.caps = caps;
    this.conversations = conversations;
    this.grant = grant;
  }

  private ConversationRecord owner(String conversation) {
    var visited = new HashSet<String>();
    while (conversation != null && visited.size() < 128 && visited.add(conversation)) {
      var record = conversations.apply(conversation).orElse(null);
      if (record == null) return null;
      if (record.budget() != null) return record;
      if (record.origin() != Origin.DELEGATION) return null;
      conversation = record.parentId();
    }
    return null;
  }

  private ProjectCaps policy(ConversationRecord owner, String session) {
    if (owner == null || owner.origin() == Origin.ORCHESTRATION || owner.home().isGlobal())
      return ProjectCaps.NONE;
    return caps.apply(owner.home().project(), session);
  }

  public boolean budget(
      String conversation,
      Budget budget,
      AgentDefinition definition,
      String session,
      Transcript transcript) {
    if (!budget.exhausted()) return false;
    var owner = owner(conversation);
    var policy = policy(owner, session);
    if (!policy.increasesAutomatically()) return false;
    int chunk =
        policy.budget().value() == null ? definition.maxModelCalls() : policy.budget().value();
    boolean increased =
        budget.increaseIfExhausted(chunk, total -> grant.commit(owner.id(), total, budget.spent()));
    if (increased)
      transcript.record(
          LoggedEntry.diagnostic(
              "Project auto-increase approved a model-call budget of "
                  + budget.limit()
                  + "; "
                  + budget.spent()
                  + " calls remain accounted for."));
    return increased;
  }

  public boolean steps(
      String conversation,
      TurnCap cap,
      int taken,
      AgentDefinition definition,
      String session,
      Transcript transcript) {
    if (!cap.stops(taken)) return false;
    var policy = policy(owner(conversation), session);
    if (!policy.increasesAutomatically() || taken == Integer.MAX_VALUE) return false;
    int chunk = policy.steps().value() == null ? definition.maxTurns() : policy.steps().value();
    int next = (int) Math.min(Integer.MAX_VALUE, (long) taken + chunk);
    cap.changeTo(next);
    transcript.record(
        LoggedEntry.diagnostic(
            "Project auto-increase approved a step limit of "
                + next
                + "; "
                + taken
                + " steps remain accounted for."));
    return true;
  }
}
