package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Project-local live declarations supplement immutable built-in registration. */
public interface ScopedTools {
  static Set<String> reservedNames(Set<String> registered) {
    var names = new java.util.HashSet<>(registered);
    names.addAll(BoardTools.NAMES);
    names.addAll(TodoTools.NAMES);
    names.addAll(FileTools.NAMES);
    names.addAll(
        Set.of(
            ResultTools.READ_NAME,
            ResultTools.LIST_NAME,
            AgentRunTool.NAME,
            SkillRuntime.RUN,
            SkillRuntime.READ,
            "inbox_read",
            WrittenCalls.REPLY_AS_WRITTEN));
    return Set.copyOf(names);
  }

  Set<String> names(Long project);

  Set<String> stagedNames(String project, Path root, Set<String> builtins);

  String revision(Long project);

  List<AgentTool> tools(Home home, String agent, String session, BooleanSupplier cancelled);

  /** Owner-aware projection; implementations must filter runtime scope assignments here. */
  default List<AgentTool> tools(
      Home home, String agent, String session, BooleanSupplier cancelled, String account) {
    return tools(home, agent, session, cancelled);
  }

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
