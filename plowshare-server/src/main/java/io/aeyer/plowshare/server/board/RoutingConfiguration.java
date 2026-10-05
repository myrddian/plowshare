package io.aeyer.plowshare.server.board;

import java.util.List;
import java.util.Objects;

/** Checked configuration values. Membership and current definition grants remain runtime checks. */
public final class RoutingConfiguration {
  private RoutingConfiguration() {}

  public record Manifest(int version, String name, Routing routing) {
    public Manifest {
      if (version != 1) throw new Board.Refused("Use project routing manifest version 1.");
      name = checkedName(name, "project name");
    }
  }

  public record Routing(List<String> acceptFrom, List<String> sendTo, List<String> routeFiles) {
    public Routing {
      acceptFrom = names(acceptFrom, "acceptFrom");
      sendTo = names(sendTo, "sendTo");
      routeFiles = names(routeFiles, "routeFiles");
      for (var file : routeFiles)
        if (file.contains("\\")
            || file.contains(":")
            || java.util.Arrays.stream(file.split("/", -1))
                .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
          throw new Board.Refused(
              "Route files must be relative paths inside the project workspace.");
    }
  }

  public record RouteFile(int version, List<Route> routes) {
    public RouteFile {
      if (version != 1) throw new Board.Refused("Use route file version 1 with a routes array.");
      Objects.requireNonNull(routes, "routes");
      if (routes.size() > 256
          || routes.stream().map(Route::name).distinct().count() != routes.size())
        throw new Board.Refused("Named routes need unique names and at most 256 entries.");
      routes = List.copyOf(routes);
    }
  }

  public record Route(
      String name, String project, String agent, String conversation, Boolean retainConversation) {
    public Route {
      name = checkedName(name, "route name");
      project = checkedName(project, "route project");
      agent = checkedName(agent, "route agent");
      if (agent.startsWith("ins_"))
        throw new Board.Refused("Named routes need an agent definition destination.");
      if (conversation != null && !conversation.matches("cnv_[A-Za-z0-9]+"))
        throw new Board.Refused("Named route conversation must be a conversation ID.");
      if (conversation != null && retainConversation != null)
        throw new Board.Refused(
            "Choose conversation or retainConversation for a named route, not both.");
    }

    public BoardMessaging.Address address() {
      return new BoardMessaging.Address(
          project, agent, conversation, Boolean.TRUE.equals(retainConversation));
    }
  }

  private static List<String> names(List<String> values, String field) {
    if (values == null) return List.of();
    if (values.size() > 256)
      throw new Board.Refused("Too many project routing " + field + " entries.");
    values = values.stream().map(value -> checkedName(value, field)).toList();
    if (values.stream().distinct().count() != values.size())
      throw new Board.Refused("Duplicate project routing " + field + " entry.");
    return values;
  }

  private static String checkedName(String value, String field) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 512
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new Board.Refused("Invalid project routing " + field + ".");
    return value;
  }
}
