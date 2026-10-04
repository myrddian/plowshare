package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Orchestration Studio's four tools — spec 2026-09-29-orchestration-studio §3: what this
 * project can grant, a reachable definition's source, a draft trialled in this project's tiers, and
 * a draft put to the person for install.
 *
 * <h2>Declared by a conductor, handed by its run</h2>
 *
 * <p>Each needs the run it serves, so none is a shared instance: the conductor's {@code RunExtras}
 * builds them per run, like {@code orchestration_check}, for a conductor whose {@code tools:} names
 * them. {@link OrchestrationParser} admits the names in a conductor's {@code tools:}; {@link
 * AgentRegistry} does not know them, so an ordinary agent cannot declare one.
 *
 * <h2>The model never writes a definition</h2>
 *
 * <p>{@code orchestration_install} asks; it installs nothing. The person answers, and the engine —
 * not this tool, and not the model — writes the bytes the question showed.
 */
public final class StudioTools {

  public static final String CATALOG_NAME = "orchestration_catalog";
  public static final String READ_NAME = "orchestration_read";
  public static final String VALIDATE_NAME = "orchestration_validate";
  public static final String INSTALL_NAME = "orchestration_install";
  public static final Set<String> NAMES =
      Set.of(CATALOG_NAME, READ_NAME, VALIDATE_NAME, INSTALL_NAME);

  /** What the tools reach: the Studio, which knows the run. */
  public interface Port {
    String catalog(String run);

    String read(String run, String name);

    String validate(String run, String path, String text);

    Installing install(String run, String path, String text);

    /**
     * Why {@code path} is not a draft this run may name — outside its artifacts directory, or the
     * run is not live — or empty when it is. Asked before the draft is read, so a path outside is
     * refused unread.
     */
    Optional<String> notADraft(String run, String path);
  }

  /** What asking to install came to. */
  public sealed interface Installing {
    record Refused(String why) implements Installing {}

    /** The question is recorded; {@code question} is its text, which ends the turn. */
    record Asked(String question) implements Installing {}
  }

  private StudioTools() {}

  /** One tool per name in {@code declared} that is a Studio tool, in {@code declared}'s order. */
  public static List<AgentTool> forRun(
      Port port,
      String run,
      TurnEnd end,
      Commands.Port commands,
      Home home,
      Collection<String> declared) {
    Objects.requireNonNull(port, "port");
    List<AgentTool> tools = new ArrayList<>();
    for (String name : declared) {
      switch (name) {
        case CATALOG_NAME -> tools.add(new Catalog(port, run));
        case READ_NAME -> tools.add(new Read(port, run));
        case VALIDATE_NAME -> tools.add(new Validate(port, run, commands, home));
        case INSTALL_NAME -> tools.add(new Install(port, run, commands, home, end));
        default -> {}
      }
    }
    return List.copyOf(tools);
  }

  private abstract static class Tool implements AgentTool {
    final Port port;
    final String run;

    Tool(Port port, String run) {
      this.port = port;
      this.run = Objects.requireNonNull(run, "run");
    }

    @Override
    public final String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      try {
        return answer(argumentsJson);
      } catch (BadArguments unusable) {
        return unusable.getMessage();
      }
    }

