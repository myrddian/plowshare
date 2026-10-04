package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * A project's swarm — spec 2026-09-29 §3: {@code projects/<id>/swarm.md}, else {@code
 * global/swarm.md}, then the shipped {@code global/swarm.md}. Its members are ordinary agent
 * definitions resolved from that project, and each one that could not be a member in this slice is
 * refused by name with a reason, never silently. Read when asked — the file is small and asked for
 * only when a topic opens or a message is posted — so an edit takes effect at once.
 */
public final class SwarmDefinitions {

  private static final Logger log = LoggerFactory.getLogger(SwarmDefinitions.class);

  public static final String FILE = "swarm.md";
  private static final String SHIPPED = "global/" + FILE;

  /** Model calls per root topic when the file names none. */
  public static final int DEFAULT_BUDGET = 100;

  private static final Set<String> KEYS = Set.of("members", "budget");

  /**
   * What a project's swarm is at the moment it is read.
   *
   * @param members the agent names that may sit on a topic, in the file's order
   * @param refused a name ({@link #FILE} for the file itself) to the sentence why
   * @param origin where it was read from, for a person reading a refusal
   */
  public record SwarmDefinition(
      List<String> members, int budget, Map<String, String> refused, String origin) {

    public SwarmDefinition {
      members = List.copyOf(members);
      refused = Map.copyOf(refused);
    }

    /** Why this swarm has no members, or what was refused from it. */
    public String why() {
      if (refused.isEmpty()) {
        return origin;
      }
      StringBuilder out = new StringBuilder(origin).append(":");
      refused.forEach(
          (name, reason) -> out.append(" ").append(name).append(" — ").append(reason).append(";"));
      return out.toString();
    }
  }

  private final DataLayout data;
  private final Function<Long, AgentRegistry> agentsFor;
  private final SwarmScheduler.Pools pools;

  /**
   * The refusals last logged for each {@code swarm.md} this instance has read, so a persistently
   * misconfigured member is not a fresh WARN on every topic open and every message post. Keyed by
   * origin (path or classpath resource) rather than by project id, on {@link #firstExisting}'s own
   * reasoning: the project tier and the global tier are two different files and each earns its own
   * memory of what was last said about it. A path absent from this map has either never been read
   * or was last read with no refusals; {@link #logRefusals} removes an entry the moment a file's
   * refusals clear, so a later regression is logged again rather than staying silent because of
   * what an earlier, unrelated fault once said.
   */
  private final Map<String, Map<String, String>> lastLogged = new ConcurrentHashMap<>();

  public SwarmDefinitions(
      DataLayout data, Function<Long, AgentRegistry> agentsFor, SwarmScheduler.Pools pools) {
    this.data = Objects.requireNonNull(data, "data");
    this.agentsFor = Objects.requireNonNull(agentsFor, "agentsFor");
    this.pools = Objects.requireNonNull(pools, "pools");
  }

  public SwarmDefinition forProject(Long projectId) {
    Path file = firstExisting(projectId);
    String origin = file == null ? "classpath:" + SHIPPED : file.toString();
    Map<String, Object> front;
    List<String> names;
    int budget;
    try {
      front = frontmatter(file == null ? shipped() : Files.readString(file));
      names = names(front.get("members"));
      budget = budget(front.get("budget"));
    } catch (IOException | IllegalStateException refused) {
      Map<String, String> whole = Map.of(FILE, String.valueOf(refused.getMessage()));
      logRefusals(origin, whole);
      return new SwarmDefinition(List.of(), DEFAULT_BUDGET, whole, origin);
    }
    AgentRegistry registry = agentsFor.apply(projectId);
    List<String> members = new ArrayList<>();
    Map<String, String> refused = new LinkedHashMap<>();
    for (String name : names) {
      Optional<AgentDefinition> found = registry.find(name);
      if (found.isEmpty()) {
        refused.put(name, "no agent named '" + name + "' resolves in this project");
        continue;
      }
      if (pools.serving(found.get().model()).isEmpty()) {
        refused.put(
            name,
            "'"
                + name
                + "' runs on the model '"
                + found.get().model()
                + "', which no pool with swarm slots serves; give a pool that serves it"
                + " `swarm:` slots, or choose another member");
        continue;
      }
      // A member rerouted on a refusal runs its next call on the fallback's model, still as
      // a scheduled seat run; if no swarm pool serves that model, the run waits for a slot
      // that never comes. So the fallback is held to the member's own model's rule.
      String fallback = found.get().fallback().model();
      if (fallback != null && pools.serving(fallback).isEmpty()) {
        refused.put(
            name,
            "'"
                + name
                + "' falls back to the model '"
                + fallback
                + "', which no pool with swarm slots serves; give a pool that serves it"
                + " `swarm:` slots, or drop the fallback");
        continue;
      }
      members.add(name);
    }
    logRefusals(origin, refused);
    return new SwarmDefinition(members, budget, refused, origin);
  }

