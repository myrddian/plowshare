package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.union.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnionFramesTest {

    private static final Asking ENZO = new Asking("s1", "enzo");
    private static final UnionStore.Union LEDGER = new UnionStore.Union(
            7L, "ledger", "laptop", "/Users/example/proj/ledger", null, List.of(), List.of());

    @TempDir Path data;

    private UnionStore unions;
    private Inbox inbox;
    private Hubs hubs;
    private UnionGate gate;
    private FrameRouter router;
    private PresenceRegistry presences;

    @BeforeEach
    void setUp() {
        unions = mock(UnionStore.class);
        inbox = mock(Inbox.class);
        hubs = new Hubs(new DataLayout(data).initialise(), name -> 7L);
        gate = new UnionGate(hubs, advanced -> {}, Instant::now, Duration.ofMillis(100), Duration.ofMinutes(2));
        presences = new PresenceRegistry();
        presences.declare(new Presence("s1", "laptop", "/Users/example/proj/ledger", "ledger"));
        when(unions.find("ledger")).thenReturn(Optional.of(LEDGER));
        router = new FrameRoutingConfig().frameRouter(List.of(
                new UnionFrames(unions, hubs, gate, presences, inbox, 5_242_880L)));
    }

    private Outcome ask(String type, String payload, Asking asking) {
        return router.route(FrameParity.frame(type, payload), asking);
    }

    @Test
    void enabling_from_a_session_that_does_not_root_the_project_is_refused() {
        Outcome outcome = ask(FrameTypes.UNION_ENABLE, "{\"project\": \"ledger\"}", new Asking("s2", "enzo"));
        assertNotEquals(Code.OK, outcome.code());
    }

    @Test
    void enabling_creates_the_hub_and_starts_syncing() {
        Outcome outcome = ask(FrameTypes.UNION_ENABLE, "{\"project\": \"ledger\"}", ENZO);
        assertEquals(Code.OK, outcome.code());
        assertTrue(hubs.of("ledger").orElseThrow().exists());
        assertEquals(UnionGate.State.SYNCING, gate.state("ledger"));
    }

    @Test
    void ready_with_a_commit_the_hub_does_not_hold_is_a_conflict() {
        ask(FrameTypes.UNION_ENABLE, "{\"project\": \"ledger\"}", ENZO);
        Outcome outcome = ask(FrameTypes.UNION_READY,
                "{\"project\": \"ledger\", \"commit\": \"0000000000000000000000000000000000000000\"}", ENZO);
        assertEquals(Code.CONFLICT, outcome.code());
    }

    @Test
    void ready_on_the_hubs_main_goes_live_and_records_the_union() throws Exception {
        ask(FrameTypes.UNION_ENABLE, "{\"project\": \"ledger\"}", ENZO);
        Hub hub = hubs.of("ledger").orElseThrow();
        Files.writeString(hub.tree().resolve("a.txt"), "one\n");
        String main = hub.commitTree("enzo@laptop", "snapshot").orElseThrow();
        Outcome outcome = ask(FrameTypes.UNION_READY,
                "{\"project\": \"ledger\", \"commit\": \"" + main + "\"}", ENZO);
        assertEquals(Code.OK, outcome.code(), outcome.said());
        assertTrue(gate.live("ledger", "s1"));
        verify(unions).enable(eq("ledger"), any());
    }

    @Test
    void opening_a_conflict_records_it_and_tells_the_account() {
        when(unions.openConflict(anyLong(), any(), any())).thenReturn(3);
        Outcome outcome = ask(FrameTypes.UNION_CONFLICT_OPEN, """
                {"project": "ledger", "path": "src/a.ts", "oursBlob": "o", "theirsBlob": "t",
                 "theirsAuthor": "nightly-bot", "runId": "run_1"}""", ENZO);
        assertEquals(Code.OK, outcome.code());
        assertEquals(Map.of("n", 3), outcome.payload());
        verify(inbox).notify(eq("enzo"), eq(InboxStore.KIND_SYNC_CONFLICT), contains("src/a.ts"));
    }

    @Test
    void a_server_rooted_project_is_not_eligible() {
        when(unions.find("served")).thenReturn(Optional.empty());
        Outcome outcome = ask(FrameTypes.UNION_STATUS, "{\"project\": \"served\"}", ENZO);
        assertEquals(Code.OK, outcome.code());
        assertEquals(false, ((Map<?, ?>) outcome.payload()).get("eligible"));
    }

    @Test
    void resolving_a_conflict_that_is_not_open_is_not_found() {
        when(unions.resolve(eq(7L), eq(9), eq("mine"), any())).thenReturn(false);
        Outcome outcome = ask(FrameTypes.UNION_CONFLICT_RESOLVE,
                "{\"project\": \"ledger\", \"n\": 9, \"resolution\": \"mine\"}", ENZO);
        assertEquals(Code.NOT_FOUND, outcome.code());
    }

    @Test
    void disabling_from_a_session_that_does_not_root_the_project_is_refused() {
        ask(FrameTypes.UNION_ENABLE, "{\"project\": \"ledger\"}", ENZO);
        Outcome outcome = ask(FrameTypes.UNION_DISABLE, "{\"project\": \"ledger\"}", new Asking("s2", "enzo"));
        assertNotEquals(Code.OK, outcome.code());
        verify(unions, never()).disable(any());
        assertTrue(hubs.of("ledger").orElseThrow().exists());
    }

    @Test
    void changing_hidden_paths_from_another_session_is_refused() {
        Outcome outcome = ask(FrameTypes.UNION_HIDDEN,
                "{\"project\": \"ledger\", \"paths\": [\".github/\"]}", new Asking("s2", "enzo"));
        assertNotEquals(Code.OK, outcome.code());
        verify(unions, never()).setHidden(any(), any());
    }

    @Test
    void resolving_from_another_session_is_refused() {
        Outcome outcome = ask(FrameTypes.UNION_CONFLICT_RESOLVE,
                "{\"project\": \"ledger\", \"n\": 9, \"resolution\": \"mine\"}", new Asking("s2", "enzo"));
        assertNotEquals(Code.OK, outcome.code());
        verify(unions, never()).resolve(anyLong(), anyInt(), any(), any());
    }

    @Test
    void hidden_paths_that_name_git_or_plowshare_at_any_depth_are_refused() {
        for (String path : List.of("./.git", "sub/.git/", "a/.plowshare", "a//b", "../.env")) {
            Outcome outcome = ask(FrameTypes.UNION_HIDDEN,
                    "{\"project\": \"ledger\", \"paths\": [\"" + path + "\"]}", ENZO);
            assertNotEquals(Code.OK, outcome.code(), path);
        }
        verify(unions, never()).setHidden(any(), any());

        Outcome outcome = ask(FrameTypes.UNION_HIDDEN,
                "{\"project\": \"ledger\", \"paths\": [\".github/\", \"..foo\"]}", ENZO);
        assertEquals(Code.OK, outcome.code(), outcome.said());
        verify(unions).setHidden("ledger", List.of(".github/", "..foo"));
    }

    @Test
    void hidden_paths_that_name_git_with_folded_case_or_unicode_are_refused() {
        Outcome outcome = ask(FrameTypes.UNION_HIDDEN,
                "{\"project\": \"ledger\", \"paths\": [\".GIT/\"]}", ENZO);
        assertNotEquals(Code.OK, outcome.code(), outcome.said());
        verify(unions, never()).setHidden(any(), any());
    }

    @Test
    void aborting_from_a_session_that_does_not_root_the_project_is_refused() {
        ask(FrameTypes.UNION_ENABLE, "{\"project\": \"ledger\"}", ENZO);
        Outcome outcome = ask(FrameTypes.UNION_ABORT, "{\"project\": \"ledger\"}", new Asking("s2", "enzo"));
        assertNotEquals(Code.OK, outcome.code());
        assertEquals(UnionGate.State.SYNCING, gate.state("ledger"));
    }

    @Test
    void aborting_a_sync_releases_the_project() {
        ask(FrameTypes.UNION_ENABLE, "{\"project\": \"ledger\"}", ENZO);
        Outcome outcome = ask(FrameTypes.UNION_ABORT, "{\"project\": \"ledger\"}", ENZO);
        assertEquals(Code.OK, outcome.code(), outcome.said());
        assertEquals(UnionGate.State.OFFLINE, gate.state("ledger"));
    }

    @Test
    void disabling_before_this_machine_is_live_is_a_conflict_and_keeps_the_hub() {
        ask(FrameTypes.UNION_ENABLE, "{\"project\": \"ledger\"}", ENZO);
        Outcome outcome = ask(FrameTypes.UNION_DISABLE, "{\"project\": \"ledger\"}", ENZO);
        assertEquals(Code.CONFLICT, outcome.code());
        assertTrue(outcome.said().contains("sync first"), outcome.said());
        verify(unions, never()).disable(any());
        assertTrue(hubs.of("ledger").orElseThrow().exists());
    }

    @Test
    void disabling_a_live_union_deletes_the_hub_and_the_union() throws Exception {
        ask(FrameTypes.UNION_ENABLE, "{\"project\": \"ledger\"}", ENZO);
        Hub hub = hubs.of("ledger").orElseThrow();
        Files.writeString(hub.tree().resolve("a.txt"), "one\n");
        String main = hub.commitTree("enzo@laptop", "snapshot").orElseThrow();
        assertEquals(Code.OK, ask(FrameTypes.UNION_READY,
                "{\"project\": \"ledger\", \"commit\": \"" + main + "\"}", ENZO).code());

        Outcome outcome = ask(FrameTypes.UNION_DISABLE, "{\"project\": \"ledger\"}", ENZO);

        assertEquals(Code.OK, outcome.code(), outcome.said());
        assertFalse(hub.exists());
        verify(unions).disable("ledger");
        assertEquals(UnionGate.State.OFFLINE, gate.state("ledger"));
    }

    @Test
    void enabling_a_union_whose_workspace_is_not_posix_is_refused() {
        presences.declare(new Presence("s3", "desktop", "C:\\Users\\enzo\\books", "books"));
        when(unions.find("books")).thenReturn(Optional.of(new UnionStore.Union(
                8L, "books", "desktop", "C:\\Users\\enzo\\books", null, List.of(), List.of())));
        Outcome outcome = ask(FrameTypes.UNION_ENABLE, "{\"project\": \"books\"}", new Asking("s3", "enzo"));
        assertNotEquals(Code.OK, outcome.code());
        assertTrue(outcome.said().contains("POSIX workspace"), outcome.said());
        assertFalse(hubs.of("books").orElseThrow().exists());
    }

    @Test
    void the_hub_url_percent_encodes_each_segment_of_the_project_name() {
        when(unions.find("my proj/ü~x")).thenReturn(Optional.of(new UnionStore.Union(
                9L, "my proj/ü~x", "laptop", "/p", null, List.of(), List.of())));
        Outcome outcome = ask(FrameTypes.UNION_STATUS, "{\"project\": \"my proj/ü~x\"}", ENZO);
        assertEquals("/v1/sync/my%20proj/%C3%BC~x.git", ((Map<?, ?>) outcome.payload()).get("url"));
    }

    @Test
    void setting_hidden_paths_says_the_list_was_replaced() {
        Outcome outcome = ask(FrameTypes.UNION_HIDDEN,
                "{\"project\": \"ledger\", \"paths\": [\".github/\"]}", ENZO);
        assertEquals(Code.OK, outcome.code(), outcome.said());
        assertEquals(true, ((Map<?, ?>) outcome.payload()).get("replaced"));
    }
}
