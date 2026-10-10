package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageReports;
import io.aeyer.plowshare.server.llm.counting.PromptCount;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Explicit refresh of the next agent projection. No supplied content, URL, or claimed account is
 * accepted.
 */
@Component
public class ContextCountFrames implements FrameArea {
  private final UsageReports access;
  private final ConversationDefinitions callers;
  private final Compaction compaction;
  private final JobRuntime runtime;
  private final LlmDispatcher models;

  public ContextCountFrames(
      UsageReports access,
      ConversationDefinitions callers,
      Compaction compaction,
      JobRuntime runtime,
      LlmDispatcher models) {
    this.access = access;
    this.callers = callers;
    this.compaction = compaction;
    this.runtime = runtime;
    this.models = models;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.CONVERSATION_CONTEXT_COUNT,
        this::count,
        FrameTypes.CONVERSATION_CONTEXT_SNAPSHOT,
        this::snapshot);
  }

  private record Projection(
      AgentDefinition definition,
      List<ChatMessage> messages,
      List<ToolSchema> tools,
      UsageAttribution owner) {
    ChatRequest request() {
      return JobRuntime.requestFor(definition, messages).withTools(tools).withAttribution(owner);
    }
  }

  private Projection projection(Map<String, Object> payload, Asking asking, String frame) {
    String account = asking.requireHandle(frame);
    String conversation =
        Payloads.required(payload, "conversation", frame, "the conversation to inspect");
    access.requireConversation(account, conversation);
    String agent =
        Payloads.required(payload, "agent", frame, "the agent whose next projection to inspect");
    var definition =
        callers.readAgent(agent, callers.callerForConversation(conversation, asking.sessionId()));
    definition =
        runtime.withAgentRules(
            definition, callers.homeOfConversation(conversation), asking.sessionId(), conversation);
    return new Projection(
        definition,
        compaction.projectionFor(conversation, definition),
        runtime.schemasOfferedTo(
            definition, callers.homeOfConversation(conversation), asking.sessionId(), account),
        access.countOwner(account, conversation));
  }

  private Outcome count(Map<String, Object> payload, Asking asking) {
    var request = projection(payload, asking, FrameTypes.CONVERSATION_CONTEXT_COUNT).request();
    return Outcome.ok(
        Map.of(
            "conversation",
            payload.get("conversation"),
            "agent",
            payload.get("agent"),
            "projection",
            "next",
            "count",
            models.count(request)));
  }

  /**
   * A pure construction preview. Only an explicit measure asks a tokenizer; neither path generates.
   */
  private Outcome snapshot(Map<String, Object> payload, Asking asking) {
    if (payload.containsKey("measure") && !(payload.get("measure") instanceof Boolean))
      throw new IllegalArgumentException("measure must be a boolean");
    var projection = projection(payload, asking, FrameTypes.CONVERSATION_CONTEXT_SNAPSHOT);
    var settings = projection.definition().sampling();
    var sampling = new LinkedHashMap<String, Object>();
    settings.temperature().ifPresent(v -> sampling.put("temperature", v));
    settings.topP().ifPresent(v -> sampling.put("top_p", v));
    settings.topK().ifPresent(v -> sampling.put("top_k", v));
    settings.maxTokens().ifPresent(v -> sampling.put("max_tokens", v));
    settings.reasoningEffort().ifPresent(v -> sampling.put("reasoning_effort", v.wire()));
    settings.responseFormat().ifPresent(v -> sampling.put("response_format", v));
    var result = new LinkedHashMap<String, Object>();
    result.put("conversation", payload.get("conversation"));
    result.put("agent", payload.get("agent"));
    result.put("projection", "next");
    result.put("captured_at", Instant.now().toString());
    result.put("model", projection.definition().model());
    result.put("sampling", sampling);
    result.put(
        "messages", projection.messages().stream().map(ContextCountFrames::message).toList());
    result.put("tools", projection.tools());
    result.put(
        "count",
        !Boolean.TRUE.equals(payload.get("measure"))
            ? null
            : projection.messages().stream().allMatch(m -> m.role() == ChatMessage.Role.SYSTEM)
                ? PromptCount.unknown(null, projection.definition().model(), "empty_conversation")
                : models.count(projection.request()));
    return Outcome.ok(result);
  }

  private static Map<String, Object> message(ChatMessage message) {
    var result = new LinkedHashMap<String, Object>();
    result.put("role", message.role().wireName());
    result.put(
        "parts",
        message.parts().stream()
            .map(
                part ->
                    part instanceof Content.Image image
                        ? Map.of("type", "image", "uid", image.uid(), "omitted", true)
                        : Map.of("type", "text", "text", part.text()))
            .toList());
    result.put("tool_calls", message.toolCalls());
    result.put("tool_call_id", message.toolCallId());
    return result;
  }
}
