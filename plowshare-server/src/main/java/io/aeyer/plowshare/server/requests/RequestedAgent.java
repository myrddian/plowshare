package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The agent a request named, or the refusal that says which agents exist.
 *
 * <h2>One copy, on {@link RequestedTurnCap}'s reasoning</h2>
 *
 * <p>{@code resolveHome} was once spelled out by hand, in {@code
 * AgentController} — three lines, and lifting them would have put a helper
 * between a request field and {@code Home}'s own message. That copy is gone
 * with the rest of {@code run}'s decision, which resolves through {@link
 * RequestedHome} like every other door. <b>This is not three lines
 * and it is not one message.</b> It is a refusal with its own subject and a
 * registry lookup beside it, wanted by the one door that still keeps its own
 * copy: {@code POST /v1/agents/&#123;name&#125;/runs}, which takes the name
 * from the path. {@code POST /v1/conversations/&#123;id&#125;/resume}, which
 * takes it from the body, resolves through {@link RequestedHome} instead.
 *
 * <h2>Why a grant has to name one at all</h2>
 *
 * <p><b>A conversation names no agent.</b> The agent is named per turn, which is
 * what lets a person open one conversation and put two different agents' turns
 * in it, and {@code turns} holds no column for which one answered — V7 lists
 * what that table deliberately does not carry. So a run continuing a stopped
 * turn cannot recover the agent from anything the archive holds, and the caller
 * says. Making it recoverable would be a column and a migration for a fact the
 * one caller already has.
 *
 * <h2>Why an unknown agent is a 400 and not a 404</h2>
 *
 * <p>The caller is choosing from a list, and the list is the correction. A 404
 * would be true and useless. {@code AgentController.run}'s javadoc has said so
 * since that endpoint existed and this is the same sentence, now in one place.
 *
 * <h2>Why a private agent gets a different sentence from a missing one</h2>
 *
 * <p><b>Decided rather than defaulted.</b> Identical refusals would leave a
 * caller no way to enumerate the private agents by probing names, which is a
 * real property and worth having on a server whose definitions belong to
 * somebody other than the person asking. It buys nothing here: Plowshare is a
 * single-user system and <em>the operator owns every file in the agent
 * directory</em>, so the only party who could probe the set already has it open
 * in an editor. What the vague answer would cost is the case that actually
 * happens — an operator who wrote an agent, ran it, and was told it does not
 * exist while looking at the file — and that cost is paid every time.
 *
 * <p>The sentence therefore names the key, and says what is still true: a
 * private agent is not broken and is not unreachable, it is merely not a front
 * door. Reading "not exported" as "not working" is the next wrong conclusion,
 * and delegation is exactly what {@code exported} was designed not to gate.
 *
 * <p>Both refusals list {@code exportedNames()} and not {@code names()}, which
 * is the half that keeps the promise: the message an unknown name gets back
 * still cannot be read as a directory listing of what is private.
 *
 * <h2>And it is not the resolved registry that is enumerated</h2>
 *
 * <p><b>A refusal may never name a definition that came off a client's own
 * machine.</b> The registry a run resolves against can include a connected
 * session's {@code .plowshare/}, so listing <em>its</em> exported names would
 * answer {@code POST /v1/agents/nope/runs} — naming any live session id — with
 * that session's local agent and bot names. {@code GET /v1/agents} had exactly
 * this capability and it was deliberately removed rather than gated, on the
 * argument its own javadoc carries: enumerating another live session's local
 * directory is a new capability, not a corollary of scoping by project.
 * Re-entering it through the refusal on the run path would be the same
 * capability with a 400 in front of it.
 *
 * <p>So the enumeration is a <b>separate supplier</b>, not {@code
 * registry.exportedNames()}: the lookup runs against everything the caller can
 * actually run — a client-local bot is still nameable and still starts — while
 * the list in the sentence is whatever the caller passes, which for {@code
 * agents.Callers} is the same server-side set {@code GET /v1/agents} would
 * answer for that project. <b>Filtered rather than dropped</b>, because the
 * class's own rule above is that the list <em>is</em> the correction, and an
 * operator who mistyped a project bot's name is exactly the reader this
 * sentence was written for; a refusal that named nothing would protect the same
 * property by taking the correction away from everybody. It is a {@code
 * Supplier} so that the second resolution it costs is paid only by a request
 * that is already being refused.
 *
 * <h2>{@code toRun} and {@code toRead} are two doors, not one door with a flag</h2>
 *
 * <p><b>{@code exported} is a rule about execution, and only {@link #toRun}
 * enforces it.</b> Its own message says so — "nothing outside this server may
 * run it" — and a caller merely looking at what an agent was shown, or what it
 * would cost, is not running it: {@code GET
 * .../projection} and the {@code priced} helper behind {@code GET
 * .../context} ask exactly that, and a delegated sub-agent — never exported,
 * by design — is the ordinary agent such a read is asked about. A rule about
 * running one that also refused reading it would leave the ordinary case of
 * auditing a delegated turn with neither its prompt nor its cost readable.
 * {@link #toRead} shares the other two refusals — no registry, no such name —
 * because those are about whether the agent exists at all, which is exactly as
 * true for a read as for a run.
 *
 * <p>This was one method taking a boolean until it was not: a caller reading
 * {@code in(agents, name, false)} cannot tell what the {@code false} governs,
 * and the next person adding a read would have had to go and look. Two names
 * carry the rule instead of a comment beside the call.
 *
 * <p><b>The class and every member here are public.</b> Before this move,
 * only {@link #toRun(AgentRegistry, Supplier, String)} was — {@code
 * agents.Callers.requireAgent} is the caller that moved out of {@code
 * AgentController} and needs this arm from below {@code api}, so it was the
 * one door in the class actually reached across a package boundary. {@link
 * #toRun(ObjectProvider, String)} and {@link #toRead} stayed
 * package-private on the strength of having no caller outside this package —
 * a statement that only {@code api} would ever call them. A second surface is
 * exactly what that statement refused: {@code ws} is being built to route
 * frames into the same subsystems this class resolves for, and a frame naming
 * an agent has to reach the same two doors this package now exists to hold
 * open for it — see this package's own javadoc. Neither method has such a
 * caller yet, but the narrower statement could not survive the move
 * regardless, and it is not recoverable by leaving two members behind.
 */
public final class RequestedAgent {

    private RequestedAgent() {
    }

    /**
     * The definition this name resolves to, for a caller about to run it.
     *
     * <p>The one entry point that also refuses an unexported agent — see the
     * class javadoc for why {@code exported} belongs here and not on {@link
     * #toRead}.
     *
     * @param agents the registry, through the provider it is taken by. <b>An
     *     {@link ObjectProvider} and not the registry</b>, because a deployment
     *     with no agent directory has no registry bean at all and a controller
     *     that required one would not start
     * @param name what the caller named, from a path or a body
     * @return the definition, never null
     * @throws CallerFault if this server defines no agents at all, if
     *     nothing is called that, or if the one that is is not exported. Three
     *     situations and three sentences: a deployment with nothing in it, a
     *     caller that can correct itself from the list, and a caller that named
     *     a real agent it may not start
     */
    public static AgentDefinition toRun(ObjectProvider<AgentRegistry> agents, String name) {
        AgentRegistry registry = registryOrRefuse(agents);
        // The registry itself, because this arm is handed the boot set -- the
        // one registry with no client tier in it. Every name it holds is the
        // same bytes on every install of this server.
        return toRun(registry, registry::exportedNames, name);
    }

    /**
     * {@link #toRun(ObjectProvider, String)}, over a registry already
     * resolved rather than one this method has to fetch.
     *
     * <p><b>{@code agents.Callers.requireAgent} calls this arm</b> — moved
     * there from {@code AgentController} itself, which called it directly
     * before this class's own javadoc named the arm's caller by package
     * rather than by class. {@code DefinitionResolver.forCaller} never
     * answers {@code null} — the boot set is always there, seeded from the
     * classpath at minimum — so there is nothing left for that caller to hand
     * an {@link ObjectProvider} for: a deployment that serves nothing is one
     * whose resolved registry holds no agents, which {@link
     * #definitionOrRefuse} already answers correctly with an empty {@code
     * exportedNames()} in the message. The {@code ObjectProvider} overload
     * survives for the caller that still can be wired with no registry at all
     * — a context that never imported {@code AgentsConfig}.
     *
     * <p>Public, unlike the rest of this class, because {@code
     * agents.Callers} is below {@code api} and needs this arm from there —
     * see this class's own javadoc.
     *
     * @param registry never null
     * @param offered what the two refusals may list — see the class javadoc's
     *     "and it is not the resolved registry that is enumerated". Asked only
     *     when a refusal is actually being built
     */
    public static AgentDefinition toRun(
            AgentRegistry registry, Supplier<Set<String>> offered, String name) {
        AgentDefinition definition = definitionOrRefuse(registry, offered, name);
        if (!definition.exported()) {
            throw new CallerFault(
                    "the agent '" + name + "' is not exported, so nothing outside this server may"
                            + " run it: its definition does not say 'exported: true'. It is not"
                            + " broken and it is not private to the model — another agent may"
                            + " still reach it through its own 'calls:'. This server runs "
                            + new TreeSet<>(offered.get()));
        }
        return definition;
    }

    /**
     * The definition this name resolves to, for a caller about to read it —
     * a projection of the prompt it would open with, or the price of that
     * prompt — rather than start it.
     *
     * <p><b>Does not check {@code exported}.</b> That axis gates who may run an
     * agent, on the class javadoc's argument, and a delegated sub-agent — the
     * ordinary subject of a read like this — is never exported. Refusing the
     * read here would be the execution rule answering a question it was never
     * written for.
     *
     * @param registry everything this caller can read, a client's own tier
     *     included
     * @param offered what a refusal may list, which is never a client tier
     * @param name what the caller named, from a path or a query parameter
     * @return the definition, never null
     * @throws CallerFault if nothing is called that — the sentence {@link #toRun}
     *     gives for the same situation
     */
    public static AgentDefinition toRead(
            AgentRegistry registry, Supplier<Set<String>> offered, String name) {
        return definitionOrRefuse(registry, offered, name);
    }

    /**
     * The same read against the boot set, for a caller that has no tier of its
     * own to be resolved for.
     */
    public static AgentDefinition toRead(ObjectProvider<AgentRegistry> agents, String name) {
        AgentRegistry registry = registryOrRefuse(agents);
        return definitionOrRefuse(registry, registry::exportedNames, name);
    }

    private static AgentRegistry registryOrRefuse(ObjectProvider<AgentRegistry> agents) {
        AgentRegistry registry = agents.getIfAvailable();
        if (registry == null) {
            throw new CallerFault(
                    "this server defines no agents, so there is nothing to run. No context wiring"
                            + " an agent registry at all is bound in this deployment");
        }
        return registry;
    }

    private static AgentDefinition definitionOrRefuse(
            AgentRegistry registry, Supplier<Set<String>> offered, String name) {
        // Looked up in `registry` -- everything this caller can actually run,
        // a client's own .plowshare/ included -- and refused with `offered`,
        // which is never that set. The lookup and the list answer two different
        // questions and only one of them may be enumerated.
        if (name == null || !registry.names().contains(name)) {
            throw new CallerFault(
                    "there is no agent called '" + name + "'; this server runs "
                            + new TreeSet<>(offered.get()));
        }
        return registry.get(name);
    }
}
