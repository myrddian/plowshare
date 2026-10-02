package io.aeyer.plowshare.server.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec 2026-09-30-local-hooks-are-served §3: a set is named by a hash over its canonical form. */
class HookFileTest {

    private static final HookFile GUARD = new HookFile("10-guard.ts", "export default {}\n// ü");
    private static final HookFile NOTES = new HookFile("20-notes.js", "export default {}");

    @Test
    void the_canonical_form_is_name_and_text_in_file_name_order() {
        assertEquals("[{\"name\":\"10-guard.ts\",\"text\":\"export default {}\\n// ü\"},"
                        + "{\"name\":\"20-notes.js\",\"text\":\"export default {}\"}]",
                HookFile.canonical(List.of(NOTES, GUARD)));
    }

    @Test
    void the_hash_is_a_sha256_of_the_canonical_form_whatever_order_the_files_came_in() {
        String hash = HookFile.hashOf(List.of(NOTES, GUARD));

        assertTrue(hash.matches("sha256:[0-9a-f]{64}"), hash);
        assertEquals(hash, HookFile.hashOf(List.of(GUARD, NOTES)));
        assertNotEquals(hash, HookFile.hashOf(List.of(GUARD)));
    }

    @Test
    void only_ts_and_js_names_that_are_not_hidden_are_hooks() {
        assertTrue(HookFile.isHookName("10-guard.ts"));
        assertTrue(HookFile.isHookName("x.js"));
        assertFalse(HookFile.isHookName(".draft.ts"));
        assertFalse(HookFile.isHookName("README.md"));
        assertFalse(HookFile.isHookName("x.TS"));
    }

    @Test
    void a_file_has_a_name_and_a_text() {
        assertThrows(NullPointerException.class, () -> new HookFile(null, ""));
        assertThrows(NullPointerException.class, () -> new HookFile("x.ts", null));
    }
}
