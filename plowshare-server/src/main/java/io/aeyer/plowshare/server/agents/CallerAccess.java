package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Refusals at the front doors, before machine routing silently omits inaccessible sessions. */
@Component
public class CallerAccess {
    private final SessionRegistry sessions;
    private final ProjectMembers members;

    public CallerAccess(SessionRegistry sessions, ProjectMembers members) {
        this.sessions = sessions;
        this.members = members;
    }

    public void requireSession(String session, String handle) {
        if (sessions.accountOf(session).filter(account -> !Objects.equals(account, handle)).isPresent()) {
            throw new CallerFault("this session is held by another account");
        }
    }

    public void requireProject(String project, String handle) {
        if (project != null && !members.mayUse(project, handle)) {
            throw new CallerFault("this account may not use project '" + project + "'");
        }
    }
}
