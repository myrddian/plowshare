package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.files.Grant;
import java.util.List;

/**
 * One agent's interface: what it is called, what it may do, and what it may delegate to.
 *
 * <h2>Deliberately not the system prompt</h2>
 *
 * <p>A console needs to offer a person a choice of agent and show what that choice permits. It has
 * no use for the instructions themselves, and <b>a prompt shipped to a browser is one XSS away from
 * being read by whatever influenced the text on the page</b> — which, on a server whose agents read
 * files and answer questions about them, is a wider set of things than it sounds. {@code
 * AgentDefinition} is the whole of what the runtime knows about an agent and this record is a
 * deliberate subset of it, so the omission is a decision and not an oversight about a field
 * somebody forgot.
 *
 * <p>{@code listing_agents_answers_what_each_may_do_and_never_its_prompt} is what holds the
 * boundary {@code description} does not cross: it asserts on a distinctive phrase from each
 * fixture's prompt rather than on the word "prompt", so a body that carried the prompt's text under
 * another field name would still fail it.
 *
 * <h2>{@code served} and {@code withheld} are the disable rule's whole surface</h2>
 *
 * <p>A definition the loader refuses no longer takes the boot down unless the system code depends
 * on that agent; it is disabled instead. <b>Abort-on-fail's value was that it could not be missed,
 * and this pair is what buys that back</b> — a row saying <em>disabled, because X</em> on the one
 * screen a person looks at. A silently missing agent is worse than a failed boot, because it is met
 * at first use with no explanation.
 *
 * <p>The two are separate because the two states are separate. {@code served: false} with one
 * reason is an agent that is not there; {@code served: true} with reasons is an agent that
 * <em>is</em> there, minus something it declared — a delegation route, or a name inside one of its
 * grants that this server was never going to issue. A single field would render the second as the
 * first.
 *
 * <p><b>Routes and grant items share the one list, deliberately.</b> They are one subject on a
 * screen — something an agent asked for and did not get, on an agent that runs — and the
 * distinction that must not be lost is from the disabled case, which {@code served} carries on its
 * own. {@code agents.Callers.withheldFrom} assembles them and owns the argument.
 *
 * <h2>{@code bot} is what kind of thing this is, and it is appended</h2>
 *
 * <p>A caller that knows an agent from a bot can offer the two apart: a bot is a character somebody
 * talks to and the ordinary way a person uses this system, an agent is a role something invokes and
 * may answer with a structure rather than a sentence. Nothing outside the loader could tell them
 * apart before this field, so a terminal listing what a deployment serves had one undifferentiated
 * list and no way to build two.
 *
 * <p><b>Appended, with nothing removed and nothing reordered.</b> Every field above it means what
 * it meant, so a console pinned to this shape reads exactly what it read before and simply does not
 * look at the last one.
 *
 * <p>It is the definition's own {@code bot:}, not the directory the file was found in — {@code
 * DataLayout.botsFor}'s javadoc owns that decision, and this view would be the easiest place to
 * quietly reverse it.
 *
 * <h2>{@code description} is who this is, and it is appended after {@code bot}</h2>
 *
 * <p>A caller that can tell a bot from an agent still could not say what either one <em>is</em>
 * without it: a bare name is survivable for an agent, whose name is usually its job, and it defeats
 * the point for a bot, whose one line of legible difference from a role is the sentence saying who
 * he is. {@code AgentDefinition} always parsed this and checked it for non-blankness; this view
 * simply stops declining to send it.
 *
 * <p><b>Appended, after {@code bot} and for the reason that field was:</b> every field above it
 * keeps meaning what it meant, so a console pinned to the shape this record had before either of
 * the last two fields existed reads exactly what it read before and simply does not look at the
 * ones it does not know.
 *
 * <p><b>Empty for a definition this server could not read</b>, the same rule {@code bot} follows
 * and for the same reason: a file that failed to parse has no {@code description:} to read, and an
 * empty string is the honestly sayable value rather than a placeholder this server invented.
 *
 * <h2>{@code scopes} is the enforced set and not a description of one</h2>
 *
 * <p>What the definition declared is also what {@code AgentRegistry.load} validated at boot — it
 * refuses any graph in which a callee holds a grant its caller does not — and what {@code
 * LocalProvider}'s constructor is handed and cannot widen. <b>The guardrail is in the loader and
 * the provider, not in the prompt</b>, so a console showing this list is showing a person what the
 * agent can reach rather than what it was asked to confine itself to.
 *
 * <p>Written as {@code Grant.declaration} — {@code workspace:read} — and not as the record's {@code
 * toString}. That method is the documented inverse of {@code Grant.parse}, so what a console
 * displays is the same string an operator would type into the agent's {@code scopes:} line; {@code
 * toString} would send a Java rendering that round-trips nowhere and would change under a refactor
 * nobody thought was a wire change.
 *
 * @param name what {@code POST /v1/agents/&#123;name&#125;/runs} takes in its path
 * @param tools the capabilities this agent holds. Structural rather than instructional: an agent
 *     without a file tool cannot open a file, whatever it is asked to do
 * @param calls the agents it may delegate to. Empty for an agent that may not — and it is empty
 *     exactly when {@code tools} lacks {@code agent_run}, which {@code AgentRegistry} refuses a
 *     definition for disagreeing about
 * @param scopes the places it may reach and how much of each, each written the way an agent file
 *     writes it
 * @param served whether this agent can be run at all. False for one this server read and refused,
 *     whose three lists above are then empty
 * @param withheld what this server took away from the agent, and why, in sentences. One entry for
 *     an agent that is not served — the refusal itself — and, for an agent that is, one per
 *     delegation route dropped and one per name dropped from a grant. Empty in the ordinary case
 * @param bot whether this is somebody to talk to rather than a role to invoke. False for every
 *     agent, and false for a definition this server could not read — see {@link #disabled}
 * @param description who or what this is, in the definition's own words. Empty for a definition
 *     this server could not read — see {@link #disabled}
 * @param preferred whether this tier's {@code bots/default} names this row; at most one row in a
 *     listing is
 * @param model the specifier this agent's requests are dispatched under, as its definition writes
 *     it — a class or a wire model, not what a pool resolved it to. Null for a definition this
 *     server could not read. Carried so a client can say what it is talking to before a
 *     conversation exists, which {@code ContextView.Prefix} cannot: a conversation is not opened
 *     until somebody speaks
 */
