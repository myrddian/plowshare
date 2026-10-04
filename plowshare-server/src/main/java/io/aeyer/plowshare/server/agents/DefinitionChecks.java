package io.aeyer.plowshare.server.agents;

/**
 * The checks on a loaded set that {@link AgentRegistry} cannot make for itself, applied to every
 * tier and not only to the boot one.
 *
 * <h2>Why this seam exists</h2>
 *
 * <p>{@code AgentRegistry.read} knows what the files said and nothing else. Two questions about a
 * definition need a fleet to answer — <em>is this model served by any pool</em> and <em>does it
 * see</em> — and one needs the sampling profiles — <em>what does this agent actually send</em>.
 * {@code AgentsConfig} has all three in its hands at boot and asked them there, which left the
 * project and client tiers judged by a strictly weaker rule than the tier they layer over: the same
 * file resolved its sampling profile in {@code global/agents/} and kept its raw declared sampling
 * in {@code projects/7/agents/}, and a model no pool serves was fatal at boot and silently accepted
 * under a project. A definition must not mean two different things depending on which directory it
 * sits in — that is precisely the invisible fact this repository designs against — so the checks
 * travel to the tiers instead of the tiers travelling to the boot.
 *
 * <p><b>A function and not a second constructor argument on the registry.</b> {@link
 * DefinitionResolver} lives in this package and must not learn what a pool or a sampling profile
 * is; {@code AgentsConfig} already knows both. One lambda handed down at wiring time is the whole
 * of the coupling, and it is the same code path the boot set runs through, so the two cannot drift.
 *
 * <h2>The contract, which is the whole reason this is not just a `Consumer`</h2>
 *
 * <p><b>An implementation may disable a definition and may never throw for a hot-loaded tier.</b>
 * {@link AgentRegistry.Loaded#without} is the one move available: it moves a definition from served
 * to refused, naming the reason, and the resolver folds that into {@code refusalsFor} and logs it.
 * Nothing a project or a client directory holds may abort a resolution — {@code
 * DefinitionResolver.readProject} would catch a throw and drop the whole tier, which is a far
 * larger blast radius than one bad definition deserves. The boot arm keeps its abort, because
 * {@code REQUIRED} still has a boot to report to; that difference is a set the caller passes, not a
 * difference in this seam.
 */
@FunctionalInterface
public interface DefinitionChecks {

  /**
   * The checks nothing performs, for a context wired with no dispatcher and no sampling profiles to
   * ask.
   *
   * <p>Named at every call site rather than defaulted, so a resolver built without the fleet's
   * opinion says so where it is built. A silent default here is how the boot and the tiers came to
   * disagree in the first place.
   */
  DefinitionChecks NONE = (loaded, source) -> loaded;

  /**
   * The same reading, with every definition the fleet cannot answer for moved from served to
   * refused, and every served one carrying whatever sampling it will actually send.
   *
   * @param loaded what the files parsed to, already validated as a graph
   * @param source what a refusal should name — {@link DefinitionSource#describe()} of the tier this
   *     reading came from, so an operator is sent to the directory holding the file rather than to
   *     whichever tier happened to be checked first
   * @return the reading to serve, never null
   */
  AgentRegistry.Loaded applyTo(AgentRegistry.Loaded loaded, String source);
}
