package io.aeyer.plowshare.server.hooks.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.Mode;
import io.aeyer.plowshare.server.hooks.Stage;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionTest {

    @Test
    void nothing_is_nothing_on_every_stage() throws Exception {
        for (Stage stage : Stage.values()) {
            assertEquals(new Decision.Nothing(), Decision.read(stage, "null"));
        }
    }

    @Test
    void each_stage_reads_the_decisions_it_allows() throws Exception {
        assertEquals(new Decision.Add("rule", Mode.DURABLE),
                Decision.read(Stage.PROMPT_PRE, "{\"add\":\"rule\",\"mode\":\"durable\"}"));
        assertEquals(new Decision.Note("seen"), Decision.read(Stage.PROMPT_POST, "{\"note\":\"seen\"}"));
        assertEquals(new Decision.Redact("x"), Decision.read(Stage.TOOL_POST, "{\"redact\":\"x\"}"));
        assertEquals(new Decision.Allow(), Decision.read(Stage.TOOL_PRE, "{\"allow\":true}"));
        assertEquals(new Decision.Deny("no"), Decision.read(Stage.TOOL_PRE, "{\"deny\":\"no\"}"));
        assertEquals(new Decision.Rewrite("{\"path\":\"b\"}"),
                Decision.read(Stage.TOOL_PRE, "{\"rewrite\":{\"path\":\"b\"}}"));
        assertEquals(new Decision.Ask("publishes"), Decision.read(Stage.TOOL_PRE, "{\"ask\":\"publishes\"}"));
    }

    @Test
    void an_ask_with_no_reason_is_a_failure_and_not_a_silent_allow() {
        assertThrows(HookFailure.class, () -> Decision.read(Stage.TOOL_PRE, "{\"ask\":\"\"}"));
        assertThrows(HookFailure.class, () -> Decision.read(Stage.PROMPT_POST, "{\"ask\":\"why\"}"));
    }

    @Test
    void a_decision_the_stage_does_not_allow_is_a_failure_that_says_what_would_have_been() {
        HookFailure wrong = assertThrows(HookFailure.class,
                () -> Decision.read(Stage.PROMPT_PRE, "{\"deny\":\"no\"}"));
        assertTrue(wrong.getMessage().contains("prompt.pre"), wrong.getMessage());
        assertThrows(HookFailure.class,
                () -> Decision.read(Stage.PROMPT_PRE, "{\"add\":\"rule\"}"), "an addition needs a mode");
        assertThrows(HookFailure.class,
                () -> Decision.read(Stage.TOOL_PRE, "{\"rewrite\":\"not an object\"}"));
        assertThrows(HookFailure.class, () -> Decision.read(Stage.TOOL_PRE, "{\"allow\":false}"));
    }

    @Test
    void an_object_naming_more_than_one_decision_is_refused_rather_than_taking_the_first() {
        // {deny, allow: true}: Java field order (or map iteration order) must not
        // decide the outcome of an ambiguous return value — first-match-wins would
        // read this as Allow and silently drop the denial.
        assertThrows(HookFailure.class,
                () -> Decision.read(Stage.TOOL_PRE, "{\"deny\":\"no\",\"allow\":true}"));
        assertThrows(HookFailure.class,
                () -> Decision.read(Stage.PROMPT_POST, "{\"redact\":\"x\",\"note\":\"y\"}"));
    }

    @Test
    void an_object_with_an_unknown_key_is_refused() {
        assertThrows(HookFailure.class,
                () -> Decision.read(Stage.TOOL_PRE, "{\"allow\":true,\"extra\":1}"));
    }

    @Test
    void step_post_reads_a_note_and_refuses_a_redaction() throws Exception {
        assertEquals(new Decision.Note("x"), Decision.read(Stage.STEP_POST, "{\"note\":\"x\"}"));
        assertThrows(HookFailure.class,
                () -> Decision.read(Stage.STEP_POST, "{\"redact\":\"x\"}"),
                "step.post's decision keys are note only, unlike prompt.post and tool.post");
    }

    @Test
    void each_log_stage_reads_the_decisions_it_allows() throws Exception {
        assertEquals(new Decision.Add("house rules", Mode.DURABLE),
                Decision.read(Stage.LOG_OPEN, "{\"add\":\"house rules\"}"));
        assertEquals(new Decision.Notify("done"),
                Decision.read(Stage.LOG_CLOSE, "{\"notify\":\"done\"}"));
        assertEquals(new Decision.Note("checked"),
                Decision.read(Stage.DELIVERY_PRE, "{\"note\":\"checked\"}"));
        assertEquals(new Decision.Notify("sent"),
                Decision.read(Stage.DELIVERY_POST, "{\"notify\":\"sent\"}"));
        assertEquals(new Decision.Deny("no"), Decision.read(Stage.STAGE_PRE, "{\"deny\":\"no\"}"));
        assertEquals(new Decision.Note("n"), Decision.read(Stage.STAGE_POST, "{\"note\":\"n\"}"));
        assertEquals(new Decision.Note("risky"),
                Decision.read(Stage.APPROVAL_PRE, "{\"note\":\"risky\"}"));
        assertEquals(new Decision.Notify("answered"),
                Decision.read(Stage.APPROVAL_POST, "{\"notify\":\"answered\"}"));
        assertEquals(new Decision.Keep("skill X@1 was loaded"),
                Decision.read(Stage.FOLD_POST, "{\"keep\":\"skill X@1 was loaded\"}"));
        assertEquals(new Decision.Notify("folded"),
                Decision.read(Stage.FOLD_POST, "{\"notify\":\"folded\"}"));
    }

    @Test
    void every_stage_refuses_every_key_it_does_not_allow() {
        for (Stage stage : Stage.values()) {
            for (String key : List.of("add", "mode", "note", "redact", "allow", "deny", "rewrite",
                    "ask", "keep", "notify")) {
                if (Decision.keys(stage).contains(key)) {
                    continue;
                }
                assertThrows(HookFailure.class,
                        () -> Decision.read(stage, "{\"" + key + "\":\"x\"}"),
                        stage.wireName() + " must refuse " + key);
            }
        }
    }

    /** Spec decision 5: no stage offers allow on an approval. */
    @Test
    void no_stage_answers_an_approval() {
        assertThrows(HookFailure.class, () -> Decision.read(Stage.APPROVAL_PRE, "{\"allow\":true}"));
        assertThrows(HookFailure.class, () -> Decision.read(Stage.APPROVAL_POST, "{\"allow\":true}"));
    }

    /**
     * Spec 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29: fold.post keeps or notifies,
     * one at a time, as every stage with several decisions reads them.
     */
    @Test
    void fold_post_keeps_or_notifies_but_not_both_at_once() {
        HookFailure both = assertThrows(HookFailure.class,
                () -> Decision.read(Stage.FOLD_POST, "{\"keep\":\"m\",\"notify\":\"n\"}"));
        assertTrue(both.getMessage().contains("{ keep } or { notify }"), both.getMessage());
        assertThrows(HookFailure.class, () -> Decision.read(Stage.FOLD_POST, "{\"keep\":3}"));
    }

    @Test
    void log_open_takes_no_mode_because_its_opening_is_sent_every_turn() {
        HookFailure wrong = assertThrows(HookFailure.class,
                () -> Decision.read(Stage.LOG_OPEN, "{\"add\":\"x\",\"mode\":\"volatile\"}"));
        assertTrue(wrong.getMessage().contains("log.open"), wrong.getMessage());
        assertTrue(wrong.getMessage().contains("{ add }"), wrong.getMessage());
    }
}
