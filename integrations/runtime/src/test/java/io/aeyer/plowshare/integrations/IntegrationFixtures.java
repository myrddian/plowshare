package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.IntegrationContracts.*;
import io.aeyer.plowshare.protocol.IntegrationPayload.*;
import java.util.*;

/** Wire fixtures convert at their boundary; fakes implement exactly the typed production API. */
final class IntegrationFixtures {
  private IntegrationFixtures() {}

  record Settings() implements AdapterConfiguration {}

  static Map<String, AdapterFactory> factories() {
    return Map.of(
        "fake",
        new AdapterFactory() {
          public String name() {
            return "fake";
          }

          public AdapterConfiguration decode(String source) throws java.io.IOException {
            JsonNode node = Json.parse(source);
            Json.fields(node);
            return new Settings();
          }

          public IntegrationAdapter create(
              AdapterConfiguration settings, java.util.function.Function<String, String> env) {
            throw new UnsupportedOperationException("parser fixture only");
          }
        });
  }

  static ObjectNode actionRecord(String binding, String fingerprint, String status) {
    Draft record = new Draft();
    record.kind = "effect";
    record.binding = binding;
    record.fingerprint = fingerprint;
    record.status = status;
    record.effect =
        new Action(binding, "notify", Map.of("message", new TextParameter("fixture")), "fixture");
    return (ObjectNode) IntegrationCodec.tree(record.build());
  }

  static EventEnvelope event(JsonNode source) {
    ObjectNode node = (ObjectNode) source.deepCopy();
    if (node.path("type").asText().equals("seed")) return new EventEnvelope(new Empty());
    if (node.isEmpty()) return new EventEnvelope(new Empty());
    if (!node.has("type")) node.put("type", "state_changed");
    if (node.path("type").asText().equals("state_changed") && !node.has("availability"))
      node.put("availability", node.has("state") ? "available" : "missing");
    if (node.path("type").asText().equals("connection.gap") && !node.has("epoch"))
      node.put("epoch", "fixture");
    if (node.path("type").asText().equals("connection.gap") && !node.has("observed_at"))
      node.put("observed_at", "2026-10-04T00:00:00Z");
    return IntegrationCodec.event(node);
  }

  static IntegrationAdapter.Result result(String state, JsonNode data) {
    return new IntegrationAdapter.Result(
        state,
        IntegrationCodec.decode(
            data, io.aeyer.plowshare.protocol.ExternalResult.IntegrationResult.class));
  }

  static Map<String, Reading> readings(JsonNode n) {
    ObjectNode source = (ObjectNode) n.deepCopy();
    source
        .fields()
        .forEachRemaining(
            e -> {
              ObjectNode value = (ObjectNode) e.getValue();
              value.remove(List.of("type", "resync"));
              if (!value.has("alias")) value.put("alias", e.getKey());
              if (!value.has("availability"))
                value.put("availability", value.has("state") ? "available" : "missing");
            });
    return IntegrationCodec.readings(source);
  }
}
