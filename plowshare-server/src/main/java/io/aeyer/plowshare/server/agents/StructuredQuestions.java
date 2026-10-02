package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A question with options — spec 2026-09-29-orchestration-studio §2.1: what a conductor's {@code
 * orchestration_ask} may carry besides its words, read and refused here, rendered to the text every
 * reader that does not know the shape reads, and stored as {@code orchestration_messages.structure}
 * (V70).
 *
 * <h2>The text is what a conductor, a caller model, the record and the inbox read</h2>
 *
 * <p>{@link #render} is not a courtesy for old clients. The question's text is still the column
 * everyone reads, a caller model answering its child included, so the options have to be in it —
 * with their labels exactly as an answer must name them.
 *
 * <h2>Refusals are sentences a model can act on</h2>
 *
 * <p>Every {@link Refused} names the rule it broke and the value that broke it, so the model that
 * sent it fixes the one thing wrong rather than guessing — a tool failure, not a call failure.
 */
public final class StructuredQuestions {

    public static final int MOST_QUESTIONS = 4;
    public static final int FEWEST_OPTIONS = 2;
    public static final int MOST_OPTIONS = 4;
    public static final int MOST_HEADER = 12;
    public static final int MOST_LABEL = 60;
    public static final int MOST_DESCRIPTION = 300;
    public static final int MOST_PREVIEW = 4096;

    static final ObjectMapper JSON = new ObjectMapper();

    /** One option: what it is called, what choosing it means, and what to show beside it. */
    public record Option(String label, String description, String preview) {
        public Option {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(description, "description");
        }
    }

    /** One question: its chip, its sentence, whether several options may be chosen, its options. */
    public record Question(String header, String question, boolean multi, List<Option> options) {
        public Question {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(question, "question");
            options = List.copyOf(Objects.requireNonNull(options, "options"));
        }
    }

    /** A question as asked: the lead-in the conductor wrote, and its questions. */
    public record Asked(String lead, List<Question> questions) {
        public Asked {
            Objects.requireNonNull(lead, "lead");
            questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
        }
    }

    /** A question or an answer that breaks one of the shape's rules; the message says which. */
    public static final class Refused extends IllegalArgumentException {
        public Refused(String why) {
            super(why);
        }
    }

    private StructuredQuestions() {
    }

    /** {@code questions} as a model sent it, checked against every rule of §2.1. */
    public static List<Question> read(JsonNode questions) {
        if (questions == null || !questions.isArray() || questions.isEmpty()
                || questions.size() > MOST_QUESTIONS) {
            throw new Refused("'questions', when present, is a list of 1 to " + MOST_QUESTIONS
                    + " questions" + (questions != null && questions.isArray()
                            ? "; it had " + questions.size() : "") + ".");
        }
        List<Question> read = new ArrayList<>();
        Set<String> headers = new HashSet<>();
        for (int at = 0; at < questions.size(); at++) {
            Question question = question(questions.get(at), at + 1);
            if (!headers.add(question.header())) {
                throw new Refused("question " + (at + 1) + "'s header '" + question.header()
                        + "' is already another question's; each header names one question.");
            }
            read.add(question);
        }
        return List.copyOf(read);
    }

    private static Question question(JsonNode node, int n) {
        String where = "question " + n;
        if (node == null || !node.isObject()) {
            throw new Refused(where + " is not an object with header, question and options.");
        }
        String header = text(node, "header", where, MOST_HEADER);
        String question = text(node, "question", where, Integer.MAX_VALUE);
        JsonNode multi = node.get("multi");
        if (multi != null && !multi.isNull() && !multi.isBoolean()) {
            throw new Refused(where + "'s 'multi' is true or false; it was " + multi + ".");
        }
        JsonNode options = node.get("options");
        if (options == null || !options.isArray() || options.size() < FEWEST_OPTIONS
                || options.size() > MOST_OPTIONS) {
            throw new Refused(where + " needs " + FEWEST_OPTIONS + " to " + MOST_OPTIONS
                    + " options" + (options != null && options.isArray()
                            ? "; it had " + options.size() : "") + ".");
        }
        List<Option> read = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (int at = 0; at < options.size(); at++) {
            Option option = option(options.get(at), where + ", option " + (at + 1));
            if (!labels.add(option.label())) {
                throw new Refused(where + " has the label '" + option.label() + "' twice; an"
                        + " answer names an option by its label.");
            }
            read.add(option);
        }
        return new Question(header, question, multi != null && multi.asBoolean(false), read);
    }

    private static Option option(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new Refused(where + " is not an object with label and description.");
        }
        String label = text(node, "label", where, MOST_LABEL);
        String description = text(node, "description", where, MOST_DESCRIPTION);
        JsonNode preview = node.get("preview");
        if (preview == null || preview.isNull()) {
            return new Option(label, description, null);
        }
        if (!preview.isTextual()) {
            throw new Refused(where + "'s 'preview' is text; it was " + preview + ".");
        }
        if (preview.asText().length() > MOST_PREVIEW) {
            throw new Refused(where + "'s preview is " + preview.asText().length()
                    + " characters; at most " + MOST_PREVIEW + ".");
        }
        return new Option(label, description, preview.asText());
    }

    private static String text(JsonNode node, String field, String where, int most) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new Refused(where + " needs '" + field + "' as text.");
        }
        String text = value.asText().strip();
        if (text.length() > most) {
            throw new Refused(where + "'s '" + field + "' is " + text.length()
                    + " characters; at most " + most + ".");
        }
        return text;
    }

    /**
     * The question as text: the lead, then each question numbered with its header, its options by
     * label, and how it may be answered. Previews are left out — they are for a screen, and a model
     * answering reads the labels.
     */
    public static String render(String lead, List<Question> questions) {
        StringBuilder text = new StringBuilder(lead.strip());
        for (int at = 0; at < questions.size(); at++) {
            Question question = questions.get(at);
            text.append('\n').append(at + 1).append(". [").append(question.header()).append("] ")
                    .append(question.question());
            for (Option option : question.options()) {
                text.append("\n   - ").append(option.label()).append(" — ")
                        .append(option.description());
            }
            text.append("\n   (").append(question.multi() ? "choose any" : "choose one")
                    .append(", or answer in words)");
        }
        return text.toString();
    }

    /** What {@code orchestration_messages.structure} holds for a question. */
    public static String structure(Asked asked) {
        ObjectNode root = JSON.createObjectNode();
        root.put("lead", asked.lead());
        ArrayNode questions = root.putArray("questions");
        for (Question question : asked.questions()) {
            ObjectNode node = questions.addObject();
            node.put("header", question.header());
            node.put("question", question.question());
            node.put("multi", question.multi());
            ArrayNode options = node.putArray("options");
            for (Option option : question.options()) {
                ObjectNode each = options.addObject();
                each.put("label", option.label());
                each.put("description", option.description());
                if (option.preview() != null) {
                    each.put("preview", option.preview());
                }
            }
        }
        return root.toString();
    }

    /**
     * A stored structure read back. Top-level fields other than {@code lead} and {@code questions}
     * are the asker's own — a harness-built question keeps what it checks on answering there — and
     * are not this class's to refuse.
     *
     * @throws IllegalStateException if the stored text is not a structure this class wrote
     */
    public static Asked parse(String structure) {
        try {
            JsonNode root = JSON.readTree(structure);
            JsonNode lead = root.get("lead");
            if (lead == null || !lead.isTextual()) {
                throw new Refused("a stored structure has no lead");
            }
            return new Asked(lead.asText(), read(root.get("questions")));
        } catch (JsonProcessingException | Refused unreadable) {
            throw new IllegalStateException("a stored question's structure could not be read: "
                    + unreadable.getMessage(), unreadable);
        }
    }
}
