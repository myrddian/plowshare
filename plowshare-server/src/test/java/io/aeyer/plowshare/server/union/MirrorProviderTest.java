package io.aeyer.plowshare.server.union;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MirrorProviderTest {

    private static final Path WORKSPACE = Path.of("/Users/example/proj/ledger");

    @TempDir Path data;
    @TempDir Path outside;

    private Hub hub;
    private UnionGate gate;
    private MirrorProvider mirror;

    @BeforeEach
    void setUp() throws Exception {
        Hubs hubs = new Hubs(new DataLayout(data).initialise(), name -> 7L);
        hub = hubs.of("ledger").orElseThrow();
        hub.create();
        Files.createDirectories(hub.tree().resolve("src"));
        Files.writeString(hub.tree().resolve("src/a.ts"), "export const a = 1\n");
        gate = new UnionGate(hubs, advanced -> {}, Instant::now,
                Duration.ofMillis(100), Duration.ofMinutes(2));
        mirror = mirror(List.of());
    }

    private MirrorProvider mirror(List<Path> exclusions) {
        return new MirrorProvider(
                "ledger", WORKSPACE, exclusions, hub.tree(), gate, List.of(readWrite()));
    }

    @Test
    void the_root_is_the_clients_path() {
        assertEquals(List.of(WORKSPACE), mirror.roots());
    }

    @Test
    void a_client_path_reads_the_servers_copy() {
        assertEquals(List.of("export const a = 1"),
                mirror.read(WORKSPACE.resolve("src/a.ts"), new Window(0, 10)).lines());
    }

    @Test
    void globs_and_grep_answer_in_client_paths() {
        // LocalProvider.glob matches a pattern relative to its roots and refuses
        // an absolute one outright (see its own "'/…' is an absolute pattern"
        // refusal), so the pattern crosses this seam unmapped, exactly as
        // LocalProviderTest exercises it ("**/*.java", never an absolute form).
        assertEquals(List.of(WORKSPACE.resolve("src/a.ts")), mirror.glob("**/*.ts"));
        Found found = mirror.grep(new Needle("const", false), null);
        assertEquals(WORKSPACE.resolve("src/a.ts").toString(), found.matches().get(0).path());
    }

    @Test
    void a_write_lands_in_the_tree() throws Exception {
        mirror.write(WORKSPACE.resolve("src/b.ts"), "b\n");
        assertEquals("b\n", Files.readString(hub.tree().resolve("src/b.ts")));
    }

    @Test
    void a_path_climbing_out_of_the_workspace_is_refused() {
        assertThrows(WorkspaceRefusedException.class,
                () -> mirror.read(WORKSPACE.resolve("../../../etc/passwd"), new Window(0, 1)));
    }

    @Test
    void a_symlink_out_of_the_tree_is_refused() throws Exception {
        Files.writeString(outside.resolve("secret"), "s\n");
        Files.createSymbolicLink(hub.tree().resolve("escape"), outside.resolve("secret"));
        assertThrows(WorkspaceRefusedException.class,
                () -> mirror.read(WORKSPACE.resolve("escape"), new Window(0, 1)));
    }

    @Test
    void a_write_through_a_directory_symlink_out_of_the_tree_is_refused() throws Exception {
        Files.createSymbolicLink(hub.tree().resolve("x"), outside);
        assertThrows(WorkspaceRefusedException.class,
                () -> mirror.write(WORKSPACE.resolve("x/y"), "escaped\n"));
        assertFalse(Files.exists(outside.resolve("y")), "nothing may be written outside the tree");
        try (var listed = Files.list(outside)) {
            assertEquals(0, listed.count());
        }
    }

    @Test
    void a_hidden_path_is_refused_even_if_it_is_in_the_tree() throws Exception {
        Files.writeString(hub.tree().resolve(".env"), "X=1\n");
        WorkspaceRefusedException refused = assertThrows(WorkspaceRefusedException.class,
                () -> mirror.read(WORKSPACE.resolve(".env"), new Window(0, 1)));
        assertFalse(refused.getMessage().contains(hub.tree().toString()),
                "a refusal names the client's path, never the server's directory");
    }

    @Test
    void an_excluded_path_cannot_be_read_or_written() throws Exception {
        MirrorProvider excluding = mirror(List.of(WORKSPACE.resolve("secrets")));
        Files.createDirectories(hub.tree().resolve("secrets"));
        Files.writeString(hub.tree().resolve("secrets/key.txt"), "shh\n");

        assertThrows(WorkspaceRefusedException.class,
                () -> excluding.read(WORKSPACE.resolve("secrets/key.txt"), new Window(0, 1)));
        assertThrows(WorkspaceRefusedException.class,
                () -> excluding.write(WORKSPACE.resolve("secrets/new.txt"), "x\n"));
        assertFalse(Files.exists(hub.tree().resolve("secrets/new.txt")),
                "a refused write must not land in the tree");
    }

    @Test
    void an_exclusion_outside_the_workspace_is_ignored() {
        MirrorProvider excluding = mirror(List.of(Path.of("/elsewhere")));
        assertEquals(List.of("export const a = 1"),
                excluding.read(WORKSPACE.resolve("src/a.ts"), new Window(0, 10)).lines());
    }

    private static Grant readWrite() {
        return Grant.parse("workspace:write");
    }
}
