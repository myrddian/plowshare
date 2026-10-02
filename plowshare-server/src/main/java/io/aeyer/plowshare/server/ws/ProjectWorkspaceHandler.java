package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ProjectView;
import io.aeyer.plowshare.server.api.SetWorkspaceRequest;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.requests.RequestedPaths;
import java.util.Map;
import java.util.Objects;

/**
 * {@code project.workspace} — point an existing project at a different
 * directory. The frame equivalent of {@code POST
 * /v1/projects/&#123;name&#125;/workspace}.
 *
 * <h2>Not {@code project.define} with the old exclusions passed back in</h2>
 *
 * <p>The endpoint's argument, and it is the reason this type exists at all
 * rather than a client being told to compose two others. Doing it through a
 * define would be a read and a write with a window between them, and a client
 * that forgot the read would silently drop every path an operator had fenced
 * off. {@link ProjectStore#moveWorkspace} is one statement and keeps them.
 *
 * <h2>A project with no workspace is {@link Code#NOT_FOUND}, and does not
 * upsert</h2>
 *
 * <p>The one place this differs from {@code project.define}, which does. Naming
 * and moving are the same act when you write the whole definition and are not
 * when you write one field, so a mistyped name that quietly created here would
 * leave a project nobody meant to define holding a real directory. The refusal
 * is the store's own {@code ArchiveException} travelling untouched to {@code
 * Faults}; nothing here catches it and nothing here decides it is a 404.
 */
public final class ProjectWorkspaceHandler implements FrameHandler {

    private final ProjectStore projects;

    /**
     * @param projects the one store both surfaces move a workspace through
     */
    public ProjectWorkspaceHandler(ProjectStore projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String name = Payloads.required(payload, "project", FrameTypes.PROJECT_WORKSPACE,
                "the name project.list answers with. Nothing was moved.");
        SetWorkspaceRequest asked =
                Payloads.as(payload, SetWorkspaceRequest.class, FrameTypes.PROJECT_WORKSPACE);
        ProjectRecord moved =
                projects.moveWorkspace(name, RequestedPaths.workspace(asked.workspace()));
        return Outcome.ok(ProjectView.of(moved, projects.effectiveExclusions(moved)));
    }
}
