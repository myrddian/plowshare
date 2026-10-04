package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.events.TriggerRecord;
import io.aeyer.plowshare.server.events.TriggerStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Objects;

/**
 * {@code trigger.define} — name a trigger, owned by the signed-in account whose user-inbox receives
 * the result.
 */
public final class TriggerDefineHandler implements FrameHandler {

  record Body(
      String trigger,
      String event,
      String project,
      String conversation,
      String agent,
      String task,
      Integer maxModelCalls,
      Integer maxTurns,
      Integer queueCap) {}

  private final TriggerStore triggers;
  private final Callers callers;

  public TriggerDefineHandler(TriggerStore triggers, Callers callers) {
    this.triggers = Objects.requireNonNull(triggers, "triggers");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String type = FrameTypes.TRIGGER_DEFINE;
    String handle = asking.requireHandle(type);
    String name = Payloads.required(payload, "trigger", type, "the name to define it under");
    String event = Payloads.required(payload, "event", type, "the event name it listens for");
    String agent = Payloads.required(payload, "agent", type, "the agent or bot that runs");
    String task = Payloads.required(payload, "task", type, "what the agent is to do, in prose");
    Body body = Payloads.as(payload, Body.class, type);
    if (body.project() != null && body.conversation() != null) {
      throw new CallerFault(
          type
              + " names both a project and a conversation. A conversation"
              + " already has a home; send one or the other. Nothing was defined.");
    }
    if (body.conversation() != null && (body.maxModelCalls() != null || body.maxTurns() != null)) {
      throw new CallerFault(
          type
              + " names a conversation and a limit. A run in a conversation"
              + " is a turn there and takes that conversation's own budget and turn cap;"
              + " 'maxModelCalls' and 'maxTurns' apply only to a trigger with no conversation."
              + " Nothing was defined.");
    }
    if (body.queueCap() != null && body.queueCap() < 1) {
      throw new CallerFault(type + " needs 'queueCap' of at least 1. Nothing was defined.");
    }
    // Refuses a missing conversation or an agent that home cannot see, before anything is stored.
    DefinitionResolver.Caller caller =
        body.conversation() == null
            ? callers.callerFor(body.project(), null, handle)
            : callers.callerForConversation(body.conversation(), null);
    callers.requireAgent(agent, caller);
    if (body.conversation() == null) callers.requireProject(body.project(), handle);
    else callers.requireConversationProject(body.conversation(), handle);
    return Outcome.ok(
        triggers.define(
            new TriggerRecord(
                name,
                event,
                body.project(),
                body.conversation(),
                agent,
                task,
                body.maxModelCalls(),
                body.maxTurns(),
                body.queueCap() == null ? 1 : body.queueCap(),
                false,
                handle)));
  }
}
