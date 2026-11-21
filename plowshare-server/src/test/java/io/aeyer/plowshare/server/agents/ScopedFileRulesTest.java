package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.FileProvider.PersonalWorkspace;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.union.Hubs;
import io.aeyer.plowshare.server.union.MirrorProvider;
import io.aeyer.plowshare.server.union.UnionGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedFileRulesTest {
  @TempDir Path temporary;

  @Test
  void personal_mirror_reads_scoped_instructions_from_its_tree() throws Exception {
    var home = Home.of(PersonalSpaces.name("alice"));
    var data = new DataLayout(temporary.resolve("data")).initialise();
    var hubs = new Hubs(data, project -> 7L);
    Path tree = hubs.of(home.project()).orElseThrow().tree();
    Files.createDirectories(tree.resolve("In"));
    Files.writeString(tree.resolve("AGENTS.md"), "Mirror root rules");
    Files.writeString(tree.resolve("In/AGENT.md"), "Mirror Inbox rules");
    var gate =
        new UnionGate(
            hubs, ignored -> {}, Instant::now, Duration.ofSeconds(1), Duration.ofMinutes(1));
    var grants = List.of(new Grant(Scope.WORKSPACE, Mode.READ));
    var mirror =
        new MirrorProvider(
            home.project(),
            PersonalWorkspace.LOGICAL_ROOT,
            List.of(),
            tree,
            gate,
            grants,
            Optional.of("alice"));
    var rules =
        new ScopedFileRules(
            new AgentRules(
                data, mock(SessionChannel.class), session -> false, (project, session) -> false),
            (project, scopes, session, owner) -> List.of(mirror));
    var agent =
        new AgentDefinition(
            "worker", "Worker", "model", List.of(), List.of(), grants, 4, 4, "Role");
    var rootRule = new AgentRules.Rule("/personal/AGENTS.md", "", "Mirror root rules");
    var inboxRule = new AgentRules.Rule("/personal/In/AGENT.md", "In", "Mirror Inbox rules");
    String input = "{\"path\":\"/personal/In/note.md\"}";

    assertEquals(List.of(rootRule), rules.forTool(agent, home, null, "alice", "file_roots", "{}"));
    assertEquals(
        List.of(rootRule, inboxRule),
        rules.forTool(agent, home, null, "alice", "file_read", input));

    Files.writeString(tree.resolve("In/AGENTS.md"), "Conflicting Inbox rules");
    var refused =
        assertThrows(
            AgentRules.Refused.class,
            () -> rules.forTool(agent, home, null, "alice", "file_read", input));
    assertTrue(refused.getMessage().contains("/personal/In"));

    Path childRules = tree.resolve("In/AGENTS.md");
    Files.write(childRules, new byte[] {(byte) 0xc3, 0x28});
    var invalid =
        assertThrows(
            AgentRules.Refused.class,
            () -> rules.forTool(agent, home, null, "alice", "file_read", input));
    assertTrue(invalid.getMessage().contains("not UTF-8"), invalid.getMessage());

    Path outside = Files.writeString(temporary.resolve("outside.md"), "Outside mirror");
    Files.delete(childRules);
    Files.createSymbolicLink(childRules, outside);
    var escaped =
        assertThrows(
            AgentRules.Refused.class,
            () -> rules.forTool(agent, home, null, "alice", "file_read", input));
    assertTrue(escaped.getMessage().contains("/personal/In/AGENTS.md"), escaped.getMessage());
    assertFalse(escaped.getMessage().contains(outside.toString()), escaped.getMessage());
  }

  @Test
  void invalid_disk_instructions_refuse_instead_of_returning_the_parent_rules() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("workspace")).toRealPath();
    Path child = Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve("AGENTS.md"), "Root instructions");
    Path invalid = child.resolve("AGENTS.md");
    Files.write(invalid, new byte[] {(byte) 0xc3, 0x28});
    var rules =
        new AgentRules(
            DataLayout.NONE,
            mock(SessionChannel.class),
            session -> false,
            (project, session) -> false);

    var refused =
        assertThrows(
            AgentRules.Refused.class, () -> rules.forFile(root, child.resolve("source.txt"), null));

    assertTrue(refused.getMessage().contains(invalid.toString()), refused.getMessage());
    assertTrue(refused.getMessage().contains("not UTF-8"), refused.getMessage());
  }

  @Test
  void selected_root_rules_apply_parent_first_and_moves_check_both_scopes() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("workspace")).toRealPath();
    Path first = Files.createDirectories(root.resolve("first"));
    Path second = Files.createDirectories(root.resolve("second"));
    Files.writeString(temporary.resolve("AGENTS.md"), "Outside root");
    Files.writeString(root.resolve("AGENTS.md"), "Root rules");
    Files.writeString(first.resolve("AGENT.md"), "First rules");
    Files.writeString(second.resolve("AGENTS.md"), "Second rules");
    var provider = mock(FileProvider.class);
    when(provider.roots()).thenReturn(List.of(root));
    var files = mock(RunProviders.class);
    when(files.forRun(any(), any(), any(), any())).thenReturn(List.of(provider));
    var rules =
        new ScopedFileRules(
            new AgentRules(
                new DataLayout(temporary.resolve("data")),
                mock(SessionChannel.class),
                session -> false,
                (project, session) -> false),
            files);
    var agent =
        new AgentDefinition(
            "worker", "Worker", "model", List.of(), List.of(), List.of(), 4, 4, "Role");
    String json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .createObjectNode()
            .put("path", first.resolve("source.txt").toString())
            .put("to", second.resolve("target.txt").toString())
            .toString();
    assertEquals(
        List.of("Root rules", "First rules"),
        rules.forTool(agent, Home.global(), null, "alice", "file_read", json).stream()
            .map(AgentRules.Rule::text)
            .toList());
    assertEquals(
        List.of("Root rules", "First rules", "Second rules"),
        rules.forTool(agent, Home.global(), null, "alice", "file_move", json).stream()
            .map(AgentRules.Rule::text)
            .toList());
    assertEquals(
        List.of("Root rules"),
        rules.forTool(agent, Home.global(), null, "alice", "file_roots", "{}").stream()
            .map(AgentRules.Rule::text)
            .toList());
    Files.writeString(first.resolve("AGENTS.md"), "Conflicting alias");
    assertThrows(
        AgentRules.Refused.class,
        () -> rules.forTool(agent, Home.global(), null, "alice", "file_read", json));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentRules(
                    new DataLayout(temporary.resolve("data")),
                    mock(SessionChannel.class),
                    session -> false,
                    (p, s) -> false)
                .forFile(root, temporary.resolve("outside.txt"), null));
  }
}
