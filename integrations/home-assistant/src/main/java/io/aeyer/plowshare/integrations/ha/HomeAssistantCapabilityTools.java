package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldRole;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldSelection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Query;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceCapabilities.SchemaChangedException;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import io.aeyer.plowshare.protocol.ToolValue;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Optional read-only SDK facade for capability-based resolution and typed field reads. Domain
 * contracts validate opaque references and schema revisions before source work. Names identify
 * candidates, never grant authority or select an arbitrary winner among duplicate names.
 */
public final class HomeAssistantCapabilityTools implements HomeAssistantToolHandlers {
  private static final int RESULT_LIMIT = 15000;
  private final String prefix;
  private final HomeAssistantResourceCapabilities capabilities;
  private final List<RelayTools.Declaration> declarations;

  public HomeAssistantCapabilityTools(
      String prefix, HomeAssistantResourceCapabilities capabilities, Duration requestTimeout) {
    this.prefix = HomeAssistantReadTools.prefix(prefix);
    this.capabilities = Objects.requireNonNull(capabilities);
    Objects.requireNonNull(requestTimeout);
    if (requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(Duration.ofSeconds(300)) > 0)
      throw new IllegalArgumentException("HA capability timeout must be positive and bounded");
    int timeoutSeconds = (int) ((requestTimeout.toNanos() + 999_999_999L) / 1_000_000_000L);
    declarations =
        List.of(
            declaration(
                "resolve",
                "Find permitted resource/field candidates by names, area and capability. Request CURRENT for measured room temperature; TARGET is a setpoint. AMBIGUOUS covers all matches before paging; availability never breaks an identity tie.",
                """
                {"text":{"type":"string","maxLength":256},
                 "domain":{"type":"string","minLength":1,"maxLength":64},
                 "area":{"type":"string","minLength":1,"maxLength":256},
                 "capability":{"type":"string","minLength":1,"maxLength":64},
                 "role":{"type":"string","enum":["CURRENT","TARGET","STATUS","UNKNOWN"]},
                 "offset":{"type":"integer","minimum":0,"maximum":16384},
                 "limit":{"type":"integer","minimum":1,"maximum":20},
                 "revision":{"type":"string","minLength":64,"maxLength":64}}
                """,
                List.of(),
                timeoutSeconds),
            declaration(
                "describe_fields",
                "Describe permitted typed fields of an opaque resource at the exact discovered revision. Fields distinguish measurement roles, units and unsupported or unknown meaning; this does not return values.",
                """
                {"resourceId":{"type":"string","minLength":66,"maxLength":66},
                 "revision":{"type":"string","minLength":64,"maxLength":64},
                 "offset":{"type":"integer","minimum":0,"maximum":64},
                 "limit":{"type":"integer","minimum":1,"maximum":16}}
                """,
                List.of("resourceId", "revision"),
                timeoutSeconds),
            declaration(
                "read_fields",
                "Read at most 20 explicitly discovered fields at one exact schema revision. Preserve each value's source, current-versus-target role, unit and availability; never average sources or substitute an unavailable reading.",
                """
                {"revision":{"type":"string","minLength":64,"maxLength":64},
                 "selections":{"type":"array","minItems":1,"maxItems":20,"items":{
                   "type":"object","properties":{
                     "resourceId":{"type":"string","minLength":66,"maxLength":66},
                     "fields":{"type":"array","minItems":1,"maxItems":20,"uniqueItems":true,
                       "items":{"type":"string","minLength":1,"maxLength":266}}},
                   "required":["resourceId","fields"],"additionalProperties":false}}}
                """,
                List.of("revision", "selections"),
                timeoutSeconds));
  }

  private RelayTools.Declaration declaration(
      String suffix,
      String description,
      String properties,
      List<String> required,
      int timeoutSeconds) {
    try {
      // Inline every nested object: provider/model templates need no reference resolver to see
      // the actual input types. This preserves the existing HA revision-schema compatibility fix.
      String schema =
          "{\"type\":\"object\",\"properties\":"
              + properties
              + ",\"required\":"
              + Json.MAPPER.writeValueAsString(required)
              + ",\"additionalProperties\":false}";
      return new RelayTools.Declaration(
          prefix + suffix,
          description,
          Json.MAPPER.readValue(schema, SchemaDefinition.class),
          timeoutSeconds);
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid built-in HA capability schema", invalid);
    }
  }

  @Override
  public List<RelayTools.Declaration> declarations() {
    return declarations;
  }

  /**
   * Compose this mode's four-tool catalogue with the unchanged local health declaration. Legacy
   * data handlers are deliberately unreachable: their frozen policy cannot enforce live Assist
   * exposure or the capability mode's exclusions. Absent capability configuration still selects the
   * original six-tool host instead.
   */
  public HomeAssistantToolHandlers withHealth(HomeAssistantToolHandlers healthSource) {
    Objects.requireNonNull(healthSource);
    var health =
        healthSource.declarations().stream()
            .filter(value -> value.name().equals(prefix + "health"))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Matching HA health tool required"));
    var composed = new ArrayList<RelayTools.Declaration>();
    composed.add(health);
    composed.addAll(declarations);
    List<RelayTools.Declaration> offered = List.copyOf(composed);
    return new HomeAssistantToolHandlers() {
      @Override
      public List<RelayTools.Declaration> declarations() {
        return offered;
      }

      @Override
      public RelayTools.Result execute(RelayTools.Call call, OperationBudget budget)
          throws IOException {
        Objects.requireNonNull(call);
        Objects.requireNonNull(budget).remaining();
        if (call.tool().equals(health.name())) return healthSource.execute(call, budget);
        if (declarations.stream().noneMatch(value -> value.name().equals(call.tool())))
          return rejected("HA tool is not offered under the configured capability policy.");
        return HomeAssistantCapabilityTools.this.execute(call, budget);
      }
    };
  }

