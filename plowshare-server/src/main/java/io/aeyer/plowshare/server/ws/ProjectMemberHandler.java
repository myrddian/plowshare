package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.auth.AuthProperties;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;

/** Project managers assign persistent project grants. */
public final class ProjectMemberHandler implements FrameHandler {
  private final ProjectMembers members;
  private final boolean add;

  public ProjectMemberHandler(ProjectMembers members, AuthProperties auth, boolean add) {
    this.members = members;
    this.add = add;
  }

  public record Changed(String project, List<String> members) {}

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String type = add ? FrameTypes.PROJECT_MEMBER_ADD : FrameTypes.PROJECT_MEMBER_REMOVE;
    String project = Payloads.required(payload, "project", type, "the project name");
    String handle = Payloads.required(payload, "handle", type, "the account handle");
    if (payload.containsKey("role") && !(payload.get("role") instanceof String))
      throw new CallerFault("role must be VIEWER, CONTRIBUTOR or MANAGER");
    if (add) {
      members.assign(
          project,
          handle,
          payload.get("role") instanceof String value
              ? io.aeyer.plowshare.server.archive.ProjectRole.parse(value)
              : io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR,
          asking.requireHandle(type),
          true);
    } else {
      members.remove(project, handle, asking.requireHandle(type));
    }
    return Outcome.ok(new Changed(project, members.members(project)));
  }
}
