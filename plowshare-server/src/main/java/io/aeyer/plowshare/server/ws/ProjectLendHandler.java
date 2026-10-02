package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.LendRequest;
import io.aeyer.plowshare.server.api.ProjectView;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.requests.RequestedPaths;
import java.util.Map;
import java.util.Objects;

/**
 * {@code project.lend} — let a project also reach further directories on this
 * server, without disturbing where it is. The frame equivalent of {@code POST
 * /v1/projects/&#123;name&#125;/lend}.
 *
 * <h2>The project comes out of the payload, under the noun</h2>
 *
 * <p>The endpoint takes it from the path; a frame has no path, so it is {@code
 * "project"} in the payload per {@link Payloads}' convention, read before the
 * body's own fields for the reason {@link DocumentStanceHandler} gives: a URL
 * resolves its path before Spring binds a body, so "which project" is the first
 * thing either surface knows.
 *
 * <p><b>It is not taken from the session.</b> {@link Asking} carries one and
 * this handler ignores it, exactly as the endpoint ignores everything but its
 * own path: lending is the verb an operator points at a project by name, and a
 * socket is opened per session rather than per project.
 *
 * <h2>Additive, and nothing here restates the fence</h2>
 *
 * <p>{@link ProjectStore#lend} is the one method both surfaces call. The bound
 * on what a lend can reach is the mandatory exclusions, which {@code
 * FileAccess.of} applies to a lent root exactly as it applies to a workspace —
 * below both surfaces, and deliberately not second-guessed here. What this does
 * make is {@code RequestedPaths.roots}' refusal, which is a caller's empty list
 * rather than a rule about directories, and it makes it by calling the same
 * factory the controller calls rather than by having a sentence of its own.
 *
 * <p>Answers with the whole resulting leash and not with what was added, so an
 * operator who mistyped one path of three reads the result and sees it.
 */
public final class ProjectLendHandler implements FrameHandler {

    private final ProjectStore projects;

    /**
     * @param projects the one store both surfaces lend through
     */
    public ProjectLendHandler(ProjectStore projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String name = Payloads.required(payload, "project", FrameTypes.PROJECT_LEND,
                "the name project.list answers with. Nothing was lent.");
        LendRequest asked = Payloads.as(payload, LendRequest.class, FrameTypes.PROJECT_LEND);
        ProjectRecord lent = projects.lend(name, RequestedPaths.roots(asked.roots()));
        // effectiveExclusions and never the row's own list, for the reason
        // ProjectView states and ProjectController's own view() repeats.
        return Outcome.ok(ProjectView.of(lent, projects.effectiveExclusions(lent)));
    }
}
