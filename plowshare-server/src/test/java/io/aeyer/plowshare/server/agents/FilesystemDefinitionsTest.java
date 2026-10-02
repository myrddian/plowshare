package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemDefinitionsTest {

    private static void write(Path dir, String name, String text) throws Exception {
        Files.writeString(dir.resolve(name + ".md"), text);
    }

    @Test
    void a_directory_lists_its_definitions_by_stem(@TempDir Path dir) throws Exception {
        write(dir, "librarian", "---\nname: librarian\n---\nbody\n");
        write(dir, "scribe", "---\nname: scribe\n---\nbody\n");

        List<DefinitionSource.Definition> found = new FilesystemDefinitions(dir).list();

        assertEquals(List.of("librarian", "scribe"),
                found.stream().map(DefinitionSource.Definition::name).toList());
    }

    @Test
    void an_absent_directory_lists_nothing_rather_than_throwing(@TempDir Path parent) {
        assertEquals(List.of(), new FilesystemDefinitions(parent.resolve("nope")).list());
    }

    @Test
    void origin_is_the_absolute_path_because_that_is_what_an_operator_opens(
            @TempDir Path dir) throws Exception {
        write(dir, "librarian", "---\nname: librarian\n---\nbody\n");

        DefinitionSource.Definition one = new FilesystemDefinitions(dir).list().get(0);

        assertTrue(one.origin().contains(dir.toAbsolutePath().toString()),
                "origin was " + one.origin());
        assertTrue(one.origin().endsWith("librarian.md"), "origin was " + one.origin());
    }

    @Test
    void non_markdown_files_are_not_definitions(@TempDir Path dir) throws Exception {
        write(dir, "librarian", "---\nname: librarian\n---\nbody\n");
        Files.writeString(dir.resolve("README.txt"), "not a definition");

        assertEquals(1, new FilesystemDefinitions(dir).list().size());
    }

    /** {@code AgentRegistry.read}'s parse-failure path uses {@code
     *  Definition.name} verbatim as the identity it tests against {@code
     *  required}, so a name this class hands back un-normalised would let a
     *  required agent on an NFD-answering filesystem disable silently instead
     *  of taking the boot down. Written NFD and read back NFD is what a real
     *  filesystem that normalises differently would do -- this pins that the
     *  name comes back NFC regardless. */
    @Test
    void a_name_from_an_nfd_filename_comes_back_nfc(@TempDir Path dir) throws Exception {
        String nfc = "caf\u00e9";        // e-acute as a single code point
        String nfd = "cafe\u0301";       // e followed by a combining acute
        write(dir, nfd, "---\nname: " + nfc + "\n---\nbody\n");

        DefinitionSource.Definition one = new FilesystemDefinitions(dir).list().get(0);

        assertEquals(nfc, one.name());
    }
}
