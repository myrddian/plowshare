package io.aeyer.plowshare.server.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import org.junit.jupiter.api.Test;

class HookRecordTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void the_record_is_json_with_the_absent_fields_left_out() throws Exception {
        HookRecord denied = new HookRecord("no-secrets-in-writes", "10-secrets.ts", Tier.PROJECT,
                Stage.TOOL_PRE, "file_write", HookRecord.DENY, "a private key", null, null, 3);

        JsonNode read = JSON.readTree(denied.json());

        assertEquals("no-secrets-in-writes", read.get("hook").asText());
        assertEquals("project", read.get("tier").asText());
        assertEquals("tool.pre", read.get("stage").asText());
        assertEquals("file_write", read.get("tool").asText());
        assertEquals("deny", read.get("decision").asText());
        assertEquals(3, read.get("tookMs").asLong());
        assertFalse(read.has("added"), "an absent field is left out, not written as null");
    }

    @Test
    void added_and_original_text_is_capped_with_a_marker() throws Exception {
        String long_ = "x".repeat(HookRecord.MAX_TEXT + 500);
        HookRecord redacted = new HookRecord("scrub", "scrub.ts", Tier.PROJECT, Stage.TOOL_POST,
                "file_read", HookRecord.REDACT, null, null, long_, 1);

        String original = JSON.readTree(redacted.json()).get("original").asText();

        assertTrue(original.length() < long_.length());
        assertTrue(original.endsWith(HookRecord.TRUNCATED), original.substring(original.length() - 40));
    }

    @Test
    void the_names_are_the_wire_names_the_typescript_contract_uses() {
        assertEquals(Stage.PROMPT_POST, Stage.of("prompt.post"));
        assertEquals(Mode.DURABLE, Mode.of("durable"));
        assertThrows(IllegalArgumentException.class, () -> Stage.of("prompt.during"));
    }

    @Test
    void a_hook_entry_is_recorded_and_never_shown_to_a_model() {
        LoggedEntry entry = LoggedEntry.hook(new HookRecord("harness:recall", null, Tier.HARNESS,
                Stage.PROMPT_PRE, null, HookRecord.ADD, null, "- mem_1 — …", null, 0));

        assertEquals(EntryKind.HOOK, entry.kind());
        assertFalse(EntryKind.HOOK.projects());
        assertEquals("hook", EntryKind.HOOK.wireName());
    }
}
