package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.MoveProjectRequest;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.Projects;
import io.aeyer.plowshare.server.requests.RequestedPaths;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import java.util.Map;
import java.util.Objects;

/**
 * {@code project.move} — give a project a different name, and its whole archive with it. The frame
 * equivalent of {@code POST /v1/projects/&#123;name&#125;/move}.
 *
 * <h2>{@link Code#NO_CONTENT}, and an {@code OK} here would be a different promise</h2>
 *
 * <p>The endpoint answers 204 with no body because nothing about the project is new except what it
 * is called, so a body would describe a change that did not happen. {@code Outcome.ok()} is 200 and
 * is the easy mistake: it is well-formed, it carries no payload either, and it would tell a client
 * something this verb does not mean. The sentence a person reads is {@code project_move}'s, on the
 * client side, and not this surface's.
 *
 * <h2>The presence rule is called here, and calling {@code rename} alone would have lost it</h2>
 *
 * <p>{@link Projects#refuseIfRooted} is not a store rule and not a request shape: it is a lookup in
 * {@link PresenceRegistry}, which is runtime state that no database transaction spans. A handler
 * that had gone straight to {@link ProjectStore#rename} would have moved a project out from under a
 * live session with nothing failing anywhere — the session rooting a name no row holds, every run
 * in the new name finding no presence, and the client re-declaring the <em>old</em> name at its
 * next reconnect. <b>Both ends are checked</b>, source and destination, and the endpoint's own
 * javadoc carries why the destination half is not the uniqueness constraint's.
 *
 * <p>It is a courtesy check rather than an invariant on this surface exactly as it is on the other:
 * a presence can be declared between the check and the write, and nothing here holds a lock across
 * two authorities.
 *
 * <h2>The order of the two reads, and why it is unobservable on HTTP</h2>
 *
 * <p>This reads {@code project} before {@code to}, where the endpoint reads {@code to} first —
 * because the endpoint has no reading of {@code project} to do at all, Spring having resolved it
 * out of the path before the method ran. A request naming neither is therefore told about {@code
 * project} here and about {@code to} there, which is not drift: the HTTP surface cannot receive
 * that request, since a URL that names no project is a different URL and gets Spring's own 404.
 * Every request both surfaces <em>can</em> receive names a project, and for those the first refusal
 * either can make is {@code to}'s.
 */
public final class ProjectMoveHandler implements FrameHandler {

  private final ProjectStore projects;

  private final Projects rules;

  /**
   * @param projects the one store both surfaces rename through
   * @param rules the presence rule, built over the same registry {@code ProjectController} builds
   *     its own over — see {@link ProjectFrames} for why it is built in the area rather than
   *     injected
   */
  public ProjectMoveHandler(ProjectStore projects, Projects rules) {
    this.projects = Objects.requireNonNull(projects, "projects");
    this.rules = Objects.requireNonNull(rules, "rules");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String name =
        Payloads.required(
            payload,
            "project",
            FrameTypes.PROJECT_MOVE,
            "the name project.list answers with. Nothing was moved.");
    MoveProjectRequest asked =
        Payloads.as(payload, MoveProjectRequest.class, FrameTypes.PROJECT_MOVE);
    String to = RequestedPaths.to(asked.to());
    rules.refuseIfRooted(name, "moved");
    rules.refuseIfRooted(to, "moved onto");
    projects.rename(name, to);
    return new Outcome(Code.NO_CONTENT, null, null);
  }
}
