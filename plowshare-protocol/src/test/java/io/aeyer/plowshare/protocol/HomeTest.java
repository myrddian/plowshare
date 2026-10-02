package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which tier a memory lives in, and — the reason this file exists — <b>exactly
 * what a project name is</b>.
 *
 * <h2>Why the non-stripping is pinned here</h2>
 *
 * <p>{@code Home.of} keeps its argument character for character. That is not an
 * incidental property: {@code ProjectStore.named} refuses a project name with
 * whitespace at one end <em>because</em> of it, since a store that stripped
 * would key workspaces by {@code "payments"} while this class kept the memories
 * under {@code " payments "} — two leashes disagreeing about which project a job
 * is in.
 *
 * <p>That argument spans two modules, so before this file nothing held it still.
 * A tidy-up that made {@code of} strip would turn a paragraph of justification
 * in {@code ProjectStore}, a user-facing refusal message naming this class, and
 * a plan annotation silently false, and every server-side test would stay green.
 * These three assertions are what make that break loudly instead.
 */
class HomeTest {

    @Test
    void a_project_name_is_kept_exactly_as_given() {
        assertEquals(" payments ", Home.of(" payments ").project(),
                "Home does not strip; ProjectStore refuses padded names because of it");
        assertEquals("payments\n", Home.of("payments\n").project());
        assertEquals("payments api", Home.of("payments api").project(),
                "an internal space is an ordinary part of a name");
    }

    /**
     * The pair that makes the padded name a real tier rather than a curiosity:
     * it is accepted here, and it is a different project from the trimmed one.
     */
    @Test
    void a_padded_name_is_a_different_project_from_the_trimmed_one() {
        assertFalse(Home.of(" payments ").equals(Home.of("payments")),
                "two spellings, two tiers — which is the whole of why the stores must agree");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void a_blank_project_name_is_refused_rather_than_folded_into_global(String blank) {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> Home.of(blank));
        assertTrue(e.getMessage().contains("global"),
                "the refusal must point the caller at Home.global(), which is what they meant");
    }

    /** Global is the absence of a project, so no project can be named into it. */
    @Test
    void global_is_not_a_project_called_global() {
        assertTrue(Home.global().isGlobal());
        assertNull(Home.global().project());

        assertFalse(Home.of("global").isGlobal(),
                "a project that happens to be called global is an ordinary project");
        assertEquals("global", Home.of("global").project());
    }
}
