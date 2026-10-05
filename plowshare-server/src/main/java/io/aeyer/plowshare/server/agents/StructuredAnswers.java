package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Question;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Refused;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An answer to a question with options — spec 2026-09-29-orchestration-studio §2.3: which options
 * were chosen, or words of the answerer's own, checked against the question it answers, and
 * rendered into the text the conductor is spoken.
 *
 * <p><b>One validator for a person and a model.</b> The frame a person's TUI sends and the tool a
 * caller model calls both come here, so a conductor never has to wonder which one answered it.
 */
public final class StructuredAnswers {

  public static final int MOST_FREE = 2000;

  /** One question's answer: the labels chosen, and any words beside or instead of them. */
  public record Choice(String header, List<String> chosen, String other, String note) {
    public Choice {
      try {
        var checked =
            new io.aeyer.plowshare.protocol.Orchestration.Choice(header, chosen, other, note);
        header = checked.header();
        chosen = checked.chosen();
        other = checked.other();
        note = checked.note();
      } catch (IllegalArgumentException invalid) {
        throw new Refused(invalid.getMessage());
      }
    }
  }

  private StructuredAnswers() {}

  /** Converts untrusted wire/tool choices before application logic receives them. */
  public static List<Choice> decode(JsonNode choices) {
    if (choices == null
        || !choices.isArray()
        || choices.size() > StructuredQuestions.MOST_QUESTIONS) {
      throw new Refused("'choices' is a bounded list with one answer per question.");
    }
    List<Choice> decoded = new ArrayList<>();
    for (int at = 0; at < choices.size(); at++) {
      JsonNode node = choices.get(at);
      String where = "choice " + (at + 1);
      if (node == null || !node.isObject()) throw new Refused(where + " must be an object.");
      node.fieldNames()
          .forEachRemaining(
              key -> {
                if (!List.of("header", "chosen", "other", "note").contains(key))
                  throw new Refused("Unknown choice field: " + key);
              });
      if (!node.path("header").isTextual()) throw new Refused(where + " needs a text header.");
      List<String> chosen = new ArrayList<>();
      JsonNode labels = node.get("chosen");
      if (labels != null && !labels.isNull()) {
        if (!labels.isArray()) throw new Refused(where + " needs a list of option labels.");
        for (JsonNode label : labels) {
          if (!label.isTextual()) throw new Refused(where + " needs text option labels.");
          chosen.add(label.textValue());
        }
      }
      decoded.add(
          new Choice(
              node.get("header").textValue(),
              chosen,
              free(node, "other", where),
              free(node, "note", where)));
    }
    return List.copyOf(decoded);
  }

  /** Boundary convenience; the application contract consumes already decoded choices. */
  public static List<Choice> read(JsonNode choices, List<Question> questions) {
    return validate(decode(choices), questions);
  }

  /** Checks typed answers against the durable question, including its cardinality and options. */
  public static List<Choice> validate(List<Choice> choices, List<Question> questions) {
    Objects.requireNonNull(choices, "choices");
    Map<String, Choice> byHeader = new LinkedHashMap<>();
    for (int at = 0; at < choices.size(); at++) {
      Choice choice = choices.get(at);
      String where = "choice " + (at + 1);
      Question question =
          questions.stream()
              .filter(q -> q.header().equals(choice.header()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new Refused(
                          where
                              + " answers '"
                              + choice.header()
                              + "', which is not one of the questions: "
                              + quoted(questions.stream().map(Question::header).toList())
                              + "."));
      if (byHeader.putIfAbsent(choice.header(), choice) != null)
        throw new Refused("'" + choice.header() + "' is answered twice.");
      for (String label : choice.chosen()) {
        if (question.options().stream().noneMatch(o -> o.label().equals(label)))
          throw new Refused(
              "'"
                  + question.header()
                  + "' has no option '"
                  + label
                  + "'; its options are "
                  + quoted(
                      question.options().stream().map(StructuredQuestions.Option::label).toList())
                  + ".");
      }
      if (!question.multi() && choice.chosen().size() > 1)
        throw new Refused(
            "'"
                + question.header()
                + "' takes one choice; it was given "
                + choice.chosen().size()
                + ".");
      if (!question.multi() && !choice.chosen().isEmpty() && choice.other() != null)
        throw new Refused(
            "'" + question.header() + "' takes one choice or words of its own, not both.");
      if (choice.chosen().isEmpty() && choice.other() == null)
        throw new Refused(
            "'" + question.header() + "' is not answered; choose an option or give 'other'.");
    }
    List<Choice> ordered = new ArrayList<>();
    for (Question question : questions) {
      Choice choice = byHeader.get(question.header());
      if (choice == null)
        throw new Refused(
            "'"
                + question.header()
                + "' is not answered; every question needs a choice or words of its own.");
      ordered.add(choice);
    }
    return List.copyOf(ordered);
  }

  private static String free(JsonNode node, String field, String where) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new Refused(where + "'s '" + field + "' is text; it was " + value + ".");
    }
    String text = value.asText().strip();
    if (text.isEmpty()) {
      return null;
    }
    if (text.length() > MOST_FREE) {
      throw new Refused(
          where
              + "'s '"
              + field
              + "' is "
              + text.length()
              + " characters; at most "
              + MOST_FREE
              + ".");
    }
    return text;
  }

  private static String quoted(List<String> names) {
    return names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
  }

  /**
   * What the conductor is spoken: one line per question, then the free words beside them. It says
   * "in words", not whose: a caller model answers with the same shape a person does.
   */
  public static String render(List<Choice> choices, String note) {
    StringBuilder text = new StringBuilder();
    for (int at = 0; at < choices.size(); at++) {
      Choice choice = choices.get(at);
      if (at > 0) {
        text.append('\n');
      }
      text.append(at + 1).append(". [").append(choice.header()).append("] ");
      if (choice.chosen().isEmpty()) {
        text.append("answered in words: ").append(choice.other());
      } else {
        text.append("chose ")
            .append(
                choice.chosen().stream()
                    .map(label -> "\"" + label + "\"")
                    .collect(Collectors.joining(", ")));
        if (choice.other() != null) {
          text.append(" and added: ").append(choice.other());
        }
      }
      if (choice.note() != null) {
        text.append(" (note: ").append(choice.note()).append(')');
      }
    }
    if (note != null && !note.isBlank()) {
      text.append("\nAlso: ").append(note.strip());
    }
    return text.toString();
  }

  /** What {@code orchestration_messages.structure} holds for an answer. */
  public static io.aeyer.plowshare.protocol.Orchestration.Structure structure(
      List<Choice> choices) {
    return new io.aeyer.plowshare.protocol.Orchestration.Structure(
        null,
        null,
        choices.stream()
            .map(
                choice ->
                    new io.aeyer.plowshare.protocol.Orchestration.Choice(
                        choice.header(), choice.chosen(), choice.other(), choice.note()))
            .toList(),
        null,
        null,
        null,
        null);
  }
}
