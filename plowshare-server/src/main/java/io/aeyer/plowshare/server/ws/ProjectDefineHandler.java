package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.DefineProjectRequest;
import io.aeyer.plowshare.server.api.ProjectView;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.requests.RequestedPaths;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * {@code project.define} — name a project's workspace and its exclusions,
 * replacing whatever it had. The frame equivalent of {@code POST /v1/projects},
 * and the write half of the two pilots the dispatcher plan names.
 *
 * <h2>The same store method the controller calls, and nothing between</h2>
 *
 * <p>{@code ProjectController.define} reads a workspace through {@code
 * RequestedPaths}, turns two absent lists into empty ones through the same
 * class, calls {@link ProjectStore#defineLending} and renders what comes back
 * as a {@link ProjectView} carrying the <em>effective</em> exclusions. This
 * does those four things and no others. <b>It does not re-check anything the
 * store checks</b> — that a workspace is a directory, that a lent root exists,
 * that a name carries no edge whitespace are all {@code ProjectStore}'s, and a
 * refusal restated here would be a second copy to keep in step with the first.
 * Nor does it catch what the store throws: {@link FrameRouter} hands every
 * exception to {@code Faults}, which is the one table this surface and the HTTP
 * surface both read their status out of, so a {@code ValidationException} is a
 * 422 on both without either surface deciding that for itself.
 *
 * <h2>No caller is built, and that is not an omission</h2>
 *
 * <p>{@code project.define} names in its own payload the project it defines,
 * exactly as the endpoint's body does, and nothing below this method resolves
 * anything against a {@code DefinitionResolver.Caller}. So this handler builds
 * none — see {@link Asking} for why the surface hands it a session rather than
 * a caller, and why a handler that <em>does</em> need one takes its project
 * from the payload too.
 *
 * <h2>Why the request and the answer are {@code api/}'s own records</h2>
 *
 * <p>{@link DefineProjectRequest} and {@link ProjectView} are read and written
 * here rather than copied into a frame-shaped pair of records. The parity this
 * slice is measured by is that the two surfaces take the same fields and answer
 * the same fields, and one record used twice is the only version of that which
 * cannot drift: a second {@code ProjectView} would agree on the day it was
 * written and diverge on the day a component was added to one of them. They sit
 * in {@code api/} because HTTP is where they were needed first, and §4 has them
 * moving rather than being duplicated when HTTP eventually narrows — a move is
 * one commit, and the alternative is two shapes that a reader has to diff.
 * Nothing HTTP-specific travels with them: they are records of strings and
 * lists, and neither names a status, a header or a {@code ResponseEntity}.
 */
public final class ProjectDefineHandler implements FrameHandler {

    private final ProjectStore projects;

    /**
     * @param projects the one store both surfaces write through
     */
    public ProjectDefineHandler(ProjectStore projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        DefineProjectRequest asked =
                Payloads.as(payload, DefineProjectRequest.class, FrameTypes.PROJECT_DEFINE);
        Path workspace = RequestedPaths.workspace(asked.workspace());
        ProjectRecord defined = projects.defineLending(asked.name(), workspace,
                RequestedPaths.each(asked.lent()), RequestedPaths.each(asked.exclusions()), asking.handle());
        // effectiveExclusions and never the row's own list, for the reason
        // ProjectView states at length and ProjectController's own view()
        // repeats: the row carries what was configured, and answering with it
        // would show an operator a shorter leash than the one they have.
        return Outcome.ok(ProjectView.of(defined, projects.effectiveExclusions(defined)));
    }
}
