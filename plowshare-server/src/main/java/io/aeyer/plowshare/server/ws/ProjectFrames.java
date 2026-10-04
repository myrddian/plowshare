package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.Projects;
import io.aeyer.plowshare.server.auth.AuthProperties;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code ProjectController}'s area of the frame surface — every {@code project.*} type this server
 * answers, which is all seven of that controller's endpoints.
 *
 * <h2>One constructor, and it is the controller's own</h2>
 *
 * <p>The two services below are the same beans {@code ProjectController} is injected with, in the
 * same order, which is what makes a frame's refusal the endpoint's own refusal rather than a second
 * one shaped like it. A surface that built its own copy would give this server two instances of
 * each — the scanned bean a frame handler injects and a private one the controller called — and the
 * day either gains a cache or a proxy the two surfaces would answer from different objects with
 * nothing failing.
 *
 * <p><b>{@link Projects} is built here rather than injected</b>, which is the one place this area
 * copies a shape instead of a bean, and it copies it on purpose: {@code ProjectController}'s own
 * constructor builds it the same way and its javadoc argues why — {@code Projects} is a thin,
 * stateless wrapper around the one thing that carries any state, the {@link PresenceRegistry}
 * singleton {@code FileChannelConfig} wires. Both surfaces therefore hold different wrappers over
 * <em>one</em> registry, which is the thing that has to be shared for {@code move}'s refusal to
 * mean anything.
 *
 * <h2>One noun, seven verbs, and two of them answer nothing</h2>
 *
 * <p>{@code project.list} reads, {@code project.define}, {@code project.lend}, {@code
 * project.unlend} and {@code project.workspace} answer with the leash they just set, and {@code
 * project.move} and {@code project.forget} answer {@link
 * io.aeyer.plowshare.protocol.frames.Code#NO_CONTENT} — this area's own reason for that code
 * existing. Their handlers say why an {@code OK} would have been a different promise.
 *
 * <p>One of the {@link FrameArea} classes that interface's javadoc describes. A later breadth task
 * adds <b>its own</b> {@code *Frames.java} rather than editing this one.
 */
@Component
public class ProjectFrames implements FrameArea {

  private final AuthProperties auth;
  private final ProjectMembers members;
  private final ProjectStore projects;

  /**
   * The presence rule {@link ProjectMoveHandler} refuses under, built from the registry that says
   * which live session roots which project — the arrangement {@code ProjectController} already has,
   * for the reason its own constructor javadoc gives.
   */
  private final Projects rules;

  /**
   * @param projects the store every handler below reads and writes through — the same bean {@code
   *     ProjectController} is handed
   * @param presences the registry a live session's rooted project is looked up in, which is the
   *     same singleton the controller is handed and the file channel declares into
   */
  public ProjectFrames(
      ProjectStore projects,
      PresenceRegistry presences,
      ProjectMembers members,
      AuthProperties auth) {
    this.auth = auth;
    this.members = members;
    this.projects = Objects.requireNonNull(projects, "projects");
    this.rules = new Projects(Objects.requireNonNull(presences, "presences"));
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(
            FrameTypes.PROJECT_ACCESS,
            (payload, asking) ->
                io.aeyer.plowshare.protocol.frames.Outcome.ok(
                    members.access(
                        Payloads.required(
                            payload, "project", FrameTypes.PROJECT_ACCESS, "the project name"),
                        asking.requireHandle(FrameTypes.PROJECT_ACCESS)))),
        Map.entry(
            FrameTypes.PROJECT_MEMBER_ROLE,
            (payload, asking) -> {
              String project =
                  Payloads.required(
                      payload, "project", FrameTypes.PROJECT_MEMBER_ROLE, "the project name");
              members.assign(
                  project,
                  Payloads.required(
                      payload, "handle", FrameTypes.PROJECT_MEMBER_ROLE, "the account handle"),
                  io.aeyer.plowshare.server.archive.ProjectRole.parse(
                      Payloads.required(
                          payload, "role", FrameTypes.PROJECT_MEMBER_ROLE, "the project role")),
                  asking.requireHandle(FrameTypes.PROJECT_MEMBER_ROLE),
                  false);
              return io.aeyer.plowshare.protocol.frames.Outcome.ok(
                  members.access(project, asking.handle()));
            }),
        Map.entry(FrameTypes.PROJECT_MEMBER_ADD, new ProjectMemberHandler(members, auth, true)),
        Map.entry(FrameTypes.PROJECT_MEMBER_REMOVE, new ProjectMemberHandler(members, auth, false)),
        Map.entry(FrameTypes.PROJECT_LIST, new ProjectListHandler(projects, members)),
        Map.entry(FrameTypes.PROJECT_DEFINE, new ProjectDefineHandler(projects)),
        Map.entry(FrameTypes.PROJECT_LEND, new ProjectLendHandler(projects)),
        Map.entry(FrameTypes.PROJECT_UNLEND, new ProjectUnlendHandler(projects)),
        Map.entry(FrameTypes.PROJECT_WORKSPACE, new ProjectWorkspaceHandler(projects)),
        Map.entry(FrameTypes.PROJECT_MOVE, new ProjectMoveHandler(projects, rules)),
        Map.entry(FrameTypes.PROJECT_FORGET, new ProjectForgetHandler(projects)));
  }
}
