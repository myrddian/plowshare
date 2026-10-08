package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.applications.ApplicationPackageValidator;
import io.aeyer.plowshare.server.board.*;
import io.aeyer.plowshare.server.events.ScheduleDefinitionCodec;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Uses the runtime's owning parsers; validation never runs a hook or admits scheduled work. */
public final class RuntimeApplicationPackageValidator implements ApplicationPackageValidator {
  private final AgentRegistry boot;
  private final Set<String> tools;
  private final DefinitionChecks checks;
  private final SwarmScheduler.Pools pools;
  private final io.aeyer.plowshare.server.relay.RelayRouteProgram relay;
  private final HookEngine hooks;

  public RuntimeApplicationPackageValidator(
      AgentRegistry boot,
      Set<String> tools,
      DefinitionChecks checks,
      SwarmScheduler.Pools pools,
      HookEngine hooks,
      io.aeyer.plowshare.server.relay.RelayRouteProgram relay) {
    this.boot = boot;
    this.tools = Set.copyOf(tools);
    this.checks = checks;
    this.pools = pools;
    this.hooks = hooks;
    this.relay = relay;
  }

  @Override
  public void validate(String project, Path root) {
    ProjectConfiguration.server(root, List.of(), project);
    if (Files.exists(root.resolve(".plowshare"), LinkOption.NOFOLLOW_LINKS))
      throw new CallerFault(
          "Application resources belong directly in the root; remove .plowshare/");
    if (Files.exists(root.resolve("swarm.md")))
      throw new CallerFault("Use the Application-root swarm/ directory for named swarm types");
    try {
      if (Files.exists(root.resolve("skills.yml")))
        SkillVisibility.parse(Files.readString(root.resolve("skills.yml")));
      if (Files.exists(root.resolve("environment.yml")))
        io.aeyer.plowshare.protocol.EnvironmentFile.parse(
            Files.readString(root.resolve("environment.yml")));
    } catch (IOException invalid) {
      throw new CallerFault("Application runtime settings could not be validated");
    }
    var source =
        new LayeredDefinitions(
            List.of(
                new FilesystemDefinitions(root.resolve("agents")),
                new FilesystemDefinitions(root.resolve("bots"))));
    var loaded =
        checks.applyTo(
            AgentRegistry.read(source, tools, Set.of(), boot.names()), source.describe());
    if (!loaded.disabled().isEmpty()
        || !loaded.withheldEdges().isEmpty()
        || !loaded.withheldTools().isEmpty())
      throw new CallerFault(
          "Application agents contain refused definitions or grants: "
              + loaded.disabled().keySet()
              + loaded.withheldEdges().keySet()
              + loaded.withheldTools().keySet());
    if (loaded.enabled().keySet().stream().anyMatch(AgentsConfig.REQUIRED::contains))
      throw new CallerFault("Application cannot replace a required server agent");
    var merged = new LinkedHashMap<>(boot.byName());
    merged.putAll(loaded.enabled());
    var registry = new AgentRegistry(merged);
    var orchestrations =
        OrchestrationRegistry.read(
            List.of(
                new OrchestrationRegistry.Layer(
                    OrchestrationDefinition.Tier.PROJECT,
                    new FilesystemDefinitions(root.resolve("orchestrations"), true))),
            tools,
            registry,
            checks);
    if (!orchestrations.disabled().isEmpty())
      throw new CallerFault(
          "Application orchestrations contain refused definitions: "
              + orchestrations.disabled().keySet());
    for (var skill : SkillSource.disk(root.resolve("skills")).list())
      SkillDefinition.parse(skill, OrchestrationDefinition.Tier.PROJECT);
    List<SwarmSources.Source> swarms = new ArrayList<>();
    Set<String> names = new HashSet<>();
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String path = root.relativize(file).toString().replace('\\', '/');
        if (path.startsWith("schedules/") && path.endsWith(".json")) {
          if (path.substring("schedules/".length()).contains("/"))
            throw new CallerFault("Schedule definitions must be direct JSON files");
          ScheduleDefinitionCodec.read(Files.readString(file));
        }
        if (path.startsWith("hooks/")
            && (path.endsWith(".js") || path.endsWith(".ts") || path.endsWith(".mjs"))) {
          String name = path.substring("hooks/".length());
          if (name.contains("/") || !io.aeyer.plowshare.server.hooks.HookFile.isHookName(name))
            throw new CallerFault(
                "Hook definitions must be direct JavaScript or erasable TypeScript files");
          try (var context = hooks.newContext()) {
            String javascript =
                io.aeyer.plowshare.server.hooks.script.Stripping.javascript(
                    name, Files.readString(file));
            context.parse(
                org.graalvm.polyglot.Source.newBuilder("js", javascript, name + ".mjs")
                    .mimeType("application/javascript+module")
                    .build());
          } catch (io.aeyer.plowshare.server.hooks.script.HookFailure
              | org.graalvm.polyglot.PolyglotException invalid) {
            throw new CallerFault(
                "Application hook could not be parsed: " + name + ": " + invalid.getMessage());
          }
        }
        if (path.startsWith("swarm/")) {
          String leaf = path.substring(6);
          if (leaf.contains("/") || !(leaf.endsWith(".md") || leaf.endsWith(".json")))
            throw new CallerFault("Swarm definitions must be direct Markdown or JSON files");
          int dot = leaf.lastIndexOf('.');
          String name = leaf.substring(0, dot);
          SwarmSelection.requireName(name);
          if (!names.add(name)) throw new CallerFault("Duplicate named swarm type");
          swarms.add(
              new SwarmSources.Source(name, leaf.substring(dot + 1), Files.readString(file), leaf));
        }
      }
    } catch (IOException invalid) {
      throw new CallerFault("Application resources could not be validated");
    }
    for (var swarm :
        new SwarmDefinitions(ignored -> List.copyOf(swarms), ignored -> registry, pools)
            .types(project))
      if (!swarm.refused().isEmpty())
        throw new CallerFault(
            "Application swarm contains refused configuration or members: " + swarm.name());
    io.aeyer.plowshare.server.relay.RelayPackageValidation.validate(root, relay);
  }
}