    abstract String answer(String argumentsJson);
  }

  /**
   * A draft's text, read where the run's files are — the client's workspace or the server's. Called
   * only once the port has said {@code path} is a draft of this run ({@link Port#notADraft}): a
   * path outside the artifacts directory is refused unread.
   */
  private static String draft(Commands.Port commands, Home home, String path) {
    if (commands == null) {
      throw new BadArguments("this run reaches no workspace, so no draft can be read.");
    }
    try {
      return commands.read(home, path);
    } catch (WorkspaceRefusedException | WorkspaceUnavailableException unreadable) {
      throw new BadArguments(path + " could not be read: " + unreadable.getMessage());
    }
  }

  private static Map<String, Object> pathField() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(
        "path",
        ToolArguments.string(
            "The draft's path, relative to the project, inside"
                + " this run's artifacts directory, ending in <name>.md."));
    return fields;
  }

  private static final class Catalog extends Tool {
    private static final ToolSchema SCHEMA =
        new ToolSchema(
            CATALOG_NAME,
            "What an orchestration in this project may be granted: the tools this server"
                + " binds, the agents a conductor may call, the orchestrations reachable"
                + " here, and which of them the caller that started this run holds. Offer"
                + " grants from this list; never invent one.",
            ToolArguments.object(new LinkedHashMap<>(), List.of()));

    Catalog(Port port, String run) {
      super(port, run);
    }

    @Override
    public ToolSchema schema() {
      return SCHEMA;
    }

    @Override
    String answer(String argumentsJson) {
      ToolArguments.parse(argumentsJson, CATALOG_NAME, "{}");
      return port.catalog(run);
    }
  }

  private static final class Read extends Tool {
    private static final ToolSchema SCHEMA;

    static {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("name", ToolArguments.string("The orchestration's name."));
      SCHEMA =
          new ToolSchema(
              READ_NAME,
              "The whole source of an orchestration this project"
                  + " can reach, shipped ones included, and the tier it comes from. Start a"
                  + " revision here.",
              ToolArguments.object(fields, List.of("name")));
    }

    Read(Port port, String run) {
      super(port, run);
    }

    @Override
    public ToolSchema schema() {
      return SCHEMA;
    }

    @Override
    String answer(String argumentsJson) {
      JsonNode args = ToolArguments.parse(argumentsJson, READ_NAME, "{\"name\": \"triage\"}");
      return port.read(
          run, ToolArguments.requireText(args, "name", READ_NAME, "the orchestration's name"));
    }
  }

  private static final class Validate extends Tool {
    private static final ToolSchema SCHEMA =
        new ToolSchema(
            VALIDATE_NAME,
            "Trial a draft with the real loader, as this project would load it: its refusals,"
                + " what else it would disable, its stages and grants, and lints. A"
                + " refusal is fixed, never argued with.",
            ToolArguments.object(pathField(), List.of("path")));

    private final Commands.Port commands;
    private final Home home;

    Validate(Port port, String run, Commands.Port commands, Home home) {
      super(port, run);
      this.commands = commands;
      this.home = home;
    }

    @Override
    public ToolSchema schema() {
      return SCHEMA;
    }

    @Override
    String answer(String argumentsJson) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson, VALIDATE_NAME, "{\"path\": \"docs/orchestrations/.../triage.md\"}");
      String path = ToolArguments.requireText(args, "path", VALIDATE_NAME, "the draft's path");
      Optional<String> outside = port.notADraft(run, path);
      if (outside.isPresent()) {
        return outside.get();
      }
      return port.validate(run, path, draft(commands, home, path));
    }
  }

  private static final class Install extends Tool {
    private static final ToolSchema SCHEMA =
        new ToolSchema(
            INSTALL_NAME,
            "Ask the person whether to install a draft into this project. It is trialled"
                + " again first; the person is shown what it grants and what it replaces;"
                + " your turn ends, and the harness — not you — installs it if they say"
                + " so. Their answer arrives as your next message.",
            ToolArguments.object(pathField(), List.of("path")));

    private final Commands.Port commands;
    private final Home home;
    private final TurnEnd end;

    Install(Port port, String run, Commands.Port commands, Home home, TurnEnd end) {
      super(port, run);
      this.commands = commands;
      this.home = home;
      this.end = Objects.requireNonNull(end, "end");
    }

    @Override
    public ToolSchema schema() {
      return SCHEMA;
    }

    @Override
    String answer(String argumentsJson) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson, INSTALL_NAME, "{\"path\": \"docs/orchestrations/.../triage.md\"}");
      String path = ToolArguments.requireText(args, "path", INSTALL_NAME, "the draft's path");
      Optional<String> outside = port.notADraft(run, path);
      if (outside.isPresent()) {
        return outside.get();
      }
      return switch (port.install(run, path, draft(commands, home, path))) {
        case Installing.Refused refused -> refused.why();
        case Installing.Asked asked -> {
          if (!end.request(Outcome.Ending.AWAITING, asked.question())) {
            yield "The install question was recorded, but this turn is already ending"
                + " for another reason; it will be delivered when that is settled.";
          }
          yield "Asked the person whether to install it. Your turn ends after this"
              + " step; their answer arrives as your next message.";
        }
      };
    }
  }
}
