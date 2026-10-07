package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One agent, as parsed from one file.
 *
 * <p>Plowshare's orchestration is data rather than code: this record is the whole of what the
 * runtime knows about an agent, and the graph it will execute is whatever a directory of these
 * describes. Anchor wires its three agents in Java in a fixed order and needs none of this; here
 * the callee list is read at boot, so every hazard {@link AgentRegistry} guards against exists
 * because the graph is unknown until the files are read.
 *
 * <p><b>{@code calls} is the delegation half that names names, and {@code tools} is the half that
 * grants the capability.</b> An agent without {@code agent_run} in {@code tools} cannot reach
 * another agent at all, the same way an agent without a file tool cannot open a file — structural,
 * not instructional. {@link AgentRegistry} refuses a definition that carries one half without the
 * other, because either alone is a file that says one thing and does another.
 *
 * <p><b>{@code scopes} is the third declaration, and the one an agent cannot widen at run time.</b>
 * It says which places the agent may reach and how much of each; <em>where</em> those places are is
 * the project's business and never this file's. Two things hold it, in different layers and neither
 * of them an instruction the model reads: {@link AgentRegistry} refuses at load any graph in which
 * a callee holds a grant its caller does not, so delegation cannot be the way an agent widens its
 * own access one file over; and {@code LocalProvider}'s constructor takes a list of these grants
 * and nothing else that could widen them, so what a job is handed at its start is the whole of what
 * its file tools can reach.
 *
 * <p>Parsed into {@link Grant}s rather than kept as text, for the reason every other value here is
 * checked at load: {@code workspace:reed} should be a boot failure naming the file, not an agent
 * that starts and silently reaches nothing. {@code Grant.parse} owns that spelling and this record
 * never re-reads it.
 *
 * <p><b>{@code exported} and {@code delegable} are the two exposure axes, and they are independent
 * of each other and of everything above.</b> {@code canDelegate}, {@code canRedeem} and {@code
 * canList} are all questions about <em>tools</em> — what this agent may do — and until these two
 * keys existed this record said nothing whatever about who may reach <em>it</em>.
 *
 * <ul>
 *   <li>{@code exported} is the outside: {@code GET /v1/agents}, {@code POST
 *       /v1/agents/&#123;name&#125;/runs}, the MCP {@code agent_run} that runs over that endpoint,
 *       and being named as the agent for a turn or a resume. <b>It deliberately does not gate
 *       delegation</b> — a private agent stays fully callable through another definition's {@code
 *       calls:}, which is the whole point of having the axis at all.
 *   <li>{@code delegable} is the inside: whether any other definition may name this one in {@code
 *       calls:}. {@link AgentRegistry} refuses the set when one does, the same way it refuses the
 *       two halves of delegation disagreeing — that check read from the callee's end.
 * </ul>
 *
 * <p><b>{@code vision} is the fourth declaration, and it is a requirement rather than a grant.</b>
 * {@code tools}, {@code calls} and {@code scopes} say what this agent may <em>do</em>; this says
 * what it needs the model underneath it to be able to do. An agent that declares it and names a
 * model no pool has declared as seeing is refused at boot, naming both — {@code
 * AgentsConfig.requireModelSees}, which is {@code requireModelServed}'s twin and sits directly
 * beside it.
 *
 * <p>It is deliberately not inferred. Nothing here looks at a model name and decides that something
 * called {@code gemma-4} probably sees: a capability guessed from a string is a capability that is
 * wrong the first time somebody renames a model in their own configuration, and it would be wrong
 * silently, at the first call, with the model answering that it cannot see an image.
 *
 * <p><b>A bot occupies the corner where the two meet:</b> exported and not delegable-to — reachable
 * by a person, unreachable by an agent. {@code interlocutor} was already that shape before either
 * key existed, so these declare something the directory already contained rather than adding a kind
 * of thing. The other three corners are all occupied too: {@code code_reviewer} is exported and
 * delegable-to, {@code scribe} is neither.
 *
 * <p><b>{@code bot} is the fifth declaration, and it says what a definition <em>is</em> where the
 * two above say who may reach it.</b> The corner is not the claim: {@code interlocutor} sits in it
 * and is a role — an agent you invoke because you want the thing it does, and when it has done it
 * the relationship is over. A bot is a character somebody returns to, the ordinary way of using
 * this server rather than a task in it, and the two facts a surface actually needs — that a person
 * may reach it, and that no agent may — were already derivable while "which of these is who I talk
 * to" was not.
 *
 * <p><b>It is a flag and not a directory</b>, which is spec §2.1's decision and {@code
 * DataLayout.botsFor}'s: an operator files {@code agents/} and {@code bots/} as they like and the
 * loader reads frontmatter, exactly as it does for the two keys above. Teaching the loader to read
 * a path would contradict a documented decision to get a fact this key already carries.
 *
 * <p><b>Absent means agent</b>, which is {@code exported}'s rule rather than {@code delegable}'s: a
 * definition that has not said it is a character has not said so, and every file written before the
 * key existed keeps its meaning without being edited.
 *
 * <p><b>{@code announcesInbox} is the sixth declaration, and it is a request rather than a grant,
 * on {@code vision}'s shape.</b> It says a bot wants to be told, in a logged {@code NOTICE} after
 * its speaker's utterance, when that speaker's user-inbox has unread arrivals since the
 * conversation's own previous turn — and it is what {@link JobRuntime#run} asks {@code Noticing}
 * about before it asks {@code Reminding}, which is a different seam: a notice is logged and a
 * reminder never is. Absent means no, {@code vision}'s rule again: a definition that has not asked
 * to be told about the inbox has not asked, and every file written before this key existed keeps
 * its meaning unedited.
 *
 * <p><b>Inference values are taken as given, and nullity is checked here.</b> The same division
 * {@code LlmPool} draws with {@code LlmConfig}: every definition is built by {@link
 * AgentRegistry#load} out of a parsed file, and that is where a blank model, a non-positive budget
 * or a sampling value that is not a finite number has to be refused — at boot, naming the file and
 * the frontmatter key an operator would have to edit. A second guard here would report the same
 * fault later with less to say about it. Nothing else may construct one without that check. Alias
 * membership and its closed guidance values are also checked here, so programmatic copies retain
 * the same selection invariants as authored definitions.
 */
public record AgentDefinition(
    String name,
    String description,
    String model,
    Sampling.Intent intent,
    Sampling sampling,
    List<String> tools,
    List<String> calls,
    List<Grant> scopes,
    int maxTurns,
    int maxModelCalls,
    String prompt,
    boolean exported,
    boolean delegable,
    boolean vision,
    boolean bot,
    boolean announcesInbox,
    Fallback fallback,
    List<String> orchestrations,
    String reviewWith,
    /** Whether this definition may open board topics from a person's project conversation. */
    boolean board,
    List<String> skills,
    String displayName,
    String origin,
    String alias,
    String guidance) {

  /** Existing definitions have no alias or guidance selector. */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable,
      boolean vision,
      boolean bot,
      boolean announcesInbox,
      Fallback fallback,
      List<String> orchestrations,
      String reviewWith,
      boolean board,
      List<String> skills,
      String displayName,
      String origin) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        skills,
        displayName,
        origin,
        null,
        null);
  }

  /** Legacy programmatic definitions have no authored display label or file origin. */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable,
      boolean vision,
      boolean bot,
      boolean announcesInbox,
      Fallback fallback,
      List<String> orchestrations,
      String reviewWith,
      boolean board,
      List<String> skills) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        skills,
        null,
        null);
  }

  /**
   * Whether a probable refusal from this agent's own model may be answered by another model, and
   * which.
   *
   * <h2>Authorisation, and never detection</h2>
   *
   * <p>{@link RefusalDetector} says a completion reads as a refusal. This says whether <em>this
   * agent</em> may be rerouted when one does, and it is the agent file's to say because it is a
   * statement about the work: an agent built for public-source OSINT or authorised security
   * research names a low-refusal class; an agent nobody has made that judgement about names none,
   * and its refusals are answers. There is no server-wide switch, for the reason there is no
   * server-wide temperature: it would move every agent at once with no file saying so.
   *
   * <p><b>{@code model} is a specifier, exactly as the definition's own {@code model:} is</b> — a
   * class from {@code plowshare.llm.classes} such as {@code low_refusal_osint}, resolved per call
   * to whichever pool serves it. So nothing here knows where the fallback runs: moving it from one
   * box to another is a pool change and not an agent change. <b>And no pool serving it is not a
   * fault in the agent</b>: {@code AgentsConfig} drops the fallback with a warning and the agent
   * runs as though it had declared none.
   *
   * <p><b>One attempt, and {@code max-attempts} says so rather than implying it.</b> The loader
   * refuses any other number. A fallback that refuses in turn is not rerouted again — nothing here
   * is recursive — and the refusal the agent's own model gave stands as the answer.
   *
   * @param on what triggers a reroute — the file's {@code when:}. Empty for {@link #NONE}
   * @param model the specifier to reroute to, or null for {@link #NONE}
   * @param maxAttempts how many reroutes one run may take: 1, or 0 for {@link #NONE}
   * @param sampling what the fallback's requests carry, resolved against the fallback's own wire
   *     model by {@code AgentsConfig} as the agent's is against its own. {@link Sampling#NONE}
   *     until then
   */
  public record Fallback(Set<Trigger> on, String model, int maxAttempts, Sampling sampling) {

    /** What a definition with no {@code fallback:} key has. */
    public static final Fallback NONE = new Fallback(Set.of(), null, 0, Sampling.NONE);

    /** What may trigger a reroute. One constant today; spelled as the file spells it. */
    public enum Trigger {
      REFUSAL("refusal");

      private final String key;

      Trigger(String key) {
        this.key = key;
      }

      public String key() {
        return key;
      }
    }

    public Fallback {
      Objects.requireNonNull(on, "on");
      Objects.requireNonNull(sampling, "sampling");
      on = Set.copyOf(on);
      if (!on.isEmpty() && (model == null || model.isBlank())) {
        throw new IllegalArgumentException(
            "a fallback that can be triggered has to name a model to fall back to");
      }
    }

    /** Whether {@code trigger} reroutes a run that has already taken {@code taken}. */
    public boolean permits(Trigger trigger, int taken) {
      return on.contains(trigger) && taken < maxAttempts;
    }

    /** The same fallback, with sampling resolved for its own model. */
    public Fallback sampling(Sampling resolved) {
      return new Fallback(on, model, maxAttempts, resolved);
    }
  }

  /**
   * The two sampling fields, and why one file now carries an intent and a resolution rather than a
   * number.
   *
   * <h2>{@code intent} is what the file asked for; {@code sampling} is what it gets on this server
   * </h2>
   *
   * <p>One number used to carry three different facts: what the agent wants, what the model
   * requires, and what the backend supports. That is why {@code temperature: 1.0} was
   * simultaneously correct on Gemma and wrong on {@code gpt-oss-20b} — a file that cannot see the
   * model was asserting the model's shape. {@code intent} is the part an agent file legitimately
   * knows ("the same input should give the same label"), and {@code sampling} is what a per-model
   * profile resolved that to.
   *
   * <p><b>{@code sampling} is a resolution and not a declaration, and the two are separated in
   * time.</b> {@link AgentRegistry} reads a file and puts into this field only what the file
   * <em>explicitly overrode</em> — almost always {@link Sampling#NONE}. {@code AgentsConfig}, which
   * is the only place that holds both a directory of definitions and a dispatcher that knows which
   * wire model serves each specifier, then layers the profile underneath it and replaces the
   * definition. That is the layering stage 6 predicted: <em>"a change at the loader, where the
   * layering would live."</em>
   *
   * <p><b>{@link Sampling#NONE} is the ordinary resting value and means send nothing.</b> A
   * definition that never met a profile — every fixture in every test, and every agent on a server
   * with no profile for its model — carries no sampling parameters at all, and the model's own
   * defaults apply. That is strictly better than the {@code 0.0} that used to sit here: nobody
   * chose that number, it was the only option before the {@code temperature:} key existed, and it
   * is the exact configuration measured driving this server's summariser cascade into deterministic
   * repetition loops.
   *
   * <h2>Neither is on {@code GET /v1/agents}</h2>
   *
   * <p>Unchanged from when this was a temperature. {@code AgentView} carries {@code tools}, {@code
   * calls}, {@code scopes} and what was withheld — the reach questions — and neither {@code model}
   * nor these. An operator reading an agent's sampling reads the file and the profile the boot log
   * names.
   *
   * <h2>Not a property, and this half is now sharper than it was</h2>
   *
   * <p>A server-wide default <em>temperature</em> would move every agent's sampling at once with no
   * file saying so, which is why there has never been one. A profile directory is not that: it
   * moves what an <em>intent</em> resolves to for a <em>named model</em>, which is a fact about the
   * model and belongs where the other facts about the model live. The agent's own file still holds
   * the only thing the agent is entitled to have an opinion about.
   */
  public static final Sampling.Intent DEFAULT_INTENT = Sampling.Intent.DEFAULT;

  /**
   * A definition with the two exposure axes left at what an absent key means.
   *
   * <p><b>Not a second door with different rules</b>, which is what the class javadoc above
   * forbids: it is the same construction with {@code exported} and {@code delegable} taking exactly
   * the values {@link AgentRegistry} gives a file that names neither key. A definition assembled in
   * Java and a definition read from a file that says nothing therefore agree, which is the drift
   * this repository keeps closing rather than opening.
   *
   * <p>The two defaults point opposite ways because the two keys are opposite kinds of thing.
   * {@code exported} is a grant — nothing here is implicit, so an agent nobody exported is not
   * exported. {@code delegable} is a refusal, and the file that has not written one has refused
   * nothing.
   *
   * <p>{@code intent} and {@code sampling} take {@link #DEFAULT_INTENT} and {@link Sampling#NONE}
   * by the same rule, and the field javadoc above owns why sending nothing is where the absent
   * value points.
   */
  public AgentDefinition(
      String name,
      String description,
      String model,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt) {
    this(
        name,
        description,
        model,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        false,
        true);
  }

  /**
   * The two exposure axes stated, and sampling left at what an absent key means.
   *
   * <p>This was the canonical constructor until {@code temperature} became the eleventh frontmatter
   * key, and it is kept for the same reason the nine-argument one above it is kept: it is the same
   * construction with the absent keys resolved exactly as {@link AgentRegistry} resolves them, so a
   * definition built in Java and a definition read from a file that says nothing about sampling
   * still agree. Every caller that has no opinion about sampling — which is every caller in {@code
   * main} but the loader, and every test fixture — goes on saying nothing rather than repeating a
   * number nobody chose.
   *
   * <p><b>What that silence now means has moved, and deliberately.</b> It used to be {@code
   * temperature 0.0}, which was sent on every call; it is now {@link Sampling#NONE}, which sends
   * nothing and lets the model's own defaults stand. Every fixture in the suite therefore stopped
   * asserting a zero onto the wire, which is the change this design is for.
   */
  public AgentDefinition(
      String name,
      String description,
      String model,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable) {
    this(
        name,
        description,
        model,
        DEFAULT_INTENT,
        Sampling.NONE,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable);
  }

  /**
   * The same definition with its sampling resolved against a profile.
   *
   * <p>The one mutation the loader performs after a file is read, and it is a copy rather than a
   * setter for the reason every other value here is immutable: a definition already validated as
   * part of a graph must not change under whoever is holding it. {@code AgentsConfig} builds the
   * replacement set and hands the registry the resolved one.
   *
   * <p>{@code intent} is carried through untouched. It is what the file asked for, and it stays
   * readable after resolution precisely so that a server where nothing could be resolved can say
   * which agents asked for what and did not get it.
   */
  public AgentDefinition sampling(Sampling resolved) {
    return new AgentDefinition(
        name,
        description,
        model,
        intent,
        resolved,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        skills,
        displayName,
        origin,
        alias,
        guidance);
  }

  /**
   * The same definition with a different fallback — {@code AgentsConfig} resolving its sampling,
   * and a fixture declaring one.
   */
  public AgentDefinition fallback(Fallback replaced) {
    return new AgentDefinition(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        replaced,
        orchestrations,
        reviewWith,
        board,
        skills,
        displayName,
        origin,
        alias,
        guidance);
  }

  /** The pre-skills shape: an ordinary agent gets no implicit skill grant. */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable,
      boolean vision,
      boolean bot,
      boolean announcesInbox,
      Fallback fallback,
      List<String> orchestrations,
      String reviewWith,
      boolean board) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        List.of());
  }

  /** This grant must still be intersected with the caller's visible skill catalog. */
  public boolean canUseSkill(String name) {
    return skills.contains("*") || skills.contains(name);
  }

  public AgentDefinition withSkills(List<String> granted) {
    return new AgentDefinition(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        granted,
        displayName,
        origin,
        alias,
        guidance);
  }

  AgentDefinition withCalls(List<String> granted) {
    return new AgentDefinition(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        granted,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        skills,
        displayName,
        origin,
        alias,
        guidance);
  }

  /** The pre-board shape: an undeclared grant allows no topic openings. */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable,
      boolean vision,
      boolean bot,
      boolean announcesInbox,
      Fallback fallback,
      List<String> orchestrations,
      String reviewWith) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        false);
  }

  /**
   * The shape this record had before an answer could require an independent review. Absent means
   * the first answer is final, as it was for every existing definition.
   */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable,
      boolean vision,
      boolean bot,
      boolean announcesInbox,
      Fallback fallback,
      List<String> orchestrations) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        null);
  }

  /**
   * The shape this record had before {@code orchestrations:} was a grant. A definition that says
   * nothing about orchestrations may start none.
   */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable,
      boolean vision,
      boolean bot,
      boolean announcesInbox,
      Fallback fallback) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        List.of());
  }

  /**
   * Every definition written before {@code fallback:} was a key. Absent means {@link
   * Fallback#NONE}: an agent nobody said may be rerouted is not.
   */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable,
      boolean vision,
      boolean bot,
      boolean announcesInbox) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        Fallback.NONE);
  }

  /**
   * Every definition written before {@code announces-inbox} or {@code fallback:} existed: it does
   * not announce, and it is not rerouted.
   *
   * <p>The same rule as every constructor below it: a definition assembled in Java and one read
   * from a file that says nothing about either key have to agree, and every existing call site of
   * the 15-argument shape keeps compiling and keeps its meaning unedited.
   */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable,
      boolean vision,
      boolean bot) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        false,
        Fallback.NONE);
  }

  /**
   * The shape this record had before {@code bot:} was a key, kept for the reason every constructor
   * below it is kept: a definition assembled in Java and one read from a file that says nothing
   * about it have to agree.
   *
   * <p><b>Absent means agent</b>, and the class javadoc above owns why. A caller that has an
   * opinion about vision and none about what kind of thing a definition is comes through here.
   */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable,
      boolean vision) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        false);
  }

  /**
   * The shape this record had before {@code vision:} was a key, kept for the reason the two shorter
   * constructors above are kept: a definition built in Java and one read from a file that says
   * nothing about vision have to agree, and every caller in {@code main} but the loader — and every
   * fixture in the suite — says nothing about it.
   *
   * <p><b>Absent means no</b>, which is {@code exported}'s rule rather than {@code delegable}'s.
   * Declaring that an agent needs a model that sees is a <em>requirement</em>, and a file that has
   * not stated one has not stated one; defaulting it to true would put every agent in the tree
   * behind a boot check about a capability none of them uses.
   */
  public AgentDefinition(
      String name,
      String description,
      String model,
      Sampling.Intent intent,
      Sampling sampling,
      List<String> tools,
      List<String> calls,
      List<Grant> scopes,
      int maxTurns,
      int maxModelCalls,
      String prompt,
      boolean exported,
      boolean delegable) {
    this(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        false);
  }

  /**
   * Copies all four lists, and rejects a null element in any of them.
   *
   * <p>The copy is the part that matters on every path: it stops a caller mutating a definition
   * after {@link AgentRegistry} has validated its graph and grants. {@code scopes} is the one where
   * mutating it afterwards would widen <em>access</em> rather than the graph — the non-escalation
   * check runs once, at load, over the grants as they were then.
   *
   * <p><b>The null-element rejection is the weaker of two guards, and an earlier version of this
   * javadoc credited it with work it does not do.</b> It claimed to be what catches {@code tools:
   * [memory_read, ~]} — YAML resolves a bare {@code ~} to null. Measured: on the load path that
   * input never reaches here, because {@code AgentRegistry}'s {@code requireStringList} refuses it
   * first with a message naming the file and the offending entry, which is the message an operator
   * can act on. {@code requireStringList} is the load-bearing guard and {@code
   * a_null_entry_in_a_tool_list_is_refused} is what pins it. What survives for {@code List.copyOf}
   * is the direct-construction path — a caller building a definition without going through the
   * registry — and {@code AgentDefinitionTest} covers that separately, so the two are distinguished
   * by tests rather than merely asserted to differ.
   */
  public AgentDefinition {
    AgentAliases.validateSelection(name, alias, guidance, bot);
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(prompt, "prompt");
    // Named for the reason the four above are: a definition assembled by
    // hand with a null here would fail as a bare NullPointerException from
    // inside whichever consumer asked it a question first.
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(sampling, "sampling");
    Objects.requireNonNull(fallback, "fallback");
    Objects.requireNonNull(orchestrations, "orchestrations");
    Objects.requireNonNull(skills, "skills");
    // Named, like the four scalars above. List.copyOf would throw on null
    // too, but its message names neither the field nor this record, so a
    // caller building a definition by hand gets a bare NullPointerException
    // from inside the JDK and has to guess which of the three lists it was.
    Objects.requireNonNull(tools, "tools");
    Objects.requireNonNull(calls, "calls");
    Objects.requireNonNull(scopes, "scopes");
    tools = List.copyOf(tools);
    calls = List.copyOf(calls);
    scopes = List.copyOf(scopes);
    orchestrations = List.copyOf(orchestrations);
    skills = List.copyOf(skills);
  }

  /**
   * Whether this agent may reach another one.
   *
   * <p>One place asks the question so that the runtime never has to spell the tool's name again,
   * and so that the answer cannot drift from the agreement {@link AgentRegistry} enforces between
   * {@code tools} and {@code calls}.
   */
  public boolean canDelegate() {
    return tools.contains(AgentRegistry.AGENT_RUN);
  }

  /**
   * This definition with {@code tools} in place of its own, and every other component as it was.
   *
   * <p>One copy, here, rather than one per caller: a hand-written copy of nineteen components is
   * where a field goes missing without a word — {@code AgentRegistry.withoutCallees} records three
   * that did.
   */
  /** Copy with a pinned script as the driver, or an ordinary model prompt. */
  public AgentDefinition withPrompt(String prompt) {
    return new AgentDefinition(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        skills,
        displayName,
        origin,
        alias,
        guidance);
  }

  public AgentDefinition withTools(List<String> tools) {
    return new AgentDefinition(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        skills,
        displayName,
        origin,
        alias,
        guidance);
  }

  /**
   * This definition with {@code scopes} in place of its own, and every other component as it was.
   * {@link #withTools}' shape exactly, and for its reason: one copy of nineteen components, here —
   * {@code DraftReport} asks {@code AgentRegistry.escalatingGrant} one scope at a time, and needs a
   * caller-shaped definition holding exactly one to ask it of.
   */
  public AgentDefinition withScopes(List<Grant> scopes) {
    return new AgentDefinition(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        maxTurns,
        maxModelCalls,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        skills,
        displayName,
        origin,
        alias,
        guidance);
  }

  /**
   * This definition with a person's caps in place of its own {@code max-turns} and {@code
   * max-model-calls} — spec 2026-09-29 §2. A null keeps the definition's own.
   *
   * <p>On {@link #withTools}' shape, for its reason: one copy of nineteen components, here. Neither
   * number is re-checked: {@code EnvironmentFile} refuses a non-positive cap as it parses, and
   * {@link TurnCap#of} and {@link Budget#of} refuse one again where it is used.
   *
   * @param steps steps per turn, or null
   * @param budget model calls per run, or null
   * @return the capped definition, or this one when neither is set
   */
  public AgentDefinition withCaps(Integer steps, Integer budget) {
    if (steps == null && budget == null) {
      return this;
    }
    return new AgentDefinition(
        name,
        description,
        model,
        intent,
        sampling,
        tools,
        calls,
        scopes,
        steps == null ? maxTurns : steps,
        budget == null ? maxModelCalls : budget,
        prompt,
        exported,
        delegable,
        vision,
        bot,
        announcesInbox,
        fallback,
        orchestrations,
        reviewWith,
        board,
        skills,
        displayName,
        origin,
        alias,
        guidance);
  }

  /**
   * Whether this agent can turn a reference back into the result it stands for.
   *
   * <p><b>{@code Compaction} asks this before it substitutes.</b> A later turn sees an earlier
   * turn's tool results as references rather than in full, and a reference is only worth its line
   * to an agent that can redeem it: the reference costs context, and the assistant message that
   * asked for the result has to be carried with it — a {@code tool} message with no {@code
   * tool_calls} above it is a request endpoints reject — so an agent with no {@code result_read}
   * would pay for both with nothing able to come back. That is strictly worse than the drop it
   * replaced, which cost nothing at all. So the substitution is conditional and this is the
   * condition; {@code Compaction.whatWasSaidAndWhatCameBack} carries the argument at length.
   *
   * <p><b>Nothing fails when it is false and the substitution happens anyway</b>, which is why it
   * is a method here rather than a check at the point of use. No shipped agent is in that state —
   * only {@code interlocutor} holds a conversation across turns — so an operator writing their own
   * definition lands in it with no boot error, no failing test and no log line, spending context in
   * the one direction nothing reports.
   *
   * <p>{@link #canDelegate()}'s shape exactly, and for its reason: one place asks the question, so
   * the runtime never spells the tool's name twice and the answer cannot drift from the name {@code
   * ResultTools.Read} registers itself under. {@code
   * AgentDefinitionTest.the_name_that_gates_references_ is_the_name_the_tool_registers} is what
   * fails if it does.
   *
   * <p><b>Unlike {@code agent_run} there is no second half to agree with.</b> Delegation is
   * declared twice — in {@code tools} and in {@code calls} — and {@link AgentRegistry} refuses a
   * definition carrying one without the other. Redemption has no callee list: the tool is built per
   * run from the run's own transcript, so declaring it is the whole of the grant.
   */
  public boolean canRedeem() {
    return tools.contains(ResultTools.READ_NAME);
  }

  /**
   * Whether this agent can be told what a fold took the reference lines away from.
   *
   * <p><b>{@code Projection} asks this before it writes the seam.</b> A seam for an agent that
   * holds {@code result_list} says the stored results behind it are still readable and names the
   * tool that lists them; a seam for one that does not says neither, because a sentence naming a
   * tool the model cannot call spends a turn on "there is no tool called result_list" and leaves a
   * false belief behind it — the same failure {@link #canRedeem()} exists to prevent one layer
   * down, arriving through a sentence instead of through a substitution.
   *
   * <p><b>Asked separately from {@link #canRedeem()} and not folded into it</b>, although the
   * shipped {@code interlocutor} declares both. Each gates exactly the text that names its own
   * tool: {@code canRedeem} decides whether results become references at all, and this decides
   * whether the seam points at the listing. An agent holding one and not the other is a definition
   * an operator can write, and each half is then right about itself rather than about a pair nobody
   * enforces.
   */
  public boolean canList() {
    return tools.contains(ResultTools.LIST_NAME);
  }

  /**
   * Whether the memory archive is in this agent's scope at all.
   *
   * <h2>It grants deliberate recall</h2>
   *
   * <p>An agent holding {@code memory_recall} has the archive in scope by an operator's decision.
   * That remains true even when {@link #canBeReminded()} refuses unsolicited recall: Daedalus may
   * ask memory for a hypothesis but must begin a diagnosis without the harness preloading one.
   *
   * <p>{@link #canRedeem()}'s shape exactly: one place asks the question, so the name cannot drift
   * from the one {@code MemoryTools.Recall} registers itself under.
   */
  public boolean canRecall() {
    return tools.contains(MemoryTools.RECALL_NAME);
  }

  /**
   * Whether the harness may inject an automatic memory shortlist.
   *
   * <p>Possessing {@code memory_recall} is necessary but not sufficient. An agent that can inspect
   * a persisted conversation trajectory is operating at an evidence boundary: unsolicited memories
   * would arrive before it had separated what the model saw from what is true, biasing the
   * diagnosis with claims from unrelated earlier conversations. It retains deliberate recall
   * through {@link #canRecall()}, but the harness remains silent.
   *
   * <p>The capability and not an agent name draws the boundary, so another diagnostic agent cannot
   * accidentally acquire reminders merely by being given a different name.
   */
  public boolean canBeReminded() {
    return canRecall() && !tools.contains(ConversationTrajectoryTool.NAME);
  }

  /**
   * Whether this agent can turn a memory's id into its full text.
   *
   * <p><b>Asked separately from {@link #canRecall()}</b>, for the reason {@link #canList()} is
   * asked separately from {@link #canRedeem()}: each gates exactly the sentence that names its own
   * tool. A reminder is a shortlist of summaries, and the clause telling a model where the full
   * text is belongs only in the reminder sent to an agent that was offered {@code memory_read} — a
   * sentence naming a tool that is not there spends a turn on "there is no tool called memory_read"
   * and leaves a false belief behind it.
   *
   * <p>The shipped {@code interlocutor} and {@code code_reviewer} declare both, and {@code
   * promotion_judge} declares this one alone. A definition holding either alone is one an operator
   * can write, and each half is then right about itself.
   */
  public boolean canReadMemory() {
    return tools.contains(MemoryTools.READ_NAME);
  }

  /**
   * Whether an answer by this agent may be read for citations.
   *
   * <h2>It gates {@code Citing}, and that is why it is here</h2>
   *
   * <p>{@code documents.Citations} reads paragraph ids out of a finished answer and writes them
   * into {@code citations}. Deciding which agents that applies to is a policy, and this
   * repository's rule is that a guardrail lives in the loader rather than in a prompt — so the
   * answer is a tool name the loader already validated, exactly as {@link #canBeReminded()} starts
   * from the recall grant before applying the diagnostic exclusion.
   *
   * <p><b>The line it draws is "the corpus was granted to this agent", not "this agent was told to
   * cite".</b> Those are different claims and the first is the one that can be checked: {@code
   * librarian.md}'s instruction to name a paragraph beside a claim is prose an editor can smooth
   * away, while {@code document_search} in a {@code tools:} list is a grant an operator made. An
   * agent holding the corpus can have been handed a paragraph id honestly; one that does not
   * cannot, so a uuid in its answer is a uuid about something else — a stored-result handle, a
   * memory id, an identifier out of somebody's source tree — and reading it as a citation would be
   * this server inventing a provenance nobody claimed.
   *
   * <p><b>It is also what keeps this off the hot path.</b> A document ingest is ~220 runs of agents
   * declaring {@code tools: []}, and {@link #canBeReminded()} names the same saving for the same
   * reason.
   *
   * <p>{@link #canRedeem()}'s shape exactly: one place asks the question, so the names cannot drift
   * from the adapters that return paragraph evidence. Ranking and outline summaries alone do not
   * grant citation eligibility.
   */
  public boolean canCite() {
    return tools.contains(DocumentTools.SEARCH_NAME)
        || tools.stream().anyMatch(RetrievalTools.EVIDENCE_TOOLS::contains);
  }
}
