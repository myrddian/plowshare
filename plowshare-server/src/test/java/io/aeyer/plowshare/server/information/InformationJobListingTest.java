package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A job listing asks each distinct authority question once per operation, with the same verdicts as
 * deciding every job alone. Job listings previously resolved a project role and ran a catalogue
 * check per retained input of every job on the server.
 */
class InformationJobListingTest {
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final JobInformationRepository repository = mock(JobInformationRepository.class);
  private final InformationReadAccess catalogue = mock(InformationReadAccess.class);
  private final InformationJobs jobs =
      new InformationJobs(
          repository, new InformationAccess(new EffectiveProjectPermissions(members)), catalogue);
  private final InformationContext.Selection application =
      InformationContext.Selection.project("application");

  private List<JobInformationRepository.Input> inputs(List<UUID> revisions) {
    return revisions.stream()
        .map(revision -> new JobInformationRepository.Input(revision, application))
        .toList();
  }

  @Test
  void sharedSelectionsRevisionsAndLogsAreAuthorizedOncePerListing() {
    var corpus = IntStream.range(0, 40).mapToObj(index -> UUID.randomUUID()).toList();
    for (int job = 0; job < 10; job++)
      when(repository.inputs("job" + job)).thenReturn(inputs(corpus));
    when(repository.inputs("plain")).thenReturn(List.of());
    when(repository.logAllowed("plain", "reader")).thenReturn(true);
    when(repository.logAllowed("log", "reader")).thenReturn(true);
    when(members.role("application", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));

    var listing = jobs.listing("reader");
    for (int job = 0; job < 10; job++) {
      assertTrue(listing.allowed("job" + job));
      assertTrue(listing.logAllowed("log"));
    }
    assertTrue(listing.allowed("plain"));

    verify(members, times(1)).role("application", "reader");
    for (var revision : corpus) verify(catalogue, times(1)).requireReadable(any(), eq(revision));
    verify(repository, times(1)).logAllowed("log", "reader");
    verify(repository, times(1)).logAllowed("plain", "reader");
  }

  @Test
  void deniedSelectionsAndUnreadableRevisionsStayDeniedForEveryJobThatSharesThem() {
    var open = UUID.randomUUID();
    var restricted = UUID.randomUUID();
    when(repository.inputs("readable")).thenReturn(inputs(List.of(open)));
    when(repository.inputs("blocked")).thenReturn(inputs(List.of(open, restricted)));
    when(repository.inputs("also-blocked")).thenReturn(inputs(List.of(restricted)));
    when(members.role("application", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
    doThrow(new io.aeyer.plowshare.server.faults.NotFoundFault("hidden"))
        .when(catalogue)
        .requireReadable(any(), eq(restricted));

    var listing = jobs.listing("reader");
    assertTrue(listing.allowed("readable"));
    assertFalse(listing.allowed("blocked"));
    assertFalse(listing.allowed("also-blocked"));
    verify(catalogue, times(1)).requireReadable(any(), eq(restricted));
    // The memo changes only how often a question is asked, never the answer.
    assertTrue(jobs.allowed("readable", "reader"));
    assertFalse(jobs.allowed("blocked", "reader"));

    when(members.role("application", "reader")).thenReturn(Optional.empty());
    var revoked = jobs.listing("reader");
    assertFalse(revoked.allowed("readable"), "A revoked project grant refuses the selection");
    assertFalse(revoked.allowed("blocked"));
    assertFalse(jobs.listing(null).allowed("readable"), "Retained inputs need an account");
  }
}