  private sealed interface Request permits Resolve, Describe, Read {}

  private record Resolve(Query query) implements Request {}

  private record Describe(String resourceId, String revision, int offset, int limit)
      implements Request {
    private Describe {
      HomeAssistantCapabilities.resourceId(resourceId);
      HomeAssistantCapabilities.revision(revision);
      if (offset < 0
          || offset > HomeAssistantCapabilities.MAX_FIELDS
          || limit < 1
          || limit > HomeAssistantCapabilities.FIELD_PAGE)
        throw new IllegalArgumentException("Invalid HA field page");
    }
  }

  private record Read(FieldReadRequest request) implements Request {}

  @Override
  public RelayTools.Result execute(RelayTools.Call call, OperationBudget budget)
      throws IOException {
    Objects.requireNonNull(call);
    Objects.requireNonNull(budget).remaining();
    RelayTools.Declaration declaration =
        declarations.stream()
            .filter(value -> value.name().equals(call.tool()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown HA capability tool"));
    if (!"plowshare-tool/2".equals(call.schema())
        || !SchemaCodec.matchesFingerprint(declaration.schema(), call.schemaFingerprint()))
      return rejected("The offered HA capability schema is no longer available.");
    Request request;
    try {
      declaration.validate(call.arguments());
      request = decode(call.tool(), call.arguments());
    } catch (IllegalArgumentException invalid) {
      return rejected("HA capability input or source revision is invalid.");
    }
    String text;
    try {
      text =
          switch (request) {
            case Resolve resolve ->
                Json.MAPPER.writeValueAsString(capabilities.resolve(resolve.query(), budget));
            case Describe describe ->
                Json.MAPPER.writeValueAsString(
                    capabilities.describe(
                        describe.resourceId(),
                        describe.revision(),
                        describe.offset(),
                        describe.limit(),
                        budget));
            case Read read ->
                Json.MAPPER.writeValueAsString(capabilities.read(read.request(), budget));
          };
    } catch (IllegalArgumentException invalid) {
      budget.remaining();
      return rejected("HA capability selection or schema revision is unavailable.");
    } catch (SchemaChangedException changed) {
      budget.remaining();
      return rejected(
          "HA capability schema changed; resolve fresh candidates before reading again.");
    } catch (IOException unavailable) {
      // Generic IO cannot identify the failing layer or a physical device cause. Preserve the
      // owner's deadline: expiry still throws rather than publishing a late diagnostic reply.
      budget.remaining();
      return rejected("HA capability observation is unavailable; no cause has been established.");
    }
    budget.remaining();
    if (text.length() > RESULT_LIMIT)
      return rejected("HA observation exceeds this result profile; narrow the page or fields.");
    return new RelayTools.Result(RelayTools.State.COMPLETED, text);
  }

  private Request decode(String tool, Map<String, ToolValue> arguments) {
    return switch (tool.substring(prefix.length())) {
      case "resolve" ->
          new Resolve(
              new Query(
                  optionalText(arguments, "text").orElse(""),
                  optionalText(arguments, "domain"),
                  optionalText(arguments, "area"),
                  optionalText(arguments, "capability"),
                  optionalText(arguments, "role").map(FieldRole::valueOf),
                  integer(arguments, "offset", 0),
                  integer(arguments, "limit", 10),
                  optionalText(arguments, "revision")));
      case "describe_fields" ->
          new Describe(
              text(arguments.get("resourceId")),
              text(arguments.get("revision")),
              integer(arguments, "offset", 0),
              integer(arguments, "limit", 16));
      case "read_fields" ->
          new Read(
              new FieldReadRequest(
                  text(arguments.get("revision")),
                  array(arguments.get("selections")).stream()
                      .map(
                          value -> {
                            Map<String, ToolValue> selection = object(value);
                            return new FieldSelection(
                                text(selection.get("resourceId")),
                                array(selection.get("fields")).stream()
                                    .map(HomeAssistantCapabilityTools::text)
                                    .toList());
                          })
                      .toList()));
      default -> throw new IllegalArgumentException("Unknown HA capability request");
    };
  }

  private static Map<String, ToolValue> object(ToolValue value) {
    if (value instanceof ToolValue.ObjectValue object) return object.values();
    throw new IllegalArgumentException("An object is required");
  }

  private static List<ToolValue> array(ToolValue value) {
    if (value instanceof ToolValue.Array array) return array.values();
    throw new IllegalArgumentException("An array is required");
  }

  private static String text(ToolValue value) {
    if (value instanceof TextParameter text) return text.value();
    throw new IllegalArgumentException("Text is required");
  }

  private static Optional<String> optionalText(Map<String, ToolValue> values, String name) {
    return values.containsKey(name) ? Optional.of(text(values.get(name))) : Optional.empty();
  }

  private static int integer(Map<String, ToolValue> values, String name, int fallback) {
    if (!values.containsKey(name)) return fallback;
    if (values.get(name) instanceof NumberParameter number) {
      try {
        return number.value().intValueExact();
      } catch (ArithmeticException invalid) {
        throw new IllegalArgumentException("An integer is required");
      }
    }
    throw new IllegalArgumentException("An integer is required");
  }

  private static RelayTools.Result rejected(String message) {
    return new RelayTools.Result(RelayTools.State.REJECTED, message);
  }
}
