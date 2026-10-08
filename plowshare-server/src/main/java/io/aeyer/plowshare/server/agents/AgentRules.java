package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.agents.DefinitionResolver.Caller;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Scoped Markdown instructions, separate from role definitions and capability grants. */
public final class AgentRules {
  public record Rule(String origin, String scope, String text) {}

  private java.util.function.Function<Caller, Long> personalIds = caller -> null;

  public void usePersonalResources(java.util.function.Function<Caller, Long> ids) {
    personalIds = ids;
  }

  private ApplicationResources resources = ApplicationResources.NONE;

  public void useApplicationResources(ApplicationResources resources) {
    this.resources = resources;
  }

  private final DataLayout data;
  private final SessionChannel channel;
  private final Predicate<String> live;
  private final BiPredicate<Long, String> roots;

  public AgentRules(
      DataLayout data,
      SessionChannel channel,
      Predicate<String> live,
      BiPredicate<Long, String> roots) {
    this.data = data;
    this.channel = channel;
    this.live = live;
    this.roots = roots;
  }

  /** Parent scopes precede child scopes. AGENT.md aliases AGENTS.md; conflicting copies refuse. */
  public List<Rule> forPath(Caller caller, String agent, String relativeFile) {
    if (!ChannelDefinitions.isSafeSegment(agent))
      throw new IllegalArgumentException("unsafe agent name");
    List<Rule> rules = new ArrayList<>();
    List<String> scopes = new ArrayList<>();
    scopes.add("");
    if (relativeFile != null) {
      Path path = Path.of(SkillSource.relative(relativeFile));
      Path parent = path.getParent();
      if (parent != null) {
        Path current = Path.of("");
        for (Path segment : parent) {
          current = current.resolve(segment);
          scopes.add(current.toString());
          if (scopes.size() > 64)
            throw new IllegalArgumentException("agent rules exceed 64 directory scopes");
        }
      }
    }
    if (data.keepsAnything()) {
      addDisk(rules, data.agentsFor(null).getParent(), "");
      addDisk(rules, data.agentsFor(null).resolve(agent), "agent:" + agent);
    }
    Long personal = personalIds.apply(caller);
    if (personal != null && !personal.equals(caller.projectId()) && data.keepsAnything()) {
      addDisk(rules, data.agentsFor(personal).getParent(), "personal");
      addDisk(rules, data.agentsFor(personal).resolve(agent), "agent:" + agent);
    }
    if (resources.root(caller.projectId()).isEmpty()
        && caller.projectId() != null
        && caller.sessionId() != null
        && live.test(caller.sessionId())
        && roots.test(caller.projectId(), caller.sessionId())) {
      ChannelDefinitions files = ChannelDefinitions.skills(channel, caller.sessionId());
      for (String scope : scopes) addChannel(rules, files, scope, scope);
      addChannel(rules, files, ".plowshare", "");
      addChannel(rules, files, ".plowshare/agents/" + agent, "agent:" + agent);
    }
    if (caller.projectId() != null
        && (data.keepsAnything() || resources.root(caller.projectId()).isPresent())) {
      Path tier =
          resources
              .directory(caller.projectId(), "")
              .orElseGet(() -> data.agentsFor(caller.projectId()).getParent());
      for (String scope : scopes) addDisk(rules, tier.resolve(scope), scope);
      addDisk(rules, tier.resolve("agents").resolve(agent), "agent:" + agent);
    }
    long bytes =
        rules.stream()
            .mapToLong(rule -> rule.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .sum();
    if (bytes > ChannelDefinitions.MAX_SOURCE_BYTES)
      throw new IllegalArgumentException("agent rules exceed source limit");
    return List.copyOf(rules);
  }

  public AgentDefinition apply(Caller caller, AgentDefinition definition) {
    List<Rule> rules = forPath(caller, definition.name(), null);
    if (rules.isEmpty()) return definition;
    StringBuilder prompt = new StringBuilder(definition.prompt());
    for (Rule rule : rules)
      prompt
          .append("\n\nApplicable agent instructions from ")
          .append(rule.origin())
          .append(" (scope: ")
          .append(rule.scope().isEmpty() ? "project" : rule.scope())
          .append("):\n")
          .append(rule.text());
    return definition.withPrompt(prompt.toString());
  }

  /** Instructions along a selected filesystem root, never above that root. */
  public List<Rule> forFile(Path root, Path file, String remoteSession) {
    Path relative = root.relativize(file.normalize());
    if (relative.startsWith(".."))
      throw new IllegalArgumentException("agent rules path escapes the selected root");
    List<Rule> rules = new ArrayList<>();
    List<Path> directories = new ArrayList<>();
    directories.add(root);
    Path parent = relative.getParent();
    Path current = root;
    if (parent != null)
      for (Path part : parent) {
        current = current.resolve(part);
        directories.add(current);
        if (directories.size() > 64)
          throw new IllegalArgumentException("agent rules exceed 64 directory scopes");
      }
    ChannelDefinitions remote =
        remoteSession == null ? null : ChannelDefinitions.skills(channel, remoteSession);
    for (Path directory : directories) {
      String scope = root.relativize(directory).toString();
      if (remote == null) addDisk(rules, directory, scope);
      else addChannel(rules, remote, directory.toString(), scope);
    }
    long bytes =
        rules.stream()
            .mapToLong(rule -> rule.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .sum();
    if (bytes > ChannelDefinitions.MAX_SOURCE_BYTES)
      throw new IllegalArgumentException("agent rules exceed source limit");
    return List.copyOf(rules);
  }

  private static void addDisk(List<Rule> rules, Path directory, String scope) {
    String canonical = disk(directory, "AGENTS.md");
    String alias = disk(directory, "AGENT.md");
    add(rules, directory.toString(), scope, canonical, alias);
  }

  private static String disk(Path directory, String name) {
    Path file = directory.resolve(name);
    if (!Files.exists(file)) return null;
    // Same bounded UTF-8 and canonical containment checks as packages.
    return SkillSource.disk(directory.getParent())
        .readResource(directory.getFileName().toString(), name);
  }

  private static void addChannel(
      List<Rule> rules, ChannelDefinitions files, String directory, String scope) {
    String prefix = directory.isEmpty() ? "" : directory + "/";
    add(
        rules,
        files.describe() + ": " + directory,
        scope,
        read(files, prefix + "AGENTS.md"),
        read(files, prefix + "AGENT.md"));
  }

  private static String read(ChannelDefinitions files, String path) {
    ChannelDefinitions.FileRead read = files.resource(path);
    if (read.absent()) return null;
    if (read.unreadable() != null)
      throw new IllegalArgumentException(
          "cannot read agent rules " + path + ": " + read.unreadable());
    return read.text();
  }

  private static void add(
      List<Rule> rules, String origin, String scope, String canonical, String alias) {
    if (canonical != null && alias != null && !canonical.equals(alias)) {
      throw new IllegalArgumentException("conflicting AGENTS.md and AGENT.md in " + origin);
    }
    String text = canonical != null ? canonical : alias;
    if (text != null && !text.isBlank())
      rules.add(
          new Rule(origin + "/" + (canonical != null ? "AGENTS.md" : "AGENT.md"), scope, text));
  }
}
