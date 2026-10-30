package io.aeyer.plowshare.server.applications;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * The first of ADR 0042's three confinement inventories, as code: the surfaces an Application's
 * source declares, read through the runtime's own owning parsers, so that a child's declaration can
 * be compared against its container's hold rather than honoured as written.
 *
 * <p>Both sides of the comparison are read by {@link #read}, one code path, so the two cannot drift
 * into meaning different things. Nothing here resolves a permission: a declaration is a request,
 * and a hold is what the container's own source already declares. The comparison narrows or
 * refuses; it never widens, and it never silently drops a declaration, because an accepted package
 * whose declaration was quietly ignored is one of the spec's two named anti-goals.
 *
 * <p>Slice one refuses rather than reduces, with one deliberate exception: a declaration identical
 * to one the container holds is admitted unchanged, which is what makes the comparison a subset
 * test rather than a blanket ban on the surface. Schedule ownership is refused outright, because
 * ADR 0020 gives a schedule operational authority over the server's own scheduler and nothing in
 * this slice mediates that for a child.
 */
final class ContainedDeclarations {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** How many filesystem entries a bounded Application package may present to a walk. */
  private static final int MAX_ENTRIES = SourceApplicationDeployments.MAX_FILES * 18;

  private ContainedDeclarations() {}

  /**
   * What an Application's source declares across the surfaces a child may not exceed.
   *
   * @param providers dynamic tool provider declarations, by provider name
   * @param ports Relay port topics mapped to the audiences (groups) declared on them; an INGRESS
   *     port maps to the empty set, which is still a held topic
   * @param swarms named swarm types
   * @param schedules schedule definitions, which a child may not declare at all in this slice
   * @param bindings tool bindings, each as {@code provider/name}: what tool a project exposes, from
   *     which provider. A binding is distinct from a provider declaration — a package may bind a
   *     tool of a provider it does not itself declare — so it is its own subset test.
   * @param workers the Relay worker accounts declared in {@code server/relay-workers.json}. An
   *     account is the identity that services the project's work, so a child naming one its
   *     container does not would have its tools serviced by an identity no container mediated.
   */
  record Hold(
      Set<String> providers,
      Map<String, Set<String>> ports,
      Set<String> swarms,
      Set<String> schedules,
      Set<String> bindings,
      Set<String> workers) {
    Hold {
      providers = Set.copyOf(providers);
      swarms = Set.copyOf(swarms);
      schedules = Set.copyOf(schedules);
      bindings = Set.copyOf(bindings);
      workers = Set.copyOf(workers);
      var copied = new LinkedHashMap<String, Set<String>>();
      ports.forEach((topic, groups) -> copied.put(topic, Set.copyOf(groups)));
      ports = Map.copyOf(copied);
    }
  }

  /**
   * Whether this Application opts into nesting. Only a literal {@code true} counts: a missing
   * field, a null, a string and a nested object all mean "not declared", so the capability cannot
   * be acquired by a manifest that merely mentions it or by an upgrade that never said so.
   */
  static boolean nesting(Path root) {
    Path file = root.resolve(io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy.FILE);
    try {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        return false;
      byte[] bytes;
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(SourceApplicationDeployments.MAX_FILE_BYTES + 1);
      }
      if (bytes.length > SourceApplicationDeployments.MAX_FILE_BYTES) return false;
      JsonNode body = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
      JsonNode declared = body == null ? null : body.get("nesting");
      return declared != null && declared.isBoolean() && declared.booleanValue();
    } catch (IOException | IllegalArgumentException unreadable) {
      // An unreadable or malformed manifest never grants the capability by default.
      return false;
    }
  }

  /** Reads the declared surfaces of a staged or retained Application root. */
  static Hold read(String project, Path root) {
    var settings = ApplicationServerSettings.read(project, root);
    Set<String> providers = new LinkedHashSet<>();
    for (var provider : settings.providers()) providers.add(provider.provider());
    Set<String> bindings = new LinkedHashSet<>();
    for (var tool : settings.tools()) bindings.add(tool.provider() + "/" + tool.name());
    Set<String> workers = new LinkedHashSet<>();
    settings.worker().ifPresent(worker -> workers.add(worker.account()));
    Map<String, Set<String>> ports = new LinkedHashMap<>();
    for (var binding : settings.ports())
      ports
          .computeIfAbsent(binding.topic(), topic -> new LinkedHashSet<>())
          .addAll(binding.groups());
    Set<String> swarms = new LinkedHashSet<>();
    Set<String> schedules = new LinkedHashSet<>();
    try (var entries = Files.walk(root)) {
      for (Path file : entries.limit(MAX_ENTRIES + 1L).toList()) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
        String path = root.relativize(file).toString().replace('\\', '/');
        if (path.startsWith("swarm/")) {
          String leaf = path.substring("swarm/".length());
          int dot = leaf.lastIndexOf('.');
          swarms.add(dot < 0 ? leaf : leaf.substring(0, dot));
        }
        if (path.startsWith("schedules/") && path.endsWith(".json"))
          schedules.add(path.substring("schedules/".length()));
      }
    } catch (IOException unavailable) {
      throw new CallerFault("Application declarations could not be read");
    }
    return new Hold(providers, ports, swarms, schedules, bindings, workers);
  }

  /**
   * Refuses every child declaration that is not already its container's. Each refusal names the
   * surface, because "refused" and "reduced" are different answers and a caller must be able to
   * tell which it got.
   */
  static void requireWithin(Hold child, Hold container) {
    if (!child.schedules().isEmpty())
      throw new CallerFault(
          "A contained Application cannot declare a schedule; schedule operational authority stays"
              + " with its container");
    for (String provider : child.providers())
      if (!container.providers().contains(provider))
        throw new CallerFault(
            "A contained Application cannot declare a tool provider its container does not hold: "
                + provider);
    for (var declared : child.ports().entrySet()) {
      Set<String> held = container.ports().get(declared.getKey());
      if (held == null)
        throw new CallerFault(
            "A contained Application cannot declare a Relay port its container does not hold: "
                + declared.getKey());
      for (String group : declared.getValue())
        if (!held.contains(group))
          throw new CallerFault(
              "A contained Application cannot declare a Relay audience its container does not"
                  + " hold: "
                  + group);
    }
    // Tool bindings and tool grants. Recorded by Task 5 as accepted-and-uncompared, which was
    // honest but is a gap: a binding is what makes a tool callable, and its provider need not
    // appear in the package's own providers[] list, so the provider subset test above does not
    // cover it. A binding identical to one the container already binds stays admissible, which
    // keeps this a subset test rather than a ban on the surface.
    for (String binding : child.bindings())
      if (!container.bindings().contains(binding))
        throw new CallerFault(
            "A contained Application cannot declare a tool binding its container does not hold: "
                + binding);
    // The Relay worker account is the identity that services a project's work. A child naming one
    // its container does not declare would have its work serviced by an identity no container
    // mediated, which is the escape the containment relation exists to prevent.
    for (String worker : child.workers())
      if (!container.workers().contains(worker))
        throw new CallerFault(
            "A contained Application cannot declare a Relay worker account its container does not"
                + " hold: "
                + worker);
    for (String swarm : child.swarms())
      if (!container.swarms().contains(swarm))
        throw new CallerFault(
            "A contained Application cannot declare a named swarm type its container does not"
                + " hold: "
                + swarm);
  }
}
