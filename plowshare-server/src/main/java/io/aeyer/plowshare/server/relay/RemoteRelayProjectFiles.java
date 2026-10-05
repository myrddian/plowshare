package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileContents;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.ProjectPresences;
import io.aeyer.plowshare.server.session.SessionOwners;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Exact hash-verified configuration through the authenticated owning client's SOURCE channel. */
public final class RemoteRelayProjectFiles implements RelayProjectFiles {
  private final ProjectWorkspaces projects;
  private final ProjectMembers members;
  private final ProjectPresences presences;
  private final SessionOwners sessions;
  private final SessionChannel channel;

  public RemoteRelayProjectFiles(
      ProjectWorkspaces projects,
      ProjectMembers members,
      ProjectPresences presences,
      SessionOwners sessions,
      SessionChannel channel) {
    this.projects = Objects.requireNonNull(projects);
    this.members = Objects.requireNonNull(members);
    this.presences = Objects.requireNonNull(presences);
    this.sessions = Objects.requireNonNull(sessions);
    this.channel = Objects.requireNonNull(channel);
  }

  @Override
  public void requireAccess(Access access) {
    presence(access);
  }

  private Presence presence(Access access) {
    if (ClientProjects.privateProject(access.project())
        || !members.mayWork(access.project(), access.account()))
      throw new CallerFault(
          "Relay configuration requires contributor access to a registered project");
    if (!Objects.equals(projects.id(access.project()), access.projectId()))
      throw new CallerFault("Relay project identity has changed or is unavailable");
    Presence presence =
        presences
            .serving(access.project())
            .orElseThrow(() -> new WorkspaceUnavailableException("The Relay workspace is offline"));
    if (!presence.project().equals(access.project()))
      throw new WorkspaceUnavailableException(
          "The Relay workspace binding does not match the project");
    if (!sessions.accountOf(presence.session()).filter(access.account()::equals).isPresent())
      throw new CallerFault("The Relay workspace belongs to another account");
    if (!channel.sources(presence.session()))
      throw new WorkspaceUnavailableException(
          "The Relay workspace requires the raw-source file channel");
    return presence;
  }

  @Override
  public Optional<String> read(Access access, String relative) {
    RelayProjectFiles.path(relative);
    Presence pinned = presence(access);
    String root = pinned.root().replace('\\', '/').replaceAll("/+$", "");
    // These are provider-native logical names, including Windows/UNC paths. Never interpret the
    // provider's root using Path or open a similarly named path on the server.
    if (root.length() > 4096
        || root.chars().anyMatch(Character::isISOControl)
        || !(root.isEmpty()
            || root.startsWith("/")
            || root.matches("^[A-Za-z]:/.*")
            || root.matches("^[A-Za-z]:$"))
        || java.util.Arrays.stream(root.split("/"))
            .anyMatch(part -> part.equals(".") || part.equals("..")))
      throw new CallerFault("Invalid Relay workspace root");
    String path = root + "/Relay/" + relative;
    FileRequest metadataRequest =
        FileRequest.source(UUID.randomUUID().toString(), path, null, null);
    FileReply first = reply(access, pinned, metadataRequest);
    if (absent(first, path)) return Optional.empty();
    requireOk(first);
    var metadata = first.source();
    int limit = relative.endsWith(".js") ? 131072 : 65536;
    if (metadata == null || metadata.data() != null || metadata.size() > limit)
      throw new WorkspaceUnavailableException(
          "Relay source metadata is missing or exceeds its byte limit");
    try {
      byte[] bytes =
          FileContents.transfer(
              path,
              metadata,
              limit,
              request -> {
                FileReply result = reply(access, pinned, request);
                requireOk(result);
                return result;
              });
      FileReply finalMetadata =
          reply(access, pinned, FileRequest.source(UUID.randomUUID().toString(), path, null, null));
      requireOk(finalMetadata);
      if (!metadata.equals(finalMetadata.source()))
        throw new WorkspaceUnavailableException(
            "Relay configuration changed during source transfer");
      return Optional.of(ServerRelayProjectFiles.decode(bytes));
    } catch (CharacterCodingException malformed) {
      throw new CallerFault("Relay configuration must be valid UTF-8");
    }
  }

  private FileReply reply(Access access, Presence pinned, FileRequest request) {
    if (!pinned.equals(presence(access)))
      throw new WorkspaceUnavailableException("The Relay workspace binding changed during read");
    FileReply reply = channel.ask(pinned.session(), request, Duration.ofSeconds(10));
    if (!pinned.equals(presence(access)) || reply == null || !request.id().equals(reply.id()))
      throw new WorkspaceUnavailableException(
          "The Relay file channel did not complete its correlated request");
    return reply;
  }

  private static boolean absent(FileReply reply, String path) {
    // SOURCE is interpreted as READ by the existing provider. Only an explicit typed no-file
    // result is absence; generic refusal/outage/truncation must never disable a relay silently.
    var result = reply.result();
    return FileReply.REFUSED.equals(reply.outcome())
        && result != null
        && Objects.equals(result.version(), FileResult.VERSION)
        && FileResult.NO_FILE.equals(result.kind())
        && (FileRequest.READ.equals(result.op()) || FileRequest.SOURCE.equals(result.op()))
        && path.equals(result.path());
  }

  private static void requireOk(FileReply reply) {
    if (!FileReply.OK.equals(reply.outcome()))
      throw new WorkspaceUnavailableException(
          "The Relay source request was refused or unavailable");
  }
}
