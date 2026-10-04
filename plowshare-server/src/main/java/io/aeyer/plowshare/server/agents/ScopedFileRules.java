package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.RunProviders;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Resolves rules on the selected provider through the same server-owned filesystem routing. */
public final class ScopedFileRules implements JobRuntime.FileRules {
  private static final Set<String> TOOLS =
      Set.of("file_read", "file_stat", "file_edit", "file_delete", "file_move");
  private final AgentRules rules;
  private final RunProviders files;

  public ScopedFileRules(AgentRules rules, RunProviders files) {
    this.rules = rules;
    this.files = files;
  }

  @Override
  public List<AgentRules.Rule> forTool(
      AgentDefinition definition,
      Home home,
      String session,
      String account,
      String tool,
      String json) {
    var router = new ProviderRouter(at -> files.forRun(at, definition.scopes(), session, account));
    if (tool.equals("file_roots")) {
      List<AgentRules.Rule> roots = new ArrayList<>();
      for (var provider : router.providersFor(home))
        for (Path root : provider.roots()) {
          roots.addAll(
              rules.forFile(
                  root,
                  root.resolve("__agent_rules_scope__"),
                  provider instanceof RemoteProvider ? session : null));
        }
      return roots.stream().distinct().toList();
    }
    if (!TOOLS.contains(tool)) return List.of();
    com.fasterxml.jackson.databind.JsonNode arguments;
    try {
      arguments = ToolArguments.parse(json, tool, "{\"path\":\"/workspace/file\"}");
    } catch (ToolArguments.BadArguments invalid) {
      return List.of();
    }
    List<AgentRules.Rule> found = new ArrayList<>();
    for (String key : tool.equals("file_move") ? List.of("path", "to") : List.of("path")) {
      var argument = arguments.get(key);
      if (argument == null || !argument.isTextual())
        continue; // The file tool owns argument validation.
      Path path;
      try {
        path = io.aeyer.plowshare.protocol.FileAccess.canonical(Path.of(argument.asText()));
      } catch (java.nio.file.InvalidPathException invalid) {
        continue;
      }
      io.aeyer.plowshare.server.files.FileProvider provider;
      try {
        provider = router.providerFor(home, path);
      } catch (io.aeyer.plowshare.server.files.WorkspaceRefusedException refused) {
        continue;
      }
      Path root =
          provider.roots().stream()
              .filter(path::startsWith)
              .max(Comparator.comparingInt(Path::getNameCount))
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "No filesystem root covers the agent rules path."));
      found.addAll(rules.forFile(root, path, provider instanceof RemoteProvider ? session : null));
    }
    return found.stream().distinct().toList();
  }
}
