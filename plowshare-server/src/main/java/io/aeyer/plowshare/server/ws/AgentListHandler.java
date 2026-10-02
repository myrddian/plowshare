package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.api.AgentRows;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.requests.RequestedProjectId;
import java.util.Map;
import java.util.Objects;

/**
 * {@code agent.list} — what can be run, and what each one may do. The frame
 * equivalent of {@code GET /v1/agents}.
 *
 * <h2>The third way of building a caller, and it is the endpoint's own — minus
 * one field, which is this surface's own</h2>
 *
 * <p>{@link Asking} offers two doors, and this handler uses neither: {@code
 * GET /v1/agents} resolves its project through {@link
 * RequestedProjectId#forListing} and hands {@link DefinitionResolver} a {@code
 * Caller(projectId, null)} — always a null session, because that endpoint has
 * no session to give it; a request arrives with no socket attached. <b>A
 * frame is different, and this is deliberately the one place that difference
 * is allowed to show.</b> {@link Asking#sessionId()} names the very socket
 * that opened this frame, so passing it into {@link DefinitionResolver.Caller}
 * cannot enumerate <em>another</em> session's {@code .plowshare/} — the id is
 * never anyone's but the asker's own. It is what lets a terminal client that
 * has rooted a project see, in the one listing this server offers, the bots it
 * keeps beside its own files and not only the ones the server tree names; see
 * {@link DefinitionResolver#defaultBot} for the other half of what that
 * session buys. The refusal this class used to state — "the same field here
 * would let any caller enumerate another live session's local {@code
 * .plowshare/}" — no longer applies once the field carries only the asking
 * socket's own id, which is the reversal {@code
 * the_frame_lists_with_its_own_session_and_the_endpoint_with_none} in {@code
 * AgentFramesTest} exists to pin down rather than assume.
 *
 * <p>A listing also refreshes that caller's rooted-session registry. The
 * server's directory stamp cannot detect edits on the client, so retaining
 * that entry would leave added, edited or repaired local bots stale after
 * Refresh. Other sessions' caches are left alone.
 *
 * <h2>A deployment that serves nothing answers an empty list</h2>
 *
 * <p>Not a refusal — the endpoint's own promise, kept where it is easiest to
 * break. {@code agent.run} refuses an unknown name because naming one is a
 * caller's mistake it can correct; asking what this server runs is not a
 * mistake, and a console met with a refusal on its first screen would report a
 * deployment broken when it is merely empty.
 *
 * <p>A disabled agent is on this list, marked {@code served: false} with its
 * reason, and is never merely absent — a file that could not be parsed has no
 * {@code exported} to read, and filtering would make the least readable
 * definitions the ones that vanish.
 */
public final class AgentListHandler implements FrameHandler {

    private final DefinitionResolver resolver;
    private final ProjectStore projects;
    private final Callers callers;

    /**
     * @param resolver what answers the resolved set for a caller
     * @param projects the store a project name becomes an id through
     * @param callers the service that reads what was withheld from each agent
     */
    public AgentListHandler(DefinitionResolver resolver, ProjectStore projects, Callers callers) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.callers = Objects.requireNonNull(callers, "callers");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        Tiered asked = Payloads.as(payload, Tiered.class, FrameTypes.AGENT_LIST);
        DefinitionResolver.Caller caller = new DefinitionResolver.Caller(
                RequestedProjectId.forListing(projects, asked.project()), asking.sessionId());
        return Outcome.ok(AgentRows.of(resolver.refreshForCaller(caller), callers,
                resolver.defaultBot(caller)));
    }
}
