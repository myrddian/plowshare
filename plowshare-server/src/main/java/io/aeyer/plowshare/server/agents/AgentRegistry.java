package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.learner.Learner;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.llm.dispatch.JsonSchema;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads a {@link DefinitionSource} of agent definitions and refuses to serve a bad one.
 *
 * <p>The same place and the same instinct as {@code LlmConfig}: a misconfiguration that would fail
 * silently at run time becomes something an operator is told about at boot, naming the file and the
 * key they have to edit. Every message here names the agent, and where two are involved, both.
 *
 * <p><b>What a refusal costs is one of three things, and {@link #read} owns the ladder.</b> An
 * agent this server's own code looks up by name takes the boot down — there is nothing to do
 * without it and the degraded path is discovered later, somewhere else, naming no file. An invalid
 * <em>item inside a grant</em> — a tool name nothing binds, a callee no file defines, a callee that
 * refused, an edge that would escalate — costs the item: it is dropped, the agent is served, and
 * the item is named. Everything else is a <em>disablement</em>: named, logged with its reason,
 * carried on the agents surface as "disabled, because X", and not served. That is what lets a
 * directory hold user-authored bots without one malformed file — or one malformed line — being able
 * to stop Plowshare starting.
 *
 * <p>{@link #load} is the strict read, where every agent is treated as depended upon and the first
 * fault is a refusal. Nothing boots through it; it is what a test asks when a whole directory has
 * to be perfect.
 *
 * <p><b>Why the checks are load-bearing rather than tidy.</b> The call graph is data. Anchor's
 * three agents are wired in Java in a known order and cannot form a cycle, name a tool that does
 * not exist, or delegate to a file somebody deleted. Here every one of those is one edit away, and
 * none of them fails loudly downstream:
 *
 * <ul>
 *   <li>an unknown tool becomes a schema the model is never offered, so the agent silently cannot
 *       do the job its prompt describes. That it is never offered is also why it costs the name
 *       rather than the file: {@link #withheldTool} owns that argument;
 *   <li>an unknown callee becomes a delegation that fails on the first request that happens to need
 *       it, in production, once;
 *   <li><b>a cycle is the one with no downstream guard at all.</b> A turn ends the moment tool
 *       calls are issued, so a delegating job does not sit on a stack waiting for its child — there
 *       is no recursion to overflow and no depth counter that would mean anything. A cycle is a job
 *       tree that never drains, and the check below is the only thing that stops it;
 *   <li>a callee holding a grant its caller lacks becomes an agent that widens its own access by
 *       delegating. A provider is built from one list of grants and never asks who asked for the
 *       job, so nothing downstream ever compares the two lists. {@link #withholdEscalatingEdges} is
 *       the comparison, and it is made once, on the static graph.
 * </ul>
 *
 * <p><b>What is deliberately not checked here.</b> That {@code model} names a specifier some pool
 * actually serves — {@code LlmDispatcher.requireServed} is that check, and it needs a dispatcher
 * this class does not take. It belongs beside the other boot refusals at wiring time; this class
 * only requires that {@code model} is a non-blank string somebody wrote on purpose.
 *
 * <p>And that a callee's {@code tools} are a subset of its caller's, which is <b>deliberately not a
 * rule</b>: a file tool reaches nothing without a grant, so the check above closes the filesystem
 * half without one, and a tools rule would forbid the ordinary shape where a delegating agent holds
 * {@code agent_run} and nothing else. {@link AgentRunTool}'s javadoc owns that argument and the
 * residue it leaves.
 *
 * <p><b>Frontmatter keys are kebab-case</b> ({@code max-turns}), matching {@code application.yml}'s
 * convention rather than Java's, and the set is closed: an unrecognised key is refused rather than
 * ignored. A dropped {@code tool:} for {@code tools:} would otherwise load as a perfectly valid
 * agent that has no tools and can never do its job.
 *
 * <p>The design spec disagreed with this when the class was written — it wrote the budget keys
 * snake_case — and was corrected to match in {@code 4cc482a}. Recorded because the refusal above is
 * what turns such a disagreement into a boot failure naming the key rather than an agent quietly
 * missing half its configuration; the two documents agree now.
 */
public final class AgentRegistry {

  private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

  /** The tool that grants delegation. Named once; see {@code AgentDefinition#canDelegate}. */
  public static final String AGENT_RUN = "agent_run";

  private static final String SUFFIX = ".md";
  private static final String FENCE = "---";

  private static final String NAME = "name";
  private static final String DESCRIPTION = "description";
  private static final String MODEL = "model";
  private static final String TEMPERATURE = "temperature";
  private static final String SAMPLING = "sampling";
  private static final String SCHEMA = "schema";
  private static final String TOOLS = "tools";
  private static final String CALLS = "calls";
  private static final String SCOPES = "scopes";
  private static final String MAX_TURNS = "max-turns";
  private static final String MAX_MODEL_CALLS = "max-model-calls";
  private static final String EXPORTED = "exported";
  private static final String DELEGABLE = "delegable";
  private static final String VISION = "vision";
  private static final String BOT = "bot";
  private static final String BOARD = "board";
  private static final String ANNOUNCES_INBOX = "announces-inbox";
  private static final String SKILLS = "skills";
  private static final String ORCHESTRATIONS = "orchestrations";
  private static final String REVIEW_WITH = "review-with";
  private static final String FALLBACK = "fallback";
  private static final String FALLBACK_ON = "when";
  private static final String FALLBACK_MODEL = "model";
  private static final String FALLBACK_MAX_ATTEMPTS = "max-attempts";

  /**
   * The closed set, and closing it is what makes an optional key safe.
   *
   * <p>{@code temperature} is the eleventh and sits beside {@code model} as the other per-agent
   * inference setting. It is optional, which is exactly the shape a closed key set has to exist
   * for: a misspelt {@code temperatrue:} in an open set would be dropped in silence and the agent
   * would run at the default forever, having been configured by somebody who can see their own file
   * saying otherwise. Here it is refused by name.
   *
   * <p><b>{@code sampling} is the twelfth, and it is the one an agent file is actually entitled to
   * hold an opinion about.</b> {@code temperature} asserts a fact about a model from a file that
   * cannot see the model — which is why {@code temperature: 1.0} is right on Gemma and wrong on
   * {@code gpt-oss-20b} — while {@code sampling: precise} asserts a fact about the <em>task</em>,
   * and a per-model profile resolves it. The older key survives as the override for an agent that
   * genuinely needs an exact number, and {@code requireTemperature} says what such a file then owes
   * a reader.
   *
   * <p><b>{@code schema} is the thirteenth, and it is where "this agent is a microservice" stops
   * being a description and becomes a contract.</b> The design left three places it could have
   * lived: here, on the calling tool per call, or in a sampling profile. The profile is wrong and
   * is named as wrong — a profile says how to talk to a model <em>family</em> and a schema is about
   * this request. Between the other two, this one puts the contract where the agent is read:
   * somebody opening {@code figure_reader.md} sees what it answers with, in the same file as what
   * it is for and what it may call. Per-call schemas would make the same agent answer different
   * shapes, which is a more flexible thing and a less legible one, and nothing has asked for it.
   *
   * <p><b>{@code vision} is the fourteenth, and it is a requirement rather than a grant.</b> Every
   * other key here says what the agent may do; this says what it needs the model underneath it to
   * be able to do, and {@code AgentsConfig} refuses at boot an agent that declares it and names a
   * model no pool has declared as seeing. Absent means no, which is {@code exported}'s rule: an
   * agent that has not stated a requirement has not stated one.
   *
   * <p><b>{@code bot} is the fifteenth, and it is the first key here that says what a definition
   * <em>is</em> rather than what it may do, what it needs or who may reach it.</b> Spec §2: an
   * agent is a role and a bot is a character — the ordinary way a person uses this server, returned
   * to rather than invoked. It belongs in the closed set for the reason {@code temperature} does:
   * {@code bots: true} in an open one would be dropped in silence and a shipped character would
   * serve as an agent forever, with its own file saying otherwise.
   *
   * <p><b>Here and not in the directory name.</b> {@code DataLayout.botsFor} states that {@code
   * agents/} and {@code bots/} are a filing convenience carrying no meaning the loader reads, and
   * that stands: this key joins {@code exported} and {@code delegable}, where the other facts about
   * what a definition is already live. Absent means agent, on {@code exported}'s rule again — every
   * definition written before this key existed keeps its meaning unedited.
   *
   * <p><b>{@code announces-inbox} is the sixteenth, and it is a request rather than a grant, on
   * {@code vision}'s rule exactly.</b> A bot that declares it is told, in a logged notice after its
   * speaker's utterance, when that speaker's user-inbox has unread arrivals since the
   * conversation's own last turn, and it holds the {@code inbox_read} tool for reading them. Absent
   * means no: a definition that has not asked to be told about the inbox has not asked, and every
   * file written before this key existed keeps its meaning unedited. It is closed into this set for
   * {@code bot}'s reason: an open set would drop {@code announces-inbox: true} in silence and a bot
   * would never learn its speaker had mail, with its own file saying otherwise.
   *
   * <p>{@code board} grants a bot permission to open project board topics. It requires {@code bot:
   * true}, because the resolution returns to that bot's person's conversation.
   */
  private static final Set<String> KNOWN_KEYS =
      Set.of(
          NAME,
          "alias",
          "guidance",
          "dynamic",
          "display-name",
          DESCRIPTION,
          MODEL,
          TEMPERATURE,
          SAMPLING,
          SCHEMA,
          TOOLS,
          CALLS,
          SCOPES,
          MAX_TURNS,
          MAX_MODEL_CALLS,
          EXPORTED,
          DELEGABLE,
          VISION,
          BOT,
          ANNOUNCES_INBOX,
          FALLBACK,
          ORCHESTRATIONS,
          REVIEW_WITH,
          BOARD,
          SKILLS);

  /** Why no agent gets the three job-lifecycle tools. */
  private static final String JOBS =
      "a run's lifecycle belongs to the harness that started it and not to a run inside"
          + " it. An agent's own delegation is '"
          + AGENT_RUN
          + "', built per run out"
          + " of that agent's own 'calls:', and it holds no job id but the one it is";

  /**
   * Why no agent gets the four project tools. {@code ProjectController} owns the argument; this is
   * the half of it that fits in a refusal.
   */
  private static final String LEASH =
      "the party that sets the leash must not be a party the leash binds. An agent that"
          + " could move its own project's workspace could point it at the directory"
          + " holding the model API key, and one that could point it at the agents"
          + " directory could grant itself any tool at the next boot";

  /**
   * The tool names this server's harness-facing MCP surface carries and its agent-facing surface
   * deliberately does not, each with the clause that says why.
   *
   * <p><b>This map enforces nothing.</b> The guard is, and stays, membership in {@code
   * knownTools()} — the exact set the tool layer registered at this boot — and it is name-agnostic.
   * Nothing here is consulted except for a name that guard has already rejected, so an entry that
   * goes stale is unreachable rather than wrong, and a name missing from it falls through to the
   * unknown-tool sentence, which is true of it either way. It buys a better sentence and nothing
   * else, and it is worth saying so because a set that looked like a denylist would invite somebody
   * to maintain it as one.
   *
   * <p>It exists because the two refusals are not the same mistake. An unknown tool is a name
   * nobody wrote. A withheld one is a real tool the author very likely saw on their own harness's
   * list beside {@code agent_run}, sitting next to {@code agent_poll}, {@code agent_result}, and
   * {@code agent_cancel} — and reasonably assumed an agent could hold those too, the same way it
   * holds {@code agent_run} — and <em>"this runtime does not bind it"</em> sends that person
   * looking for a wiring bug.
   *
   * <p>The server cannot import the external TypeScript MCP adapter. Tests compare the maintained
   * MCP menu with agent bindings and their explicit operator-only exceptions.
   *
   * <p><b>Four of that surface's seventeen are deliberately absent from here</b> — {@code
   * memory_index}, {@code memory_curate}, {@code memory_proposals}, {@code memory_resolve}. They
   * are not bound for agents either, so a definition naming one is dropped exactly the same way;
   * what they lack is a recorded argument for <em>never</em>, and claiming one this repository has
   * not made would be the map asserting a policy rather than quoting one. They get the unknown-tool
   * sentence, which does not lie.
   */
  private static final Map<String, String> WITHHELD =
      Map.ofEntries(
          Map.entry("project_define", LEASH),
          // The two lending verbs are here for LEASH's reason and not a
          // weaker one. `project_lend` is the quietest of the six: it extends
          // what a project reaches without touching its workspace, so an
          // agent holding it could add the directory its own definitions sit
          // in and leave every sentence an operator would go and check saying
          // exactly what it said yesterday.
          Map.entry("project_lend", LEASH),
          Map.entry("project_unlend", LEASH),
          Map.entry("project_workspace_set", LEASH),
          Map.entry("project_move", LEASH),
          Map.entry("project_forget", LEASH),
          Map.entry("agent_poll", JOBS),
          Map.entry("agent_result", JOBS),
          Map.entry("agent_cancel", JOBS));

  /**
   * Tools that were renamed, by their old name, so a definition still naming one is told the new
   * name rather than that the tool does not exist.
   *
   * <p>{@link #WITHHELD}'s shape and its disclaimer: this enforces nothing, and a definition naming
   * an old name has it dropped exactly as any unknown tool is. There is deliberately no alias — two
   * names for one tool would have to be matched by every hook's {@code tools:} list for as long as
   * both existed.
   */
  private static final Map<String, String> RENAMED = Map.of("file_write", FileTools.EDIT_NAME);

  /**
   * The agents that ARE the memory pipeline, and so may not author into it.
   *
   * <p>Spelled from the three classes' own constants, exactly as {@code AgentsConfig.REQUIRED} is
   * and for its reason: a rename must not leave this set pointing at an agent nobody looks up.
   *
   * <p><b>The criterion is the pipeline and not {@code REQUIRED}, though today they select the same
   * three.</b> {@code REQUIRED} means "the agents whose absence the code cannot work around" — a
   * different property that happens to coincide, and a future required agent with nothing to do
   * with memory would inherit a ban its own job does not justify. This class has refused that
   * substitution once already: the cut is dependency, not location, and a proxy is only ever a
   * proxy.
   *
   * <p>{@code promotion_judge} keeps {@code memory_read}, and the rule is why: it is about
   * AUTHORING, not about touching memories. An agent that rules on a memory has to be able to read
   * it.
   */
  private static final Set<String> THE_MEMORY_PIPELINE =
      Set.of(Scribe.AGENT, Curator.AGENT, Learner.AGENT);

  /**
   * The verbs {@link #THE_MEMORY_PIPELINE} may not hold. One today; a set because the rule is about
   * a category and not about a name.
   */
  private static final Set<String> AUTHORS_A_MEMORY = Set.of(MemoryTools.WRITE_NAME);

  private final Map<String, AgentDefinition> byName;
  private final AgentGuidance guidance;
  private final Map<String, List<AgentDefinition>> aliases;
  private final Map<String, String> disabled;
  private final Map<String, String> withheldEdges;
  private final Map<String, String> withheldTools;

  /**
   * What one directory came to: what is served, what is not, and what routes between the served
   * ones were taken away.
   *
   * <p>Four maps and not one, because they are four different subjects. An agent in {@code
   * disabled} was read and refused and will not run. An entry in {@code withheldEdges} is a
   * <em>route</em> that was refused, keyed {@code "caller -&gt; callee"}, with the caller still
   * running — its callee too, where there is one. An entry in {@code withheldTools} is a <em>name
   * inside one agent's {@code tools:}</em> that was taken out, keyed {@code "agent: tool"}, with
   * that agent still running and serving without it. Collapsing any of them would make the surface
   * unable to say which happened, and "disabled, because X" — as against "served, minus X" — is the
   * whole of what this mechanism buys over a boot failure.
   *
   * <p>Every value is a sentence naming the file and the fault — the same sentence the strict
   * loader would have thrown — so an operator reads the same words whichever way the server treated
   * it.
   */
  public record Loaded(
      Map<String, AgentDefinition> enabled,
      Map<String, String> disabled,
      Map<String, String> withheldEdges,
      Map<String, String> withheldTools) {

    public Loaded {
      enabled =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(enabled, "enabled")));
      disabled =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(disabled, "disabled")));
      withheldEdges =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(withheldEdges, "withheldEdges")));
      withheldTools =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(withheldTools, "withheldTools")));
    }

    /**
     * A reading with no dropped tools, for the callers that build one by hand. A fixture asserting
     * the three older maps says nothing about the fourth, and having to write {@code Map.of()} at
     * every such site would be noise about a subject those tests are not about.
     */
    public Loaded(
        Map<String, AgentDefinition> enabled,
        Map<String, String> disabled,
        Map<String, String> withheldEdges) {
      this(enabled, disabled, withheldEdges, Map.of());
    }

    /**
     * The same reading with one more agent moved from served to refused.
     *
     * <p>For the checks this class cannot make for itself. {@code AgentsConfig} asks {@code
     * LlmDispatcher} whether each agent's model is served by some pool — a question needing a
     * dispatcher the registry does not take — and the answer has to land in the same two buckets as
     * every other refusal, or an agent on an unserved model would be the one fault that still took
     * the boot down for everybody.
     *
     * <p>Moving an agent out can only relax the set: it removes edges and never adds one, so
     * nothing that validated before it can fail after.
     */
    public Loaded without(String name, String reason) {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(reason, "reason");
      Map<String, AgentDefinition> remaining = new LinkedHashMap<>(enabled);
      if (remaining.remove(name) == null) {
        return this;
      }
      Map<String, String> refused = new LinkedHashMap<>(disabled);
      refused.put(name, reason);
      return new Loaded(remaining, refused, withheldEdges, withheldTools);
    }
  }

  /**
   * A definition parsed with keys this registry does not own set aside, for a definition kind that
   * is an agent plus something — an orchestration's conductor is the one today. Package private:
   * the extra keys are another reader's to check, and nothing outside this package reads a
   * definition.
   */
  record Parsed(AgentDefinition definition, Map<String, Object> extras) {}

  /** One agent and the sentence that says why it is not being served. */
  private record Fault(String agent, String reason) {}

  /**
   * Wraps a set of definitions built elsewhere, and validates it exactly as {@link #load} does.
   *
   * <p><b>An earlier version took the map on trust, and that was a hole straight through the
   * guarantee in this class's javadoc.</b> It ran none of the set-level checks, so a caller — Task
   * 10's wiring, or any fixture — could construct a registry holding a cycle, an unknown callee, or
   * an agent whose two halves of delegation disagree, and get no error at all. The class exists to
   * make a bad set impossible; a second public door into it that skipped the checks made that claim
   * false for everything that did not come through {@code load}.
   *
   * <p>It also checks something {@code load} cannot get wrong and a direct caller can: that each
   * key <em>is</em> the name of the definition filed under it. The cycle walk looks agents up by
   * key, so a map where the two disagree is a graph whose edges point at different nodes than its
   * definitions name.
   *
   * <p>{@link #of} therefore validates twice — once inside {@code load}, once here. That is
   * deliberate and it costs a depth-first walk over a handful of nodes at boot: each entry point
   * has to carry the guarantee on its own, because an entry point that is only safe when called
   * from somewhere else is the hole this replaced.
   */
  public AgentRegistry(Map<String, AgentDefinition> byName) {
    this(new Loaded(Objects.requireNonNull(byName, "byName"), Map.of(), Map.of()));
  }

  /**
   * The registry a directory came to, disablements and all.
   *
   * <p>Validated exactly as the map constructor is, with one difference that is the whole point of
   * the disable rule: <b>a callee may be a name in {@code disabled} rather than a served agent.</b>
   * A caller whose callee was refused for the callee's own reasons keeps running with an inert
   * grant — {@code calls:} is a grant and not a dependency — so the unknown-callee check is asked
   * about every name this directory <em>defines</em>, and only a name no file defines at all is the
   * typo it was always meant to catch.
   */
  public AgentRegistry(Loaded loaded) {
    this(loaded, AgentGuidance.NONE);
  }

  /** Uses model guidance to select aliases; concrete definitions remain immutable and named. */
  public AgentRegistry(Loaded loaded, AgentGuidance guidance) {
    Objects.requireNonNull(loaded, "loaded");
    Map<String, AgentDefinition> copy = Map.copyOf(loaded.enabled());
    requireKeysAreNames(copy);
    Set<String> defined = new TreeSet<>(copy.keySet());
    defined.addAll(loaded.disabled().keySet());
    defined.addAll(AgentAliases.families(copy).keySet());
    requireValidSet(copy, defined);
    this.byName = copy;
    this.guidance = Objects.requireNonNull(guidance, "guidance");
    this.aliases = AgentAliases.families(copy);
    this.disabled = Collections.unmodifiableMap(new TreeMap<>(loaded.disabled()));
    this.withheldEdges = Collections.unmodifiableMap(new TreeMap<>(loaded.withheldEdges()));
    this.withheldTools = Collections.unmodifiableMap(new TreeMap<>(loaded.withheldTools()));
  }

  private static void requireKeysAreNames(Map<String, AgentDefinition> byName) {
    for (Map.Entry<String, AgentDefinition> entry : byName.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().name())) {
        throw new IllegalStateException(
            "the agent filed under '"
                + entry.getKey()
                + "' is named '"
                + entry.getValue().name()
                + "'. The call graph is walked by key"
                + " and declared by name, so the two must agree");
      }
    }
  }

  /**
   * The checks that need the whole set, shared by both ways in.
   *
   * @param byName the agents this registry will serve
   * @param defined every name this directory defines, served or not. Wider than {@code
   *     byName.keySet()} exactly when something was disabled, and the only check that reads it is
   *     the unknown-callee one — for the reason the disable rule turns on: a callee that exists and
   *     was refused is not a typo in its caller's file
   */
  private static void requireValidSet(Map<String, AgentDefinition> byName, Set<String> defined) {
    Map<String, String> aliasFaults = AgentAliases.faults(byName);
    if (!aliasFaults.isEmpty())
      throw new IllegalStateException(new TreeMap<>(aliasFaults).values().iterator().next());
    // Over a copy, because the drop passes rewrite the callers they narrow
    // and this door narrows nothing: a map handed in by a caller is either
    // valid as written or refused. `required` is "everything" here for the
    // same reason it is in `load` -- a set built in Java has no author to be
    // lenient towards.
    withholdUnreachableCallees(new LinkedHashMap<>(byName), defined, name -> true);
    for (Fault fault : disagreeingHalves(byName)) {
      throw new IllegalStateException(fault.reason());
    }
    for (Fault fault : cycles(byName)) {
      throw new IllegalStateException(fault.reason());
    }
    // Last, and after the cycle in particular. Grants inside a cycle must be
    // equal — each is a subset of the other — so a cyclic pair whose grants
    // differ is both faults at once, and reporting the escalation would send
    // an operator to fix grants only to meet the cycle on the next boot. The
    // cycle is the one nothing downstream catches.
    withholdEscalatingEdges(new LinkedHashMap<>(byName), name -> true);
  }

  /** Loads {@code dir} and holds the result. Throws exactly as {@link #load} does. */
  public static AgentRegistry of(Path dir, Set<String> knownTools) {
    return new AgentRegistry(load(dir, knownTools));
  }

  /**
   * Reads {@code dir} under the disable rule and holds the result.
   *
   * <p>The door a boot comes through; {@link #of(Path, Set)} above is the strict one. See {@link
   * #read} for what {@code required} means and for why everything else is disabled rather than
   * fatal.
   */
  public static AgentRegistry of(Path dir, Set<String> knownTools, Set<String> required) {
    return new AgentRegistry(read(dir, knownTools, required));
  }

  /** The names this registry read and is not serving, and why, sorted. */
  public Map<String, String> disabled() {
    return disabled;
  }

  /**
   * The delegation routes this registry took away, keyed {@code "caller -&gt; callee"}, and why.
   *
   * <p>Both agents named in such a key are still served. This is the one place "disable" means
   * something other than "disable an agent", and it is kept separate from {@link #disabled()} so
   * that a surface reading either cannot report one as the other.
   */
  public Map<String, String> withheldEdges() {
    return withheldEdges;
  }

  /**
   * The tool names this registry took out of served agents' declarations, keyed {@code "agent:
   * tool"}, and why.
   *
   * <p>The agent named in such a key is served, and is serving without that tool. Kept separate
   * from {@link #disabled()} and from {@link #withheldEdges()} on the reasoning {@link Loaded}
   * gives: three states, and a surface reading any of them must not be able to report one as
   * another. A dropped tool is the quietest of the three and the one most in need of saying — the
   * agent looks fine.
   */
  public Map<String, String> withheldTools() {
    return withheldTools;
  }

  /**
   * The concrete definition addressed by {@code name}, or the variant selected for an alias. Alias
   * selection returns the variant's own identity, never a renamed executable definition.
   *
   * <p>Every callee named by a loaded definition was checked to exist at load, so a miss here came
   * from outside the graph — an MCP request naming an agent nobody wrote. That is a caller's
   * mistake rather than a broken configuration, hence {@link IllegalArgumentException} and not the
   * {@link IllegalStateException} the boot checks throw; it lists the agents that do exist, because
   * the usual cause is a spelling.
   */
  public AgentDefinition get(String name) {
    return find(name)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "no agent named '" + name + "'; this server serves " + names()));
  }

  /**
   * The concrete definition addressed by {@code name}, or empty when this process serves none. A
   * logical alias resolves its model profile before selecting one immutable variant.
   *
   * <p>{@link #get}'s question without {@link #get}'s answer to a miss, for the one caller that has
   * to ask before it has anywhere to report a failure. {@code AgentRunTool} builds its schema in
   * its constructor, out of the calling agent's {@code calls:} list, and a callee named there is
   * checked to exist by every path that builds a registry — so a miss reaches that constructor only
   * from a definition somebody built by hand. Throwing at it would turn that into a run with no
   * turns and no calls to report against; answering "nothing" lets the schema name the callee
   * without a description and lets the model's own call be refused with a sentence it can read.
   *
   * <p>Not a second door into the map: it returns exactly what {@link #get} returns, and {@link
   * #get} is written in terms of it so the two cannot disagree about what is registered.
   */
  public Optional<AgentDefinition> find(String name) {
    List<AgentDefinition> candidates = aliases.get(name);
    if (candidates == null) return Optional.ofNullable(byName.get(name));
    return Optional.of(
        AgentAliases.select(candidates, guidance.profileFor(candidates.getFirst().model())));
  }

  /** Looks up a recorded concrete identity without reselecting a logical alias on continuation. */
  public Optional<AgentDefinition> findConcrete(String name) {
    return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
  }

  /** The names this registry serves, sorted, for messages and listings. */
  public Set<String> names() {
    TreeSet<String> names = new TreeSet<>(byName.keySet());
    names.addAll(aliases.keySet());
    return Collections.unmodifiableSet(names);
  }

  /**
   * Every definition this registry serves, by name.
   *
   * <p>This class's own javadoc says its public doors are guarded and a new one needs a reason.
   * This one's reason is {@link DefinitionResolver}: it layers a project tier over this whole set,
   * and doing that needs the whole map rather than one name at a time. {@link #names()} followed by
   * {@link #get(String)} per name would answer the same question at the cost of one lookup per
   * definition, which is worse for a caller that is always going to ask for every one of them.
   *
   * <p>Unmodifiable and copied rather than the field itself, so nothing outside this class can
   * reach the live map.
   */
  public Map<String, AgentDefinition> byName() {
    return Map.copyOf(byName);
  }

  /** Revalidates a merged tier while retaining this registry's model-guidance resolver. */
  AgentRegistry replacing(Map<String, AgentDefinition> definitions) {
    return new AgentRegistry(new Loaded(definitions, Map.of(), Map.of()), guidance);
  }

  /**
   * The names anything outside this server may name, sorted.
   *
   * <p><b>{@link #names} and not a replacement for it.</b> The two answer different questions and
   * both are asked: {@code names()} is what this process serves, which is what delegation, the
   * scribe's tripwire and a curator pass ask about; this is what a caller reaching in over HTTP or
   * MCP may choose from. Keeping the wide one is deliberate — {@code exported} gates the outside
   * and was never meant to gate {@code calls:}, and a single narrowed {@code names()} would quietly
   * sever every private agent's edges as well.
   */
  public Set<String> exportedNames() {
    TreeSet<String> exported = new TreeSet<>();
    for (AgentDefinition definition : byName.values()) {
      if (definition.exported()) {
        exported.add(definition.name());
      }
    }
    for (String alias : aliases.keySet()) {
      if (get(alias).exported()) exported.add(alias);
      else exported.remove(alias);
    }
    return Collections.unmodifiableSet(exported);
  }

  /**
   * Parses every {@code *.md} in {@code dir} and validates the set they form.
   *
   * <p>Not recursive: a directory of agents is a flat list, and a nested one would let a file be
   * either an agent or not depending on where it sits.
   *
   * <p>Order of work matters. Each file is parsed and refused on its own terms first — a definition
   * that is wrong by itself should not be reported as a graph problem — and only then is the set
   * checked. <b>Which checks those are, and in what order, is {@link #requireValidSet} and is not
   * repeated here:</b> the list that used to be was wrong within one commit of the check that
   * changed it, naming the cycle as last and as the only check needing the whole graph when neither
   * had been true since.
   *
   * @param dir directory of agent files; must exist
   * @param knownTools the exact set of tool names the tool layer registers at this boot — not a
   *     superset, and not a constant kept beside this one. Too narrow refuses a shipped agent at
   *     boot, which is loud and gets fixed in a minute. <b>Too broad is silent and is the one that
   *     matters:</b> every name in the set is a name this check waves through, so a superset
   *     reduces the unknown-tool refusal to a no-op for exactly the names nobody bound — the agent
   *     starts, is never offered the tool, and simply cannot do what its prompt describes. <b>The
   *     caller this warned about arrived and did the right thing:</b> {@code AgentsConfig} passes
   *     {@code JobRuntime.knownTools()}, the set the tool layer actually registered at this boot,
   *     rather than a constant kept beside it. (This paragraph ended "there is no caller yet" until
   *     a later slice went looking for one and found it.)
   * @return the definitions by name, in name order
   * @throws IllegalStateException if {@code dir} is missing or not a directory, or on any invalid
   *     definition or invalid set
   * @throws java.io.UncheckedIOException if a definition could not be read
   */
  public static Map<String, AgentDefinition> load(Path dir, Set<String> knownTools) {
    requireDirectory(dir);
    Loaded loaded = read(new FilesystemDefinitions(dir), knownTools, name -> true);
    return loaded.enabled();
  }

  /**
   * Reads {@code dir}, aborting for the agents the system depends on and disabling everything else.
   *
   * <h2>The rule, and the cut it draws</h2>
   *
   * <p>{@link #load} refuses a whole directory over one bad file, and a boot that cannot read one
   * agent does not start. That is right for a handful of curated definitions shipped inside this
   * repository and <b>hostile for user content</b>: somebody's malformed bot must not be able to
   * stop Plowshare starting, and "restart the server to add a bot" is not a feature anyone wants.
   * So the fatal set is narrowed to the agents whose absence the code cannot work around, and every
   * other refusal becomes a disablement.
   *
   * <p><b>The cut is dependency, not location.</b> An earlier draft split on which directory a file
   * sat in; a directory is only ever a proxy for the property that matters, which is whether Java
   * somewhere looks the agent up by name and has nothing to do when it is missing.
   *
   * <h2>{@code calls:} is a grant, not a dependency</h2>
   *
   * <p>It says one agent is permitted to reach another, never that it needs it. Two things follow,
   * and they are what keeps this simple:
   *
   * <ul>
   *   <li><b>Disabling does not cascade.</b> A disabled callee leaves its caller served, holding an
   *       inert grant; the edge fails at call time, where {@code AgentRunTool} already answers with
   *       a sentence the model can read.
   *   <li><b>The abort set has no transitive closure.</b> It is exactly the names {@code required}
   *       carries. Nothing is dragged in through the graph, so a required agent's callee is an
   *       ordinary agent.
   * </ul>
   *
   * <p>What survives is a question about <em>whose file is wrong</em>. A caller naming an agent no
   * file defines is a typo in its own definition — the boot check that catches it is still worth
   * having, as a typo-check rather than as a dependency check — and what that typo costs is the
   * name, not the caller. A caller whose callee exists and was refused stays up, grant intact.
   *
   * <h2>The third rung: an item inside a grant costs the item</h2>
   *
   * <p><b>The escalation case's answer, generalised from edges to grants.</b> A definition's {@code
   * tools:}, {@code calls:} and {@code scopes:} are lists of things it is permitted to reach, and a
   * single entry can be wrong on its own — a tool nothing binds, a callee nobody wrote, a callee
   * that refused, an edge that would widen access. Every one of those entries names something
   * <em>that was never issuable</em>: the tool is never constructed, and no job is ever started
   * down the route. So dropping the entry changes nothing a model can reach, and what it saves is
   * the agent.
   *
   * <p>That matters because bots are user-authored, which is the whole reason the rung below
   * exists. Refusing a stranger's whole bot over one line it could never have used is the same
   * mistake as refusing the whole server over one file, made one level down. {@link #withheldTool}
   * and {@link #withholdUnreachableCallees} carry the argument in full.
   *
   * <p><b>Where the boundary sits, and the two faults that sit just outside it.</b> A
   * <em>cycle</em> involves edges and so looks item-shaped, but which of its edges to drop is
   * arbitrary — no file is wrong alone and nothing here can know which author meant what — so it
   * stays a disablement, as the fault nothing downstream catches. An <em>unreadable {@code scopes:}
   * entry</em> stays a disablement too, and for a reason the tool case does not share: a grant that
   * failed to parse is not a grant that was never issuable. Dropping it would serve an agent with
   * narrower access than its file describes, silently, which is the direction nothing reports; and
   * the scope vocabulary is one scope and two modes, so an invalid one is a typo rather than a
   * capability somebody saw offered elsewhere and reasonably reached for. {@code Grant.parseAll}'s
   * other refusal — two grants over one scope — is a fault about a pair with no non-arbitrary half
   * to drop, which is the cycle's argument again.
   *
   * <h2>The loudness has to survive</h2>
   *
   * <p>Abort-on-fail's real value is that it cannot be missed, and availability is bought here at
   * exactly that price — so the reporting carries the weight. Every disablement is logged at {@code
   * WARN} with its reason and is carried on {@link Loaded#disabled()} for the agents surface to
   * render as <em>disabled, because X</em>. <b>A silently missing agent is worse than a failed
   * boot</b>, because it is met at first use with no explanation.
   *
   * <p><b>A dropped item owes exactly the same debt and is the quietest of the three</b>, because
   * the agent looks fine. It is logged at {@code WARN} with the item named and carried on {@link
   * Loaded#withheldTools()} or {@link Loaded#withheldEdges()} beside the agent it belongs to, so
   * that "the agent cannot do what its prompt describes" is something an operator reads at boot
   * rather than infers from a run.
   *
   * @param required the agents this server's own code looks up by name. Too wide is loud and gets
   *     fixed; <b>too narrow is the silent direction</b> — an agent left out of it becomes a
   *     disablement nothing downstream can work around, met later as a write filed flat or a
   *     curator pass that judged nothing
   */
  public static Loaded read(Path dir, Set<String> knownTools, Set<String> required) {
    requireDirectory(dir);
    return read(new FilesystemDefinitions(dir), knownTools, required);
  }

  /**
   * The same reading, from a source that need not be a directory.
   *
   * <p>The {@code Path} overload above delegates here through {@link FilesystemDefinitions}, so
   * there is one loader and not two. What changed with the source is <b>what a refusal names</b>:
   * {@link DefinitionSource.Definition#origin} rather than a file, because the jar and a client's
   * socket have no path this server could print.
   */
  public static Loaded read(DefinitionSource source, Set<String> knownTools, Set<String> required) {
    Objects.requireNonNull(required, "required");
    return read(source, knownTools, Set.copyOf(required)::contains, Set.of());
  }

  /**
   * The same reading, with a set of names this source may call without defining — because some
   * other, already-validated set defines them.
   *
   * <p>Package-private for {@link DefinitionResolver}, which reads one project's own {@code
   * agents/}/{@code bots/} tier and has to let a definition in it {@code calls:} an agent the
   * <em>boot set</em> defines, not this tier. Without {@code alsoDefined} the unknown-callee check
   * below only ever sees this source's own names, so an edge to an inherited agent reads exactly
   * like a typo — a callee "no file in this directory defines" — and {@link
   * #withholdUnreachableCallees} would drop it silently. It affects only that one check: {@code
   * alsoDefined} names are never added to {@code enabled}, so the cycle and escalation walks below,
   * which iterate {@code enabled} and not {@code defined}, cannot cross into them — a fault that
   * only exists once this tier is actually merged with the set that defines those names is a fault
   * for the merged graph's own validation to find, not this one's.
   */
  static Loaded read(
      DefinitionSource source,
      Set<String> knownTools,
      Set<String> required,
      Set<String> alsoDefined) {
    Objects.requireNonNull(required, "required");
    return read(source, knownTools, Set.copyOf(required)::contains, Set.copyOf(alsoDefined));
  }

  private static Loaded read(
      DefinitionSource source, Set<String> knownTools, Predicate<String> required) {
    return read(source, knownTools, required, Set.of());
  }

  private static Loaded read(
      DefinitionSource source,
      Set<String> knownTools,
      Predicate<String> required,
      Set<String> alsoDefined) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(knownTools, "knownTools");

    Map<String, AgentDefinition> enabled = new LinkedHashMap<>();
    Map<String, String> disabled = new LinkedHashMap<>();
    Map<String, String> withheldEdges = new LinkedHashMap<>();
    Map<String, String> withheldTools = new LinkedHashMap<>();

    for (DefinitionSource.Definition entry : source.list()) {
      AgentDefinition definition;
      Map<String, String> droppedTools = new LinkedHashMap<>();
      try {
        definition = parse(entry, knownTools, droppedTools);
      } catch (IllegalStateException bad) {
        // The name from the source and not the frontmatter's, which is
        // exactly what a definition that failed to parse may not have.
        // AgentRegistry requires the two to be equal, so the source's
        // name is the identity for every entry that could have loaded —
        // and for one whose name key is the fault, it is the only name
        // an operator can act on anyway.
        String stem = entry.name();
        if (required.test(stem)) {
          throw bad;
        }
        disabled.put(stem, bad.getMessage());
        continue;
      }
      if (!droppedTools.isEmpty()) {
        if (required.test(definition.name())) {
          // The rung above: any fault on an agent this code depends on
          // is still the boot. A required agent quietly missing a tool
          // is exactly the silence abort-on-fail exists for -- it is
          // met later as a write filed flat or a fold that mined
          // nothing, with nothing naming a file.
          throw new IllegalStateException(droppedTools.values().iterator().next());
        }
        withheldTools.putAll(droppedTools);
        definition = withoutDelegationTheToolDropTook(definition, droppedTools, withheldEdges);
      }
      AgentDefinition clash = enabled.put(definition.name(), definition);
      if (clash != null) {
        // Unreachable while the name must equal the entry's name, since
        // two entries from one source cannot share a name. Kept because
        // that equality is the only thing making it so: relax the name
        // check and this becomes the silent case where one agent
        // replaces another and the graph is built on whichever sorted
        // last.
        throw new IllegalStateException(
            "two agents in " + source.describe() + " are both named '" + definition.name() + "'");
      }
    }

    // Capture alias addresses before disabling a malformed family. Calls to a known but
    // disabled alias stay inert grants, exactly like calls to disabled concrete definitions.
    List<Fault> aliasFaults =
        new TreeMap<>(AgentAliases.faults(enabled))
            .entrySet().stream().map(entry -> new Fault(entry.getKey(), entry.getValue())).toList();
    Set<String> aliasesDefined = AgentAliases.families(enabled).keySet();
    refuse(enabled, disabled, required, aliasFaults);
    Set<String> defined = new TreeSet<>(enabled.keySet());
    defined.addAll(aliasesDefined);
    defined.addAll(disabled.keySet());
    // Names this source may name without defining, because some other
    // already-validated set defines them -- see this overload's own
    // javadoc. Empty for every caller but DefinitionResolver.
    defined.addAll(alsoDefined);

    // Order as the strict loader's, and for its reasons. The item-shaped
    // faults on `calls:` come first, so that the two agent-shaped checks
    // below are asked about the graph as it will actually be served: an
    // edge that was dropped is not a route, and a "cycle" running through
    // one is not a cycle. The escalation stays last, and the cycle stays
    // before it -- grants inside a cycle must be equal, so a cyclic pair
    // whose grants differ is both faults at once, and reporting the
    // escalation would send an operator to fix grants only to meet the
    // cycle next boot.
    withheldEdges.putAll(withholdUnreachableCallees(enabled, defined, required));
    refuse(enabled, disabled, required, disagreeingHalves(enabled));
    refuse(enabled, disabled, required, cycles(enabled));
    withheldEdges.putAll(withholdEscalatingEdges(enabled, required));

    log.info(
        "Agent registry: {} agent(s) {} from {}",
        enabled.size(),
        enabled.keySet(),
        source.describe());
    for (Map.Entry<String, String> refused : new TreeMap<>(disabled).entrySet()) {
      log.warn(
          "The agent '{}' is DISABLED and will not be served, because: {}",
          refused.getKey(),
          refused.getValue());
    }
    for (Map.Entry<String, String> edge : new TreeMap<>(withheldEdges).entrySet()) {
      log.warn(
          "The delegation '{}' is WITHHELD; the caller is served and that route is"
              + " not, because: {}",
          edge.getKey(),
          edge.getValue());
    }
    // As loud as a disablement, which is the whole of what this rung has to
    // pay: a dropped item that was merely absent from the definition would
    // be met at first use as an agent that cannot do what its prompt says,
    // with nothing anywhere naming the line.
    for (Map.Entry<String, String> tool : new TreeMap<>(withheldTools).entrySet()) {
      log.warn(
          "The tool '{}' is DROPPED; that agent is served without it, because: {}",
          tool.getKey(),
          tool.getValue());
    }
    return new Loaded(enabled, disabled, withheldEdges, withheldTools);
  }

  /**
   * The other half of a dropped {@code agent_run}, and the second place an item drop empties
   * something that then needs its own remedy.
   *
   * <p>{@link JobRuntime#knownTools()} names {@code agent_run} exactly when the runtime was handed
   * a graph, so a runtime that cannot delegate drops the name from every definition that asked for
   * it — and leaves {@code calls:} behind with no way to reach any of it. That is precisely the
   * shape {@link #disagreeingHalves} disables an agent for, so without this the item drop would
   * cascade into a disablement, which is the outcome this whole rung exists to prevent.
   *
   * <p>It is {@link #withoutCallees}' argument run backwards. That method applies the loader's own
   * stated remedy — "either list the callees or drop the tool" — from the callee end when the last
   * route goes; this one applies it from the tool end, and the names go with the tool for the same
   * reason the tool goes with the names.
   *
   * <p>Unreachable through {@code AgentsConfig}, which always hands {@link JobRuntime} a registry
   * supplier and so always has {@code agent_run} in the set. It is reachable through this class's
   * own public doors, which take whatever {@code knownTools} a caller passes, and a rule that held
   * only for one caller would not be a rule.
   */
  private static AgentDefinition withoutDelegationTheToolDropTook(
      AgentDefinition definition,
      Map<String, String> droppedTools,
      Map<String, String> withheldEdges) {

    if (!droppedTools.containsKey(definition.name() + ": " + AGENT_RUN)
        || definition.calls().isEmpty()) {
      return definition;
    }
    for (String callee : definition.calls()) {
      withheldEdges.put(
          definition.name() + " -> " + callee,
          "the agent '"
              + definition.name()
              + "' calls '"
              + callee
              + "', and its '"
              + AGENT_RUN
              + "' was dropped from 'tools' because this runtime binds"
              + " no delegation, so it has no way to reach any callee. The names"
              + " go with the tool rather than leaving a definition that says it"
              + " delegates and cannot");
    }
    return withoutCallees(definition, List.of());
  }

  /**
   * Applies one check's findings: fatal for the agents the code depends on, a disablement for the
   * rest.
   *
   * <p>Each check hands back {@link Fault}s rather than throwing, which is the shape that lets one
   * body of rules serve both doors — the strict {@link #load}, where every name is required and the
   * first fault is a boot failure, and a boot, where most are not. A check written to throw could
   * only ever do the first.
   */
  private static void refuse(
      Map<String, AgentDefinition> enabled,
      Map<String, String> disabled,
      Predicate<String> required,
      List<Fault> faults) {

    for (Fault fault : faults) {
      if (required.test(fault.agent())) {
        throw new IllegalStateException(fault.reason());
      }
      // Only if it was still there: a cycle names each of its members
      // once, but two independent faults can land on one agent and the
      // first reason is the one an operator should see.
      if (enabled.remove(fault.agent()) != null) {
        disabled.put(fault.agent(), fault.reason());
      }
    }
  }

  /**
   * The directory-existence half of the old {@code agentFiles} check, kept on the {@code Path}
   * doors rather than folded into {@link FilesystemDefinitions}.
   *
   * <p>{@link FilesystemDefinitions#list} answers an absent directory with an empty list rather
   * than a refusal — right for a tier nobody configured, which is a shape later callers need. It is
   * wrong for {@link #read(Path, Set, Set)} and {@link #load}, whose callers write a directory they
   * believe holds this server's own definitions: a missing or mistyped path there is a
   * configuration mistake, and reading it as "zero agents" would turn a typo into a silent empty
   * registry instead of the boot refusal an operator can act on.
   */
  private static void requireDirectory(Path dir) {
    Objects.requireNonNull(dir, "dir");
    if (!Files.isDirectory(dir)) {
      throw new IllegalStateException(
          "the agent directory "
              + dir
              + " does not exist, or is not a directory:"
              + " an agent is a file, and the server has none to read");
    }
  }

  // ------------------------------------------------------------------
  // One definition
  // ------------------------------------------------------------------

  /**
   * One definition, with any tool name this runtime does not bind taken out of it and reported
   * instead.
   *
   * @param dropped receives one entry per tool dropped, keyed {@code "agent: tool"} and valued with
   *     the sentence naming the definition and the name. The definition this returns is the entry
   *     minus those, so a caller that ignores the map is serving an agent quietly missing part of
   *     its declaration — which is why {@link #read} is the only caller and looks at it
   */
  private static AgentDefinition parse(
      DefinitionSource.Definition entry, Set<String> knownTools, Map<String, String> dropped) {
    return parseWith(entry, knownTools, dropped, Set.of(), Set.of()).definition();
  }

  static Parsed parseWith(
      DefinitionSource.Definition entry,
      Set<String> knownTools,
      Map<String, String> dropped,
      Set<String> extraKeys,
      Set<String> refusedKeys) {
    if (!Collections.disjoint(extraKeys, KNOWN_KEYS)) {
      Set<String> clash = new TreeSet<>(extraKeys);
      clash.retainAll(KNOWN_KEYS);
      throw new IllegalArgumentException("extra keys may not be agent keys: " + clash);
    }
    String text = entry.text();

    // A leading byte-order mark is dropped rather than refused. Measured:
    // Character.isWhitespace('\uFEFF') is false, so strip() leaves it and
    // readString hands it back as the first character — a file that visually
    // opens with --- would fail the fence check below and be told it "does
    // not open with a '---' frontmatter fence", which points at nothing the
    // operator can see. Windows tools emit one by default; PowerShell's
    // Out-File does. The mark is an encoding artefact, not content.
    if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
      text = text.substring(1);
    }

    // String.lines() splits on \n, \r\n and a lone \r alike, which is what
    // makes the fence test independent of how the file was saved. Measured:
    // snakeyaml parses a CRLF frontmatter body fine either way, so this is
    // about finding the fence, not about the YAML.
    List<String> lines = text.lines().toList();
    if (lines.isEmpty() || !FENCE.equals(lines.get(0).strip())) {
      throw new IllegalStateException(
          refusal(entry)
              + " does not open with a '---' frontmatter fence. Every "
              + SUFFIX
              + " file in the agent directory is an agent; skipping this one silently would"
              + " mean an agent whose fence was mistyped just ceases to exist, and a caller"
              + " that names it gets 'no such agent' instead of the real fault");
    }
    int close = -1;
    for (int i = 1; i < lines.size(); i++) {
      if (FENCE.equals(lines.get(i).strip())) {
        close = i;
        break;
      }
    }
    if (close < 0) {
      throw new IllegalStateException(
          refusal(entry)
              + " never closes its frontmatter: there is no second '---' line."
              + " Everything after the opening fence would be read as YAML");
    }

    // The first closing fence wins, so a body containing a '---' rule of its
    // own stays in the prompt rather than truncating it.
    String frontmatter = String.join("\n", lines.subList(1, close));
    String prompt = String.join("\n", lines.subList(close + 1, lines.size())).strip();

    Map<String, Object> keys = frontmatterOf(entry, frontmatter, extraKeys, refusedKeys);
    Map<String, Object> extras = new LinkedHashMap<>();
    for (String extra : extraKeys) {
      if (keys.containsKey(extra)) {
        extras.put(extra, keys.remove(extra));
      }
    }

    String name = requireString(entry, keys, NAME);
    String stem = entry.name();
    // Compared in NFC, because the two sides come from different places: the
    // frontmatter is bytes in the file, the stem is whatever the filesystem
    // hands back from readdir. Measured on APFS: a name written NFC comes
    // back NFC, and a path in either form resolves to the same file — but
    // that is a property of this filesystem, and on one that answers in NFD
    // a raw comparison would refuse a file over a difference invisible in
    // the refusal message, which is the same diagnostic dead end the BOM
    // case above avoids.
    if (!Normalizer.normalize(name, Normalizer.Form.NFC)
        .equals(Normalizer.normalize(stem, Normalizer.Form.NFC))) {
      throw new IllegalStateException(
          refusal(entry)
              + " is named '"
              + name
              + "' in its frontmatter but '"
              + stem
              + "' by its file."
              + " The frontmatter is the identity, so one of the two is a typo and the call"
              + " graph would be built on the wrong node. Make them agree");
    }
    if (prompt.isEmpty()) {
      throw new IllegalStateException(
          refusal(entry)
              + " has an empty body. A promptless agent is a model call with no"
              + " instructions: it will do something, which is worse than failing."
              + " Note that an empty 'tools' list is a different thing and is allowed");
    }

    boolean dynamic = requireBoolean(entry, keys, "dynamic", false);
    List<String> tools = new ArrayList<>();
    for (String tool : requireStringList(entry, keys, TOOLS)) {
      if (BoardTools.NAMES.contains(tool)) {
        throw new IllegalStateException(
            refusal(entry)
                + " lists '"
                + tool
                + "' in 'tools', but it is a harness tool granted by a board seat or"
                + " by 'board: true', never declared in 'tools'");
      }
      // BEFORE the knownTools check and not after: memory_write IS bound for
      // agents now, so a check that ran second would never be reached.
      if (AUTHORS_A_MEMORY.contains(tool) && THE_MEMORY_PIPELINE.contains(name)) {
        dropped.put(name + ": " + tool, mayNotAuthor(entry, tool));
        continue;
      }
      if (knownTools.contains(tool)
          || (dynamic && tool.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*") && tool.length() <= 64)) {
        tools.add(tool);
        continue;
      }
      // Dropped rather than thrown, and the caller decides what that
      // costs. See withheldTool for why an item nobody could have issued
      // must not cost an agent, and read() for the one caller that still
      // turns this into a refusal.
      dropped.put(name + ": " + tool, withheldTool(entry, tool, knownTools));
    }

    List<String> calls = requireStringList(entry, keys, CALLS);
    String reviewWith = optionalString(entry, keys, REVIEW_WITH);
    if (reviewWith != null && !calls.contains(reviewWith)) {
      throw new IllegalStateException(
          refusal(entry)
              + " names '"
              + reviewWith
              + "' in 'review-with' but does not grant that agent in 'calls'. A mandatory"
              + " reviewer must be a validated edge in the call graph");
    }

    boolean board = requireBoolean(entry, keys, BOARD, false);
    boolean bot = requireBoolean(entry, keys, BOT, false);
    String alias = optionalString(entry, keys, "alias");
    String guidance = optionalString(entry, keys, "guidance");
    try {
      AgentAliases.validateSelection(name, alias, guidance, bot);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(refusal(entry) + ": " + invalid.getMessage(), invalid);
    }

    return new Parsed(
        new AgentDefinition(
            name,
            requireString(entry, keys, DESCRIPTION),
            requireString(entry, keys, MODEL),
            requireIntent(entry, keys),
            requireOverrides(entry, keys, name),
            tools,
            calls,
            requireGrants(entry, keys),
            requirePositiveInt(entry, keys, MAX_TURNS),
            requirePositiveInt(entry, keys, MAX_MODEL_CALLS),
            prompt,
            requireBoolean(entry, keys, EXPORTED, false),
            requireBoolean(entry, keys, DELEGABLE, true),
            requireBoolean(entry, keys, VISION, false),
            requireBoolean(entry, keys, BOT, false),
            requireBoolean(entry, keys, ANNOUNCES_INBOX, false),
            requireFallback(entry, keys),
            requireStringList(entry, keys, ORCHESTRATIONS),
            reviewWith,
            board,
            requireStringList(entry, keys, SKILLS),
            optionalString(entry, keys, "display-name"),
            entry.origin(),
            alias,
            guidance,
            dynamic),
        Collections.unmodifiableMap(new LinkedHashMap<>(extras)));
  }

  /**
   * Parses the frontmatter to a map of recognised keys.
   *
   * <p><b>Two snakeyaml behaviours here were measured against 2.2 on this branch, not assumed.</b>
   * First, with default {@code LoaderOptions} a repeated key is accepted and the <em>last</em>
   * occurrence wins in silence — a file listing {@code tools:} twice would load as whichever half
   * sits lower — so {@code setAllowDuplicateKeys(false)} is what turns that into the {@code
   * DuplicateKeyException} caught below. Second, {@code SafeConstructor} refuses a global tag such
   * as {@code !!java.net.URL}; so, as it happens, does a plain {@code new Yaml()} in 2.x, which is
   * a change from the 1.x behaviour every write-up about YAML deserialization describes. It is
   * named explicitly anyway, because relying on a default that used to be the other way is relying
   * on a default nobody can see.
   */
  private static Map<String, Object> frontmatterOf(
      DefinitionSource.Definition entry,
      String frontmatter,
      Set<String> extraKeys,
      Set<String> refusedKeys) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object loaded;
    try {
      loaded = new Yaml(new SafeConstructor(options)).load(frontmatter);
    } catch (YAMLException malformed) {
      throw new IllegalStateException(
          refusal(entry) + " has frontmatter that is not valid YAML: " + malformed.getMessage(),
          malformed);
    }
    if (!(loaded instanceof Map<?, ?> raw)) {
      throw new IllegalStateException(
          refusal(entry)
              + " has no frontmatter keys between its fences; it needs at least "
              + new TreeSet<>(List.of(NAME, DESCRIPTION, MODEL, MAX_TURNS, MAX_MODEL_CALLS)));
    }

    // Copied entry by entry rather than cast. A cast would be an unchecked
    // warning, which this build fails on, and it would also be a lie: YAML
    // keys are not necessarily strings, and `1: x` binds an Integer key.
    Map<String, Object> keys = new LinkedHashMap<>();
    for (Map.Entry<?, ?> pair : raw.entrySet()) {
      if (!(pair.getKey() instanceof String key)) {
        throw new IllegalStateException(
            refusal(entry) + " has the non-text frontmatter key '" + pair.getKey() + "'");
      }
      if (refusedKeys.contains(key)) {
        throw new IllegalStateException(
            refusal(entry)
                + " has the frontmatter key '"
                + key
                + "', which this kind of definition does not take");
      }
      if (!KNOWN_KEYS.contains(key) && !extraKeys.contains(key)) {
        Set<String> allowed = new TreeSet<>(KNOWN_KEYS);
        allowed.addAll(extraKeys);
        allowed.removeAll(refusedKeys);
        throw new IllegalStateException(
            refusal(entry)
                + " has the unrecognised frontmatter key '"
                + key
                + "'. The keys are "
                + allowed
                + ", kebab-case as in application.yml."
                + " An unrecognised key is refused rather than ignored because ignoring"
                + " it makes a mistyped 'tools' into an agent that starts, runs, and can"
                + " never do its job");
      }
      keys.put(key, pair.getValue());
    }
    return keys;
  }

  private static String requireString(
      DefinitionSource.Definition entry, Map<String, Object> keys, String key) {
    Object value = require(entry, keys, key);
    // Measured: snakeyaml resolves an unquoted 3.5 to a Double and an
    // unquoted `yes` to Boolean.TRUE. toString() on either would hand the
    // dispatcher a specifier nobody wrote — "true" for an agent named yes —
    // so the type is checked where the value was written instead.
    if (!(value instanceof String text)) {
      throw new IllegalStateException(
          refusal(entry)
              + " has '"
              + key
              + ": "
              + value
              + "', which YAML reads as a "
              + value.getClass().getSimpleName()
              + " rather than text. Quote it");
    }
    if (text.isBlank()) {
      throw new IllegalStateException(refusal(entry) + " has a blank '" + key + "'");
    }
    return text;
  }

  private static String optionalString(
      DefinitionSource.Definition entry, Map<String, Object> keys, String key) {
    if (!keys.containsKey(key)) {
      return null;
    }
    Object value = keys.remove(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalStateException(
          refusal(entry) + " has '" + key + "' but it is not a non-blank string");
    }
    return text;
  }

  private static List<String> requireStringList(
      DefinitionSource.Definition entry, Map<String, Object> keys, String key) {
    if (!keys.containsKey(key)) {
      // Absent means empty, and that is legal for both list keys: the
      // scribe has no tools by design, and a leaf agent calls nobody.
      // A misspelled key cannot hide in this leniency, because an
      // unrecognised key was already refused above.
      return List.of();
    }
    Object value = require(entry, keys, key);
    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException(
          refusal(entry) + " has a '" + key + "' that is not a list; write it as [a, b] or []");
    }
    List<String> items = new ArrayList<>(list.size());
    for (Object item : list) {
      // Measured against snakeyaml 2.2: `[workspace:read]` is a list of
      // one string and `[workspace: read]` is a list of one *map*,
      // because in flow context a colon followed by a space opens a
      // mapping. The generic refusal below is true of that map and useless
      // to whoever wrote it, since what they typed looks exactly like a
      // name; `scopes:` is where the mistake is natural, no tool or agent
      // name having a colon in it, and this is the one entry shape whose
      // cause a message can point at.
      if (item instanceof Map<?, ?>) {
        throw new IllegalStateException(
            refusal(entry)
                + " has the entry '"
                + item
                + "' in '"
                + key
                + "', which YAML read as a"
                + " mapping rather than as a name: a colon followed by a space starts"
                + " one. Remove the space, or quote the whole entry");
      }
      // The isBlank half of this condition is masked and no test
      // distinguishes it: a blank entry in `tools` is refused a few lines
      // later as an unknown tool, and a blank entry in `calls` as an
      // unknown callee. It stays because it is this method's job to say
      // the entry is not a name, and because both maskers are guards that
      // could reasonably move; the instanceof half is the load-bearing
      // one, and a_null_entry_in_a_tool_list_is_refused pins it.
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalStateException(
            refusal(entry) + " has the entry '" + item + "' in '" + key + "', which is not a name");
      }
      items.add(text);
    }
    return items;
  }

  /**
   * The grants a definition declares, parsed at load into the vocabulary the file layer already
   * speaks.
   *
   * <p>Absent means none, exactly as {@code tools:} and {@code calls:} do — every agent shipped
   * before this key existed declares no grants and reaches no file, which is the right reading of a
   * file that says nothing.
   *
   * <p>{@link Grant#parseAll} owns the spelling, the duplicate rule and every refusal either can
   * produce; this method owns only the sentence naming the file, which is what turns a complaint
   * about a string into one an operator can act on. The duplicate rule sat here once and did not
   * belong: it is a fact about a list of grants, and a caller assembling one in Java never met it.
   *
   * <p>A repeated {@code scopes:} <em>key</em> never reaches any of this, because snakeyaml is
   * configured to refuse one — {@code frontmatterOf} says so and {@code a_duplicate_key_is_refused}
   * holds it. What arrives here is one list.
   */
  private static List<Grant> requireGrants(
      DefinitionSource.Definition entry, Map<String, Object> keys) {
    try {
      return Grant.parseAll(requireStringList(entry, keys, SCOPES));
    } catch (IllegalArgumentException unreadable) {
      throw new IllegalStateException(
          refusal(entry) + " cannot be read for its '" + SCOPES + "': " + unreadable.getMessage(),
          unreadable);
    }
  }

  private static int requirePositiveInt(
      DefinitionSource.Definition entry, Map<String, Object> keys, String key) {
    Object value = require(entry, keys, key);
    // Integer and not Number: snakeyaml resolves an integer too large for an
    // int to a BigInteger and 4.0 to a Double, and intValue() on either
    // would quietly invent a budget. A budget is a number an operator has to
    // be able to reason about, so it is the number they wrote or nothing.
    if (!(value instanceof Integer number)) {
      throw new IllegalStateException(
          refusal(entry) + " has '" + key + ": " + value + "', which is not a whole number");
    }
    if (number <= 0) {
      throw new IllegalStateException(
          refusal(entry)
              + " has '"
              + key
              + ": "
              + number
              + "'. A budget of zero or less is an agent"
              + " that can never run, which is a configuration nobody meant to write");
    }
    return number;
  }

  /**
   * A declared sampling temperature, or what an absent key means.
   *
   * <h2>The range is {@code ChatRequest}'s, quoted rather than re-decided</h2>
   *
   * <p>Finite, and otherwise unrestricted. That is not this method's judgement — it is {@code
   * ChatRequest}'s, which argues it: "backends disagree about the ceiling and llama.cpp reads a
   * temperature at or below zero as greedy sampling rather than as an error. Refusing a value the
   * local server would have honoured is the worse of the two mistakes." A narrower range here would
   * be a second answer to a question already answered once, and an operator would meet whichever
   * layer they happened to hit first. So a negative temperature and a large one both load, and a
   * value this loader accepts is a value the request accepts.
   *
   * <p>Non-finite is refused <em>here</em> rather than left to that constructor, because the two
   * failures are not the same event. A file read at boot can name the file and the key; the same
   * value reaching {@code ChatRequest} arrives inside a run, from a definition nothing in the stack
   * trace names. YAML has {@code .inf} and {@code .nan} and JSON has neither, so this is a shape
   * somebody can actually write.
   *
   * <h2>{@link Number} and not {@link Integer}, which is the opposite of {@link
   * #requirePositiveInt}</h2>
   *
   * <p>That method takes {@code Integer} and nothing else, because {@code max-turns: 4.0} is not a
   * whole number and {@code intValue()} would quietly invent a budget. Here the direction reverses:
   * {@code temperature: 1} is an exact temperature, snakeyaml resolves it to {@code Integer}, and
   * {@code doubleValue()} loses nothing — so demanding a decimal point would be the parser refusing
   * a correct file for a reason its own refusal could not explain. What is still refused is a value
   * that is not a number at all: {@code temperature: "0.3"} resolves to {@link String}, which is
   * {@link #requireBoolean}'s lesson — a quoted or misspelt value must not be read as the default,
   * or a pair of quotes silently runs an agent at zero.
   *
   * <h2>A refusal, and which rung that is</h2>
   *
   * <p>Throwing puts this on the third rung: the agent is <b>disabled</b> and named, or the boot
   * stops if the code depends on it. It is not the item-drop rung and there is no fourth. A
   * temperature is not item-shaped — there is no remainder to serve without — and the {@code
   * scopes:} precedent settles the rest: an unreadable grant stays a disablement "because a grant
   * that failed to parse is not a grant that was never issuable". Neither is a temperature.
   * Dropping it would serve an agent sampling at a value its own file contradicts, which is the
   * same silent narrowing in the other direction.
   */
  private static Sampling requireOverrides(
      DefinitionSource.Definition entry, Map<String, Object> keys, String name) {
    Sampling overrides = requireSchema(entry, keys, name);
    if (!keys.containsKey(TEMPERATURE)) {
      return overrides;
    }
    // require(), so `temperature:` with nothing after it is the `max-turns:
    // ~` refusal rather than the default. A written key with no value is a
    // half-finished edit, and reading it as absent would make the one thing
    // the operator can see mean nothing.
    Object value = require(entry, keys, TEMPERATURE);
    if (!(value instanceof Number number)) {
      throw new IllegalStateException(
          refusal(entry)
              + " has '"
              + TEMPERATURE
              + ": "
              + value
              + "', which is not a number. Write"
              + " it unquoted: a quoted or misspelt value reads as text, and this agent"
              + " would otherwise be served at whatever its model's profile resolves"
              + " while its file says otherwise");
    }
    double temperature = number.doubleValue();
    if (!Double.isFinite(temperature)) {
      throw new IllegalStateException(
          refusal(entry)
              + " has '"
              + TEMPERATURE
              + ": "
              + value
              + "', which is not a finite number."
              + " YAML has '.inf' and '.nan' and the JSON sent to the endpoint has"
              + " neither, so the call would be refused by the model host as a type error"
              + " naming a field nobody here wrote");
    }
    // Only the temperature, and only when the key is there. An override
    // states what it means to move and nothing else: a file naming a
    // temperature must not erase the profile's top_p and top_k underneath
    // it, because a temperature separated from the truncation it was
    // recommended beside is a different configuration and not a smaller
    // change. Sampling.overriddenBy is what enforces that, field by field.
    return overrides.withTemperature(temperature);
  }

  /**
   * The {@code schema:} block, which is what makes an agent answerable in a shape rather than in
   * prose.
   *
   * <h2>The schema is named after the agent, and there is no second name</h2>
   *
   * <p>The wire wants {@code json_schema.name}. It could have been a {@code name:} inside the
   * block, and then one agent would have had two names — its own and its contract's — with nothing
   * keeping them related and a log line about a refused call naming whichever one the operator did
   * not search for. An agent is one microservice with one contract, so it is one name.
   *
   * <p>The cost is stated rather than discovered: <b>an agent whose name is not a legal schema name
   * cannot carry a schema</b>, and that is a refusal at boot naming both. Agent names are file
   * stems, so this is reachable only by a file with a space or a dot in its name, and the refusal
   * says which character.
   *
   * <h2>The root must be an object, and that is the endpoint's rule not this one</h2>
   *
   * <p>Structured output constrains a sampler to produce one JSON document. A root of {@code type:
   * array} or a bare {@code type: string} is refused by the endpoints this project targets, and a
   * file that shipped one would be an agent that loads, runs, and fails on its first call with a
   * message about a document nobody here wrote. It is refused where the file is read.
   *
   * <h2>A refusal and not a dropped item</h2>
   *
   * <p>{@code requireOverrides}' rung, for its reason. A schema is not item-shaped: there is no
   * remainder to serve without. Serving an agent with its contract quietly removed would be an
   * agent whose callers parse JSON out of prose, which is the state the whole feature exists to
   * end.
   */
  private static Sampling requireSchema(
      DefinitionSource.Definition entry, Map<String, Object> keys, String name) {
    if (!keys.containsKey(SCHEMA)) {
      return Sampling.NONE;
    }
    // require(), so `schema:` with nothing after it is a refusal rather than
    // the default -- a half-finished edit must not read as "no contract".
    Object value = require(entry, keys, SCHEMA);
    if (!(value instanceof Map<?, ?> raw)) {
      throw new IllegalStateException(
          refusal(entry)
              + " has a '"
              + SCHEMA
              + "' that is not a block of keys but a "
              + value.getClass().getSimpleName()
              + ". It is the JSON Schema this agent"
              + " answers in, written as YAML: a 'type: object' with 'properties' under"
              + " it");
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    for (Map.Entry<?, ?> pair : raw.entrySet()) {
      if (!(pair.getKey() instanceof String key)) {
        throw new IllegalStateException(
            refusal(entry)
                + " has the non-text key '"
                + pair.getKey()
                + "' in its '"
                + SCHEMA
                + "'. JSON object keys are strings, so a YAML key that is not one is a"
                + " document that cannot be sent");
      }
      schema.put(key, pair.getValue());
    }
    if (!"object".equals(schema.get("type"))) {
      throw new IllegalStateException(
          refusal(entry)
              + " has a '"
              + SCHEMA
              + "' whose root is '"
              + schema.get("type")
              + "' rather than 'object'. Structured output constrains the sampler to one"
              + " JSON document, and the endpoints this project targets refuse a root"
              + " that is not an object -- so an agent shipping one would load, run, and"
              + " fail on its first call");
    }
    try {
      return Sampling.NONE.withResponseFormat(JsonSchema.from(name, schema));
    } catch (IllegalArgumentException unusable) {
      throw new IllegalStateException(
          refusal(entry)
              + " has a '"
              + SCHEMA
              + "' this server cannot send: "
              + unusable.getMessage()
              + ". The schema is named after the agent, so an agent that carries one"
              + " needs a name the wire will take",
          unusable);
    }
  }

  /**
   * A declared sampling intent, or what an absent key means.
   *
   * <h2>Three names, and a fourth is a refusal</h2>
   *
   * <p>{@code precise}, {@code balanced}, {@code exploratory}. The vocabulary is closed because it
   * has to be resolvable against a profile written by somebody who has never read this agent's
   * file: a name no profile can carry an entry for is a declaration that could never be honoured,
   * and reading it as the default would be the invisible-fact failure this whole design exists to
   * end.
   *
   * <p><b>An unparseable value is a disablement and not a dropped item</b>, which is the {@code
   * scopes:} precedent stated once more. There is no remainder to serve without — an intent is not
   * item-shaped — and serving the agent at {@code balanced} while its file says {@code presice}
   * would be exactly the silent narrowing that made a closed {@link #KNOWN_KEYS} worth having. Same
   * rung as {@code requireOverrides}: the agent is named and disabled, or the boot stops if the set
   * requires it.
   *
   * <p>{@link #require} rather than a {@code containsKey} test on the value, so {@code sampling:}
   * with nothing after it is the {@code max-turns: ~} refusal rather than the default. A written
   * key with no value is a half-finished edit.
   */
  private static Sampling.Intent requireIntent(
      DefinitionSource.Definition entry, Map<String, Object> keys) {
    if (!keys.containsKey(SAMPLING)) {
      return AgentDefinition.DEFAULT_INTENT;
    }
    Object value = require(entry, keys, SAMPLING);
    if (value instanceof String declared) {
      for (Sampling.Intent intent : Sampling.Intent.values()) {
        if (intent.declared().equals(declared.strip())) {
          return intent;
        }
      }
    }
    StringBuilder known = new StringBuilder();
    for (Sampling.Intent intent : Sampling.Intent.values()) {
      known.append(known.isEmpty() ? "" : ", ").append(intent.declared());
    }
    throw new IllegalStateException(
        refusal(entry)
            + " has '"
            + SAMPLING
            + ": "
            + value
            + "', which is not one of "
            + known
            + ". This key says what the TASK needs, and a per-model profile turns it into"
            + " numbers; a name no profile has an entry for is a declaration nothing could"
            + " ever honour, so it is refused here rather than served as '"
            + AgentDefinition.DEFAULT_INTENT.declared()
            + "' by a file that says otherwise");
  }

  /**
   * A declared yes-or-no, or what an absent key means.
   *
   * <p><b>Written out rather than read through {@code Boolean.parseBoolean}, which is the whole of
   * this method's value.</b> That method answers {@code false} for every string that is not {@code
   * "true"} — so {@code exported: ture}, {@code exported: "yes"} and {@code exported: 1} would all
   * load as an agent quietly closed to the world, which is the direction nothing reports. Measured
   * against snakeyaml 2.2: an unquoted {@code yes}, {@code on} and {@code true} all resolve to
   * {@link Boolean} and every quoted form resolves to {@link String}, so the type check here is
   * what separates a value somebody wrote from a value somebody nearly wrote.
   *
   * <p>{@code defaultValue} rather than a refusal for an absent key, and the two keys that use this
   * pass opposite ones: see {@link AgentDefinition}'s javadoc, which owns why a grant defaults
   * closed and a refusal defaults open.
   */
  /**
   * A declared fallback, or {@link AgentDefinition.Fallback#NONE} for an agent that names none.
   *
   * <p><b>{@code when} and not {@code on}</b>, and that is YAML's decision rather than a
   * preference: snakeyaml reads YAML 1.1, where an unquoted {@code on} is the boolean {@code true},
   * so an {@code on:} key arrives as {@code Boolean.TRUE} and a closed-set check would refuse it as
   * a key called "true". The refusal names this, because the spelling is natural.
   *
   * <p><b>Closed, like the frontmatter itself</b>: {@code when}, {@code model} and {@code
   * max-attempts}, each required once the block exists. A misspelt {@code modle:} silently ignored
   * would leave an agent its author believes is rerouted running without a fallback, and a misspelt
   * trigger would do the same one level down.
   *
   * <p><b>{@code max-attempts} must say 1.</b> One attempt is what the runtime implements, and a
   * file asking for three would otherwise be granted one in silence. Written out anyway, rather
   * than defaulted, so a later runtime that honours more has a key that already means what it says.
   *
   * <p>Whether a pool serves {@code model} is not this method's question, for the reason it is not
   * for the agent's own {@code model:}: {@code AgentsConfig} holds the dispatcher and asks it at
   * boot.
   */
  private static AgentDefinition.Fallback requireFallback(
      DefinitionSource.Definition entry, Map<String, Object> keys) {
    if (!keys.containsKey(FALLBACK)) {
      return AgentDefinition.Fallback.NONE;
    }
    Object value = require(entry, keys, FALLBACK);
    if (!(value instanceof Map<?, ?> raw)) {
      throw new IllegalStateException(
          refusal(entry)
              + " has a '"
              + FALLBACK
              + "' that is not a mapping; write it as '"
              + FALLBACK
              + ":' followed by indented '"
              + FALLBACK_ON
              + ": [refusal]', '"
              + FALLBACK_MODEL
              + ": <class>' and '"
              + FALLBACK_MAX_ATTEMPTS
              + ": 1'");
    }
    Map<String, Object> block = new java.util.LinkedHashMap<>();
    for (Map.Entry<?, ?> pair : raw.entrySet()) {
      if (Boolean.TRUE.equals(pair.getKey())) {
        throw new IllegalStateException(
            refusal(entry)
                + " has 'on:' under '"
                + FALLBACK
                + "', which YAML reads as the boolean"
                + " true rather than as a key. Write '"
                + FALLBACK_ON
                + ":' instead");
      }
      String key = String.valueOf(pair.getKey());
      if (!Set.of(FALLBACK_ON, FALLBACK_MODEL, FALLBACK_MAX_ATTEMPTS).contains(key)) {
        throw new IllegalStateException(
            refusal(entry)
                + " has the unknown key '"
                + key
                + "' under '"
                + FALLBACK
                + "'. It takes '"
                + FALLBACK_ON
                + "', '"
                + FALLBACK_MODEL
                + "' and '"
                + FALLBACK_MAX_ATTEMPTS
                + "'");
      }
      block.put(key, pair.getValue());
    }
    List<String> named = requireStringList(entry, block, FALLBACK_ON);
    if (named.isEmpty()) {
      throw new IllegalStateException(
          refusal(entry)
              + " has a '"
              + FALLBACK
              + "' that nothing triggers: its '"
              + FALLBACK_ON
              + "' is empty. Remove the block, or say what it is for");
    }
    Set<AgentDefinition.Fallback.Trigger> on = new java.util.LinkedHashSet<>();
    for (String name : named) {
      AgentDefinition.Fallback.Trigger trigger =
          java.util.Arrays.stream(AgentDefinition.Fallback.Trigger.values())
              .filter(known -> known.key().equals(name))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          refusal(entry)
                              + " falls back on '"
                              + name
                              + "', which is not a trigger this"
                              + " server knows. It knows "
                              + java.util.Arrays.stream(AgentDefinition.Fallback.Trigger.values())
                                  .map(AgentDefinition.Fallback.Trigger::key)
                                  .toList()));
      on.add(trigger);
    }
    String model = requireString(entry, block, FALLBACK_MODEL);
    int attempts = requirePositiveInt(entry, block, FALLBACK_MAX_ATTEMPTS);
    if (attempts != 1) {
      throw new IllegalStateException(
          refusal(entry)
              + " has '"
              + FALLBACK
              + "."
              + FALLBACK_MAX_ATTEMPTS
              + ": "
              + attempts
              + "'. One attempt is what this server implements: a fallback is not"
              + " rerouted again, so any other number would be granted as 1 without"
              + " saying so");
    }
    return new AgentDefinition.Fallback(on, model, attempts, Sampling.NONE);
  }

  private static boolean requireBoolean(
      DefinitionSource.Definition entry,
      Map<String, Object> keys,
      String key,
      boolean defaultValue) {
    if (!keys.containsKey(key)) {
      return defaultValue;
    }
    Object value = require(entry, keys, key);
    if (!(value instanceof Boolean flag)) {
      throw new IllegalStateException(
          refusal(entry)
              + " has '"
              + key
              + ": "
              + value
              + "', which is not true or false. Write it"
              + " unquoted: a quoted or misspelt value reads as text, and text that is not"
              + " the word true would silently mean false");
    }
    return flag;
  }

  private static Object require(
      DefinitionSource.Definition entry, Map<String, Object> keys, String key) {
    if (!keys.containsKey(key)) {
      throw new IllegalStateException(
          refusal(entry) + " is missing the required frontmatter key '" + key + "'");
    }
    Object value = keys.get(key);
    if (value == null) {
      // `max-turns:` with nothing after it binds a null, the same shape
      // LlmConfig has to name for `fast: ~` in plowshare.llm.classes.
      // (That key was per pool until 2026-09-07; the shape of the fault
      // and the reason it needs naming are unchanged by the move.)
      throw new IllegalStateException(
          refusal(entry) + " has the key '" + key + "' written with no value");
    }
    return value;
  }

  private static String refusal(DefinitionSource.Definition entry) {
    return "the agent definition '" + entry.name() + "' (" + entry.origin() + ")";
  }

  /**
   * Why one tool name is being taken out of a definition, in the two flavours the mistake comes in.
   *
   * <h2>Why dropping the name is safe, and the same sentence for both</h2>
   *
   * <p><b>The grant was never issuable.</b> {@code knownTools()} is derived from the tools the
   * layer actually registered at this boot, so a name outside it is a schema that is never built
   * and never offered — {@code JobRuntime.offeredTo} can only hand the model what it holds. The
   * model's reach is byte-for-byte the same whether this definition is served or refused. What
   * dropping it changes is only whether one bad line costs somebody their whole bot, and bots are
   * user-authored: the disable rule exists precisely so that a stranger's typo cannot stop the
   * server, and refusing a whole agent over an item it could never have used is the same mistake
   * one rung further down.
   *
   * <p>The sentence says what is wrong and what it asked for and does not say what it cost, on the
   * same convention {@link Loaded}'s values already use: the disposition — dropped and served, or a
   * boot that will not start because this was a required agent — belongs to the log line and the
   * surface, so an operator reads the same words whichever way it landed.
   *
   * <h2>Two flavours, because they are not the same mistake</h2>
   *
   * <p>{@link #WITHHELD} owns the distinction and why it enforces nothing. An unknown name is a
   * spelling; a withheld one is a real tool on the MCP surface the author's own harness offers,
   * which is the likeliest honest error here and the one a bare "this runtime does not bind it"
   * would send somebody hunting a wiring bug over.
   */
  private static String withheldTool(
      DefinitionSource.Definition entry, String tool, Set<String> knownTools) {
    String renamed = RENAMED.get(tool);
    if (renamed != null) {
      return refusal(entry)
          + " lists the tool '"
          + tool
          + "', which was renamed '"
          + renamed
          + "'. It is dropped from that agent's 'tools', and the agent cannot do whatever"
          + " its prompt describes with it until the definition names '"
          + renamed
          + "' instead. The tools it binds are "
          + new TreeSet<>(knownTools);
    }
    String why = WITHHELD.get(tool);
    if (why != null) {
      return refusal(entry)
          + " lists the tool '"
          + tool
          + "', which is a real tool on the"
          + " MCP surface a person's own harness offers and is deliberately never on"
          + " the agent-facing one: "
          + why
          + ". It is dropped from that agent's"
          + " 'tools', which changes nothing a model can reach — the tool is never"
          + " built for an agent and never offered either way — but as written the"
          + " agent cannot do whatever its prompt describes with it. The tools an"
          + " agent may hold are "
          + new TreeSet<>(knownTools);
    }
    return refusal(entry)
        + " lists the tool '"
        + tool
        + "', which this runtime does not"
        + " bind: no tool of that name is served to an agent anywhere on this server."
        + " It is dropped from that agent's 'tools', which changes nothing a model can"
        + " reach — an unknown tool is never offered either way — but as written the"
        + " agent cannot do whatever its prompt describes with it. The tools it binds"
        + " are "
        + new TreeSet<>(knownTools);
  }

  /**
   * Why one of the three agents that constitute the memory pipeline is being refused an authoring
   * verb.
   *
   * <p>A different refusal from {@link #withheldTool}'s two and it says so: the tool is real, it is
   * bound for agents, and this particular agent may not have it. Saying "this runtime does not bind
   * it" here would be false, and saying nothing would leave an author to conclude the grant simply
   * failed.
   *
   * <p>The disposition is the log line's and the surface's, on {@link Loaded}'s convention: all
   * three of these are in {@code AgentsConfig.REQUIRED}, so in practice this sentence arrives as a
   * boot failure.
   */
  private static String mayNotAuthor(DefinitionSource.Definition entry, String tool) {
    return refusal(entry)
        + " lists the tool '"
        + tool
        + "', which is bound for agents"
        + " but never for this one: '"
        + entry.name()
        + "' is part of the memory"
        + " pipeline, and an agent that judges or rules on what is written must not"
        + " also be a writer of it. What every agent produces is a proposal, judged"
        + " by the scribe and filed by the archive; these three are that judgement"
        + " and that filing. It is dropped from that agent's 'tools'";
  }

  // ------------------------------------------------------------------
  // The set
  // ------------------------------------------------------------------

  /**
   * The two halves of delegation must agree.
   *
   * <p>Either alone is a definition that says one thing and does another: {@code agent_run} with no
   * {@code calls} offers the model a tool with an empty list of legal targets, so every delegation
   * it attempts fails at run time; {@code calls} with no {@code agent_run} names collaborators the
   * agent has no way to reach, which reads to anyone editing the graph — and to any diagram drawn
   * from it — as an edge that exists.
   */
  private static List<Fault> disagreeingHalves(Map<String, AgentDefinition> byName) {
    List<Fault> faults = new ArrayList<>();
    for (String name : sortedNames(byName)) {
      AgentDefinition definition = byName.get(name);
      boolean granted = definition.canDelegate();
      boolean named = !definition.calls().isEmpty();
      if (definition.reviewWith() != null
          && !definition.calls().contains(definition.reviewWith())) {
        faults.add(
            new Fault(
                name,
                "the agent '"
                    + definition.name()
                    + "' names '"
                    + definition.reviewWith()
                    + "' in 'review-with' but does not grant"
                    + " that agent in 'calls'. A mandatory reviewer must be a"
                    + " validated edge in the call graph"));
      }
      if (granted && !named) {
        faults.add(
            new Fault(
                name,
                "the agent '"
                    + definition.name()
                    + "' has the '"
                    + AGENT_RUN
                    + "' tool but names no 'calls'. "
                    + AGENT_RUN
                    + " may only reach"
                    + " the agents a definition names, so as written it can reach"
                    + " none: either list the callees or drop the tool"));
      }
      if (named && !granted) {
        faults.add(
            new Fault(
                name,
                "the agent '"
                    + definition.name()
                    + "' names calls "
                    + definition.calls()
                    + " but does not have the '"
                    + AGENT_RUN
                    + "' tool, so it has no way to reach any of them. Delegation is a"
                    + " declared capability: either add the tool or drop the calls"));
      }
    }
    return faults;
  }

  /**
   * The names in a {@code calls:} list that no job could ever be started down, dropped one at a
   * time.
   *
   * <h2>Two faults, one pass, because they cost the same thing</h2>
   *
   * <p><b>A callee no file defines</b> is a spelling in the caller's own definition. It is asked
   * about what this directory <em>defines</em> and not about what it serves, which is what the
   * disable rule changed here: a callee whose own file was refused still exists, so the caller
   * keeps running with an inert grant, and only a name nobody wrote at all is the typo this was
   * always meant to catch.
   *
   * <p><b>A callee that declared {@code delegable: false}</b> is {@link #disagreeingHalves} read
   * from the other end: that one asks whether a caller's own two halves agree, this asks whether
   * the callee agreed to be a callee at all. It is the second half of what makes a <b>bot</b> a
   * declaration rather than an observation — exported, so a person may reach it, and refusing
   * delegation. Both agents and bots remain messaging participants.
   *
   * <h2>What it costs is the name, and not the file that wrote it</h2>
   *
   * <p>Both were disablements until the item rule, and both are entries inside a grant that was
   * never issuable: no job is ever started down an edge whose far end nobody wrote, and none down
   * one whose far end has refused. So the entry goes and the caller stays, which is {@link
   * #withholdEscalatingEdges}' answer applied to the two faults sitting next to it. {@code calls:}
   * is a grant, and dropping one name from a grant is not the same act as refusing the agent that
   * holds it.
   *
   * <p><b>The two differ on whose requiredness is asked about, and that is not an oversight.</b> An
   * undefined callee is the caller's mistake alone, so only the caller's membership is consulted:
   * asking about the callee would turn an absent {@code scribe.md} — which is a degraded server
   * today and not a refused one — into a boot failure by way of somebody else's spelling. A
   * refusing callee is a real pair of files, so both ends are asked, exactly as the escalation case
   * asks.
   *
   * @param byName mutated: a caller that lost names is replaced by the definition without them, and
   *     — when it lost all of them — without {@code agent_run} either. {@link #withoutCallees} owns
   *     that remedy
   */
  private static Map<String, String> withholdUnreachableCallees(
      Map<String, AgentDefinition> byName, Set<String> defined, Predicate<String> required) {

    Map<String, String> withheld = new LinkedHashMap<>();
    for (String callerName : sortedNames(byName)) {
      AgentDefinition caller = byName.get(callerName);
      List<String> kept = new ArrayList<>();
      for (String calleeName : caller.calls()) {
        if (!defined.contains(calleeName)) {
          String reason =
              "the agent '"
                  + callerName
                  + "' calls '"
                  + calleeName
                  + "', which no file in this directory defines, so no job can be"
                  + " started down that route and the name is dropped from '"
                  + callerName
                  + "'. The agents defined are "
                  + new TreeSet<>(defined);
          if (required.test(callerName)) {
            throw new IllegalStateException(reason);
          }
          withheld.put(callerName + " -> " + calleeName, reason);
          continue;
        }
        AgentDefinition callee =
            AgentAliases.targets(byName, calleeName).stream()
                .filter(target -> target.bot() || !target.delegable())
                .findFirst()
                .orElse(null);
        if (callee != null) {
          String reason =
              "the agent '"
                  + callerName
                  + "' calls '"
                  + calleeName
                  + "', which is a bot or declares 'delegable: false' and cannot be a delegation target. The name is"
                  + " dropped from '"
                  + callerName
                  + "', which is the first of the two"
                  + " remedies this refusal has always named; the other is to drop the"
                  + " refusal from '"
                  + calleeName
                  + "'";
          if (required.test(callerName) || required.test(callee.name())) {
            throw new IllegalStateException(reason);
          }
          withheld.put(callerName + " -> " + calleeName, reason);
          continue;
        }
        kept.add(calleeName);
      }
      if (kept.size() != caller.calls().size()) {
        byName.put(callerName, withoutCallees(caller, kept));
      }
    }
    return withheld;
  }

  /**
   * A callee may hold fewer grants than its caller, and never more.
   *
   * <p><b>Structural, at load, over the graph {@code calls:} already declares</b> — which is what
   * makes it checkable at all: the edges are data in a directory, not a call somebody makes at run
   * time.
   *
   * <p>There is nothing useful left to check at run time, <b>on the condition {@link
   * AgentRunTool}'s javadoc states rather than as a fact</b>: a provider is built from the grants
   * it is handed and holds no way to widen them — {@code LocalProvider}'s constructor takes a list
   * of them and nothing else — so once a job's provider is built from that job's own definition,
   * the question is answered before the child starts, and a guard on the delegation call would be a
   * second reading of the same file reachable only down a path somebody has to remember to test.
   *
   * <p><b>The condition now holds, so the argument above is unconditional.</b> {@link JobRuntime}
   * builds a router over {@code definition.scopes()} once per run and {@code AgentsConfig} turns
   * that into a {@code LocalProvider} carrying those grants and nothing else, so a child's provider
   * really is built from the child's own definition before it starts. This paragraph said
   * <em>"nothing builds a provider from a definition yet; that is this slice's last task"</em>
   * until that task ran and made it false — the third time this passage has been wrong about its
   * own tense, after two drafts that asserted the condition as done while it was still pending. It
   * is recorded rather than quietly swapped because the direction of the error inverted:
   * over-claiming, then over-correcting, then going stale in the corrected direction.
   *
   * <p>This is 3a's deferred question; {@code AgentRunTool} carried the deferral and says what
   * changed about the answer, which is that the intersection it predicted at run time is this walk
   * at boot instead.
   *
   * <p><b>Every edge, and not every node.</b> The cycle walk beside this one visits each node once
   * — that is what lets a diamond through — and a subset check written onto that visit would
   * examine one of a node's incoming edges and skip the rest. Two callers, one callee within the
   * first's grants and outside the second's, and the escalation loads clean. {@code
   * a_diamond_checks_both_edges_and_not_just_the_first} is the test that can see the difference,
   * and nothing else in this file can.
   *
   * <p>Callers are walked in {@link #sortedNames} order, which owns why.
   */
  private static Map<String, String> withholdEscalatingEdges(
      Map<String, AgentDefinition> byName, Predicate<String> required) {

    Map<String, String> withheld = new LinkedHashMap<>();
    for (String callerName : sortedNames(byName)) {
      AgentDefinition caller = byName.get(callerName);
      List<String> kept = new ArrayList<>();
      for (String calleeName : caller.calls()) {
        List<AgentDefinition> targets = AgentAliases.targets(byName, calleeName);
        AgentDefinition callee =
            targets.stream()
                .filter(target -> escalatingGrant(caller, target).isPresent())
                .findFirst()
                .orElse(null);
        if (targets.isEmpty()) {
          // A callee that is defined and not served. The grant is
          // inert either way, and the edge is left as the file wrote
          // it: nothing here is wrong, and rewriting the caller would
          // make a disablement look like a decision about the caller.
          kept.add(calleeName);
          continue;
        }
        Grant escalating = callee == null ? null : escalatingGrant(caller, callee).orElseThrow();
        if (escalating == null) {
          kept.add(calleeName);
          continue;
        }
        String reason =
            "the agent '"
                + callerName
                + "' calls '"
                + calleeName
                + "', which is granted "
                + escalating.declaration()
                + " — a grant '"
                + callerName
                + "' does not hold: it "
                + holdings(caller)
                + ". A callee may hold fewer grants"
                + " than its caller and never more, or delegation is how"
                + " an agent widens its own access, one file over."
                + " Narrow '"
                + calleeName
                + "', or widen '"
                + callerName
                + "'";
        if (required.test(callerName) || required.test(callee.name())) {
          throw new IllegalStateException(reason);
        }
        withheld.put(callerName + " -> " + calleeName, reason);
      }
      if (kept.size() != caller.calls().size()) {
        byName.put(callerName, withoutCallees(caller, kept));
      }
    }
    return withheld;
  }

  /**
   * The caller with the withheld routes taken out, and — when none is left — without the tool
   * either.
   *
   * <p>{@link #disagreeingHalves} refuses a file holding {@code agent_run} with an empty {@code
   * calls:}, and its refusal names the remedy: "either list the callees or drop the tool". With the
   * last callee withheld there is nothing left to list, so this applies the loader's own remedy
   * rather than leaving behind a state the loader would have refused. The model is then not offered
   * a tool that could reach nobody, which is what that rule was protecting against in the first
   * place.
   */
  private static AgentDefinition withoutCallees(AgentDefinition caller, List<String> kept) {
    List<String> tools = caller.tools();
    if (kept.isEmpty()) {
      tools = new ArrayList<>(tools);
      tools.remove(AGENT_RUN);
    }
    // Every field carried through, including the ones this copy used to
    // drop: an agent that lost a withheld route came back with `vision`
    // false whatever its file said, which was inert only because no shipped
    // definition declared both. `bot` would have been the second such loss,
    // and a character quietly demoted to an agent is the direction nothing
    // reports. `announcesInbox` is the third: a bot that also delegates and
    // loses its last callee must not silently stop being told about its
    // speaker's mail either. The board grant is carried through for the same reason.
    return new AgentDefinition(
        caller.name(),
        caller.description(),
        caller.model(),
        caller.intent(),
        caller.sampling(),
        tools,
        kept,
        caller.scopes(),
        caller.maxTurns(),
        caller.maxModelCalls(),
        caller.prompt(),
        caller.exported(),
        caller.delegable(),
        caller.vision(),
        caller.bot(),
        caller.announcesInbox(),
        caller.fallback(),
        caller.orchestrations(),
        caller.reviewWith(),
        caller.board(),
        caller.skills(),
        caller.displayName(),
        caller.origin(),
        caller.alias(),
        caller.guidance(),
        caller.dynamic());
  }

  /**
   * Whether one of {@code grants} covers {@code wanted}.
   *
   * <p><b>A mode question and not a scope question, because {@code Scope} has one value</b> — see
   * {@code Scope}'s javadoc, which owns that and why the second value from the source did not port.
   * A comparison of two scopes here is therefore a tautology: measured, added back it survives
   * every test in this file, and this project removes such a line rather than keeping it for the
   * shape of the thing. {@code LocalProvider}'s constructor makes the same collapse. The day {@code
   * Scope} gains a value, {@code the_scope_catalogue_names_only_the_workspace} fails and both
   * places need the comparison.
   *
   * <p>The mode question is asked of {@link Grant#allows} rather than answered here; that method
   * owns "write implies read" and says why a second comparison anywhere is the copy that drifts.
   */
  private static boolean covers(List<Grant> grants, Grant wanted) {
    for (Grant grant : grants) {
      if (grant.allows(wanted.mode())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The first grant {@code callee} holds that {@code caller} does not cover, or empty when the edge
   * does not escalate. {@link #withholdEscalatingEdges}' own test, package private so that an
   * orchestration's conductor edges are held to the identical comparison rather than a copy.
   */
  static Optional<Grant> escalatingGrant(AgentDefinition caller, AgentDefinition callee) {
    for (Grant held : callee.scopes()) {
      if (!covers(caller.scopes(), held)) {
        return Optional.of(held);
      }
    }
    return Optional.empty();
  }

  /**
   * What an agent holds, said so that holding nothing reads as a statement rather than as a list
   * somebody forgot to fill in.
   */
  static String holdings(AgentDefinition definition) {
    if (definition.scopes().isEmpty()) {
      return "holds none at all";
    }
    List<String> declared = new ArrayList<>();
    for (Grant grant : definition.scopes()) {
      declared.add(grant.declaration());
    }
    return "holds " + declared;
  }

  private enum Mark {
    /** On the current recursion stack: reaching it again is a cycle. */
    ON_STACK,
    /** Fully explored and clean: reaching it again is a diamond, not a cycle. */
    DONE
  }

  /**
   * Refuses a cycle in the call graph, naming the whole path.
   *
   * <p><b>A recursion stack, not a global visited set, and the distinction is the entire point of
   * this method.</b> A checker that marks a node visited forever and treats any second arrival as a
   * cycle rejects a diamond — one agent calling two others that both call a third — which is the
   * most ordinary useful shape there is. So there are two marks: {@link Mark#ON_STACK} is an
   * ancestor of the node being explored and means a cycle; {@link Mark#DONE} is a node already
   * explored and found clean, and means nothing at all except that there is no work to repeat.
   *
   * <p>A self-call is the shortest cycle and lands on the {@code ON_STACK} branch on the node's own
   * first child, which is why the check is at the top of {@code walk} rather than inside the loop
   * over children — a walk that only inspects children misses it.
   *
   * <p>Recursive rather than iterative: the depth is the length of a delegation chain in a
   * hand-written directory, and the recursion carries the path that the refusal message needs.
   */
  /**
   * Every agent on every cycle, each given the cycle as its reason.
   *
   * <p><b>All of them and not one of them</b>, which is the only defensible answer here: a cycle is
   * the one fault where no single file is wrong. Breaking it means an edit to one of the members
   * and nothing in this class can know which, so leaving any member served would leave the job tree
   * that never drains reachable through it.
   *
   * <p>Found one at a time, with each cycle's members taken out of the working graph before the
   * next search, so two independent cycles are both reported instead of the walk stopping at the
   * first.
   */
  private static List<Fault> cycles(Map<String, AgentDefinition> byName) {
    List<Fault> faults = new ArrayList<>();
    Map<String, AgentDefinition> graph = new LinkedHashMap<>(byName);
    while (true) {
      List<String> cycle = findCycle(graph);
      if (cycle == null) {
        return faults;
      }
      String reason =
          "the agents "
              + String.join(" -> ", cycle)
              + " form a cycle in the"
              + " call graph. Nothing downstream catches this: a turn ends when tool"
              + " calls are issued, so a delegating job is not waiting on a stack"
              + " and there is no depth counter to trip — a cycle is a job tree"
              + " that never drains. Break the loop in one of these files";
      for (String name : cycle) {
        if (graph.remove(name) != null) {
          faults.add(new Fault(name, reason));
        }
      }
    }
  }

  private static List<String> findCycle(Map<String, AgentDefinition> byName) {
    Map<String, Mark> marks = new HashMap<>();
    List<String> path = new ArrayList<>();
    for (String name : sortedNames(byName)) {
      List<String> cycle = walk(name, byName, marks, path);
      if (cycle != null) {
        return cycle;
      }
    }
    return null;
  }

  /**
   * The names of a set, in the order every set-level check walks them.
   *
   * <p>Sorted, so that a directory with two independent faults of one kind — two cycles, two
   * escalating edges — reports the same one on every boot rather than whichever the map iterated
   * first. <b>The two walks share this for one reason and to the same degree</b>, which is worth
   * saying because a draft of one of them claimed the order mattered more there: measured, {@code
   * Map.copyOf}'s iteration order differs between JVM runs, so the map constructor's view of a bad
   * set is a different order every time the server starts, and both walks are handed the same map
   * from the same two entry points.
   *
   * <p><b>No test can force the other order in one JVM</b> — the salt is fixed for the life of the
   * process — so this is reasoned rather than pinned, in both places, which is the other reason it
   * is one line and not two.
   */
  private static Set<String> sortedNames(Map<String, AgentDefinition> byName) {
    return new TreeSet<>(byName.keySet());
  }

  /**
   * The cycle reaching {@code name}, or null. Answers rather than throws, so that the caller can
   * decide between a boot failure and a disablement.
   */
  private static List<String> walk(
      String name,
      Map<String, AgentDefinition> byName,
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
    for (String requested : byName.get(name).calls()) {
      for (AgentDefinition callee : AgentAliases.targets(byName, requested)) {
        List<String> cycle = walk(callee.name(), byName, marks, path);
        if (cycle != null) return cycle;
      }
    }
    path.remove(path.size() - 1);
    marks.put(name, Mark.DONE);
    return null;
  }
}
