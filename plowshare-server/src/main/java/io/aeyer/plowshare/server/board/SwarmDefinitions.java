package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.io.IOException;
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

/** Named project definitions. Current files govern new topics; existing topics own a snapshot. */
public final class SwarmDefinitions implements SwarmCatalog {

  private static final Logger log = LoggerFactory.getLogger(SwarmDefinitions.class);

  public static final String FILE = "swarm definition";

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
      String name,
      List<String> members,
      int budget,
      Map<String, String> refused,
      String origin,
      SwarmSelection selection) {

    public SwarmDefinition {
      members = List.copyOf(members);
      refused = Map.copyOf(refused);
      SwarmSelection.requireName(name);
      Objects.requireNonNull(origin, "origin");
      if (budget < 2
          || members.size() > 64
          || (selection == null && !members.isEmpty())
          || (selection != null
              && (!name.equals(selection.name())
                  || budget != selection.budget()
                  || !members.equals(selection.members()))))
        throw new IllegalArgumentException("Swarm catalog and retained selection must agree");
    }

    /**
     * Programmatically supplied default definitions retain their canonical participant identity.
     */
    public SwarmDefinition(
        List<String> members, int budget, Map<String, String> refused, String origin) {
      this(
          "default",
          members,
          budget,
          refused,
          origin,
          members.isEmpty()
              ? null
              : new SwarmSelection(
                  "default",
                  SwarmSelection.digest(String.join("\0", members) + "\0" + budget),
                  "",
                  members,
                  budget));
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

  private final SwarmSources sources;
  private final Function<String, AgentRegistry> agentsFor;
  private final SwarmScheduler.Pools pools;
  private final Map<String, Map<String, String>> lastLogged = new ConcurrentHashMap<>();

  public SwarmDefinitions(
      SwarmSources sources, Function<String, AgentRegistry> agentsFor, SwarmScheduler.Pools pools) {
    this.sources = Objects.requireNonNull(sources);
    this.agentsFor = Objects.requireNonNull(agentsFor);
    this.pools = Objects.requireNonNull(pools);
  }

  /** Server-data tier constructor for isolated fixtures and embedded configurations. */
  public SwarmDefinitions(
      DataLayout data, Function<Long, AgentRegistry> agentsFor, SwarmScheduler.Pools pools) {
    this(
        project -> {
          Long id = project == null ? null : Long.valueOf(project);
          if (data.keepsAnything()) {
            Path own = data.swarmFor(id).getParent();
            if (Files.exists(own, java.nio.file.LinkOption.NOFOLLOW_LINKS))
              return ServerSwarmSources.directory(data.root(), own, null, own + "/");
            Path global = data.swarmFor(null).getParent();
            if (Files.exists(global, java.nio.file.LinkOption.NOFOLLOW_LINKS))
              return ServerSwarmSources.directory(data.root(), global, null, global + "/");
          }
          return ServerSwarmSources.shipped();
        },
        project -> agentsFor.apply(project == null ? null : Long.valueOf(project)),
        pools);
  }

  public SwarmDefinition forProject(Long projectId) {
    return select(projectId == null ? null : projectId.toString(), null);
  }

  @Override
  public List<SwarmDefinition> types(String project) {
    return sources.read(project).stream().map(source -> resolve(project, source)).toList();
  }

  private SwarmDefinition resolve(String project, SwarmSources.Source source) {
    String origin = source.origin();
    List<String> names;
    int budget;
    String description;
    try {
      Map<String, Object> front;
      if (source.format().equals("md")) {
        front = frontmatter(source.text());
        String normalised = source.text().replace("\r\n", "\n");
        int end = normalised.indexOf("\n---", 4) + 4;
        description = normalised.substring(end).strip();
      } else {
        front = json(source.text());
        Object prose = front.remove("description");
        if (prose != null && !(prose instanceof String))
          throw new IllegalStateException("Swarm description must be text");
        description = prose == null ? "" : (String) prose;
      }
      names = names(front.get("members"));
      if (front.containsKey("budget") && front.get("budget") == null)
        throw new IllegalStateException("Swarm budget must be a whole number");
      budget = budget(front.get("budget"));
      if (description.length() > 4096 || description.indexOf('\0') >= 0)
        throw new IllegalStateException("Swarm description must fit within 4096 characters");
    } catch (IOException | IllegalStateException refused) {
      Map<String, String> whole = Map.of(FILE, String.valueOf(refused.getMessage()));
      logRefusals(origin, whole);
      return new SwarmDefinition(source.name(), List.of(), DEFAULT_BUDGET, whole, origin, null);
    }
    AgentRegistry registry = agentsFor.apply(project);

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
    return new SwarmDefinition(
        source.name(),
        members,
        budget,
        refused,
        origin,
        members.isEmpty()
            ? null
            : new SwarmSelection(
                source.name(), SwarmSelection.digest(source.text()), description, members, budget));
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

  /** Strict JSON is converted inside the configuration boundary before domain values are built. */
  private static Map<String, Object> json(String text) throws IOException {
    var mapper =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    var body = mapper.readTree(text);
    if (body == null || !body.isObject())
      throw new IllegalStateException("A JSON swarm must be an object");
    var result = new LinkedHashMap<String, Object>();
    var fields = body.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      if (!Set.of("members", "budget", "description").contains(entry.getKey()))
        throw new IllegalStateException("Unknown swarm key '" + entry.getKey() + "'");
      var value = entry.getValue();
      switch (entry.getKey()) {
        case "members" -> {
          if (!value.isArray())
            throw new IllegalStateException("Swarm members must be a non-empty list");
          var members = new ArrayList<String>();
          for (var member : value) {
            if (!member.isTextual())
              throw new IllegalStateException("Swarm members must be agent names");
            members.add(member.textValue());
          }
          result.put("members", members);
        }
        case "budget" -> {
          if (!value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalStateException("Swarm budget must be a whole number");
          result.put("budget", value.intValue());
        }
        case "description" -> {
          if (!value.isTextual()) throw new IllegalStateException("Swarm description must be text");
          result.put("description", value.textValue());
        }
        default -> throw new IllegalStateException("Unknown swarm key");
      }
    }
    return result;
  }

  private static Map<String, Object> frontmatter(String text) {
    String normalised = text.replace("\r\n", "\n");
    if (!normalised.startsWith("---\n")) {
      throw new IllegalStateException(
          FILE + " opens with a '---' frontmatter fence and" + " this one does not");
    }
    int close = normalised.indexOf("\n---", 4);
    if (close >= 0 && close + 4 < normalised.length() && normalised.charAt(close + 4) != '\n')
      throw new IllegalStateException("Swarm frontmatter closing fence must be a complete line");
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
    if (!(members instanceof List<?> list) || list.isEmpty() || list.size() > 64) {
      throw new IllegalStateException(
          FILE + " names its members as a non-empty list:" + " members: [researcher, critic]");
    }
    Set<String> names = new LinkedHashSet<>();
    for (Object each : list) {
      if (!(each instanceof String name)
          || name.isBlank()
          || name.length() > 128
          || name.indexOf('\0') >= 0) {
        throw new IllegalStateException(
            FILE + "'s members are agent names; '" + each + "' is not one");
      }
      if (!names.add(name.strip()))
        throw new IllegalStateException("Swarm member names must be unique");
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
