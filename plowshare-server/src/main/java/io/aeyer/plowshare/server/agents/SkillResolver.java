package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.agents.DefinitionResolver.Caller;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/** Same authority order as agent definitions. A malformed override does not reveal a lower tier. */
public final class SkillResolver {
  public record Layer(Tier tier, SkillSource source) {}

  public record Resolved(SkillDefinition definition, SkillSource resources) {}

  public record Catalog(Map<String, Resolved> skills, Map<String, String> refused) {
    public Catalog {
      skills = Map.copyOf(skills);
      refused = Map.copyOf(refused);
    }

    public Map<String, Resolved> granted(AgentDefinition agent) {
      Map<String, Resolved> allowed = new LinkedHashMap<>();
      skills.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                if (agent.canUseSkill(entry.getKey()))
                  allowed.put(entry.getKey(), entry.getValue());
              });
      return java.util.Collections.unmodifiableMap(allowed);
    }
  }

  private java.util.function.Function<Caller, Long> personalIds = caller -> null;
  private java.util.function.Function<Long, ProjectConfiguration> projectConfiguration =
      id -> ProjectConfiguration.NONE;

  public void useProjectConfiguration(
      java.util.function.Function<Long, ProjectConfiguration> source) {
    projectConfiguration = source;
  }

  public void usePersonalResources(java.util.function.Function<Caller, Long> personalIds) {
    this.personalIds = personalIds;
  }

  private ApplicationResources applicationResources = ApplicationResources.NONE;

  /** Composition supplies registered server sources; client sessions cannot replace them. */
  public void useApplicationResources(ApplicationResources resources) {
    applicationResources = java.util.Objects.requireNonNull(resources);
  }

  private final DataLayout data;
  private final SessionChannel channel;
  private final LongPredicate projectExists;
  private final Predicate<String> live;
  private final BiPredicate<Long, String> roots;

  public SkillResolver(
      DataLayout data,
      SessionChannel channel,
      LongPredicate projectExists,
      Predicate<String> live,
      BiPredicate<Long, String> roots) {
    this.data = data;
    this.channel = channel;
    this.projectExists = projectExists;
    this.live = live;
    this.roots = roots;
  }

  /** Fresh reads see repaired client files without introducing a client-side cache/loader. */
  public Catalog forCaller(Caller caller) {
    List<Layer> layers = new ArrayList<>();
    if (caller.projectId() != null) {
      if (!projectExists.test(caller.projectId()))
        throw new IllegalArgumentException("project does not exist");
      if (data.keepsAnything() || applicationResources.root(caller.projectId()).isPresent())
        layers.add(new Layer(Tier.PROJECT, SkillSource.disk(skillsDirectory(caller.projectId()))));
      if (applicationResources.root(caller.projectId()).isEmpty()
          && caller.sessionId() != null
          && live.test(caller.sessionId())
          && roots.test(caller.projectId(), caller.sessionId())) {
        layers.add(new Layer(Tier.SESSION, SkillSource.channel(channel, caller.sessionId())));
      }
    }
    Long personal = personalIds.apply(caller);
    if (personal != null && !personal.equals(caller.projectId()) && data.keepsAnything()) {
      layers.add(new Layer(Tier.PERSONAL, SkillSource.disk(skillsDirectory(personal))));
    }
    if (data.keepsAnything())
      layers.add(new Layer(Tier.GLOBAL, SkillSource.disk(skillsDirectory(null))));
    layers.add(new Layer(Tier.SHIPPED, SkillSource.shipped()));
    Catalog catalog = load(layers);
    try {
      Map<String, Boolean> visibility = new LinkedHashMap<>();
      // Account Personal policy is a default for this caller's skills. More
      // specific session/project policy wins without creating any grants.
      if (personal != null && !personal.equals(caller.projectId()) && data.keepsAnything()) {
        visibility.putAll(visibilityAt(personal));
      }
      if (caller.projectId() != null
          && applicationResources.root(caller.projectId()).isEmpty()
          && caller.sessionId() != null
          && live.test(caller.sessionId())
          && roots.test(caller.projectId(), caller.sessionId())) {
        ChannelDefinitions.FileRead read =
            ChannelDefinitions.skills(channel, caller.sessionId())
                .resource(".plowshare/skills.yml");
        if (read.unreadable() != null) throw new IllegalArgumentException(read.unreadable());
        if (!read.absent()) visibility.putAll(SkillVisibility.parse(read.text()));
        visibility.putAll(
            ProjectConfiguration.local(new ChannelDefinitions(channel, caller.sessionId()), null)
                .skills());
      }
      if (caller.projectId() != null
          && (data.keepsAnything() || applicationResources.root(caller.projectId()).isPresent())) {
        visibility.putAll(visibilityAt(caller.projectId()));
      }
      Map<String, Resolved> resolved = new LinkedHashMap<>();
      catalog
          .skills()
          .forEach(
              (name, skill) ->
                  resolved.put(
                      name,
                      new Resolved(
                          visibility.containsKey(name)
                              ? skill.definition().withAgentVisible(visibility.get(name))
                              : skill.definition(),
                          skill.resources())));
      return new Catalog(resolved, catalog.refused());
    } catch (RuntimeException failed) {
      Map<String, String> refused = new LinkedHashMap<>(catalog.refused());
      refused.put(
          "(skill visibility)", "Skill discovery configuration refused: " + failed.getMessage());
      return new Catalog(Map.of(), refused);
    }
  }

  private Map<String, Boolean> visibilityAt(Long projectId) {
    Path directory =
        applicationResources
            .directory(projectId, "")
            .orElseGet(() -> skillsDirectory(projectId).getParent());
    Map<String, Boolean> result = new LinkedHashMap<>();
    if (Files.exists(directory.resolve("skills.yml"), LinkOption.NOFOLLOW_LINKS)) {
      try (var input =
          Files.newInputStream(directory.resolve("skills.yml"), LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = input.readNBytes(65537);
        if (bytes.length > 65536) throw new java.io.IOException("Skill visibility exceeds 64 KiB");
        String source =
            java.nio.charset.StandardCharsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        result.putAll(SkillVisibility.parse(source));
      } catch (java.io.IOException invalid) {
        throw new IllegalArgumentException("Skill visibility is unreadable", invalid);
      }
    }
    result.putAll(projectConfiguration.apply(projectId).skills());
    return Map.copyOf(result);
  }

  public static Catalog load(List<Layer> layers) {
    Map<String, Resolved> served = new LinkedHashMap<>();
    Map<String, String> refused = new LinkedHashMap<>();
    Set<String> claimed = new HashSet<>();
    for (Layer layer : layers) {
      List<DefinitionSource.Definition> files;
      try {
        files = layer.source().list();
      } catch (RuntimeException failed) {
        refused.put(
            "(" + layer.tier() + " tier)", layer.source().describe() + ": " + failed.getMessage());
        // An unreadable authority tier cannot authorize a fallback of unknown identity.
        break;
      }
      Set<String> inLayer = new HashSet<>();
      for (DefinitionSource.Definition file : files) {
        if (!inLayer.add(file.name())) {
          if (served.containsKey(file.name())
              && served.get(file.name()).resources() != layer.source()) continue;
          served.remove(file.name());
          refused.put(file.name(), "duplicate skill in " + layer.source().describe());
          continue;
        }
        if (!claimed.add(file.name())) continue;
        try {
          served.put(
              file.name(), new Resolved(SkillDefinition.parse(file, layer.tier()), layer.source()));
        } catch (RuntimeException failed) {
          refused.put(file.name(), file.origin() + ": " + failed.getMessage());
        }
      }
    }
    return new Catalog(served, refused);
  }

  private java.nio.file.Path skillsDirectory(Long id) {
    return applicationResources.directory(id, "skills").orElseGet(() -> data.skillsFor(id));
  }
}
