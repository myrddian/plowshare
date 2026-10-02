package io.aeyer.plowshare.server.hooks.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.hooks.Mode;
import io.aeyer.plowshare.server.hooks.Stage;
import java.util.Iterator;
import java.util.Set;

/** What a hook returned, checked against what its stage allows. */
public sealed interface Decision {

    record Nothing() implements Decision {}

    record Add(String text, Mode mode) implements Decision {}

    record Note(String text) implements Decision {}

    record Redact(String text) implements Decision {}

    record Allow() implements Decision {}

    record Deny(String reason) implements Decision {}

    /** {@code { ask: reason }}: a person decides. Read by {@code run}'s gate only. */
    record Ask(String reason) implements Decision {}

    record Rewrite(String argumentsJson) implements Decision {}

    /** {@code { keep: text }} at {@code fold.post}: kept verbatim after the fold's summary. */
    record Keep(String text) implements Decision {}

    /** {@code { notify: text }}: for the log owner's inbox (spec 2026-09-28-hooks-reach-the-log decision 8). */
    record Notify(String text) implements Decision {}

    ObjectMapper JSON = new ObjectMapper();

    /**
     * Every key a stage's decision object may carry, {@code prompt.pre}'s {@code mode} included.
     * One table, read by {@link #read} and by {@code HookContractMirrorTest} against the
     * TypeScript decision types (spec 2026-09-28-hooks-reach-the-log §4). No stage offers
     * {@code allow} on an approval (decision 5).
     */
    static Set<String> keys(Stage stage) {
        return switch (stage) {
            case PROMPT_PRE -> Set.of("add", "mode");
            case PROMPT_POST, TOOL_POST -> Set.of("note", "redact");
            case TOOL_PRE -> Set.of("allow", "deny", "rewrite", "ask");
            case STEP_POST, DELIVERY_PRE -> Set.of("note");
            case LOG_OPEN -> Set.of("add");
            case LOG_CLOSE, APPROVAL_POST, DELIVERY_POST -> Set.of("notify");
            case STAGE_PRE, STAGE_POST, APPROVAL_PRE -> Set.of("deny", "note");
            case FOLD_POST -> Set.of("keep", "notify");
        };
    }

    static Decision read(Stage stage, String json) throws HookFailure {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (Exception unreadable) {
            throw new HookFailure("a " + stage.wireName() + " hook returned something that is not JSON");
        }
        if (node == null || node.isNull()) {
            return new Nothing();
        }
        if (!node.isObject()) {
            throw wrong(stage, node);
        }
        // Exactly one of the stage's decision keys, nothing this build has never
        // heard of: without this, an object naming several decisions at once (a
        // hook bug, or two branches merged badly) would read as whichever `if` in
        // the switch below happens to run first — Java field order, not the
        // hook's intent — and every other decision it also named would be
        // silently dropped.
        Set<String> allowedKeys = keys(stage);
        // "mode" is not itself a decision — it is prompt.pre `add`'s required
        // companion — but it is still a key a prompt.pre object may carry.
        Set<String> decisionKeys = stage == Stage.PROMPT_PRE ? Set.of("add") : allowedKeys;
        int decisionKeysPresent = 0;
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowedKeys.contains(name)) {
                throw wrong(stage, node);
            }
            if (decisionKeys.contains(name)) {
                decisionKeysPresent++;
            }
        }
        if (decisionKeysPresent != 1) {
            throw wrong(stage, node);
        }
        switch (stage) {
            case PROMPT_PRE -> {
                if (node.path("add").isTextual()) {
                    if (!node.path("mode").isTextual()) {
                        throw new HookFailure("a prompt.pre addition needs a mode: volatile or durable");
                    }
                    try {
                        return new Add(node.get("add").asText(), Mode.of(node.get("mode").asText()));
                    } catch (IllegalArgumentException badMode) {
                        throw new HookFailure(badMode.getMessage());
                    }
                }
            }
            case LOG_OPEN -> {
                // No mode: a log's opening is sent in every request of the log, which is
                // what durable means (spec 2026-09-28-hooks-reach-the-log decision 9).
                if (node.path("add").isTextual()) {
                    return new Add(node.get("add").asText(), Mode.DURABLE);
                }
            }
            case PROMPT_POST, TOOL_POST -> {
                if (node.path("note").isTextual()) {
                    return new Note(node.get("note").asText());
                }
                if (node.path("redact").isTextual()) {
                    return new Redact(node.get("redact").asText());
                }
            }
            case TOOL_PRE -> {
                if (node.path("allow").isBoolean() && node.get("allow").asBoolean()) {
                    return new Allow();
                }
                if (node.path("deny").isTextual()) {
                    return new Deny(node.get("deny").asText());
                }
                if (node.path("rewrite").isObject()) {
                    return new Rewrite(node.get("rewrite").toString());
                }
                if (node.path("ask").isTextual() && !node.get("ask").asText().isBlank()) {
                    return new Ask(node.get("ask").asText());
                }
            }
            case STEP_POST, DELIVERY_PRE -> {
                if (node.path("note").isTextual()) {
                    return new Note(node.get("note").asText());
                }
            }
            case LOG_CLOSE, APPROVAL_POST, DELIVERY_POST -> {
                if (node.path("notify").isTextual()) {
                    return new Notify(node.get("notify").asText());
                }
            }
            case STAGE_PRE, STAGE_POST, APPROVAL_PRE -> {
                if (node.path("deny").isTextual()) {
                    return new Deny(node.get("deny").asText());
                }
                if (node.path("note").isTextual()) {
                    return new Note(node.get("note").asText());
                }
            }
            case FOLD_POST -> {
                if (node.path("keep").isTextual()) {
                    return new Keep(node.get("keep").asText());
                }
                if (node.path("notify").isTextual()) {
                    return new Notify(node.get("notify").asText());
                }
            }
        }
        throw wrong(stage, node);
    }

    private static HookFailure wrong(Stage stage, JsonNode node) {
        String allowed = switch (stage) {
            case PROMPT_PRE -> "{ add, mode }";
            case LOG_OPEN -> "{ add }";
            case PROMPT_POST, TOOL_POST -> "{ note } or { redact }";
            case TOOL_PRE -> "{ allow: true }, { deny }, { rewrite } or { ask }";
            case STEP_POST, DELIVERY_PRE -> "{ note }";
            case LOG_CLOSE, APPROVAL_POST, DELIVERY_POST -> "{ notify }";
            case STAGE_PRE, STAGE_POST, APPROVAL_PRE -> "{ deny } or { note }";
            case FOLD_POST -> "{ keep } or { notify }";
        };
        String shown = node.toString();
        return new HookFailure("a " + stage.wireName() + " hook returned "
                + (shown.length() > 200 ? shown.substring(0, 200) + "…" : shown)
                + ", and that stage allows nothing, " + allowed);
    }
}
