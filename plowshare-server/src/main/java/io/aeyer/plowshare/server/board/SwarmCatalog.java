package io.aeyer.plowshare.server.board;

import java.util.List;

/** Project-scoped definitions. Reads validate current files; selections are retained by topics. */
public interface SwarmCatalog {
  List<SwarmDefinitions.SwarmDefinition> types(String project);

  /** Missing selection is allowed only when there is exactly one definition. */
  default SwarmDefinitions.SwarmDefinition select(String project, String name) {
    if (name != null) SwarmSelection.requireName(name);
    var definitions = types(project);
    if (name == null && definitions.size() == 1) return definitions.getFirst();
    return definitions.stream()
        .filter(definition -> definition.name().equals(name))
        .findFirst()
        .orElseThrow(
            () -> new Board.Refused("Choose a swarm type from swarm.types for this project"));
  }
}
