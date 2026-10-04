package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.SessionChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedFileRulesTest {
  @TempDir Path temporary;

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
        IllegalArgumentException.class,
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
