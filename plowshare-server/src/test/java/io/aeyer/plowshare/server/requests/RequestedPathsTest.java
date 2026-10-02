package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestedPaths}, standing in for the four private helpers {@code
 * ProjectController} used to hold.
 */
class RequestedPathsTest {

    @Test
    void reads_a_path() {
        assertEquals(Path.of("/tmp/pay"), RequestedPaths.one("/tmp/pay"));
    }

    /**
     * {@code Path.of(" bad")} does not raise {@link
     * java.nio.file.InvalidPathException} on this platform — measured on JDK 21
     * on macOS, where almost nothing is an invalid path — so the fixture that
     * trips the refusal is a NUL byte instead, the same one {@code
     * ProjectControllerTest.a_workspace_that_is_not_a_path_at_all_is_a_400}
     * already relies on.
     */
    @Test
    void refuses_text_that_is_not_a_path_and_says_nothing_was_written() {
        // Every refusal on this surface ends "Nothing was written." — a caller
        // that cannot tell a rejected write from a partial one retries blind.
        CallerFault refused =
                assertThrows(CallerFault.class, () -> RequestedPaths.one("a\0b"));
        assertTrue(refused.getMessage().contains("Nothing was written"), refused.getMessage());
    }

    @Test
    void treats_an_absent_list_as_empty_rather_than_refusing() {
        // `each` is the lenient one: an absent list is not a caller error, it is
        // an empty lend. `roots` is where the requirement lives.
        assertEquals(List.of(), RequestedPaths.each(null));
    }

    /**
     * Both spellings of "none", because {@code roots} is the strict counterpart
     * to {@code each} above and had no test of its own while it took a {@code
     * LendRequest} — the DTO stood between the refusal and anything that wanted
     * to assert on it.
     */
    @Test
    void refuses_an_absent_root_list_and_an_empty_one_alike() {
        CallerFault absent =
                assertThrows(CallerFault.class, () -> RequestedPaths.roots(null));
        assertTrue(absent.getMessage().contains("'roots' is required"), absent.getMessage());
        CallerFault empty =
                assertThrows(CallerFault.class, () -> RequestedPaths.roots(List.of()));
        assertTrue(empty.getMessage().contains("Nothing was written"), empty.getMessage());
    }

    @Test
    void reads_the_roots_a_lend_named() {
        assertEquals(List.of(Path.of("/tmp/a"), Path.of("/tmp/b")),
                RequestedPaths.roots(List.of("/tmp/a", "/tmp/b")));
    }

    /**
     * A bad entry inside an otherwise-good, non-empty list still refuses —
     * {@link #reads_the_roots_a_lend_named} above is happy-path only, and
     * {@code roots} delegating to {@link RequestedPaths#each} must not stop
     * checking after the list is confirmed non-empty. Same NUL-byte fixture
     * as {@link #refuses_text_that_is_not_a_path_and_says_nothing_was_written},
     * this time as the second of two entries rather than the only one.
     */
    @Test
    void refuses_a_bad_entry_inside_a_non_empty_list_too() {
        CallerFault refused = assertThrows(CallerFault.class,
                () -> RequestedPaths.roots(List.of("/tmp/a", "a\0b")));
        assertTrue(refused.getMessage().contains("cannot be read as a path"), refused.getMessage());
    }

    @Test
    void requires_a_workspace_and_names_what_it_is_for() {
        CallerFault refused =
                assertThrows(CallerFault.class, () -> RequestedPaths.workspace("   "));
        assertTrue(refused.getMessage().contains("workspace"), refused.getMessage());
    }

    @Test
    void reads_an_ordinary_destination_name() {
        assertEquals("payments-2", RequestedPaths.to("payments-2"));
    }

    @Test
    void refuses_a_null_destination_and_says_nothing_was_written() {
        CallerFault refused = assertThrows(CallerFault.class, () -> RequestedPaths.to(null));
        assertEquals("'to' is required: it is the name the project should have after the move."
                + " Nothing was written.", refused.getMessage());
    }

    @Test
    void refuses_a_blank_destination_the_same_way_as_a_missing_one() {
        CallerFault refused = assertThrows(CallerFault.class, () -> RequestedPaths.to("   "));
        assertEquals("'to' is required: it is the name the project should have after the move."
                + " Nothing was written.", refused.getMessage());
    }
}
