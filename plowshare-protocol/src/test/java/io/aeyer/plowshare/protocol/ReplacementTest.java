package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** The shared case table, which the TUI's copy of this rule runs too. */
class ReplacementTest {

    @TestFactory
    List<DynamicTest> every_case_in_the_shared_table() throws Exception {
        JsonNode cases;
        try (InputStream in = ReplacementTest.class.getResourceAsStream("replacements.json")) {
            cases = new ObjectMapper().readTree(in);
        }
        assertTrue(cases.size() > 0);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                String text = c.get("text").asText();
                String old = c.get("old").asText();
                String replacement = c.get("new").asText();
                if (c.has("result")) {
                    assertEquals(c.get("result").asText(), Replacement.apply(text, old, replacement));
                } else {
                    Replacement.Refused refused = assertThrows(Replacement.Refused.class,
                            () -> Replacement.apply(text, old, replacement));
                    assertEquals(c.get("refused").asText(),
                            refused.kind().name().toLowerCase(Locale.ROOT));
                    assertEquals(c.get("count").asInt(), refused.count());
                }
            }));
        }
        return tests;
    }

    @Test
    void every_refusal_is_a_result_naming_the_file_and_never_a_sentence() {
        Replacement.Refused ambiguous = assertThrows(Replacement.Refused.class,
                () -> Replacement.apply("a a a", "a", "b"));
        assertEquals(FileResult.manyMatches("/repo/A.java", 3), ambiguous.result("/repo/A.java"));
        Replacement.Refused absent = assertThrows(Replacement.Refused.class,
                () -> Replacement.apply("a", "z", "b"));
        assertEquals(FileResult.NO_MATCH, absent.result("/repo/A.java").kind());
        assertEquals("/repo/A.java", absent.result("/repo/A.java").path());
        Replacement.Refused empty = assertThrows(Replacement.Refused.class,
                () -> Replacement.apply("a", "", "b"));
        assertEquals(FileResult.refused(FileRequest.EDIT, FileResult.EMPTY_OLD, "/repo/A.java"),
                empty.result("/repo/A.java"));
        assertFalse(empty.result("/repo/A.java").made());
    }

    @Test
    void decoding_is_strict_and_round_trips() {
        byte[] bytes = "﻿line\r\nnext 🎵".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(bytes, Replacement.decode(bytes).getBytes(StandardCharsets.UTF_8));
        assertNull(Replacement.decode(new byte[] {(byte) 0xC3, (byte) 0x28}));
    }
}
