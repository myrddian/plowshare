package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.aeyer.plowshare.protocol.ToolCall;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Complete immutable parent-log handoff. Stored metadata has an explicit vocabulary: source
 * migrations must update this DTO rather than quietly introduce new unvalidated property bags.
 * Ejected payloads remain visible as missing data and cannot become an inherited context.
 */
public record SkillContextLog(List<Entry> entries) {
  public SkillContextLog {
    entries = List.copyOf(entries);
    if (entries.size() > 50000)
      throw new IllegalArgumentException("skill context has too many entries");
  }

  public int through() {
    return entries.stream().mapToInt(Entry::ordinal).max().orElse(0);
  }

  public boolean complete() {
    return entries.stream().noneMatch(entry -> entry.content() == null);
  }

  public record Entry(
      @JsonProperty("conversation_id") String conversationId,
      int ordinal,
      String kind,
      String role,
      String content,
      @JsonProperty("tool_call_id") String toolCallId,
      @JsonProperty("tool_calls") List<ToolCall> toolCalls,
      @JsonProperty("superseded_by") Integer supersededBy,
      @JsonProperty("turn_ordinal") int turnOrdinal,
      UUID handle,
      @JsonProperty("recorded_at") Instant recordedAt,
      @JsonProperty("took_ms") Long tookMillis,
      @JsonProperty("ejected_at") Instant ejectedAt,
      String export,
      @JsonProperty("ejected_chars") Integer ejectedChars,
      @JsonProperty("learned_at") Instant learnedAt,
      UUID invocation,
      @JsonProperty("produced_by") String producedBy,
      String dispatch,
      @JsonProperty("model_specifier") String modelSpecifier,
      @JsonProperty("model_pool") String modelPool,
      @JsonProperty("wire_model") String wireModel,
      String completion,
      @JsonProperty("finish_reason") String finishReason,
      @JsonProperty("prompt_tokens") Integer promptTokens,
      @JsonProperty("completion_tokens") Integer completionTokens,
      @JsonProperty("reasoning_tokens") Integer reasoningTokens,
      @JsonProperty("sent_at") Instant sentAt,
      @JsonProperty("fallback_reason") String fallbackReason,
      String thinking,
      @JsonProperty("first_token_ms") Long firstTokenMillis,
      String speaker,
      @JsonProperty("speaker_name") String speakerName,
      String outcome,
      java.util.Map<String, String> salients) {
    public Entry {
      InvocationValues.identity(conversationId);
      if (ordinal < 1 || turnOrdinal < 1)
        throw new IllegalArgumentException("invalid skill context ordinal");
      EntryKind.of(kind);
      if (role != null && !Set.of("system", "user", "assistant", "tool").contains(role))
        throw new IllegalArgumentException("invalid skill context role");
      for (String identity :
          new String[] {
            toolCallId,
            producedBy,
            dispatch,
            modelSpecifier,
            modelPool,
            wireModel,
            completion,
            speakerName
          }) if (identity != null) InvocationValues.identity(identity);
      for (String text : new String[] {content, finishReason, fallbackReason, thinking, export})
        InvocationValues.text(text, true);
      if ((content == null) != (ejectedAt != null))
        throw new IllegalArgumentException("skill context has inconsistent ejected content");
      for (Number count :
          new Number[] {
            tookMillis,
            ejectedChars,
            promptTokens,
            completionTokens,
            reasoningTokens,
            firstTokenMillis
          })
        if (count != null && count.longValue() < 0)
          throw new IllegalArgumentException("negative skill context count");
      if (supersededBy != null && supersededBy <= ordinal)
        throw new IllegalArgumentException("invalid supersession ordinal");
      if (speaker != null && !Set.of("person", "harness").contains(speaker))
        throw new IllegalArgumentException("invalid skill context speaker");
      if (outcome != null) {
        InvocationValues.identity(outcome);
        if (!kind.equals("tool_result"))
          throw new IllegalArgumentException("only a tool result has an outcome");
      }
      if (salients != null) {
        salients = java.util.Map.copyOf(salients);
        if (salients.size() > 1000 || !salients.isEmpty() && !kind.equals("answer"))
          throw new IllegalArgumentException("invalid context call salients");
        salients.forEach(
            (id, text) -> {
              InvocationValues.identity(id);
              InvocationValues.text(text, false);
            });
      }
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
      if (toolCalls.size() > 1000)
        throw new IllegalArgumentException("too many skill context calls");
      for (ToolCall call : toolCalls) {
        InvocationValues.identity(call.id());
        InvocationValues.identity(call.name());
        InvocationValues.text(call.arguments(), false);
      }
    }
  }
}
