package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.messaging.MessageRefused.Code;
import io.aeyer.plowshare.server.messaging.MessageToolCodec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Lists native messaging destinations under the authenticated run context. */
public final class AgentListTool implements AgentTool {
  public static final String NAME = "agent_list";
  private static final int MAX_LIMIT = 50;
  private static final int MAX_OFFSET = Integer.MAX_VALUE - MAX_LIMIT;
  private static final Set<String> FIELDS = Set.of("project", "kind", "offset", "limit");
  private static final ToolSchema SCHEMA =
      ToolSchema.from(
          NAME,
          "List destinations for send_message from this run. This read creates no instances and"
              + " sends no messages. Pass each messageable row's project as to_project and its to"
              + " as to. Follow next_offset for more rows. Access and capacity can change before a send.",
          ToolArguments.object(
              Map.of(
                  "project",
                      ToolArguments.string("Destination project. Defaults to this run's project."),
                  "kind",
                      Map.of(
                          "type",
                          "string",
                          "enum",
                          List.of("definition", "instance"),
                          "description",
                          "List exported definitions (default) or existing instances."),
                  "offset", ToolArguments.integer("Page offset. Defaults to 0."),
                  "limit", ToolArguments.integer("Page size from 1 to 50. Defaults to 20.")),
              List.of()));

  public enum Kind {
    DEFINITION,
    INSTANCE
  }

  public record Request(String project, Kind kind, int offset, int limit) {
    public Request {
      Objects.requireNonNull(kind);
      if (project != null) {
        try {
          Home.of(project);
        } catch (IllegalArgumentException invalid) {
          throw new ToolArguments.BadArguments("project must be a valid project name");
        }
      }
      if (offset < 0 || offset > MAX_OFFSET || limit < 1 || limit > MAX_LIMIT)
        throw new ToolArguments.BadArguments(
            "offset must be between 0 and " + MAX_OFFSET + " and limit must be between 1 and 50");
    }

    public Integer nextOffset(boolean more) {
      int next = offset + limit;
      return more && next <= MAX_OFFSET ? next : null;
    }
  }

  @FunctionalInterface
  public interface Directory {
    String list(Request request, Home home);
  }

  private final Directory directory;

  public AgentListTool(Directory directory) {
    this.directory = Objects.requireNonNull(directory);
  }

  @Override
  public ToolSchema schema() {
    return SCHEMA;
  }

  @Override
  public String run(String arguments, Home home) {
    try {
      return directory.list(parse(arguments), home);
    } catch (ToolArguments.BadArguments invalid) {
      return MessageToolCodec.refusal(Code.INVALID_ARGUMENT, invalid.getMessage());
    }
  }

  private static Request parse(String arguments) {
    JsonNode args = ToolArguments.parse(arguments, NAME, "{}");
    for (var fields = args.fieldNames(); fields.hasNext(); ) {
      String field = fields.next();
      if (!FIELDS.contains(field) || args.get(field).isNull())
        throw new ToolArguments.BadArguments("agent_list accepts project, kind, offset and limit");
    }
    String project =
        ToolArguments.optionalText(
            args,
            "project",
            ignored -> new ToolArguments.BadArguments("project must be a project name"));
    String kind =
        ToolArguments.optionalText(
            args,
            "kind",
            ignored -> new ToolArguments.BadArguments("kind must be definition or instance"));
    Kind selected =
        switch (kind == null ? "definition" : kind) {
          case "definition" -> Kind.DEFINITION;
          case "instance" -> Kind.INSTANCE;
          default -> throw new ToolArguments.BadArguments("kind must be definition or instance");
        };
    return new Request(project, selected, integer(args, "offset", 0), integer(args, "limit", 20));
  }

  private static int integer(JsonNode args, String field, int fallback) {
    JsonNode value = args.get(field);
    if (value == null) return fallback;
    if (!value.isIntegralNumber() || !value.canConvertToInt())
      throw new ToolArguments.BadArguments(field + " must be an integer");
    return value.intValue();
  }
}
