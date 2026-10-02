package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.union.UnionRouting;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Where a project's files can be reached, said to the model once each time
 * that changes.
 *
 * <p><b>The bug this exists for</b> is cnv_313AE867D7AEA6EE: a project rooted
 * with `/here`, file tools offered on every turn, and a model that answered "give
 * me a legitimate path inside a defined workspace" without calling one. Nothing
 * had told it the project had files. A tool's answer only exists once the tool is
 * called, so the harness says it first.
 */
class ProjectWhereaboutsTest {

    private static final Home STORY = Home.of("story");

    private final SessionRegistry sessions = new SessionRegistry();
    private final PresenceRegistry presences = new PresenceRegistry();
    private final ProjectStore projects = mock(ProjectStore.class);
    private final ProjectWhereabouts whereabouts =
            new ProjectWhereabouts(projects, sessions, presences, UnionRouting.NONE);

    private final AgentDefinition withFiles = definition(List.of(FileTools.ROOTS_NAME,
            FileTools.GLOB_NAME, FileTools.READ_NAME));

    @Test
    void a_project_rooted_by_a_connected_machine_is_announced_with_that_machine_and_root() {
        rootedBy("laptop", "MacBook-Pro.local", "/Users/example/proj/story");

        String said = whereabouts.noticeFor(withFiles, STORY, "cnv_a").orElseThrow();

        assertTrue(said.contains("story"), said);
        assertTrue(said.contains("MacBook-Pro.local"), said);
        assertTrue(said.contains("/Users/example/proj/story"), said);
        assertTrue(said.contains(FileTools.ROOTS_NAME), "and it says how to look: " + said);
    }

    @Test
    void the_next_turn_with_nothing_changed_is_told_nothing() {
        rootedBy("laptop", "MacBook-Pro.local", "/Users/example/proj/story");
        whereabouts.noticeFor(withFiles, STORY, "cnv_a");

        assertEquals(Optional.empty(), whereabouts.noticeFor(withFiles, STORY, "cnv_a"),
                "it is logged once, so saying it again would only repeat the history");
    }

    @Test
    void rooting_somewhere_else_is_announced_again() {
        rootedBy("laptop", "MacBook-Pro.local", "/Users/example/proj/story");
        whereabouts.noticeFor(withFiles, STORY, "cnv_a");
        presences.withdraw("laptop");
        rootedBy("laptop", "MacBook-Pro.local", "/Users/example/elsewhere/story");

        String said = whereabouts.noticeFor(withFiles, STORY, "cnv_a").orElseThrow();

        assertTrue(said.contains("/Users/example/elsewhere/story"), said);
    }

    @Test
    void each_conversation_is_told_for_itself() {
        rootedBy("laptop", "MacBook-Pro.local", "/Users/example/proj/story");
        whereabouts.noticeFor(withFiles, STORY, "cnv_a");

        assertTrue(whereabouts.noticeFor(withFiles, STORY, "cnv_b").isPresent(),
                "what one conversation was told is not in another's history");
    }

    @Test
    void a_project_on_this_servers_disk_is_announced_as_this_server() {
        when(projects.find("story")).thenReturn(Optional.of(
                new ProjectRecord("story", Path.of("/srv/story"), List.of(), List.of())));

        String said = whereabouts.noticeFor(withFiles, STORY, "cnv_a").orElseThrow();

        assertTrue(said.contains("this server"), said);
        assertTrue(said.contains("/srv/story"), said);
    }

    @Test
    void a_machine_that_disconnects_is_announced_as_unreachable_and_again_when_it_returns() {
        when(projects.rootedElsewhere("story")).thenReturn(Optional.of("bench.local"));
        rootedBy("bench", "bench.local", "/srv/story");
        whereabouts.noticeFor(withFiles, STORY, "cnv_a");

        presences.withdraw("bench");
        String gone = whereabouts.noticeFor(withFiles, STORY, "cnv_a").orElseThrow();
        assertTrue(gone.contains("bench.local"), gone);
        assertTrue(gone.contains("not connected"), gone);

        presences.declare(new Presence("bench", "bench.local", "/srv/story", "story"));
        assertTrue(whereabouts.noticeFor(withFiles, STORY, "cnv_a").orElseThrow()
                .contains("/srv/story"), "and reachable again is a change too");
    }

    @Test
    void a_presence_whose_session_holds_no_file_channel_is_not_a_reachable_machine() {
        sessions.attach("bench", Role.LISTENER, new Object());
        presences.declare(new Presence("bench", "bench.local", "/srv/story", "story"));
        when(projects.rootedElsewhere("story")).thenReturn(Optional.of("bench.local"));

        String said = whereabouts.noticeFor(withFiles, STORY, "cnv_a").orElseThrow();

        assertTrue(said.contains("not connected"),
                "the same gate runProviders uses, so the note never promises files the"
                        + " tools cannot reach: " + said);
    }

    @Test
    void the_global_tier_is_told_nothing() {
        assertEquals(Optional.empty(), whereabouts.noticeFor(withFiles, Home.global(), "cnv_a"));
    }

    @Test
    void a_run_with_no_file_tools_is_told_nothing() {
        rootedBy("laptop", "MacBook-Pro.local", "/Users/example/proj/story");

        assertEquals(Optional.empty(), whereabouts.noticeFor(
                definition(List.of("memory_recall")), STORY, "cnv_a"));
        assertTrue(whereabouts.noticeFor(withFiles, STORY, "cnv_a").isPresent(),
                "and nothing was marked as said, so a run that can look is still told");
    }

    @Test
    void a_run_with_no_conversation_is_told_nothing() {
        rootedBy("laptop", "MacBook-Pro.local", "/Users/example/proj/story");

        assertEquals(Optional.empty(), whereabouts.noticeFor(withFiles, STORY, null),
                "a notice is logged to a conversation, and there is none to log it to");
    }

    @Test
    void a_union_served_by_its_mirror_is_described_as_the_servers_copy() {
        UnionRouting unions = org.mockito.Mockito.mock(UnionRouting.class);
        org.mockito.Mockito.when(unions.mirrorPlace("ledger"))
                .thenReturn(java.util.Optional.of("the server's copy of the files on the machine 'laptop'"));
        org.mockito.Mockito.when(unions.conflictNote("ledger")).thenReturn("");
        ProjectWhereabouts mirrored = new ProjectWhereabouts(projects, sessions, presences, unions);

        String notice = mirrored.noticeFor(withFiles, Home.of("ledger"), "cnv_1").orElseThrow();

        assertTrue(notice.contains("server's copy"), notice);
    }

    private void rootedBy(String session, String machine, String root) {
        sessions.attach(session, Role.FILE_PROVIDER, new Object());
        presences.declare(new Presence(session, machine, root, "story"));
    }

    private static AgentDefinition definition(List<String> tools) {
        return new AgentDefinition("aristoxenus", "a bot", "m", tools, List.of(), List.of(),
                5, 5, "you are a bot");
    }
}
