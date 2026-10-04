package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
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
      for (String marker : List.of(".plowshare/plowshare", ".plowshare/project", "plowshare")) {
        text = read(row, root, marker, true);
        if (text != null && !text.isBlank()) break;
      }
      if (text == null || text.isBlank() || !text.stripLeading().startsWith("{"))
        return Policy.CLOSED;
      JsonNode manifest = JSON.readTree(text);
      if (!manifest.isObject()
          || !manifest.path("version").isIntegralNumber()
          || !manifest.path("version").canConvertToInt()
          || manifest.path("version").asInt() != 1
          || !manifest.path("name").isTextual()
          || !(manifest.path("name").asText().equals(project)
              || manifest.path("name").asText().equals(identify(project)))) {
        throw new Board.Refused("Invalid project routing manifest identity or version.");
      }
      JsonNode routing = manifest.get("routing");
      if (routing == null) return Policy.CLOSED;
      if (!routing.isObject()) throw new Board.Refused("Project routing must be an object.");
      List<String> inbound = strings(routing, "acceptFrom"), outbound = strings(routing, "sendTo");
      Map<String, BoardMessaging.Address> routes = new LinkedHashMap<>();
      int bytes = text.getBytes(StandardCharsets.UTF_8).length;
      for (String file : strings(routing, "routeFiles")) {
        if (!validFile(file))
          throw new Board.Refused(
              "Route files must be relative paths inside the project workspace.");
        String contents = read(row, root, file, false);
        bytes += contents.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 1048576)
          throw new Board.Refused("Project routing files exceed the total size limit.");
        JsonNode config = JSON.readTree(contents);
        if (config == null
            || !config.isObject()
            || !config.path("version").isIntegralNumber()
            || !config.path("version").canConvertToInt()
            || config.path("version").asInt() != 1
            || !config.path("routes").isArray()) {
          throw new Board.Refused("Use route file version 1 with a routes array.");
        }
        for (JsonNode entry : config.path("routes")) {
          String name = text(entry, "name"),
              target = text(entry, "project"),
              agent = text(entry, "agent");
          String conversation = entry.has("conversation") ? text(entry, "conversation") : null;
          JsonNode retain = entry.get("retainConversation");
          if (retain != null && !retain.isBoolean())
            throw new Board.Refused("Named route retainConversation must be true or false.");
          if (conversation != null && retain != null)
            throw new Board.Refused(
                "Choose conversation or retainConversation for a named route, not both.");
          if (conversation != null && !conversation.matches("cnv_[A-Za-z0-9]+"))
            throw new Board.Refused("Named route conversation must be a conversation ID.");
          if (agent.startsWith("ins_")
              || routes.putIfAbsent(
                      name,
                      new BoardMessaging.Address(
                          target, agent, conversation, retain != null && retain.asBoolean()))
                  != null) {
            throw new Board.Refused(
                "Named routes need unique names and an agent definition destination.");
          }
          if (routes.size() > 256) throw new Board.Refused("Too many named project routes.");
        }
      }
      return new Policy(inbound, outbound, Map.copyOf(routes));
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

  private static List<String> strings(JsonNode parent, String field) {
    JsonNode entries = parent.get(field);
    if (entries == null) return List.of();
    if (!entries.isArray() || entries.size() > 256)
      throw new Board.Refused("Invalid project routing " + field + ".");
    var values = new java.util.ArrayList<String>();
    for (JsonNode entry : entries) {
      if (!entry.isTextual() || !validName(entry.asText()) || values.contains(entry.asText()))
        throw new Board.Refused("Invalid project routing " + field + ".");
      values.add(entry.asText());
    }
    return List.copyOf(values);
  }

  private static String text(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual() || !validName(value.asText()))
      throw new Board.Refused("Invalid named project route " + field + ".");
    return value.asText();
  }

  private static boolean validName(String value) {
    return !value.isBlank()
        && value.equals(value.strip())
        && value.length() <= 512
        && value.indexOf('\n') < 0
        && value.indexOf('\r') < 0
        && value.indexOf('\0') < 0;
  }

  private static boolean validFile(String value) {
    return validName(value)
        && !value.contains("\\")
        && !value.contains(":")
        && java.util.Arrays.stream(value.split("/", -1))
            .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
  }
}
