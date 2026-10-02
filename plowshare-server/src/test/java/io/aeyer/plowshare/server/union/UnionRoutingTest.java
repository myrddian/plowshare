package io.aeyer.plowshare.server.union;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.FileProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnionRoutingTest {

    private static final UnionStore.Union ENABLED = new UnionStore.Union(7L, "ledger", "laptop",
            "/Users/example/proj/ledger", Instant.parse("2026-09-14T09:00:00Z"), List.of(), List.of());

    @TempDir Path data;

    private UnionStore unions;
    private UnionGate gate;
    private UnionRouting routing;
    private final FileProvider remote = mock(FileProvider.class);

    @BeforeEach
    void setUp() throws Exception {
        unions = mock(UnionStore.class);
        Hubs hubs = new Hubs(new DataLayout(data).initialise(), name -> 7L);
        Hub hub = hubs.of("ledger").orElseThrow();
        hub.create();
        // Client-authored, not hub.commitTree(...): a server commit's email
        // always carries Hub.SERVER_EMAIL_DOMAIN, which lastClientCommitTime
        // (and so mirrorPlace's "last synced") skips. Seeding with one of those
        // would make every place string say "never" and hide the bug this
        // fixture is for -- see the_sync_time_does_not_move_when_an_agent_commits.
        hub.resetTree(pushClientCommit(hub, "a.txt", "x"));
        gate = new UnionGate(hubs, a -> {}, Instant::now, Duration.ofMillis(100), Duration.ofMinutes(2));
        routing = new UnionRouting(unions, hubs, gate);
        when(unions.find("ledger")).thenReturn(Optional.of(ENABLED));
        when(unions.open(7L)).thenReturn(List.of());
    }

    /** Commits one file in a throwaway clone and pushes it to the hub's main,
     *  authored the way a real client's push is -- {@code HubTest.pushFile}'s
     *  reasoning, copied here because a mirror's "last synced" time is exactly
     *  what distinguishes a client's commit from the server's own. */
    private String pushClientCommit(Hub hub, String path, String content) throws Exception {
        Path work = Files.createTempDirectory(data, "c");
        try (Git client = Git.cloneRepository().setURI(hub.bare().toUri().toString())
                .setDirectory(work.toFile()).call()) {
            Files.writeString(work.resolve(path), content);
            client.add().addFilepattern(".").call();
            String id = client.commit().setMessage("seed").setAuthor("enzo@laptop", "enzo@laptop")
                    .setCommitter("enzo@laptop", "enzo@laptop").call().getName();
            client.push().setRemote("origin").setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
            return id;
        }
    }

    @Test
    void a_project_that_is_not_a_union_is_left_to_the_existing_rules() {
        when(unions.find("other")).thenReturn(Optional.empty());
        assertTrue(routing.providers("other", List.of(), Optional.empty(), s -> remote).isEmpty());
    }

    @Test
    void with_no_client_the_mirror_serves() {
        List<FileProvider> providers =
                routing.providers("ledger", List.of(), Optional.empty(), s -> remote).orElseThrow();
        assertEquals(1, providers.size());
        assertEquals(MirrorProvider.NAME, providers.get(0).name());
    }

    @Test
    void a_client_that_has_not_reconciled_still_gets_the_mirror() {
        gate.begin("ledger", "s1");
        assertEquals(MirrorProvider.NAME, routing.providers("ledger", List.of(), Optional.of("s1"), s -> remote)
                .orElseThrow().get(0).name());
    }

    @Test
    void a_live_client_serves_its_own_files() {
        gate.begin("ledger", "s1");
        gate.ready("ledger", "s1", hubMain());
        assertEquals(List.of(remote),
                routing.providers("ledger", List.of(), Optional.of("s1"), s -> remote).orElseThrow());
    }

    @Test
    void the_mirror_is_described_with_its_machine_and_open_conflicts() {
        when(unions.open(7L)).thenReturn(List.of(new UnionStore.Conflict(1, "src/a.ts", null, "o", "t",
                "nightly-bot", "run_1", Instant.now())));
        String place = routing.mirrorPlace("ledger").orElseThrow();
        assertTrue(place.contains("server's copy"), place);
        assertTrue(place.contains("laptop"), place);
        assertTrue(routing.conflictNote("ledger").contains("src/a.ts"));
        assertTrue(routing.conflictNote("ledger").contains("nightly-bot"));
    }

    @Test
    void a_live_union_is_not_described_as_a_mirror() {
        gate.begin("ledger", "s1");
        gate.ready("ledger", "s1", hubMain());
        assertTrue(routing.mirrorPlace("ledger").isEmpty());
    }

    /**
     * The bug this is for: {@code mirrorPlace} used to read {@code
     * Hub.mainTime()}, the tip of {@code main} -- which after a run's own
     * commit is the agent's commit time, not the machine's. Because the time
     * is baked into the place string {@code ProjectWhereabouts} dedupes on,
     * that also meant the notice was re-said to the model after every writing
     * run. {@code lastClientCommitTime} skips the server's own commits, so an
     * agent commit must not move what this answers.
     */
    @Test
    void the_sync_time_does_not_move_when_an_agent_commits() throws Exception {
        String before = routing.mirrorPlace("ledger").orElseThrow();
        Hub hub = new Hubs(new DataLayout(data), name -> 7L).of("ledger").orElseThrow();
        Files.writeString(hub.tree().resolve("b.txt"), "y");
        hub.commitTree("nightly-bot", "run r2");

        assertEquals(before, routing.mirrorPlace("ledger").orElseThrow());
    }

    private String hubMain() {
        return new Hubs(new DataLayout(data), name -> 7L).of("ledger").orElseThrow().main().orElseThrow();
    }
}
