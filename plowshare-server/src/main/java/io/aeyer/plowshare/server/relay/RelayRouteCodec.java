package io.aeyer.plowshare.server.relay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Configuration and guest JSON boundary. Raw nodes never leave this conversion utility. */
final class RelayRouteCodec {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RelayRouteCodec() {}

  static List<String> active(String source) {
    JsonNode root = parse(source);
    fields(root, Set.of("version", "active"), Set.of());
    version(root);
    JsonNode active = array(root.get("active"), 32);
    var names = new ArrayList<String>();
    var unique = new HashSet<String>();
    for (var value : active) {
      String name = RelayRouting.folder(text(value));
      if (!unique.add(name)) throw invalid();
      names.add(name);
    }
    return List.copyOf(names);
  }

  static Map<String, Relay.Policy> policies(String source) {
    JsonNode root = parse(source);
    fields(root, Set.of("version", "topics"), Set.of());
    version(root);
    JsonNode topics = root.get("topics");
    if (!topics.isObject() || topics.size() > 256) throw invalid();
    Map<String, Relay.Policy> policies = new LinkedHashMap<>();
    topics
        .fields()
        .forEachRemaining(
            entry -> {
              String name = RelayValues.name(entry.getKey(), "topic");
              JsonNode policy = entry.getValue();
              fields(policy, Set.of("retentionDays"), Set.of("maxRecords"));
              long days = positive(policy.get("retentionDays"));
              if (days > 3650) throw invalid();
              Long cap = policy.has("maxRecords") ? positive(policy.get("maxRecords")) : null;
              policies.put(name, new Relay.Policy(Duration.ofDays(days), cap));
            });
    return Map.copyOf(policies);
  }

  static RelayRouting.Manifest manifest(String encoded) {
    JsonNode root = parse(encoded);
    fields(root, Set.of("version", "subscriptions"), Set.of());
    version(root);
    var result = new ArrayList<RelayRouting.Subscription>();
    for (var sub : array(root.get("subscriptions"), 32)) {
      fields(sub, Set.of("name", "topic", "kind", "start"), Set.of());
      var kind =
          switch (text(sub.get("kind"))) {
            case "empty" -> RelayPayload.Kind.EMPTY;
            case "text" -> RelayPayload.Kind.TEXT;
            case "schedule.due" -> RelayPayload.Kind.SCHEDULE_DUE;
            case "lifecycle" -> RelayPayload.Kind.LIFECYCLE;
            default -> throw invalid();
          };
      var start =
          switch (text(sub.get("start"))) {
            case "oldest-retained" -> Relay.Start.OLDEST_RETAINED;
            case "latest" -> Relay.Start.LATEST;
            default -> throw invalid();
          };
      result.add(
          new RelayRouting.Subscription(
              text(sub.get("name")), text(sub.get("topic")), kind, start));
    }
    return new RelayRouting.Manifest(result);
  }

  static List<RelayRouting.Selection> selections(String encoded) {
    var result = new ArrayList<RelayRouting.Selection>();
    var names = new HashSet<String>();
    for (var branch : array(parse(encoded), 32)) {
      fields(branch, Set.of("name", "receiver"), Set.of("script", "publishTo", "work"));
      var selection =
          new RelayRouting.Selection(
              text(branch.get("name")),
              text(branch.get("receiver")),
              branch.has("script") ? text(branch.get("script")) : null,
              branch.has("publishTo") ? text(branch.get("publishTo")) : null,
              branch.has("work") ? work(branch.get("work")) : null);
      if (!names.add(selection.name())) throw invalid();
      result.add(selection);
    }
    return List.copyOf(result);
  }

  private static RelayWork work(JsonNode value) {
    fields(value, Set.of("agent"), Set.of("project", "definition"));
    return new RelayWork(
        text(value.get("agent")),
        value.has("project") ? text(value.get("project")) : null,
        value.has("definition") ? text(value.get("definition")) : null);
  }

  static String event(Relay.Publication input) {
    return input(
        new RelayRouting.Subscription(
            "event", input.topic().name(), input.event().payload().kind(), Relay.Start.LATEST),
        input);
  }

  static String input(RelayRouting.Subscription subscription, Relay.Publication input) {
    var root = JSON.createObjectNode();
    root.put("subscription", subscription.name());
    root.put("topic", input.topic().name());
    // Database positions/IDs must not lose precision in JavaScript's IEEE-754 number type.
    root.put("projectId", Long.toString(input.topic().projectId()));
    root.put("position", Long.toString(input.position()));
    root.put("schemaVersion", input.schemaVersion());
    root.put("eventId", input.event().eventId());
    root.put("publisher", input.event().publisher());
    root.put("occurredAt", input.event().occurredAt().toString());
    root.put("publishedAt", input.publishedAt().toString());
    root.put("correlationId", input.event().correlationId());
    root.put("causationId", input.event().causationId());
    root.set("causation", JSON.valueToTree(input.event().causation()));
    root.set("payload", parse(RelayPayloadCodec.write(input.event().payload()), 262144));
    return root.toString();
  }

  private static JsonNode parse(String source) {
    return parse(source, 65536);
  }

  private static JsonNode parse(String source, int limit) {
    if (source == null
        || source.length() > limit
        || source.getBytes(StandardCharsets.UTF_8).length > limit) throw invalid();
    try {
      var value = JSON.readTree(source);
      if (value == null) throw invalid();
      return value;
    } catch (IOException failed) {
      throw invalid();
    }
  }

  private static void fields(JsonNode value, Set<String> required, Set<String> optional) {
    if (value == null || !value.isObject()) throw invalid();
    for (String name : required) if (!value.has(name)) throw invalid();
    value
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!required.contains(name) && !optional.contains(name)) throw invalid();
            });
  }

  private static JsonNode array(JsonNode value, int limit) {
    if (value == null || !value.isArray() || value.size() > limit) throw invalid();
    return value;
  }

  private static String text(JsonNode value) {
    if (value == null || !value.isTextual()) throw invalid();
    return value.textValue();
  }

  private static long positive(JsonNode value) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 1) throw invalid();
    return value.longValue();
  }

  private static void version(JsonNode value) {
    if (positive(value.get("version")) != 1) throw invalid();
  }

  private static CallerFault invalid() {
    return new CallerFault(
        "Invalid Relay configuration or routing result: check version, fields, types and limits");
  }
}