  /**
   * Logs {@code refused} for {@code file}, exactly once per distinct set of refusals — not once per
   * read. A file read repeatedly with the same refusals (a topic staying open, a board polling)
   * logs nothing after the first time; a change to what is refused, including clearing to none,
   * logs again. See {@link #lastLogged} for why the memory is kept per path.
   */
  private void logRefusals(String file, Map<String, String> refused) {
    if (refused.isEmpty()) {
      lastLogged.remove(file);
      return;
    }
    if (refused.equals(lastLogged.get(file))) {
      return;
    }
    refused.forEach(
        (name, why) -> {
          if (FILE.equals(name)) {
            log.warn("{} at {} is refused: {}", FILE, file, why);
          } else {
            log.warn("{} at {}: member {} refused: {}", FILE, file, name, why);
          }
        });
    lastLogged.put(file, refused);
  }

  private Path firstExisting(Long projectId) {
    if (!data.keepsAnything()) {
      return null;
    }
    if (projectId != null && Files.isRegularFile(data.swarmFor(projectId))) {
      return data.swarmFor(projectId);
    }
    Path global = data.swarmFor(null);
    return Files.isRegularFile(global) ? global : null;
  }

  private static String shipped() throws IOException {
    try (var input = SwarmDefinitions.class.getResourceAsStream("/" + SHIPPED)) {
      if (input == null) {
        throw new IllegalStateException("the shipped " + SHIPPED + " is missing");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Map<String, Object> frontmatter(String text) {
    String normalised = text.replace("\r\n", "\n");
    if (!normalised.startsWith("---\n")) {
      throw new IllegalStateException(
          FILE + " opens with a '---' frontmatter fence and" + " this one does not");
    }
    int close = normalised.indexOf("\n---", 4);
    if (close < 0) {
      throw new IllegalStateException(FILE + "'s frontmatter is never closed with '---'");
    }
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object loaded;
    try {
      loaded = new Yaml(new SafeConstructor(options)).load(normalised.substring(4, close));
    } catch (YAMLException malformed) {
      // Unchecked, on SnakeYAML's own design, so forProject's try/catch of IOException
      // and IllegalStateException never sees it unless it is turned into one here —
      // AgentRegistry.frontmatterOf does the same, around the same call, for the same
      // reason: a repeated key (DuplicateKeyException extends this) or a syntax error is
      // as much a reason to refuse the whole file as an unrecognised key is, and refusing
      // it with a reason is the point of this class, not an uncaught exception two
      // callers up. Only the cause's first line: SnakeYAML's messages run several lines
      // with a caret under the offending column, and the rest belongs in the log this
      // refusal is already headed for via the cause, not in the sentence a reader of
      // why() sees.
      String detail = malformed.getMessage();
      String firstLine = detail == null ? "" : detail.strip().split("\n", 2)[0];
      throw new IllegalStateException(
          FILE + "'s frontmatter is not valid YAML: " + firstLine, malformed);
    }
    if (!(loaded instanceof Map<?, ?> map)) {
      throw new IllegalStateException(FILE + "'s frontmatter is not a set of keys");
    }
    Map<String, Object> front = new LinkedHashMap<>();
    map.forEach((key, value) -> front.put(String.valueOf(key), value));
    for (String key : front.keySet()) {
      if (!KEYS.contains(key)) {
        throw new IllegalStateException(
            FILE
                + " has the unrecognised key '"
                + key
                + "'. The keys are budget and members. An unrecognised key is refused"
                + " rather than ignored, as an agent definition's is");
      }
    }
    return front;
  }

  private static List<String> names(Object members) {
    if (!(members instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalStateException(
          FILE + " names its members as a non-empty list:" + " members: [researcher, critic]");
    }
    Set<String> names = new LinkedHashSet<>();
    for (Object each : list) {
      if (!(each instanceof String name) || name.isBlank()) {
        throw new IllegalStateException(
            FILE + "'s members are agent names; '" + each + "' is not one");
      }
      names.add(name.strip());
    }
    return List.copyOf(names);
  }

  private static int budget(Object budget) {
    if (budget == null) {
      return DEFAULT_BUDGET;
    }
    if (!(budget instanceof Integer calls) || calls < 2) {
      throw new IllegalStateException(
          FILE
              + "'s budget is a number of model calls per"
              + " root topic, at least 2 (one for a member, one kept for closing); it is '"
              + budget
              + "'");
    }
    return calls;
  }
}
