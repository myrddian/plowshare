package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
            Objects.requireNonNull(header, "header");
            chosen = List.copyOf(Objects.requireNonNull(chosen, "chosen"));
        }
    }

    private StructuredAnswers() {
    }

    /** {@code choices} as sent, checked against {@code questions}; one per question, in order. */
    public static List<Choice> read(JsonNode choices, List<Question> questions) {
        if (choices == null || !choices.isArray()) {
            throw new Refused("'choices' is a list with one answer per question.");
        }
        Map<String, Choice> byHeader = new LinkedHashMap<>();
        for (int at = 0; at < choices.size(); at++) {
            JsonNode node = choices.get(at);
            String where = "choice " + (at + 1);
            if (node == null || !node.isObject()) {
                throw new Refused(where + " is not an object with header and chosen.");
            }
            JsonNode header = node.get("header");
            if (header == null || !header.isTextual()) {
                throw new Refused(where + " needs 'header', naming the question it answers.");
            }
            String named = header.asText().strip();
            Question question = questions.stream().filter(q -> q.header().equals(named))
                    .findFirst().orElseThrow(() -> new Refused(where + " answers '" + named
                            + "', which is not one of the questions: " + quoted(questions.stream()
                                    .map(Question::header).toList()) + "."));
            if (byHeader.containsKey(named)) {
                throw new Refused("'" + named + "' is answered twice.");
            }
            byHeader.put(named, choice(node, question, where));
        }
        List<Choice> ordered = new ArrayList<>();
        for (Question question : questions) {
            Choice choice = byHeader.get(question.header());
            if (choice == null) {
                throw new Refused("'" + question.header() + "' is not answered; every question"
                        + " needs a choice or words of its own.");
            }
            ordered.add(choice);
        }
        return List.copyOf(ordered);
    }

    private static Choice choice(JsonNode node, Question question, String where) {
        String header = question.header();
        List<String> chosen = new ArrayList<>();
        JsonNode labels = node.get("chosen");
        if (labels != null && !labels.isNull()) {
            if (!labels.isArray()) {
                throw new Refused(where + "'s 'chosen' is a list of option labels.");
            }
            for (JsonNode label : labels) {
                if (!label.isTextual()) {
                    throw new Refused(where + "'s 'chosen' is a list of option labels; it held "
                            + label + ".");
                }
                String named = label.asText().strip();
                if (question.options().stream().noneMatch(o -> o.label().equals(named))) {
                    throw new Refused("'" + header + "' has no option '" + named + "'; its options"
                            + " are " + quoted(question.options().stream()
                                    .map(StructuredQuestions.Option::label).toList()) + ".");
                }
                if (chosen.contains(named)) {
                    throw new Refused("'" + header + "' chooses '" + named + "' twice.");
                }
                chosen.add(named);
            }
        }
        String other = free(node, "other", where);
        String note = free(node, "note", where);
        if (!question.multi() && chosen.size() > 1) {
            throw new Refused("'" + header + "' takes one choice; it was given " + chosen.size()
                    + ".");
        }
        if (!question.multi() && !chosen.isEmpty() && other != null) {
            throw new Refused("'" + header + "' takes one choice or words of its own, not both.");
        }
        if (chosen.isEmpty() && other == null) {
            throw new Refused("'" + header + "' is not answered; choose an option or give"
                    + " 'other'.");
        }
        return new Choice(header, chosen, other, note);
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
            throw new Refused(where + "'s '" + field + "' is " + text.length()
                    + " characters; at most " + MOST_FREE + ".");
        }
        return text;
    }

    private static String quoted(List<String> names) {
        return names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
    }

    /**
     * What the conductor is spoken: one line per question, then the free words beside them. It
     * says "in words", not whose: a caller model answers with the same shape a person does.
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
                text.append("chose ").append(choice.chosen().stream()
                        .map(label -> "\"" + label + "\"").collect(Collectors.joining(", ")));
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
    public static String structure(List<Choice> choices) {
        ObjectNode root = StructuredQuestions.JSON.createObjectNode();
        ArrayNode array = root.putArray("choices");
        for (Choice choice : choices) {
            ObjectNode node = array.addObject();
            node.put("header", choice.header());
            ArrayNode chosen = node.putArray("chosen");
            choice.chosen().forEach(chosen::add);
            if (choice.other() != null) {
                node.put("other", choice.other());
            }
            if (choice.note() != null) {
                node.put("note", choice.note());
            }
        }
        return root.toString();
    }
}
