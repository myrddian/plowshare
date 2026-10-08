package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Project-local live declarations supplement immutable built-in registration. */
public interface ScopedTools {
  Set<String> names(Long project);

  Set<String> stagedNames(String project, Path root, Set<String> builtins);

  String revision(Long project);

  default java.util.Optional<ToolFailure> checkAccess(
      Home home,
      String agent,
      String session,
      String tool,
      io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner) {
    return java.util.Optional.empty();
  }

  List<AgentTool> tools(Home home, String agent, String session, BooleanSupplier cancelled);

  ScopedTools NONE =
      new ScopedTools() {
        public Set<String> names(Long project) {
          return Set.of();
        }

        public Set<String> stagedNames(String project, Path root, Set<String> builtins) {
          return Set.of();
        }

        public String revision(Long project) {
          return "";
        }

        public List<AgentTool> tools(
            Home home, String agent, String session, BooleanSupplier cancelled) {
          return List.of();
        }
      };
}
