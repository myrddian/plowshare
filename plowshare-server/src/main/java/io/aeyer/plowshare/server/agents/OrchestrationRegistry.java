package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.files.Grant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A tier's orchestrations: every file parsed, each conductor's callees checked against the agents
 * that tier serves, and the server's {@link DefinitionChecks} run over the conductors as they are
 * over any agent. A bad file is disabled with its reason; {@link #readRequired} makes a required
 * system definition's absence or refusal fatal at boot.
 */
public final class OrchestrationRegistry {

  private static final Logger log = LoggerFactory.getLogger(OrchestrationRegistry.class);

  /** System capabilities that every boot must serve, protected from project/session shadows. */
  public static final Set<String> REQUIRED = Set.of("design_orchestration");

  /**
   * @param disabled name to the sentence that says why it is not offered
   */
  public record Loaded(Map<String, OrchestrationDefinition> enabled, Map<String, String> disabled) {

    public static final Loaded EMPTY = new Loaded(Map.of(), Map.of());

    public Loaded {
      enabled = Map.copyOf(enabled);
      disabled = Map.copyOf(disabled);
    }
  }

  private OrchestrationRegistry() {}

  /** One layer of a tier stack. A name read here hides the same name in every later layer. */
  public record Layer(OrchestrationDefinition.Tier tier, DefinitionSource source) {
    public Layer {
      Objects.requireNonNull(tier, "tier");
      Objects.requireNonNull(source, "source");
    }
  }

  /**
   * One pinned definition re-parsed from its own text, as a run's row holds it — Decision 1: the
   * conductor is rebuilt from its pinned source, not re-resolved. The file-only checks only:
   * callees and escalation were checked when the run started and are not asked again, and no {@link
   * DefinitionChecks} run here.
   *
   * <p>Public because the parser is package private and the engine that needs this lives in {@code
   * orchestrations}; this is the one door it is given, rather than the parser itself.
   *
   * @throws IllegalStateException with the parser's sentence, if the text no longer parses
   */
  public static OrchestrationDefinition parsePinned(
      String name,
      String origin,
      String source,
      Set<String> knownTools,
      OrchestrationDefinition.Tier tier) {
    return OrchestrationParser.parse(
        new DefinitionSource.Definition(name, origin, source), knownTools, tier);
  }

  public static Loaded read(
      List<Layer> mostSpecificFirst,
      Set<String> knownTools,
      AgentRegistry agents,
      DefinitionChecks checks) {
    return read(mostSpecificFirst, knownTools, agents, checks, false);
  }

  /**
   * The global/shipped boot set, including every required system orchestration. An operator's
   * global replacement is allowed, but a missing, malformed or unserved required definition aborts
   * startup with the loader's reason. Other definitions retain the tolerant read policy.
   */
  public static Loaded readRequired(
      List<Layer> mostSpecificFirst,
      Set<String> knownTools,
      AgentRegistry agents,
      DefinitionChecks checks) {
    Loaded loaded = read(mostSpecificFirst, knownTools, agents, checks);
    for (String name : new TreeSet<>(REQUIRED)) {
      if (!loaded.enabled().containsKey(name)) {
        throw new IllegalStateException(
            "The required system orchestration '"
                + name
                + "' cannot be served: "
                + loaded.disabled().getOrDefault(name, "no global or shipped definition was found")
                + ". Repair the global definition or restore the shipped definition"
                + " and configure a served model before starting the server.");
      }
    }
    return loaded;
  }

  static String protectedRefusal(String name) {
    return "The orchestration '"
        + name
        + "' is a required system capability; project and"
        + " session definitions cannot replace it. Configure its global definition or"
        + " system model binding instead.";
  }

  /**
   * {@link #read(List, Set, AgentRegistry, DefinitionChecks)}, and with {@code quiet} the two
   * warnings a boot logs are left out: a trial of a draft (spec 2026-09-29-orchestration-studio
   * §3.3) reads a whole tier on every call, and its refusals are the answer, not news.
   */
  public static Loaded read(
      List<Layer> mostSpecificFirst,
      Set<String> knownTools,
      AgentRegistry agents,
      DefinitionChecks checks,
      boolean quiet) {
    Objects.requireNonNull(mostSpecificFirst, "mostSpecificFirst");
    Objects.requireNonNull(agents, "agents");
    Objects.requireNonNull(checks, "checks");
    Map<String, OrchestrationDefinition> enabled = new LinkedHashMap<>();
    Map<String, String> disabled = new LinkedHashMap<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Layer layer : mostSpecificFirst) {
      List<DefinitionSource.Definition> entries = layer.source().list();
      Map<String, Long> counts =
          entries.stream()
              .collect(
                  Collectors.groupingBy(DefinitionSource.Definition::name, Collectors.counting()));
      for (DefinitionSource.Definition entry : entries) {
        if (!seen.add(entry.name())) {
          continue;
        }
        if (REQUIRED.contains(entry.name())
            && (layer.tier() == OrchestrationDefinition.Tier.PROJECT
                || layer.tier() == OrchestrationDefinition.Tier.SESSION
                || layer.tier() == OrchestrationDefinition.Tier.PERSONAL)) {
          disabled.put(entry.name(), protectedRefusal(entry.name()));
          // Exclude the shadow before checking grants/cycles, so it cannot change the
          // authority or availability of other definitions in this tier.
          continue;
        }
        if (counts.get(entry.name()) > 1) {
          disabled.put(
              entry.name(),
              "the orchestration '"
                  + entry.name()
                  + "' is defined more than once in "
                  + layer.source().describe()
                  + "; keep one .md or .js definition");
          continue;
        }
        try {
          OrchestrationDefinition definition =
              OrchestrationParser.parse(entry, knownTools, layer.tier());
          Optional<String> refused = calleeRefusal(definition, agents);
          if (refused.isPresent()) {
            throw new IllegalStateException(refused.get());
          }
          enabled.put(definition.name(), definition);
        } catch (IllegalStateException bad) {
          disabled.put(entry.name(), bad.getMessage());
        } catch (RuntimeException unforeseen) {
          // A shape nothing above anticipated costs this one file, never the boot.
          disabled.put(
              entry.name(),
              "the orchestration definition '"
                  + entry.name()
                  + "' ("
                  + entry.origin()
                  + ") could not be read: "
                  + unforeseen);
        }
      }
    }
    // Both the grant graph and the escalation a grant might carry are only knowable once every
    // layer's files are parsed: a grant may name a file this loop has not reached yet, in this
    // layer or a less specific one. calleeRefusal's calls() half has no such gap and is asked
    // per file, above, because `agents` is already the complete, already-loaded registry.
    // Escalation is asked first, so a definition already disabled for its own escalating grant
    // is out of the graph before the cycle walk goes looking, and a cycle is disabled entirely
    // rather than edge by edge, since a cycle is the one fault where no single file is the
    // wrong one — see cycles' own javadoc.
    for (Map.Entry<String, String> escalating : escalatingGrants(enabled).entrySet()) {
      enabled.remove(escalating.getKey());
      disabled.put(escalating.getKey(), escalating.getValue());
    }
    for (Fault fault : cycles(enabled)) {
      enabled.remove(fault.name());
      disabled.put(fault.name(), fault.reason());
    }
    String source =
        mostSpecificFirst.stream()
            .map(l -> l.source().describe())
            .collect(Collectors.joining(", then "));
    Map<String, AgentDefinition> conductors = new LinkedHashMap<>();
    enabled.forEach((name, definition) -> conductors.put(name, definition.conductor()));
    AgentRegistry.Loaded checked =
        checks.applyTo(new AgentRegistry.Loaded(conductors, Map.of(), Map.of()), source);
    for (Map.Entry<String, String> refused : checked.disabled().entrySet()) {
      if (enabled.remove(refused.getKey()) != null) {
        disabled.put(refused.getKey(), refused.getValue());
      }
    }
    // The checks hand conductors back changed (sampling resolved); keep what they returned.
    enabled.replaceAll(
        (name, definition) -> {
          AgentDefinition conductor = checked.enabled().get(name);
          return conductor == null ? definition : definition.withConductor(conductor);
        });
    if (!quiet) {
      enabled.values().stream()
          .filter(definition -> !definition.triggers().isEmpty())
          .filter(
              definition ->
                  agents.names().stream()
                      .map(agents::find)
                      .flatMap(Optional::stream)
                      .noneMatch(agent -> agent.orchestrations().contains(definition.name())))
          // A trigger is also reachable through another orchestration's own conductor,
          // which is not one of `agents` at all — an agent and a conductor are both
          // AgentDefinitions, but only the first loop asks agents. Without this, every
          // trigger granted only by nesting is warned about as orphaned on every boot.
          .filter(
              definition ->
                  enabled.values().stream()
                      .map(OrchestrationDefinition::conductor)
                      .noneMatch(
                          conductor -> conductor.orchestrations().contains(definition.name())))
          .forEach(
              definition ->
                  log.warn(
                      "The orchestration '{}' declares triggers, but no loaded agent is granted"
                          + " it; those triggers cannot be noticed",
                      definition.name()));
      disabled.forEach(
          (name, reason) ->
              log.warn(
                  "The orchestration '{}' is DISABLED and will not be offered, because: {}",
                  name,
                  reason));
    }
    return new Loaded(enabled, disabled);
  }

  /**
   * Why this orchestration's conductor may not call its callees through {@code agents}, or empty
   * when it may: each callee is served, delegable, and holds no grant the conductor does not.
   * Package private so {@link OrchestrationResolver} can re-ask it of a boot definition against a
   * project's agents.
   *
   * <p>Says nothing about {@code orchestrations:} grants — {@link #grantEscalationRefusal} is that
   * half, and it deliberately takes a finished map rather than {@code agents}: unlike a callee,
   * which {@code agents} already has every one of, a grantee may be a file this tier's own read has
   * not reached yet, so the two checks cannot share one signature.
   */
  static Optional<String> calleeRefusal(OrchestrationDefinition definition, AgentRegistry agents) {
    String refusal =
        "the orchestration definition '" + definition.name() + "' (" + definition.origin() + ")";
    AgentDefinition conductor = definition.conductor();
    // Not checked: calls without agent_run. OrchestrationParser.parse grants it to a conductor
    // that names calls (spec §4.3).
    for (String callee : conductor.calls()) {
      Optional<AgentDefinition> found = agents.find(callee);
      if (found.isEmpty()) {
        return Optional.of(
            refusal + " calls '" + callee + "', which is not an agent this tier serves");
      }
      if (found.get().bot() || !found.get().delegable()) {
        return Optional.of(
            refusal + " calls '" + callee + "', which is not delegable: an agent can never run it");
      }
      Optional<Grant> escalating = AgentRegistry.escalatingGrant(conductor, found.get());
      if (escalating.isPresent()) {
        return Optional.of(
            refusal
                + " calls '"
                + callee
                + "', which is granted "
                + escalating.get().declaration()
                + " — a grant the conductor '"
                + definition.name()
                + "' does not hold: it "
                + AgentRegistry.holdings(conductor)
                + ". A callee may hold fewer grants"
                + " than its caller and never more, or delegation is how an agent widens"
                + " its own access, one file over. Narrow '"
                + callee
                + "', or widen '"
                + definition.name()
                + "'");
      }
    }
    return checkerRefusal(definition, agents, refusal);
  }

  /**
   * The only tools an acceptance checker may hold (spec 2026-10-01 §2): it reads the project and
   * nothing more — no {@code run}, no write, no delegation.
   */
  public static final Set<String> CHECKER_TOOLS =
      Set.of(
          FileTools.CODE_MAP_NAME,
          FileTools.ROOTS_NAME,
          FileTools.READ_NAME,
          FileTools.GLOB_NAME,
          FileTools.GREP_NAME,
          FileTools.STAT_NAME);

  /**
   * Why the agent a definition's {@code checker:} names cannot be its checker, or empty when it can
   * (spec 2026-10-01 §2): it is an agent this tier serves, it holds only {@link #CHECKER_TOOLS} and
   * calls nobody, and it is not delegable — the harness runs it, and a conductor it checks must
   * never be able to run it, or call it to agree with itself.
   */
  static Optional<String> checkerRefusal(
      OrchestrationDefinition definition, AgentRegistry agents, String refusal) {
    String name = definition.checker();
    if (name == null) {
      return Optional.empty();
    }
    Optional<AgentDefinition> found = agents.find(name);
    if (found.isEmpty()) {
      return Optional.of(
          refusal
              + " names the checker '"
              + name
              + "', which is not an agent"
              + " this tier serves");
    }
    AgentDefinition checker = found.get();
    List<String> beyond =
        checker.tools().stream().filter(tool -> !CHECKER_TOOLS.contains(tool)).toList();
    if (!beyond.isEmpty() || !checker.calls().isEmpty()) {
      return Optional.of(
          refusal
              + " names the checker '"
              + name
              + "', which holds "
              + (beyond.isEmpty() ? "calls " + checker.calls() : "the tools " + beyond)
              + ": a checker reads the project and nothing more — "
              + new TreeSet<>(CHECKER_TOOLS)
              + " at most, and no calls");
    }
    if (checker.delegable()) {
      return Optional.of(
          refusal
              + " names the checker '"
              + name
              + "', which is delegable:"
              + " the harness runs a checker, and a conductor must never be able to");
    }
    return Optional.empty();
  }

  /**
   * Every orchestration in {@code byName} whose {@code orchestrations:} grant escalates scopes its
   * conductor does not hold, mapped to the sentence naming it. {@link #read} moves every key from
   * {@code enabled} to {@code disabled} with the paired value as the reason.
   *
   * <p>Run over the finished map for the reason {@link #cycles} is: a grantee may be a file this
   * tier's read has not reached yet when its granter is parsed, in this layer or a less specific
   * one, so the check can only be answered once every file is in. {@code calleeRefusal}'s {@code
   * calls()} half has no such gap, because {@code agents} handed into it is already the complete,
   * already-loaded registry — only a grant among orchestrations being read together in the same
   * call has this ordering problem.
   *
   * <p>Modelled on {@link AgentRegistry#withholdEscalatingEdges}'s "every edge, and not every
   * node": every definition in {@code byName} is asked in full on its own turn, through {@link
   * #grantEscalationRefusal}, rather than through a walk that marks a node visited and skips it on
   * a second arrival — the mistake that method's own javadoc warns against, and which would be just
   * as wrong to make here for the same reason: an escalation on one caller's edge to a shared
   * grantee must never be hidden by a different caller's clean edge to the same name.
   */
  private static Map<String, String> escalatingGrants(Map<String, OrchestrationDefinition> byName) {
    Map<String, String> refused = new LinkedHashMap<>();
    for (String name : new TreeSet<>(byName.keySet())) {
      grantEscalationRefusal(byName.get(name), byName)
          .ifPresent(reason -> refused.put(name, reason));
    }
    return refused;
  }

  /**
   * Why {@code definition}'s conductor may not hold its {@code orchestrations:} grants against
   * {@code enabled}, or empty when it may: the first grant that resolves in {@code enabled} and
   * escalates scopes the conductor does not hold. A grant to a name absent from {@code enabled} is
   * skipped rather than refused, exactly like an unresolvable callee: it may simply be a file no
   * tier serves, or one this same {@code enabled} already disabled for a fault of its own.
   *
   * <p>Package private, alongside {@link #calleeRefusal}, so {@link OrchestrationResolver} can
   * re-ask a boot definition's grant against a project's merged orchestrations, the same way it
   * already re-asks {@code calleeRefusal} against a project's agents.
   */
  static Optional<String> grantEscalationRefusal(
      OrchestrationDefinition definition, Map<String, OrchestrationDefinition> enabled) {
    AgentDefinition conductor = definition.conductor();
    for (String granted : conductor.orchestrations()) {
      OrchestrationDefinition target = enabled.get(granted);
      if (target == null) {
        continue;
      }
      Optional<Grant> escalating = AgentRegistry.escalatingGrant(conductor, target.conductor());
      if (escalating.isPresent()) {
        return Optional.of(
            "the orchestration definition '"
                + definition.name()
                + "' ("
                + definition.origin()
                + ") grants '"
                + granted
                + "', which is granted "
                + escalating.get().declaration()
                + " — a grant the conductor '"
                + definition.name()
                + "' does not hold: it "
                + AgentRegistry.holdings(conductor)
                + ". A grantee may hold fewer grants"
                + " than its granter and never more, or nesting is how a run widens its"
                + " own reach. Narrow '"
                + granted
                + "', or widen '"
                + definition.name()
                + "'");
      }
    }
    return Optional.empty();
  }

  private record Fault(String name, String reason) {}

  private enum Mark {
    /** On the current recursion stack: reaching it again is a cycle. */
    ON_STACK,
    /** Fully explored and clean: reaching it again is a diamond, not a cycle. */
    DONE
  }

  /**
   * Every orchestration on every cycle its {@code orchestrations:} grant forms with another, each
   * given the cycle as its reason. {@link #read} moves every one of them from {@code enabled} to
   * {@code disabled} before the server checks run.
   *
   * <p><b>A copy of {@link AgentRegistry}'s own call-graph walk, deliberately, and not a
   * generalisation of it.</b> The two graphs disagree on both axes a shared walk would need to
   * abstract over: the node type ({@link OrchestrationDefinition} here, {@link AgentDefinition}
   * there) and the edge accessor ({@code conductor().orchestrations()} here, {@code calls()}
   * there). A walk parameterised over both would take an extractor function and a lookup function
   * as arguments in place of the two lines below that just say "orchestrations" and "grants" —
   * worse to read at every call site for the sake of one shared method body. The {@code
   * ON_STACK}/{@code DONE} marking, and why a recursion stack and not a visited set, is argued
   * once, in {@code AgentRegistry.cycles}'s javadoc, and applies here unchanged.
   *
   * <p>A pair whose grants would recurse is refused here, at load, rather than left for the depth
   * cap to catch at run time — which it eventually would, but not before starting every child along
   * the way. A grant cycle is a run tree that never drains, so it is cheaper to refuse the file
   * than to run into it.
   */
  private static List<Fault> cycles(Map<String, OrchestrationDefinition> byName) {
    List<Fault> faults = new ArrayList<>();
    Map<String, OrchestrationDefinition> graph = new LinkedHashMap<>(byName);
    while (true) {
      List<String> cycle = findCycle(graph);
      if (cycle == null) {
        return faults;
      }
      String reason =
          "the orchestrations "
              + String.join(" -> ", cycle)
              + " form a cycle in"
              + " the orchestration grants. A pair that would recurse never boots, rather"
              + " than being caught by the depth cap at run time only after starting every"
              + " child along the way: a grant cycle is a run tree that never drains. Break"
              + " the loop in one of these files";
      for (String name : cycle) {
        if (graph.remove(name) != null) {
          faults.add(new Fault(name, reason));
        }
      }
    }
  }

  private static List<String> findCycle(Map<String, OrchestrationDefinition> byName) {
    Map<String, Mark> marks = new HashMap<>();
    List<String> path = new ArrayList<>();
    for (String name : new TreeSet<>(byName.keySet())) {
      List<String> cycle = walk(name, byName, marks, path);
      if (cycle != null) {
        return cycle;
      }
    }
    return null;
  }

  /**
   * The cycle reaching {@code name}, or null. Answers rather than throws, so that the caller can
   * decide between a boot failure and a disablement.
   */
  private static List<String> walk(
      String name,
      Map<String, OrchestrationDefinition> byName,
      Map<String, Mark> marks,
      List<String> path) {
    Mark mark = marks.get(name);
    if (mark == Mark.DONE) {
      return null;
    }
    if (mark == Mark.ON_STACK) {
      List<String> cycle = new ArrayList<>(path.subList(path.indexOf(name), path.size()));
      cycle.add(name);
      return cycle;
    }
    marks.put(name, Mark.ON_STACK);
    path.add(name);
    for (String granted : byName.get(name).conductor().orchestrations()) {
      // Absent when the grant names a file that was never parsed, or was disabled for its
      // own reason. Not an edge in this graph: a run can never start down it.
      if (!byName.containsKey(granted)) {
        continue;
      }
      List<String> cycle = walk(granted, byName, marks, path);
      if (cycle != null) {
        return cycle;
      }
    }
    path.remove(path.size() - 1);
    marks.put(name, Mark.DONE);
    return null;
  }
}
