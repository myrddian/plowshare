package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageQueryService;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Explicit refresh of the next agent projection. No supplied content, URL, or claimed account is accepted. */
@Component
public class ContextCountFrames implements FrameArea {
    private final UsageQueryService access;
    private final Callers callers;
    private final Compaction compaction;
    private final JobRuntime runtime;
    private final LlmDispatcher models;
    public ContextCountFrames(UsageQueryService access,Callers callers,Compaction compaction,JobRuntime runtime,LlmDispatcher models) {
        this.access=access;this.callers=callers;this.compaction=compaction;this.runtime=runtime;this.models=models;
    }
    @Override public Map<String,FrameHandler> frames() {return Map.of(FrameTypes.CONVERSATION_CONTEXT_COUNT,this::count);}
    private Outcome count(Map<String,Object> payload,Asking asking) {
        String account=asking.requireHandle(FrameTypes.CONVERSATION_CONTEXT_COUNT);
        String conversation=Payloads.required(payload,"conversation",FrameTypes.CONVERSATION_CONTEXT_COUNT,"the conversation to count");
        access.requireConversation(account,conversation);
        String agent=Payloads.required(payload,"agent",FrameTypes.CONVERSATION_CONTEXT_COUNT,"the agent whose next projection to count");
        var definition=callers.readAgent(agent,callers.callerForConversation(conversation,asking.sessionId()));
        var request=JobRuntime.requestFor(definition,compaction.projectionFor(conversation,definition))
                .withTools(runtime.schemasOfferedTo(definition))
                .withAttribution(access.countOwner(account,conversation));
        return Outcome.ok(Map.of("conversation",conversation,"agent",agent,"projection","next", "count",models.count(request)));
    }
}
