package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ProjectView;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import java.util.Map;
import java.util.Objects;

/**
 * {@code project.list} — every leash that has been set. The frame equivalent of {@code GET
 * /v1/projects}.
 *
 * <h2>Each row carries its <em>effective</em> exclusions, not its own</h2>
 *
 * <p>The one thing this listing exists to get right, and the one a second surface could drop while
 * still answering a well-formed list of well-formed projects: a row holds what was configured, and
 * the paths no project may override are added on top by {@link ProjectStore#effectiveExclusions}. A
 * client shown the row's list would be shown a shorter leash than the one this server enforces,
 * which is precisely the question a person opens this screen to answer. {@code ProjectView} argues
 * it at length and {@code ProjectController.view} makes the same call for the same reason.
 *
 * <h2>Each row also carries which machine roots it</h2>
 *
 * <p>A path alone cannot tell a terminal starting up whether it is sitting inside a project the
 * server already knows: the same string names two different trees on two different machines. So
 * each row asks {@link ProjectStore#rootedElsewhere} for the client machine currently rooting it,
 * {@code null} when the server holds the files or nothing does. Ruling 5 keeps this query off every
 * other answer this type is rendered into — {@code define}, {@code lend}, {@code workspace} and
 * {@code move} keep the two- argument {@code ProjectView.of} — because only a listing is read by
 * something deciding whether to root.
 *
 * <h2>A payload with nothing in it, deliberately</h2>
 *
 * <p>The endpoint takes no path value, no body and no query parameter, so there is nothing for this
 * type's payload to carry and no record to bind — {@link JobListHandler} is in the same shape for
 * the same reason. Unfiltered and unpaged: a project is the thing an operator chooses
 * <em>between</em> here, so a {@code project} filter would answer a question nobody is asking, and
 * a limit would be a number invented rather than measured.
 *
 * <h2>What this hands out, and why it is still not a tool</h2>
 *
 * <p>The answer is every project's workspace and every path fenced off around it — a map of this
 * server's disk and of where its secrets are not. {@code ProjectController} records that no MCP
 * tool reaches it for exactly that reason, and a frame type changes nothing about that: the socket
 * surface is reached by a client a person opened, the same door the console uses, and never by
 * {@code JobRuntime.knownTools()}.
 */
public final class ProjectListHandler implements FrameHandler {

  private final ProjectMembers members;
  private final ProjectStore projects;

  /**
   * @param projects the same store the controller is injected with, which decides both the rows and
   *     each one's effective exclusions
   */
  public ProjectListHandler(ProjectStore projects, ProjectMembers members) {
    this.members = members;
    this.projects = Objects.requireNonNull(projects, "projects");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(
        projects.allForSession(asking.handle(), asking.sessionId()).stream()
            .map(
                project ->
                    view(project)
                        .withRole(
                            members
                                .role(project.name(), asking.handle())
                                .map(Enum::name)
                                .orElse(null)))
            .toList());
  }

  private ProjectView view(ProjectRecord project) {
    ProjectView view =
        ProjectView.of(
            project,
            projects.effectiveExclusions(project),
            projects.rootedElsewhere(project.name()).orElse(null),
            members.members(project.name()));
    return projects.personalOwner(project.name()).map(view::personal).orElse(view);
  }
}
