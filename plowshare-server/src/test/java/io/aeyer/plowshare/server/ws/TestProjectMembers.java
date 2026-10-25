package io.aeyer.plowshare.server.ws;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;

/** Membership policy for existing file-channel fixtures that test transport rather than access. */
public final class TestProjectMembers {
  private TestProjectMembers() {}

  public static ProjectMembers allowed() {
    ProjectMembers members = mock(ProjectMembers.class);
    when(members.mayUse(anyString(), nullable(String.class))).thenReturn(true);
    when(members.mayWork(anyString(), nullable(String.class))).thenReturn(true);
    when(members.mayManage(anyString(), nullable(String.class))).thenReturn(true);
    when(members.isMember(anyString(), nullable(String.class))).thenReturn(true);
    when(members.role(anyString(), nullable(String.class)))
        .thenReturn(java.util.Optional.of(io.aeyer.plowshare.server.archive.ProjectRole.MANAGER));
    when(members.roles(any(), nullable(String.class)))
        .thenAnswer(
            call -> {
              java.util.Collection<String> names = call.getArgument(0);
              return names.stream()
                  .collect(
                      java.util.stream.Collectors.toUnmodifiableMap(
                          value -> value,
                          value -> io.aeyer.plowshare.server.archive.ProjectRole.MANAGER));
            });
    return members;
  }
}
