package io.aeyer.plowshare.client.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a client asserts about itself before it asserts anything about files.
 *
 * <h2>Only the overridable half can be pinned, and that is the point</h2>
 *
 * <p>The default is the hostname of whatever box the suite runs on, so no
 * assertion can name it. What <em>can</em> be measured is that the override
 * wins, that a client with no override still says something rather than nothing,
 * and that the default is not a name anybody would mistake for a real one — the
 * three properties an operator's setup actually depends on.
 */
class RootingTest {

    @TempDir
    Path tmp;

    @AfterEach
    void unset() {
        System.clearProperty(Rooting.MACHINE_PROPERTY);
    }

    @Test
    void a_machine_name_the_operator_set_wins() {
        System.setProperty(Rooting.MACHINE_PROPERTY, "bench.local");

        assertEquals("bench.local", Rooting.thisMachine());
    }

    @Test
    void a_blank_setting_is_not_a_setting() {
        System.setProperty(Rooting.MACHINE_PROPERTY, "   ");

        assertEquals(Rooting.thisMachine(), unsetMachine(),
                "an exported-but-empty value is somebody who meant to set one, and a machine"
                        + " called '' would compose a canonical name with a hole in it");
    }

    @Test
    void a_client_that_was_told_nothing_still_names_itself() {
        String named = unsetMachine();

        assertFalse(named.isBlank(), "a presence with a blank machine is refused by the server,"
                + " so a client that could not name itself would simply fail to root anything");
    }

    @Test
    void the_default_is_a_name_an_operator_would_want_to_change() {
        // Not an assertion about which branch answered — on a box with a working
        // hostname it is the hostname. What is pinned is the fallback's own
        // spelling, which is deliberately not plausible: a canonical name built
        // on a guess should look like one.
        assertTrue(Rooting.UNNAMED_MACHINE.contains("unnamed"),
                "a plausible-looking default is one nobody notices, and the collision mode is"
                        + " two machines sharing a name");
    }

    // --- the root is an identity, not a path a person typed ------------------

    @Test
    void a_root_is_resolved_so_that_two_spellings_of_one_place_are_one_identity()
            throws IOException {
        System.setProperty(Rooting.MACHINE_PROPERTY, "bench");
        Path project = Files.createDirectory(tmp.resolve("ledger"));
        Path sideways = tmp.resolve("ledger").resolve("..").resolve("ledger");

        assertEquals(Rooting.of(project, "ledger").root(), Rooting.of(sideways, "ledger").root(),
                "'../ledger' and the directory itself are one place, and a canonical name that"
                        + " told them apart would be an identity a move operation could never"
                        + " resolve");
        assertEquals(project.toRealPath().toString(), Rooting.of(project, "ledger").root(),
                "and it is the real path, which is what FileAccess compares against on both"
                        + " sides of this wire");
    }

    @Test
    void a_relative_root_is_made_absolute_even_when_it_is_not_there() {
        System.setProperty(Rooting.MACHINE_PROPERTY, "bench");

        String root = Rooting.of(Path.of("no-such-directory-here"), "ledger").root();

        assertTrue(Path.of(root).isAbsolute(),
                "a directory that cannot be read is not a reason to refuse to open a socket,"
                        + " and the server refuses a relative root outright — " + root);
    }

    @Test
    void a_rooting_carries_the_three_things_the_server_cannot_work_out_for_itself() {
        System.setProperty(Rooting.MACHINE_PROPERTY, "bench.local");

        Rooting rooted = Rooting.of(tmp, "ledger");

        assertEquals("bench.local", rooted.machine());
        assertEquals("ledger", rooted.project());
        assertFalse(rooted.root().isBlank());
    }

    /** The machine name with the override taken away, restored afterwards by
     *  {@link #unset()}. */
    private static String unsetMachine() {
        System.clearProperty(Rooting.MACHINE_PROPERTY);
        return Rooting.thisMachine();
    }
}
