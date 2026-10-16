package io.aeyer.plowshare.server.agents;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Release definitions loaded together, retaining the production loader's trusted provenance. */
public final class ShippedDefinitions {
  private ShippedDefinitions() {}

  /**
   * Only main resources: a test JVM's classpath also contains intentionally invalid agent fixtures.
   * Reading these source files models their installed bundle provenance, not ordinary disk content.
   */
  public static DefinitionSource source() {
    return source(
        List.of(Path.of("src/main/resources/agents"), Path.of("src/main/resources/bots")));
  }

  private static DefinitionSource source(List<Path> directories) {
    var definitions =
        directories.stream()
            .flatMap(path -> new FilesystemDefinitions(path).list().stream())
            .map(
                entry ->
                    new DefinitionSource.Definition(
                        entry.name(),
                        entry.origin(),
                        entry.text(),
                        DefinitionSource.Provenance.BUNDLED))
            .toList();
    return new DefinitionSource() {
      public String describe() {
        return "the release resource bundle";
      }

      public List<Definition> list() {
        return definitions;
      }
    };
  }

  /** Strictly load just the release's agents directory with bundled provenance. */
  public static java.util.Map<String, AgentDefinition> agents(Set<String> tools) {
    var source = source(List.of(Path.of("src/main/resources/agents")));
    var required =
        source.list().stream().map(DefinitionSource.Definition::name).collect(Collectors.toSet());
    return AgentRegistry.read(source, tools, required).enabled();
  }

  /** Duplicate names and invalid release definitions fail rather than being layered or disabled. */
  public static AgentRegistry registry(Set<String> tools) {
    var source = source();
    var required =
        source.list().stream().map(DefinitionSource.Definition::name).collect(Collectors.toSet());
    return new AgentRegistry(AgentRegistry.read(source, tools, required));
  }
}
