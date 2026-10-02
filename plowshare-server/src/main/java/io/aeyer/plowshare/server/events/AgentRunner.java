package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedTurnCap;
import java.util.Objects;
import java.util.function.BiConsumer;

/** The production Runner: the same doors a person's run goes through, with no session. */
public final class AgentRunner implements Dispatcher.Runner {

    private final Callers callers;
    private final JobStore jobs;
    private final Turn turns;

    public AgentRunner(Callers callers, JobStore jobs, Turn turns) {
        this.callers = Objects.requireNonNull(callers, "callers");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.turns = Objects.requireNonNull(turns, "turns");
    }

    @Override
    public boolean busy(TriggerRecord trigger) {
        return trigger.conversation() != null && turns.isSpeaking(trigger.conversation());
    }

    @Override
    public String start(TriggerRecord trigger, String utterance, BiConsumer<String, Outcome> ended) {
        if (trigger.conversation() == null) {
            TurnCap cap = RequestedTurnCap.in(trigger.maxTurns(), null, "this trigger's run");
            AgentDefinition definition = callers.requireAgent(trigger.agent(),
                    callers.callerFor(trigger.project(), null));
            return jobs.submitEvent(definition, utterance, RequestedHome.in(trigger.project()),
                    trigger.maxModelCalls(), cap, trigger.definedBy(),
                    Speaker.event(trigger.name()), ended).id();
        }
        String conversation = trigger.conversation();
        AgentDefinition definition = callers.requireAgent(trigger.agent(),
                callers.callerForConversation(conversation, null));
        // A trigger's limits apply to untargeted runs only: a turn in a conversation runs under
        // that conversation's ceilings, as a person's own turn there does. trigger.define refuses
        // limits beside a conversation; null here also covers a row stored before it did.
        //
        // The trigger speaks, not the account that defined it: the task is the harness's words
        // on the trigger's behalf, and a reader of the log must never be told the person said it.
        return turns.speak(conversation, definition, utterance, null, null,
                Speaker.event(trigger.name()), outcome -> ended.accept(conversation, outcome));
    }
}
