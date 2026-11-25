package io.aeyer.plowshare.server.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Action;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Checkpoint;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Contract;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Diagnostic;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Issue;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Limit;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Plan;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Problem;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Request;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Strict sandbox/model boundary. Raw JSON never enters the recovery policy's typed contracts. */
public final class ModelOutputRecoveryCodec {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ModelOutputRecoveryCodec() {}

  record Decoded(Map<String, String> fields, List<Issue> issues, String pass, boolean truncated) {
    Decoded {
      fields = Map.copyOf(fields);
      issues = List.copyOf(issues);
    }
  }

  static Decoded decode(String text) {
    var result = LlmJson.parse(text);
    if (!result.recovered()
        && result.attempts().stream()
            .anyMatch(
                attempt -> attempt.error() != null && attempt.error().contains("Duplicate field")))
      return new Decoded(
          Map.of(), List.of(new Issue(Problem.CONTENT, "ambiguous_fields", 0, 0)), "none", false);
    if (result.truncation().isPresent())
      return new Decoded(
          Map.of(), List.of(new Issue(Problem.FORMAT, "truncation", 0, 0)), "none", true);
    if (!result.recovered())
      return new Decoded(
          Map.of(), List.of(new Issue(Problem.FORMAT, "response", 0, 0)), "none", false);
    if (!result.value().isObject())
      return new Decoded(
          Map.of(), List.of(new Issue(Problem.SHAPE, "response", 0, 0)), result.pass(), false);
    var fields = new LinkedHashMap<String, String>();
    var issues = new ArrayList<Issue>();
    result
        .value()
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue().isTextual())
                fields.put(entry.getKey(), entry.getValue().textValue());
              else
                issues.add(
                    new Issue(
                        Problem.SHAPE,
                        entry.getKey().matches("[a-z][a-z0-9_]{0,63}")
                            ? entry.getKey()
                            : "invalid_field",
                        0,
                        0));
            });
    return new Decoded(fields, issues, result.pass(), false);
  }

  /** Only authored policy/checkpoint JSON is read strictly; model text uses LlmJson separately. */
  public static Request request(String raw, String specification, String retained) {
    JsonNode spec = strict(specification);
    exact(spec, Set.of("name", "worker", "fields"));
    JsonNode fields = spec.get("fields");
    if (!fields.isObject()) throw new IllegalArgumentException("Recovery fields must be an object");
    var limits = new LinkedHashMap<String, Limit>();
    fields
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode limit = entry.getValue();
              exact(limit, Set.of("words", "required"));
              if (!limit.get("words").isIntegralNumber()
                  || !limit.get("words").canConvertToInt()
                  || !limit.get("required").isBoolean())
                throw new IllegalArgumentException("Invalid recovery field limit");
              limits.put(
                  entry.getKey(),
                  new Limit(limit.get("words").intValue(), limit.get("required").booleanValue()));
            });
    Contract contract = new Contract(string(spec, "name"), limits, string(spec, "worker"));
    Optional<Checkpoint> checkpoint = Optional.empty();
    if (!retained.equals("null")) {
      JsonNode state = strict(retained);
      exact(state, Set.of("original", "contractHash", "action"));
      checkpoint =
          Optional.of(
              new Checkpoint(
                  string(state, "original"),
                  string(state, "contractHash"),
                  Action.valueOf(string(state, "action"))));
    }
    return new Request(raw, contract, checkpoint);
  }

  /** Result values are ordinary guest-owned objects; tasks use the normal journaled agent_run. */
  public static String result(Plan plan) {
    ObjectNode node = JSON.createObjectNode().put("action", plan.action().name());
    node.set("value", JSON.valueToTree(plan.value()));
    node.put("reason", plan.diagnostic().reason());
    node.set(
        "checkpoint", plan.checkpoint().<JsonNode>map(JSON::valueToTree).orElse(JSON.nullNode()));
    plan.task()
        .ifPresent(
            task -> {
              var command = node.putObject("command").put("tool", "agent_run");
              command
                  .putObject("arguments")
                  .put("agent", task.worker())
                  .put("task", task.instruction())
                  .put("recovery", true);
            });
    return node.toString();
  }

  public static String diagnostic(
      Diagnostic decision, String conversation, int command, String sourceHash) {
    ObjectNode node = JSON.valueToTree(decision);
    node.put("type", "model_output_recovery")
        .put("conversation", conversation)
        .put("decisionCommand", command)
        .put("scriptHash", sourceHash);
    // The helper may examine retained state or initial input rather than the latest receipt.
    // This is an execution anchor, not a claim that the preceding command produced the text.
    if (command > 0) node.put("previousCommand", command - 1);
    else node.putNull("previousCommand");
    return node.toString();
  }

  /** Syntax success is not semantic acceptance; legacy workflows retain their own shape checks. */
  public static Diagnostic parsingDiagnostic(String raw, LlmJson.Recovery parsed) {
    String hash = StandardModelOutputRecovery.hash(raw);
    boolean truncated = parsed.truncation().isPresent();
    return new Diagnostic(
        ModelOutputRecovery.VERSION,
        "json-syntax",
        parsed.recovered() ? Action.PARSED : Action.NEEDS_REVIEW,
        0,
        hash,
        hash,
        parsed.recovered() ? parsed.pass() : "none",
        parsed.recovered()
            ? List.of()
            : List.of(new Issue(Problem.FORMAT, truncated ? "truncation" : "response", 0, 0)),
        parsed.recovered()
            ? "Model JSON parsed; domain validation remains with the caller"
            : truncated
                ? "Model JSON ended early; original response remains retained"
                : "Local JSON recovery exhausted; original response remains retained");
  }

  static String contractJson(Contract contract) {
    return encode(contract);
  }

  static String fieldsJson(Map<String, String> fields) {
    return encode(new TreeMap<>(fields));
  }

  static String issuesJson(List<Issue> issues) {
    return encode(issues);
  }

  private static String encode(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Recovery value cannot be encoded", invalid);
    }
  }

  private static JsonNode strict(String value) {
    if (value == null || value.length() > 131072)
      throw new IllegalArgumentException("Invalid recovery specification size");
    try {
      return JSON.reader()
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .readTree(value);
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Invalid recovery specification JSON", invalid);
    }
  }

  private static void exact(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject() || node.size() != fields.size())
      throw new IllegalArgumentException("Invalid recovery specification fields");
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!fields.contains(name))
                throw new IllegalArgumentException("Unknown recovery specification field");
            });
  }

  private static String string(JsonNode node, String field) {
    if (!node.path(field).isTextual())
      throw new IllegalArgumentException("Expected recovery text: " + field);
    return node.get(field).textValue();
  }
}
