package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.VerdictKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Model-response boundary for memory judgments and schedule readings. Syntax recovery is allowed
 * only here; field types, vocabulary and bounds are checked before application code sees an answer.
 * Referenced memory ownership, cron semantics and available agents remain application checks.
 */
public final class ModelAnswers {
  private ModelAnswers() {}

  public record Scribe(VerdictKind verdict, String target, String reason) {
    public Scribe {
      Objects.requireNonNull(verdict, "verdict");
      reason = checked(reason, "reason", 32768, true);
      if (target != null) {
        target = checked(target, "target", 1024, false).strip();
        if (target
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
          throw new ModelJson.Unreadable("invalid 'target'");
      }
      if (verdict != VerdictKind.NEW && (target == null || target.isBlank()))
        throw new ModelJson.Unreadable("a '" + verdict.wireName() + "' verdict names no memory");
    }
  }

  public enum Decision {
    PROMOTE,
    ASK,
    KEEP
  }

  public record Curation(Decision decision, String reason) {
    public Curation {
      Objects.requireNonNull(decision, "decision");
      reason = checked(reason, "reason", 32768, true);
    }
  }

  public record Candidate(String summary, String scope, String body) {
    public Candidate {
      summary = checked(summary, "summary", 32768, true).strip();
      scope = checked(scope, "scope", 32768, true).strip();
      body = checked(body, "body", 524288, true).strip();
      if (summary
          .codePoints()
          .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
        throw new ModelJson.Unreadable("summary must be a single line");
    }
  }

  public record Learning(List<Candidate> memories) {
    public Learning {
      memories = List.copyOf(memories);
      if (memories.size() > 3) throw new ModelJson.Unreadable("too many proposed memories");
    }
  }

  public record Schedule(
      String unreadable,
      String cron,
      String agent,
      String task,
      String when,
      boolean intoConversation) {
    public Schedule {
      unreadable = checked(unreadable, "unreadable", 32768, false).strip();
      cron = checked(cron, "cron", 4096, false).strip();
      agent = checked(agent, "agent", 1024, false).strip();
      task = checked(task, "task", 524288, false).strip();
      when = checked(when, "when", 32768, false).strip();
      if (agent
              .codePoints()
              .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029)
          || cron.codePoints()
              .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
        throw new ModelJson.Unreadable("invalid schedule identity");
    }
  }

  public record Advice(String note) {
    public Advice {
      if (note != null) note = checked(note, "note", 524288, false);
    }
  }

  public static Advice advice(String content) {
    JsonNode answer = object(content, Set.of("note"));
    JsonNode note = answer.get("note");
    if (note == null || note.isNull()) return new Advice(null);
    if (!note.isTextual()) throw new ModelJson.Unreadable("the note is not text");
    return new Advice(note.textValue());
  }

  public static Scribe scribe(String content) {
    JsonNode answer = object(content, Set.of("verdict", "target", "reason"));
    String word = required(answer, "verdict");
    VerdictKind kind;
    try {
      kind = VerdictKind.fromWireName(word);
    } catch (IllegalArgumentException unknown) {
      throw new ModelJson.Unreadable(
          "'" + MemoryTools.oneLine(word) + "' is not one of new, merged_into, supersedes");
    }
    JsonNode target = answer.get("target");
    if (target != null && !target.isNull() && !target.isTextual())
      throw new ModelJson.Unreadable("invalid 'target'");
    return new Scribe(
        kind,
        target == null || target.isNull() ? null : target.textValue(),
        required(answer, "reason"));
  }

  public static Curation curation(String content) {
    JsonNode answer = object(content, Set.of("decision", "reason"));
    String word = required(answer, "decision");
    Decision decision;
    try {
      decision = Decision.valueOf(word.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new ModelJson.Unreadable(
          "'" + MemoryTools.oneLine(word) + "' is not one of promote, ask, keep");
    }
    return new Curation(decision, required(answer, "reason"));
  }

  public static Learning learning(String content) {
    JsonNode answer = object(content, Set.of("memories"));
    JsonNode memories = answer.get("memories");
    if (memories == null) return new Learning(List.of());
    if (!memories.isArray() || memories.size() > 3)
      throw new ModelJson.Unreadable("memories must be an array of at most three proposals");
    List<Candidate> result = new ArrayList<>();
    for (JsonNode candidate : memories) {
      fields(candidate, Set.of("summary", "scope", "body"));
      result.add(
          new Candidate(
              required(candidate, "summary"),
              required(candidate, "scope"),
              required(candidate, "body")));
    }
    return new Learning(result);
  }

  public static Schedule schedule(String content) {
    JsonNode answer =
        object(content, Set.of("unreadable", "cron", "agent", "task", "when", "intoConversation"));
    return new Schedule(
        optional(answer, "unreadable"),
        optional(answer, "cron"),
        optional(answer, "agent"),
        optional(answer, "task"),
        optional(answer, "when"),
        optionalBoolean(answer, "intoConversation"));
  }

  private static JsonNode object(String content, Set<String> allowed) {
    if (content != null && content.length() > 524288)
      throw new ModelJson.Unreadable("answer exceeds its bound");
    JsonNode answer = ModelJson.object(content);
    fields(answer, allowed);
    return answer;
  }

  private static void fields(JsonNode answer, Set<String> allowed) {
    if (!answer.isObject()) throw new ModelJson.Unreadable("answer must be an object");
    answer
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name))
                throw new ModelJson.Unreadable("answer has an unsupported field");
            });
  }

  private static String required(JsonNode answer, String field) {
    JsonNode value = answer.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank())
      throw new ModelJson.Unreadable("no '" + field + "'");
    return value.textValue();
  }

  private static String optional(JsonNode answer, String field) {
    JsonNode value = answer.get(field);
    if (value == null) return "";
    if (!value.isTextual()) throw new ModelJson.Unreadable("invalid '" + field + "'");
    return value.textValue();
  }

  private static boolean optionalBoolean(JsonNode answer, String field) {
    JsonNode value = answer.get(field);
    if (value == null) return false;
    if (!value.isBoolean()) throw new ModelJson.Unreadable("invalid '" + field + "'");
    return value.booleanValue();
  }

  private static String checked(String value, String field, int limit, boolean required) {
    if (value == null
        || required && value.isBlank()
        || value.length() > limit
        || value.indexOf('\0') >= 0) throw new ModelJson.Unreadable("invalid '" + field + "'");
    return value;
  }
}