public record AgentView(
    String name,
    List<String> tools,
    List<String> calls,
    List<String> scopes,
    boolean served,
    List<String> withheld,
    boolean bot,
    String description,
    boolean preferred,
    String model,
    List<String> orchestrations,
    List<String> skills,
    List<io.aeyer.plowshare.server.agents.CommandCatalog.Entry> commands,
    String displayName,
    String origin,
    boolean dynamic) {

  /** Compatibility for existing programmatic views without runtime-tool opt-in. */
  public AgentView(
      String name,
      List<String> tools,
      List<String> calls,
      List<String> scopes,
      boolean served,
      List<String> withheld,
      boolean bot,
      String description,
      boolean preferred,
      String model,
      List<String> orchestrations,
      List<String> skills,
      List<io.aeyer.plowshare.server.agents.CommandCatalog.Entry> commands,
      String displayName,
      String origin) {
    this(
        name,
        tools,
        calls,
        scopes,
        served,
        withheld,
        bot,
        description,
        preferred,
        model,
        orchestrations,
        skills,
        commands,
        displayName,
        origin,
        false);
  }

  public AgentView(
      String name,
      List<String> tools,
      List<String> calls,
      List<String> scopes,
      boolean served,
      List<String> withheld,
      boolean bot,
      String description,
      boolean preferred,
      String model,
      List<String> orchestrations,
      List<String> skills,
      List<io.aeyer.plowshare.server.agents.CommandCatalog.Entry> commands) {
    this(
        name,
        tools,
        calls,
        scopes,
        served,
        withheld,
        bot,
        description,
        preferred,
        model,
        orchestrations,
        skills,
        commands,
        null,
        null);
  }

  public AgentView(
      String name,
      List<String> tools,
      List<String> calls,
      List<String> scopes,
      boolean served,
      List<String> withheld,
      boolean bot,
      String description,
      boolean preferred,
      String model,
      List<String> orchestrations,
      List<String> skills) {
    this(
        name,
        tools,
        calls,
        scopes,
        served,
        withheld,
        bot,
        description,
        preferred,
        model,
        orchestrations,
        skills,
        List.of());
  }

  public AgentView withCommands(
      List<io.aeyer.plowshare.server.agents.CommandCatalog.Entry> entries) {
    return new AgentView(
        name,
        tools,
        calls,
        scopes,
        served,
        withheld,
        bot,
        description,
        preferred,
        model,
        orchestrations,
        skills,
        entries,
        displayName,
        origin,
        dynamic);
  }

  /** Lists a logical address with the selected variant's capabilities and visible identity. */
  public AgentView addressedAs(String address) {
    if (name.equals(address)) return this;
    return new AgentView(
        address,
        tools,
        calls,
        scopes,
        served,
        withheld,
        bot,
        description + " (" + address + " resolves to " + name + ")",
        preferred,
        model,
        orchestrations,
        skills,
        commands,
        displayName,
        origin,
        dynamic);
  }

  public AgentView withSkillRefusals(List<String> reasons) {
    var all = new java.util.ArrayList<>(withheld);
    all.addAll(reasons);
    return new AgentView(
        name,
        tools,
        calls,
        scopes,
        served,
        all,
        bot,
        description,
        preferred,
        model,
        orchestrations,
        skills,
        commands,
        displayName,
        origin,
        dynamic);
  }

  /** The pre-skills wire shape. */
  public AgentView(
      String name,
      List<String> tools,
      List<String> calls,
      List<String> scopes,
      boolean served,
      List<String> withheld,
      boolean bot,
      String description,
      boolean preferred,
      String model,
      List<String> orchestrations) {
    this(
        name,
        tools,
        calls,
        scopes,
        served,
        withheld,
        bot,
        description,
        preferred,
        model,
        orchestrations,
        List.of());
  }

  /** The wire shape before orchestration grants were visible. */
  public AgentView(
      String name,
      List<String> tools,
      List<String> calls,
      List<String> scopes,
      boolean served,
      List<String> withheld,
      boolean bot,
      String description,
      boolean preferred,
      String model) {
    this(
        name,
        tools,
        calls,
        scopes,
        served,
        withheld,
        bot,
        description,
        preferred,
        model,
        List.of());
  }

  /**
   * <b>Public because {@code ws} renders this view too.</b> It was package-private while {@code
   * api} was the only surface that had a shape to render into; a frame handler for {@code
   * agent.list} and one for {@code agent.define} answer with exactly this record, and a second
   * factory of their own is how the two surfaces would come to disagree about what a console may
   * say about an agent.
   *
   * @param definition the agent as this server resolved it
   * @param withheld what this server took away from it, in sentences
   */
  public static AgentView of(AgentDefinition definition, List<String> withheld) {
    return new AgentView(
        definition.name(),
        definition.tools(),
        definition.calls(),
        definition.scopes().stream().map(Grant::declaration).toList(),
        true,
        withheld,
        definition.bot(),
        definition.description(),
        false,
        definition.model(),
        definition.orchestrations(),
        definition.skills(),
        List.of(),
        definition.displayName(),
        definition.origin(),
        definition.dynamic());
  }

  /**
   * An agent this server read and is not serving.
   *
   * <p>The three declaration lists are empty rather than absent, and that is the honest shape: a
   * definition that failed to parse has no {@code tools} to report, and one that parsed and was
   * refused for a reason about the <em>set</em> has declarations that were never allowed to take
   * effect. Either way what a console may say about this agent is its name and why it is not there.
   *
   * <p><b>Public for {@link #of}'s reason</b>: both frame handlers that render an agent reach this
   * branch too — a listing carries the ones this server read and refused, and a global-tier write
   * has no resolved view at all.
   *
   * <p><b>{@code bot} is false here for the same reason the lists are empty</b>, and it is a
   * statement about this server rather than about the file. A definition that failed to parse has
   * no {@code bot:} to read, just as it has no {@code exported}; what is honestly sayable is that
   * nothing here can be talked to, which false is. A caller building a list of bots from this gets
   * the right list, and the row still appears under its name with the reason it is not there.
   *
   * <p><b>{@code description} is empty here for the same reason</b>: a definition that failed to
   * parse has no {@code description:} to read, and an empty string says that honestly rather than
   * inventing a placeholder sentence about a file this server never understood.
   *
   * @param name the agent's own name, as the caller wrote it
   * @param reason why this server is not serving it
   */
  public static AgentView disabled(String name, String reason) {
    return new AgentView(
        name,
        List.of(),
        List.of(),
        List.of(),
        false,
        List.of(reason),
        false,
        "",
        false,
        null,
        List.of());
  }

  /** The same row, marked as the one its tier's {@code bots/default} names. */
  public AgentView preferring() {
    return new AgentView(
        name,
        tools,
        calls,
        scopes,
        served,
        withheld,
        bot,
        description,
        true,
        model,
        orchestrations,
        skills,
        commands,
        displayName,
        origin,
        dynamic);
  }
}
