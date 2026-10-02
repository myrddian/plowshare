package io.aeyer.plowshare.protocol;

/**
 * The body of {@code POST /v1/agents} — what a caller hands the server to
 * create or replace one agent or bot definition.
 *
 * <p>Lives here rather than beside {@code RunAgentRequest} in {@code
 * plowshare-server}'s own {@code api} package, unlike every other request body
 * that controller reads. That is deliberate rather than an inconsistency: a
 * run or a curator pass is submitted only from inside this server's own HTTP
 * layer, but a definition is authored — this is the one door an operator's own
 * tooling writes through rather than merely calls. The CLI verb and the MCP
 * tool that later front this same endpoint live in {@code plowshare-client},
 * which depends on this module and not on {@code plowshare-server}; putting the
 * wire shape here is what lets them serialise the identical request rather
 * than each inventing their own record that has to be kept in step with this
 * one by hand.
 *
 * <p><b>This is an operator surface and not an agent-facing one.</b> Nothing
 * about this record changes that — {@code plowshare-protocol} is read by both
 * sides of every wire format this codebase has, agent-facing and
 * operator-facing alike — the boundary is enforced by which tools a running
 * agent is ever handed, not by which module a request type is declared in.
 *
 * <p><b>{@code project} is a name, matching every other identifier this
 * module's callers already type.</b> A first version of this record carried a
 * numeric {@code projectId} on the reasoning that {@code DefinitionWriter}
 * itself takes a surrogate id — true, but the wrong layer to expose it at:
 * {@code GET /v1/agents} and {@code POST /v1/agents/&#123;name&#125;/runs}
 * both take a project <em>name</em> and translate it to an id inside the
 * server, and three different clients (a CLI, an MCP tool, a console screen)
 * were about to each bind to whichever of the two contracts this record
 * happened to expose. One tier now has one identifier on every door that
 * names it; the translation from name to surrogate id happens once, inside
 * {@code agents.Definitions}, exactly where the other two doors already do it.
 *
 * @param project which tier the definition is written into: a project's own
 *     {@code bots/}, named the way a person types it, or the global tier for
 *     {@code null} or blank — the same null-means-global convention {@link
 *     Home} follows elsewhere in this module. A name with no row is refused
 *     rather than silently written into the global tier; see {@code
 *     requests.RequestedProjectId.forWrite}'s own javadoc for why a write
 *     cannot take the
 *     read-side rule that lets an unresolved project name degrade there
 * @param name the file's stem the definition is written under — refused, not
 *     corrected, if it disagrees with the file's own {@code name:} frontmatter
 *     or is not a bare filename stem
 * @param text the whole file, frontmatter and body, exactly as it is to land on
 *     disk. Validated before anything is written and never reformatted, and
 *     bounded in size — {@code DefinitionWriter.MAX_DEFINITION_BYTES} is the
 *     ceiling and its own javadoc argues the number
 * @param overwrite whether a definition already at this name should be
 *     replaced. {@code false} is the ordinary case and refuses rather than
 *     silently destroying whatever was there; a caller that means to replace an
 *     existing definition has to say so
 */
public record DefineAgentRequest(String project, String name, String text, boolean overwrite) {}
