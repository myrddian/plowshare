package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ActionDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Cursor;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.State;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.UnsupportedAction;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.Parameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import io.aeyer.plowshare.protocol.ToolValue;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Four read-only SDK tools over one explicitly configured resource binding. Input trees are decoded
 * here into the existing typed contracts; neither caller-supplied policies nor arbitrary HA targets
 * cross that boundary. Results retain source revision/provenance and never imply control grants.
 */
public final class HomeAssistantReadTools implements HomeAssistantToolHandlers {
  // JSON text contains no raw ASCII controls, so SDK-envelope quoting expands it at most twice.
  // Reserve room for the envelope's bounded project/provider/tool identities as well as its text
  // field's independent 16,384-unit ceiling. This is a protocol/data bound, not a prose target.
  private static final int RESULT_LIMIT = 15000;
  private static final String REVISION_SCHEMA =
      """
      {"type":"object","description":"Pass the revision object returned by discovery, not a JSON-encoded string.","properties":{
        "binding":{"type":"string","minLength":1,"maxLength":256},
        "credential":{"type":"string","minLength":1,"maxLength":256},
        "policy":{"type":"string","minLength":1,"maxLength":256},
        "epoch":{"type":"string","minLength":1,"maxLength":256},
        "metadata":{"type":"string","minLength":1,"maxLength":19},
        "state":{"type":"string","minLength":1,"maxLength":19}},
       "required":["binding","credential","policy","epoch","metadata","state"],
       "additionalProperties":false}
      """;

  private final Binding binding;
  private final String prefix;
  private final ResourceDiscovery discovery;
  private final ResourceReader reader;
  private final int timeoutSeconds;
  private final List<RelayTools.Declaration> declarations;

  public HomeAssistantReadTools(
      Binding binding, String prefix, ResourceDiscovery discovery, ResourceReader reader) {
    this(binding, prefix, discovery, reader, Duration.ofSeconds(2));
  }

  public HomeAssistantReadTools(
      Binding binding,
      String prefix,
      ResourceDiscovery discovery,
      ResourceReader reader,
      Duration requestTimeout) {
    this.binding = Objects.requireNonNull(binding);
    this.prefix = prefix(prefix);
    this.discovery = Objects.requireNonNull(discovery);
    this.reader = Objects.requireNonNull(reader);
    Objects.requireNonNull(requestTimeout);
    if (requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(Duration.ofSeconds(300)) > 0)
      throw new IllegalArgumentException(
          "HA read timeout must be positive and at most 300 seconds");
    timeoutSeconds = (int) ((requestTimeout.toNanos() + 999_999_999L) / 1_000_000_000L);
    declarations =
        List.of(
            declaration(
                "search",
                "Search permitted HA resources; discovery grants no control.",
                """
          {"text":{"type":"string","maxLength":256},
           "domain":{"type":"string","minLength":1,"maxLength":256},
           "area":{"type":"string","minLength":1,"maxLength":256},
           "limit":{"type":"integer","minimum":1,"maximum":20},
           "cursor":{"type":"object","properties":{
             "revision":%s,
             "offset":{"type":"integer","minimum":0,"maximum":256}},
             "required":["revision","offset"],"additionalProperties":false}}
          """
                    .formatted(REVISION_SCHEMA),
                List.of()),
            declaration(
                "describe",
                "Describe one permitted alias and a bounded page of attribute names.",
                """
          {"alias":{"type":"string","minLength":1,"maxLength":256},
           "revision":%s,
           "attributeOffset":{"type":"integer","minimum":0,"maximum":257}}
          """
                    .formatted(REVISION_SCHEMA),
                List.of("alias", "revision")),
            declaration(
                "read",
                "Read a fresh batch of permitted aliases; optional attributes project only already-permitted values.",
                """
          {"aliases":{"type":"array","items":{"type":"string","minLength":1,"maxLength":256},
             "minItems":1,"maxItems":20,"uniqueItems":true},
           "revision":%s,
           "attributes":{"type":"array","items":{"type":"string","minLength":1,"maxLength":256},
             "maxItems":16,"uniqueItems":true}}
          """
                    .formatted(REVISION_SCHEMA),
                List.of("aliases", "revision")),
            declaration(
                "list_actions",
                "List configured action offers for a permitted alias; does not execute or grant them.",
                """
          {"alias":{"type":"string","minLength":1,"maxLength":256},
           "revision":%s,
           "offset":{"type":"integer","minimum":0,"maximum":128},
           "limit":{"type":"integer","minimum":1,"maximum":20}}
          """
                    .formatted(REVISION_SCHEMA),
                List.of("alias", "revision")));
  }

