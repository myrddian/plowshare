package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Server workspace policy, re-read for each new message; a checkout cannot grant server access. */
@Component
public final class ProjectMessageRouting implements BoardMessaging.Routing {
  private static final int FILE_BYTES = 65536;
  private final ProjectStore projects;
  private final ProjectMembers members;
  private final MessagingProperties settings;
  private final PersonalSpaces personal;

  public ProjectMessageRouting(ProjectStore projects, ProjectMembers members) {
    this(projects, members, new MessagingProperties(), null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ProjectMessageRouting(
      ProjectStore projects,
      ProjectMembers members,
      MessagingProperties settings,
      PersonalSpaces personal) {
    this.projects = projects;
    this.members = members;
    this.settings = settings;
    this.personal = personal;
  }

  private record Policy(
      List<String> acceptFrom, List<String> sendTo, Map<String, BoardMessaging.Address> routes) {
    static final Policy CLOSED = new Policy(List.of(), List.of(), Map.of());
  }

  @Override
  public BoardMessaging.Address resolve(
      String account, String source, String project, String to, String route) {
    if (route == null)
      return new BoardMessaging.Address(
          PersonalSpaces.resolveAddress(project == null ? source : project, account), to);
    BoardMessaging.Address address = policy(source).routes().get(route);
    if (address == null) throw new Board.Refused("No configured route with that name.");
    return new BoardMessaging.Address(
        PersonalSpaces.resolveAddress(address.project(), account),
        address.to(),
        address.conversation(),
        address.retainConversation());
  }

  @Override
  public String identify(String project) {
    return projects.personalOwner(project).map(PersonalSpaces::address).orElse(project);
  }

  @Override
  public void require(String account, String source, String destination) {
    if (!members.mayWork(source, account))
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "This account needs CONTRIBUTOR access to send project messages");
    if (source.equals(destination)) return;
    var sourceOwner = projects.personalOwner(source);
    var destinationOwner = projects.personalOwner(destination);
    if (sourceOwner.filter(owner -> !owner.equals(account)).isPresent()
        || destinationOwner.filter(owner -> !owner.equals(account)).isPresent()) {
      throw new Board.Refused("This Personal project belongs to another account.");
    }
    // Private attachments never become server-addressable projects, even with routing metadata.
    if (ClientProjects.privateProject(source)
        || ClientProjects.privateProject(destination)
        || projects.id(source) == null
        || projects.id(destination) == null
        || !members.mayWork(source, account)
        || !members.mayWork(destination, account)) {
      throw new Board.Refused("Cross-project messaging is not permitted.");
    }
    boolean sendDefault = sourceOwner.isPresent() && settings.getPersonal().isSendToAnyProject();
    boolean receiveDefault =
        destinationOwner.isPresent() && settings.getPersonal().isAcceptFromAnyProject();
    if (sourceOwner.isPresent() && destinationOwner.isPresent()) {
      if (sendDefault && receiveDefault) return;
    } else if (sendDefault || receiveDefault) return;
    if (!allows(policy(source).sendTo(), destination)
        || !allows(policy(destination).acceptFrom(), source)) {
      throw new Board.Refused("Both projects must permit this message route.");
    }
  }

  private boolean allows(List<String> configured, String project) {
    return configured.contains(project) || configured.contains(identify(project));
  }

  private Policy policy(String project) {
    if (ClientProjects.privateProject(project)) return Policy.CLOSED;
    var recorded = projects.find(project);
    var personalRoot =
        personal == null ? java.util.Optional.<Path>empty() : personal.workspace(project);
    if (recorded.isEmpty() && personalRoot.isEmpty()) return Policy.CLOSED;
    ProjectRecord row = recorded.orElse(null);
    try {
      Path root = (row == null ? personalRoot.orElseThrow() : row.workspace()).toRealPath();
      String text = null;
      for (String marker :
          List.of("plowshare.json", ".plowshare/plowshare", ".plowshare/project", "plowshare")) {
        text = read(row, root, marker, true);
        if (marker.equals("plowshare.json") && text != null) {
          if (!text.stripLeading().startsWith("{"))
            throw new Board.Refused("Invalid application manifest.");
          break;
        }
        if (text != null && !text.isBlank()) break;
      }
      if (text == null || text.isBlank() || !text.stripLeading().startsWith("{"))
        return Policy.CLOSED;
      var manifest = RoutingConfigurationCodec.manifest(text);
      if (!(manifest.name().equals(project) || manifest.name().equals(identify(project))))
        throw new Board.Refused("Invalid project routing manifest identity or version.");
      var routing = manifest.routing();
      if (routing == null) return Policy.CLOSED;
      Map<String, BoardMessaging.Address> routes = new LinkedHashMap<>();
      int bytes = text.getBytes(StandardCharsets.UTF_8).length;
      for (String file : routing.routeFiles()) {
        String contents = read(row, root, file, false);
        bytes += contents.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 1048576)
          throw new Board.Refused("Project routing files exceed the total size limit.");
        for (var entry : RoutingConfigurationCodec.routes(contents).routes()) {
          if (routes.putIfAbsent(entry.name(), entry.address()) != null)
            throw new Board.Refused("Named routes need unique names.");
          if (routes.size() > 256) throw new Board.Refused("Too many named project routes.");
        }
      }
      return new Policy(routing.acceptFrom(), routing.sendTo(), Map.copyOf(routes));
    } catch (IOException invalid) {
      throw new Board.Refused("The project routing configuration could not be read.");
    }
  }

  private String read(ProjectRecord row, Path root, String file, boolean optional)
      throws IOException {
    Path path = root.resolve(file);
    Path at = root;
    for (Path segment : root.relativize(path)) {
      at = at.resolve(segment);
      if (Files.isSymbolicLink(at))
        throw new Board.Refused("Project routing files must not use symbolic links.");
    }
    if (optional && Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return null;
    // Personal's canonical union is server-owned private storage, deliberately under
    // the data directory; its computed root is its fence, as with normal union reads.
    var exclusions = row == null ? List.<Path>of() : projects.effectiveExclusions(row);
    // Identity markers are framework metadata. Grant only that exact file, while
    // retaining all explicit/mandatory exclusions; ordinary route files use the normal fence.
    var fence =
        optional
            ? io.aeyer.plowshare.protocol.FileAccess.of(List.of(path), exclusions)
            : row == null
                ? io.aeyer.plowshare.protocol.FileAccess.of(List.of(root), exclusions)
                : row.reach(exclusions);
    if (!fence.permits(path))
      throw new Board.Refused("Project routing file is outside the permitted workspace.");
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new Board.Refused("Project routing files must be regular files.");
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw new Board.Refused("Project routing files must be regular files.");
      byte[] bytes = input.readNBytes(FILE_BYTES + 1);
      if (bytes.length > FILE_BYTES)
        throw new Board.Refused("The project routing file is too large.");
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (NoSuchFileException missing) {
      if (optional) return null;
      throw missing;
    }
  }
}
