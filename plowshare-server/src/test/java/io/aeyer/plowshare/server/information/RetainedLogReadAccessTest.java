package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetainedLogReadAccessTest {
  @Test
  void retainedInputAttributionDoesNotVetoTheReadersCurrentProjectGrant() {
    var members = mock(ProjectMembers.class);
    var repository = mock(JobInformationRepository.class);
    var selection = InformationContext.Selection.project("application");
    var revision = UUID.randomUUID();
    when(repository.inputs("job"))
        .thenReturn(List.of(new JobInformationRepository.Input(revision, selection)));
    when(members.role("application", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
    when(repository.readable(revision, "reader", selection)).thenReturn(true);
    var jobs =
        new InformationJobs(
            repository, new InformationAccess(new EffectiveProjectPermissions(members)));
    assertTrue(jobs.allowed("job", "reader"));
    verify(repository).readable(revision, "reader", selection);

    when(repository.readable(revision, "reader", selection)).thenReturn(false);
    assertFalse(jobs.allowed("job", "reader"), "Resource ACL still controls each input");
    clearInvocations(repository);
    when(members.role("application", "reader")).thenReturn(Optional.empty());
    assertFalse(jobs.allowed("job", "reader"), "A revoked project grant refuses the selection");
    verify(repository, never()).readable(any(), any(), any());
    assertFalse(jobs.allowed("job", null));
  }
}
