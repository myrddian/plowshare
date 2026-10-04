package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/agents}: what was written, and whether this running process already
 * serves it.
 *
 * <h2>Why a boolean and not a sentence</h2>
 *
 * <p>A write to the global tier lands on disk correctly and is not visible to this process until it
 * restarts — {@code DefinitionResolver}'s own top javadoc is explicit that the boot set is read
 * once and never rebuilt. Before this type existed, that fact lived only inside {@link
 * AgentView#withheld}'s prose, which is the wrong shape for the readers of this endpoint: a CLI or
 * an MCP tool built over this same response has to be able to say "restart required" without
 * parsing a sentence for one, and a sentence that changed wording would silently break that parse.
 * {@link #restartRequired} is the one bit those callers actually need, asked for directly.
 *
 * <p><b>The status stays {@code 201}/{@code 200} either way</b>, rather than moving to {@code 202
 * Accepted} for the global case. The write genuinely completed — the bytes are on disk, atomically,
 * before this response is ever built — and {@code 202} means work was merely <em>queued</em>, which
 * this is not. A status implying deferred processing would be a less accurate account of what
 * happened than a field saying "written, and also: not served by this process yet".
 *
 * <p><b>Public because {@code agent.define} answers with it too.</b> This was package-private while
 * {@code api} was the only surface that could render a body; the frame surface answers the
 * identical record, so that a client reading the socket and a client reading the endpoint are
 * reading one shape and not two spellings of it.
 *
 * @param agent the resolved view. {@code served: false} here means exactly what it means on {@code
 *     GET /v1/agents} — this server read the definition back and is not serving it — and never
 *     stands in for "this process has not reread its directories yet"; {@link #restartRequired}
 *     carries that fact on its own rather than overloading {@code served}
 * @param restartRequired {@code true} for a write to the global tier, where nothing an operator
 *     does short of restarting this process makes the write visible; {@code false} for a
 *     project-tier write, which {@code agents.Definitions.define} has already made visible by the
 *     time this is built, through {@code DefinitionResolver.invalidate}
 */
public record DefinedAgent(AgentView agent, boolean restartRequired) {}
