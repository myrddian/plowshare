package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.auth.AuthProperties;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;

/** The seeded admin's socket-only membership management. */
public final class ProjectMemberHandler implements FrameHandler {
    private final ProjectMembers members;
    private final AuthProperties auth;
    private final boolean add;

    public ProjectMemberHandler(ProjectMembers members, AuthProperties auth, boolean add) {
        this.members = members;
        this.auth = auth;
        this.add = add;
    }

    public record Changed(String project, List<String> members) {}

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String type = add ? FrameTypes.PROJECT_MEMBER_ADD : FrameTypes.PROJECT_MEMBER_REMOVE;
        String admin = auth.getAdminHandle();
        if (admin == null || !asking.requireHandle(type).equals(admin.trim())) {
            throw new CallerFault("only the seeded admin may manage project members");
        }
        String project = Payloads.required(payload, "project", type, "the project name");
        String handle = Payloads.required(payload, "handle", type, "the account handle");
        if (add) {
            members.add(project, handle);
        } else {
            members.remove(project, handle);
        }
        return Outcome.ok(new Changed(project, members.members(project)));
    }
}