  static String prefix(String value) {
    if (value == null || value.length() > 220 || !value.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*_"))
      throw new IllegalArgumentException(
          "An explicit lower-case HA tool prefix ending in _ is required");
    return value;
  }

  @Override
  public List<RelayTools.Declaration> declarations() {
    return declarations;
  }

  private RelayTools.Declaration declaration(
      String suffix, String description, String properties, List<String> required) {
    try {
      // Keep each nested object explicit so model/provider templates need not resolve references
      // to see its type. The live read test emitted strings for reference-only revision fields.
      // Inlining changes presentation only: the same closed revision object is still mandatory.
      String source =
          "{\"type\":\"object\",\"properties\":"
              + properties
              + ",\"required\":"
              + Json.MAPPER.writeValueAsString(required)
              + ",\"additionalProperties\":false}";
      return new RelayTools.Declaration(
          prefix + suffix,
          description,
          Json.MAPPER.readValue(source, SchemaDefinition.class),
          timeoutSeconds);
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid built-in HA tool schema", invalid);
    }
  }

  private sealed interface Request permits Find, Describe, Read, Actions {}

  private record Find(Search search) implements Request {}

  private record Describe(Reference reference, Revision revision, int attributeOffset)
      implements Request {}

  private record Read(ReadRequest selection, Set<String> attributes) implements Request {}

  private record Actions(Reference reference, Revision revision, int offset, int limit)
      implements Request {}

  @Override
  public RelayTools.Result execute(RelayTools.Call call, OperationBudget budget)
      throws IOException {
    Objects.requireNonNull(call);
    Objects.requireNonNull(budget).remaining();
    RelayTools.Declaration declaration =
        declarations.stream()
            .filter(value -> value.name().equals(call.tool()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown HA read tool"));
    if (!"plowshare-tool/2".equals(call.schema())
        || !SchemaCodec.matchesFingerprint(declaration.schema(), call.schemaFingerprint()))
      return rejected("The offered HA tool schema is no longer available.");
    declaration.validate(call.arguments());
    Request request;
    try {
      request = decode(call.tool(), call.arguments());
    } catch (IllegalArgumentException invalid) {
      return rejected("HA tool input or source revision is invalid.");
    }
    Response response =
        switch (request) {
          case Find find -> {
            var page = discovery.search(find.search(), budget);
            yield new SearchResult(
                view(page.revision()),
                page.resources().stream().map(this::summary).toList(),
                page.next()
                    .map(value -> new CursorView(view(value.revision()), value.offset()))
                    .orElse(null));
          }
          case Describe describe -> {
            Optional<Description> found =
                discovery.describe(describe.reference(), describe.revision(), budget);
            if (found.isEmpty())
              yield new DescribeResult(view(describe.revision()), null, List.of(), null);
            Description description = found.orElseThrow();
            List<String> attributes = description.readableAttributes().stream().sorted().toList();
            if (describe.attributeOffset() > attributes.size())
              yield new ErrorResult("HA attribute page is unavailable.");
            int end = Math.min(attributes.size(), describe.attributeOffset() + 16);
            yield new DescribeResult(
                view(describe.revision()),
                summary(description),
                attributes.subList(describe.attributeOffset(), end),
                end < attributes.size() ? end : null);
          }
          case Read read -> {
            var batch = reader.read(read.selection(), budget);
            yield new ReadResult(
                view(batch.revision()),
                batch.states().stream().map(value -> state(value, read.attributes())).toList());
          }
          case Actions actions -> {
            List<ActionDescription> offered =
                discovery.actions(actions.reference(), actions.revision(), budget);
            if (actions.offset() > offered.size())
              yield new ErrorResult("HA action page is unavailable.");
            int end = Math.min(offered.size(), actions.offset() + actions.limit());
            yield new ActionsResult(
                view(actions.revision()),
                offered.subList(actions.offset(), end).stream()
                    .map(HomeAssistantReadTools::action)
                    .toList(),
                end < offered.size() ? end : null);
          }
        };
    budget.remaining();
    if (response instanceof ErrorResult error) return rejected(error.message());
    String text = Json.MAPPER.writeValueAsString(response);
    budget.remaining();
    if (text.length() > RESULT_LIMIT)
      return rejected(
          "HA observation exceeds this tool's response profile; narrow the page, aliases or attributes.");
    return new RelayTools.Result(RelayTools.State.COMPLETED, text);
  }

  private Request decode(String tool, Map<String, ToolValue> args) {
    String suffix = tool.substring(prefix.length());
    return switch (suffix) {
      case "search" ->
          new Find(
              new Search(
                  optionalText(args, "text").orElse(""),
                  optionalText(args, "domain"),
                  optionalText(args, "area"),
                  integer(args, "limit", 10),
                  Optional.ofNullable(args.get("cursor"))
                      .map(
                          value -> {
                            Map<String, ToolValue> fields = object(value);
                            return new Cursor(
                                revision(fields.get("revision")), integer(fields, "offset", 0));
                          })));
      case "describe" ->
          new Describe(
              reference(text(args.get("alias"))),
              revision(args.get("revision")),
              integer(args, "attributeOffset", 0));
      case "read" ->
          new Read(
              new ReadRequest(
                  revision(args.get("revision")),
                  strings(args.get("aliases")).stream().map(this::reference).toList()),
              args.containsKey("attributes")
                  ? Set.copyOf(strings(args.get("attributes")))
                  : Set.of());
      case "list_actions" ->
          new Actions(
              reference(text(args.get("alias"))),
              revision(args.get("revision")),
              integer(args, "offset", 0),
              integer(args, "limit", 10));
      default -> throw new IllegalArgumentException("Unknown HA read tool");
    };
  }

  private Reference reference(String alias) {
    return new Reference(binding, alias);
  }

  private Revision revision(ToolValue source) {
    Map<String, ToolValue> values = object(source);
    Binding supplied = new Binding(text(values.get("binding")), text(values.get("credential")));
    if (!binding.equals(supplied)) throw new IllegalArgumentException("Foreign HA binding");
    return new Revision(
        supplied,
        text(values.get("policy")),
        text(values.get("epoch")),
        counter(values.get("metadata")),
        counter(values.get("state")));
  }

  private static long counter(ToolValue value) {
    String text = text(value);
    if (!text.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid revision");
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Invalid revision");
    }
  }

  private static Map<String, ToolValue> object(ToolValue value) {
    if (value instanceof ToolValue.ObjectValue object) return object.values();
    throw new IllegalArgumentException("An object is required");
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

  private static List<String> strings(ToolValue value) {
    if (value instanceof ToolValue.Array array)
      return array.values().stream().map(HomeAssistantReadTools::text).toList();
    throw new IllegalArgumentException("An array is required");
  }

  private ResourceView summary(Description value) {
    return new ResourceView(
        value.reference().alias(),
        value.entityId(),
        value.name(),
        value.domain(),
        value.device().orElse(null),
        value.area().orElse(null),
        value.registries());
  }

  private static StateView state(State source, Set<String> selected) {
    Map<String, Parameter> attributes = new LinkedHashMap<>();
    source
        .attributes()
        .forEach(
            (name, value) -> {
              if (selected.contains(name)) attributes.put(name, value);
            });
    return new StateView(
        source.reference().alias(),
        source.availability().name(),
        source.value().orElse(null),
        Map.copyOf(attributes),
        source.unsupportedAttributes().stream().filter(selected::contains).sorted().toList(),
        source.sourceUpdatedAt().map(Object::toString).orElse(null),
        source.observedAt().toString(),
        source.stale());
  }

  private static ActionView action(ActionDescription value) {
    return switch (value) {
      case ConfiguredAction configured ->
          new ActionView(
              configured.alias(),
              true,
              configured.profile().service(),
              configured.profile().targets().size(),
              configured.profile().parameters().keySet().stream().sorted().toList(),
              null);
      case UnsupportedAction unsupported ->
          new ActionView(
              unsupported.alias(), false, null, 0, List.of(), unsupported.reason().name());
    };
  }

  private static RevisionView view(Revision revision) {
    return new RevisionView(
        revision.binding().binding(),
        revision.binding().credentialContext(),
        revision.policy(),
        revision.epoch(),
        Long.toString(revision.metadataVersion()),
        Long.toString(revision.stateVersion()));
  }

  private static RelayTools.Result rejected(String message) {
    return new RelayTools.Result(RelayTools.State.REJECTED, message);
  }

  private sealed interface Response
      permits SearchResult, DescribeResult, ReadResult, ActionsResult, ErrorResult {}

  private record RevisionView(
      String binding,
      String credential,
      String policy,
      String epoch,
      String metadata,
      String state) {}

  private record CursorView(RevisionView revision, int offset) {}

  private record ResourceView(
      String alias,
      String entity,
      String name,
      String domain,
      String device,
      String area,
      HomeAssistantResources.Registries registries) {}

  private record StateView(
      String alias,
      String availability,
      String value,
      Map<String, Parameter> attributes,
      List<String> unsupportedAttributes,
      String sourceUpdatedAt,
      String observedAt,
      boolean stale) {}

  private record ActionView(
      String alias,
      boolean supported,
      String service,
      int targetCount,
      List<String> parameters,
      String unsupportedReason) {}

  private record SearchResult(RevisionView revision, List<ResourceView> resources, CursorView next)
      implements Response {}

  private record DescribeResult(
      RevisionView revision,
      ResourceView resource,
      List<String> attributes,
      Integer nextAttributeOffset)
      implements Response {}

  private record ReadResult(RevisionView revision, List<StateView> states) implements Response {}

  private record ActionsResult(RevisionView revision, List<ActionView> actions, Integer nextOffset)
      implements Response {}

  private record ErrorResult(String message) implements Response {}
}
