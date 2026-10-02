package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The render gate, and the sentinels it exists to stop.
 *
 * <p>Anchor's {@code StructuralRef} and {@code SyntheticTitles}, ported as a
 * pair. The contract V5 states for them is that a parser-invented unit's stored
 * title "must never reach the LLM, the API, or the UI" — and that <b>what to
 * render instead is per-call-site policy</b>. Anchor's own tree has three such
 * policies re-implemented inline at five different sites; here they are the
 * argument.
 */
class StructuralRefTest {

    @Test
    void a_title_the_document_wrote_is_rendered_under_every_policy() {
        StructuralRef ref = StructuralRef.of("Results and Discussion", false);

        assertInstanceOf(StructuralRef.Named.class, ref);
        for (StructuralRef.WhenSynthetic policy : StructuralRef.WhenSynthetic.values()) {
            assertEquals("Results and Discussion", ref.render(policy));
        }
    }

    /**
     * <b>The whole point.</b> The stored title of a synthetic unit is a sentinel
     * and no policy renders it.
     */
    @Test
    void the_sentinel_is_never_what_comes_out() {
        StructuralRef chapter = StructuralRef.of(SyntheticTitles.CHAPTER, true);
        StructuralRef section = StructuralRef.of(SyntheticTitles.SECTION, true);

        for (StructuralRef.WhenSynthetic policy : StructuralRef.WhenSynthetic.values()) {
            assertNotSentinel(chapter.render(policy));
            assertNotSentinel(section.render(policy));
        }
    }

    /** The three degradations Anchor has, in the three places it has them. */
    @Test
    void a_parser_invented_unit_degrades_the_way_the_call_site_asked_for() {
        StructuralRef ref = StructuralRef.of(SyntheticTitles.SECTION, true);

        assertNull(ref.render(StructuralRef.WhenSynthetic.OMIT));
        assertEquals("", ref.render(StructuralRef.WhenSynthetic.BLANK));
        assertEquals("(unnamed segment)", ref.render(StructuralRef.WhenSynthetic.PLACEHOLDER));
    }

    /**
     * The flag decides and the string does not. A document that really is
     * headed {@code __SYNTHETIC_HEAP__} is a document, not a parser fallback —
     * and the far more likely case is a writer that set the flag and forgot the
     * sentinel, which must still be gated.
     */
    @Test
    void the_flag_and_not_the_string_is_what_gates() {
        assertInstanceOf(StructuralRef.Synthetic.class, StructuralRef.of("Method", true));
        assertInstanceOf(
                StructuralRef.Named.class, StructuralRef.of(SyntheticTitles.SECTION, false));
    }

    /** A named unit with no name is not a state the schema allows either —
     *  {@code chapters_a_named_chapter_has_a_title}. */
    @Test
    void a_named_unit_with_no_title_is_refused_rather_than_rendered_empty() {
        assertThrows(IllegalArgumentException.class, () -> StructuralRef.of(null, false));
        assertThrows(IllegalArgumentException.class, () -> StructuralRef.of("  ", false));
    }

    /**
     * <b>The grep Anchor's javadoc names, run as a test.</b> "Grep for {@code
     * .title()} outside this file + the persistence mapper finds any boundary
     * that skipped the helper" — a rule that only holds while somebody keeps
     * running it. The sentinels are a wide hazard on a wide surface: agent
     * bodies, tool descriptions and {@code Outcome.text} are all read by a
     * model, and nothing enumerates them. What can be enumerated is where the
     * strings themselves are allowed to appear.
     */
    @Test
    void the_sentinel_strings_are_spelled_out_in_one_file_only() throws IOException {
        List<String> holders = new ArrayList<>();
        Path module = Path.of("src");
        try (Stream<Path> sources = Files.walk(module)) {
            for (Path path : sources.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".java") && !name.endsWith(".sql")) {
                    continue;
                }
                if (Files.readString(path).contains("__SYNTHETIC_")) {
                    holders.add(module.relativize(path).toString());
                }
            }
        }
        holders.sort(String::compareTo);
        assertEquals(
                List.of("main/java/io/aeyer/plowshare/server/documents/SyntheticTitles.java",
                        "test/java/io/aeyer/plowshare/server/documents/StructuralRefTest.java"),
                holders,
                "these files spell a synthetic sentinel out. It belongs in SyntheticTitles and"
                        + " nowhere else in main: everything that turns a stored unit into text"
                        + " goes through StructuralRef, which is handed the constant by the"
                        + " persistence mapper and discards it everywhere else. A second copy is"
                        + " a render site that will not move when the sentinel does.");
    }

    private static void assertNotSentinel(String rendered) {
        if (rendered != null && (rendered.contains("SYNTHETIC") || rendered.contains("__"))) {
            throw new AssertionError("a sentinel reached a render site: " + rendered);
        }
    }
}
