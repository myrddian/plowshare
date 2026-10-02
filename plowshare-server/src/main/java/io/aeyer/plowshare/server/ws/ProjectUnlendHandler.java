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
 * {@code project.unlend} — stop lending directories. The frame equivalent of
 * {@code POST /v1/projects/&#123;name&#125;/unlend}.
 *
 * <h2>A type of its own rather than a direction on {@link ProjectLendHandler}</h2>
 *
 * <p>The endpoint's own decision, kept rather than re-argued: one route taking a
 * direction would make "lend this and unlend it" a request with no defensible
 * answer, and a caller who filled in the wrong field would do the opposite of
 * what they meant with nothing to say so. That argument is if anything stronger
 * on a socket, where a client builds the payload rather than a person typing a
 * URL.
 *
 * <h2>The paths are not checked against the disk, on either surface</h2>
 *
 * <p>{@link ProjectStore#unlend} argues why: the commonest reason to unlend a
 * directory is that it has gone, and a check would leave such a root
 * permanently stuck in the row. So the only refusal this makes before the store
 * is {@code RequestedPaths.roots}', which is about a caller naming none — the
 * same factory, and therefore the same sentence, as {@link ProjectLendHandler}
 * and as both endpoints.
 *
 * <p>Answers 200 with the remaining leash and not {@link
 * io.aeyer.plowshare.protocol.frames.Code#NO_CONTENT} the way {@link
 * ProjectForgetHandler} does: there is still a leash to describe, which is the
 * whole difference between taking a lent directory back and dropping the
 * project's place.
 */
public final class ProjectUnlendHandler implements FrameHandler {

    private final ProjectStore projects;

    /**
     * @param projects the one store both surfaces unlend through
     */
    public ProjectUnlendHandler(ProjectStore projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String name = Payloads.required(payload, "project", FrameTypes.PROJECT_UNLEND,
                "the name project.list answers with. Nothing was taken back.");
        LendRequest asked = Payloads.as(payload, LendRequest.class, FrameTypes.PROJECT_UNLEND);
        ProjectRecord left = projects.unlend(name, RequestedPaths.roots(asked.roots()));
        return Outcome.ok(ProjectView.of(left, projects.effectiveExclusions(left)));
    }
}
