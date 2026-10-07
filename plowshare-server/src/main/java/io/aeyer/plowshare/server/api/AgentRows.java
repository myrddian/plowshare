package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One listing of who can be reached, built once for both surfaces.
 *
 * <p>{@code AgentController} and {@code AgentListHandler} each assembled these rows in eleven
 * identical lines, and adding the default bot to one and not the other is exactly the drift the
 * parity tests exist to catch after the fact. Built here, there is nothing to drift.
 */
public final class AgentRows {

  private AgentRows() {}

  /**
   * @param preferred what {@link DefinitionResolver#defaultBot} found for the same caller the
   *     registry was resolved for
   */
  public static List<AgentView> of(
      AgentRegistry registry, Callers callers, Optional<DefinitionResolver.DefaultBot> preferred) {
    Map<String, AgentView> rows = new TreeMap<>();
    for (String name : registry.exportedNames()) {
      var selected = registry.get(name);
      rows.put(
          name,
          AgentView.of(selected, callers.withheldFrom(registry, selected.name()))
              .addressedAs(name));
    }
    for (Map.Entry<String, String> refused : registry.disabled().entrySet()) {
      rows.put(refused.getKey(), AgentView.disabled(refused.getKey(), refused.getValue()));
    }
    preferred.ifPresent(
        named -> {
          AgentView row = rows.get(named.name());
          // A DEFAULT NAMING NOTHING IS SAID, NOT SKIPPED. A client that found no
          // marked row would fall back to "the only bot" and quietly answer with
          // somebody the tier did not choose; a disabled row carrying the reason
          // is the refusal arriving where the choice is made.
          rows.put(
              named.name(),
              row == null
                  ? AgentView.disabled(
                          named.name(),
                          "named as the default bot in "
                              + named.where()
                              + ", and nothing a person can reach here is called"
                              + " that")
                      .preferring()
                  : row.preferring());
        });
    return List.copyOf(rows.values());
  }

  /** Discovery is the server's, on both surfaces, scoped by each row's grants. */
  public static List<AgentView> withCommands(
      List<AgentView> rows,
      AgentRegistry registry,
      io.aeyer.plowshare.server.agents.SkillResolver.Catalog packages,
      Map<String, io.aeyer.plowshare.server.agents.OrchestrationDefinition> procedures) {
    return rows.stream()
        .map(
            row ->
                row.served()
                    ? row.withCommands(
                            io.aeyer.plowshare.server.agents.CommandCatalog.of(
                                registry.get(row.name()), packages, procedures))
                        .withSkillRefusals(
                            packages.refused().entrySet().stream()
                                .filter(
                                    entry ->
                                        registry.get(row.name()).canUseSkill(entry.getKey())
                                            || (entry.getKey().startsWith("(")
                                                && !registry.get(row.name()).skills().isEmpty()))
                                .sorted(Map.Entry.comparingByKey())
                                .map(entry -> "Skill " + entry.getKey() + ": " + entry.getValue())
                                .toList())
                    : row)
        .toList();
  }
}
