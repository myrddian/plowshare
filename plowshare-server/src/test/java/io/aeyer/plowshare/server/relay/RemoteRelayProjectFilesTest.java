package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.FileSource;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileContents;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.ProjectPresences;
import io.aeyer.plowshare.server.session.SessionOwners;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteRelayProjectFilesTest {
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectPresences presences = mock(ProjectPresences.class);
  private final SessionOwners sessions = mock(SessionOwners.class);
  private final RelayProjectFiles.Access access =
      new RelayProjectFiles.Access("operator", "project", 9);
  private final List<FileRequest> requests = new ArrayList<>();
  private byte[] source = "export const value = '雪';\r\n\r\n".getBytes(StandardCharsets.UTF_8);
  private boolean capable = true;
  private Function<FileRequest, FileReply> response = this::sourceReply;
  private final SessionChannel channel =
      new SessionChannel() {
        public boolean sources(String session) {
          return capable;
        }

        public FileReply ask(String session, FileRequest request) {
          assertEquals("session", session);
          assertEquals(FileRequest.SOURCE, request.op());
          assertNull(request.purpose(), "normal provider workspace fences apply");
          requests.add(request);
          return response.apply(request);
        }
      };
  private final RelayProjectFiles files =
      new RemoteRelayProjectFiles(projects, members, presences, sessions, channel);

  @BeforeEach
  void workspace() {
    when(members.mayWork("project", "operator")).thenReturn(true);
    when(projects.id("project")).thenReturn(9L);
    when(presences.serving("project"))
        .thenReturn(Optional.of(new Presence("session", "machine", "/remote/project", "project")));
    when(sessions.accountOf("session")).thenReturn(Optional.of("operator"));
  }

  private FileReply sourceReply(FileRequest request) {
    if (request.offset() == null)
      return FileReply.source(
          request.id(), new FileSource(source.length, FileContents.sha256(source), null, null));
    int end = Math.min(source.length, request.offset() + request.limit());
    return FileReply.source(
        request.id(),
        new FileSource(
            source.length,
            null,
            request.offset(),
            Base64.getEncoder().encodeToString(Arrays.copyOfRange(source, request.offset(), end))));
  }

  @Test
  void exact_unicode_and_line_endings_use_authenticated_source_without_server_lookup() {
    assertEquals(
        Optional.of(new String(source, StandardCharsets.UTF_8)),
        files.read(access, "notices/routes.js"));
    assertEquals(3, requests.size());
    assertTrue(
        requests.stream()
            .allMatch(request -> request.path().equals("/remote/project/Relay/notices/routes.js")));
    assertNull(requests.getLast().offset(), "source metadata is rechecked after transfer");
    verify(projects, never()).find(anyString());
    verify(projects, never()).effectiveExclusions(any());
  }

  @Test
  void provider_native_windows_unc_and_posix_root_are_not_server_paths() {
    for (String root : List.of("C:\\work\\project\\", "//host/share/project/", "/")) {
      when(presences.serving("project"))
          .thenReturn(Optional.of(new Presence("session", "machine", root, "project")));
      requests.clear();
      files.read(access, "active.json");
      String expected = root.replace('\\', '/').replaceAll("/+$", "") + "/Relay/active.json";
      assertEquals(expected, requests.getFirst().path());
    }
    for (String root :
        List.of("C:relative", "/remote/../private", "/remote/./project", "/remote/\u0000project")) {
      when(presences.serving("project"))
          .thenReturn(Optional.of(new Presence("session", "machine", root, "project")));
      requests.clear();
      assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
      assertTrue(requests.isEmpty());
    }
    assertThrows(IllegalArgumentException.class, () -> files.read(access, "../secret"));
  }

  @Test
  void live_identity_membership_account_binding_and_raw_capability_precede_reads() {
    when(members.mayWork("project", "operator")).thenReturn(false);
    assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
    verifyNoInteractions(projects, presences, sessions);
    when(members.mayWork("project", "operator")).thenReturn(true);
    when(projects.id("project")).thenReturn(10L);
    assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
    when(projects.id("project")).thenReturn(9L);
    when(sessions.accountOf("session")).thenReturn(Optional.of("another"));
    assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
    when(sessions.accountOf("session")).thenReturn(Optional.of("operator"));
    capable = false;
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
    capable = true;
    when(presences.serving("project")).thenReturn(Optional.empty());
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
    assertThrows(
        CallerFault.class,
        () ->
            files.read(
                new RelayProjectFiles.Access("operator", "client:private", 9), "active.json"));
    assertTrue(requests.isEmpty());
  }

  @Test
  void only_correlated_explicit_no_file_is_absence() {
    response =
        request ->
            FileReply.refused(request.id(), FileResult.noFile(FileRequest.READ, request.path()));
    assertEquals(Optional.empty(), files.read(access, "active.json"));
    response =
        request ->
            FileReply.refused(request.id(), FileResult.noFile(FileRequest.READ, "/another/file"));
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
    response =
        request ->
            FileReply.refused("wrong-id", FileResult.noFile(FileRequest.READ, request.path()));
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
    response = request -> null;
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
    response =
        request -> {
          throw new WorkspaceUnavailableException("offline");
        };
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
  }

  @Test
  void rebind_revoked_membership_and_changed_metadata_never_return_a_snapshot() {
    when(sessions.accountOf("replacement")).thenReturn(Optional.of("operator"));
    response =
        request -> {
          FileReply result = sourceReply(request);
          when(presences.serving("project"))
              .thenReturn(
                  Optional.of(
                      new Presence("replacement", "machine", "/remote/project", "project")));
          return result;
        };
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
    assertEquals(1, requests.size());
    workspace();
    response =
        request -> {
          FileReply result = sourceReply(request);
          if (request.offset() != null)
            when(members.mayWork("project", "operator")).thenReturn(false);
          return result;
        };
    assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
    workspace();
    response =
        request -> {
          FileReply result = sourceReply(request);
          if (request.offset() != null) source = "changed".getBytes(StandardCharsets.UTF_8);
          return result;
        };
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
  }

  @Test
  void bounded_hash_verified_ranges_reject_corruption_and_invalid_utf8() {
    source = "x".repeat(70000).getBytes(StandardCharsets.UTF_8);
    assertEquals(Optional.of("x".repeat(70000)), files.read(access, "notices/routes.js"));
    assertEquals(4, requests.size());
    requests.clear();
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
    assertEquals(1, requests.size(), "oversized JSON must never transfer bytes");
    source = new byte[] {(byte) 0xc3, 0x28};
    var invalid = assertThrows(CallerFault.class, () -> files.read(access, "notices/routes.js"));
    assertNull(invalid.getCause());
    source = "source".getBytes(StandardCharsets.UTF_8);
    response =
        request ->
            request.offset() == null
                ? sourceReply(request)
                : FileReply.source(
                    request.id(), new FileSource(source.length, null, request.offset(), "AAAA"));
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
    response =
        request -> {
          FileReply result = sourceReply(request);
          if (request.offset() == null) source = "CHANGE".getBytes(StandardCharsets.UTF_8);
          return result;
        };
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
  }
}
