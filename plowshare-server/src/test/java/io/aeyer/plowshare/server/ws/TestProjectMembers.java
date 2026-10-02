package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Membership policy for existing file-channel fixtures that test transport rather than access. */
public final class TestProjectMembers {
    private TestProjectMembers() {}
    public static ProjectMembers allowed() {
        ProjectMembers members = mock(ProjectMembers.class);
        when(members.mayUse(anyString(), nullable(String.class))).thenReturn(true);
        when(members.isMember(anyString(), nullable(String.class))).thenReturn(true);
        return members;
    }
}
