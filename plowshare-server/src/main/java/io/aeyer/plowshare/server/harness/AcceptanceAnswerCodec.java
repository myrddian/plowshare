package io.aeyer.plowshare.server.harness;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.agents.ModelJson;
import io.aeyer.plowshare.server.orchestrations.AcceptanceChecker.*;
import io.aeyer.plowshare.server.orchestrations.Concerns;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Decodes complete acceptance answers before harness code can persist or act on them. */
final class AcceptanceAnswerCodec {
  private AcceptanceAnswerCodec() {}

  private static final int MOST_FIELD = ModelAcceptanceChecker.MOST_FIELD;

  /** {@code {"concerns": [{"about", "why", "ask"?}]}}. */
  static List<Raised> parsePlan(String content) {
    JsonNode node = object(content);
    fields(node, Set.of("concerns"));
    JsonNode list = node.path("concerns");
    if (!list.isArray()) {
      throw new Unreadable("its answer has no 'concerns' list");
    }
    List<Raised> raised = new ArrayList<>();
    bounded(list);
    for (JsonNode each : list) {
      fields(each, Set.of("about", "why", "ask"));
      raised.add(new Raised(required(each, "about"), required(each, "why"), optional(each, "ask")));
    }
    return List.copyOf(raised);
  }

  /** {@code {"resolved": true|false, "objection"?, "ask"?}}. */
  static Judged parseJudged(String content) {
    JsonNode node = object(content);
    fields(node, Set.of("resolved", "objection", "ask"));
    optional(node, "objection");
    optional(node, "ask");
    JsonNode resolved = node.path("resolved");
    if (!resolved.isBoolean()) {
      throw new Unreadable("its answer has no true or false 'resolved'");
    }
    if (resolved.asBoolean()) {
      return new Judged(true, null, null);
    }
    return new Judged(false, required(node, "objection"), optional(node, "ask"));
  }

  private static final Set<String> VERDICTS =
      Set.of(Concerns.HOLDS, Concerns.DOES_NOT_HOLD, Concerns.CANNOT_CHECK);

  /**
   * {@code {"verdicts": [{"concern", "verdict", "finding", "person_check"?}], "found": [{"about",
   * "why", "verdict", "finding", "person_check"?}]}}. A verdict for a concern it was not shown is
   * dropped; one it left out is the person's ({@code Checking}).
   */
  static End parseEnd(String content, Set<String> known) {
    JsonNode node = object(content);
    fields(node, Set.of("verdicts", "found"));
    JsonNode verdicts = node.path("verdicts");
    if (!verdicts.isArray()) {
      throw new Unreadable("its answer has no 'verdicts' list");
    }
    List<Verdict> read = new ArrayList<>();
    bounded(verdicts);
    for (JsonNode each : verdicts) {
      fields(each, Set.of("concern", "verdict", "finding", "person_check"));
      String concern = required(each, "concern");
      Verdict accepted =
          new Verdict(concern, verdict(each), required(each, "finding"), personCheck(each));
      if (known.contains(concern)) read.add(accepted);
    }
    List<Found> found = new ArrayList<>();
    JsonNode raised = node.path("found");
    if (!raised.isMissingNode() && !raised.isNull()) {
      if (!raised.isArray()) {
        throw new Unreadable("its 'found' is not a list");
      }
      bounded(raised);
      for (JsonNode each : raised) {
        fields(each, Set.of("about", "why", "verdict", "finding", "person_check"));
        found.add(
            new Found(
                required(each, "about"),
                required(each, "why"),
                verdict(each),
                required(each, "finding"),
                personCheck(each)));
      }
    }
    return new End(read, found);
  }

  private static String verdict(JsonNode each) {
    String verdict = required(each, "verdict");
    if (!VERDICTS.contains(verdict)) {
      throw new Unreadable(
          "its verdict '" + verdict + "' is not holds, does_not_hold or" + " cannot_check");
    }
    return verdict;
  }

  /** What the person is to check: required with {@code cannot_check}, ignored otherwise. */
  private static String personCheck(JsonNode each) {
    String offered = optional(each, "person_check");
    if (!Concerns.CANNOT_CHECK.equals(each.path("verdict").textValue())) return null;
    return required(each, "person_check");
  }

  private static JsonNode object(String content) {
    try {
      return ModelJson.object(content);
    } catch (ModelJson.Unreadable unreadable) {
      throw new Unreadable("its answer could not be read: " + unreadable.getMessage(), unreadable);
    }
  }

  private static String required(JsonNode node, String field) {
    String value = optional(node, field);
    if (value == null) {
      throw new Unreadable("its answer has no '" + field + "' text where one is needed");
    }
    return value;
  }

  private static String optional(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) return null;
    if (!value.isTextual()) throw new Unreadable("its '" + field + "' is not text");
    String text = value.textValue().strip();
    if (text.length() > MOST_FIELD || text.indexOf('\0') >= 0)
      throw new Unreadable("its '" + field + "' exceeds text bounds");
    return text.isEmpty() ? null : text;
  }

  private static void fields(JsonNode value, Set<String> allowed) {
    if (!value.isObject()) throw new Unreadable("its answer entry is not an object");
    value
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name))
                throw new Unreadable("its answer has an unsupported field");
            });
  }

  private static void bounded(JsonNode values) {
    if (values.size() > 1000) throw new Unreadable("its answer has too many entries");
  }
}
