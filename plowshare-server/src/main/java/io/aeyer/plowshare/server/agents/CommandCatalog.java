package io.aeyer.plowshare.server.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Server-owned discovery, shared by skills and orchestrations; contains no execution path. */
public final class CommandCatalog {
  public record Entry(
      String command,
      List<String> aliases,
      String kind,
      String name,
      String description,
      String argumentHint,
      String executor,
      String mode,
      String tier,
      String hash,
      boolean agentVisible) {
    public Entry {
      aliases = List.copyOf(aliases);
    }

    public Entry(
        String command,
        List<String> aliases,
        String kind,
        String name,
        String description,
        String argumentHint,
        String executor,
        String mode,
        String tier,
        String hash) {
      this(
          command,
          aliases,
          kind,
          name,
          description,
          argumentHint,
          executor,
          mode,
          tier,
          hash,
          false);
    }
  }

  private CommandCatalog() {}

  public static List<Entry> of(
      AgentDefinition agent,
      SkillResolver.Catalog skills,
      Map<String, OrchestrationDefinition> orchestrations) {
    Map<String, SkillResolver.Resolved> granted = skills.granted(agent);
    List<Entry> entries = new ArrayList<>();
    granted.forEach(
        (name, resolved) -> {
          SkillDefinition skill = resolved.definition();
          entries.add(
              new Entry(
                  "/skill:" + name,
                  List.of(),
                  "skill",
                  name,
                  skill.description(),
                  "Arguments for the skill",
                  skill.mode() == SkillDefinition.Mode.DIRECT ? agent.name() : skill.agent(),
                  skill.mode() == null ? null : skill.mode().name(),
                  skill.tier().name(),
                  skill.hash(),
                  skill.agentVisible()));
        });
    orchestrations.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              if (!agent.orchestrations().contains(entry.getKey())) return;
              OrchestrationDefinition definition = entry.getValue();
              entries.add(
                  new Entry(
                      "/orchestration:" + entry.getKey(),
                      List.of(),
                      "orchestration",
                      entry.getKey(),
                      definition.description(),
                      "Work for the orchestration to do",
                      definition.conductor().name(),
                      null,
                      definition.tier().name(),
                      definition.hash()));
            });
    return List.copyOf(entries);
  }
}
