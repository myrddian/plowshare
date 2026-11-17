package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Delivery;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import io.aeyer.plowshare.protocol.IntegrationPayload.Parameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import io.aeyer.plowshare.protocol.ToolValue;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit opt-in control provider. It cannot share the read-only provider identity or overlap its
 * prefix. Server scopes remain the admission authority; local policy and current offers further
 * narrow effects. Use only through the existing journal-owning SDK provider host.
 */
public final class HomeAssistantActionTools implements HomeAssistantToolHandlers {
  private static final String COMMON =
      """
      "alias":{"type":"string","minLength":1,"maxLength":256},
      "action":{"type":"string","minLength":1,"maxLength":256},
      "revision":{"type":"object","properties":{
        "binding":{"type":"string","minLength":1,"maxLength":256},
        "credential":{"type":"string","minLength":1,"maxLength":256},
        "policy":{"type":"string","minLength":1,"maxLength":256},
        "epoch":{"type":"string","minLength":1,"maxLength":256},
        "metadata":{"type":"string","minLength":1,"maxLength":19},
        "state":{"type":"string","minLength":1,"maxLength":19}},
       "required":["binding","credential","policy","epoch","metadata","state"],"additionalProperties":false}
      """;
  private final Binding binding;
  private final HomeAssistantToolProvider.Configuration configuration;
  private final ResourceDiscovery discovery;
  private final ActionOfferValidator offers;
  private final HomeAssistantActionPolicy policy;
  private final ActionExecutor executor;
  private final List<RelayTools.Declaration> declarations;

  public HomeAssistantActionTools(
      Binding binding,
      HomeAssistantToolProvider.Configuration control,
      HomeAssistantToolProvider.Configuration readOnly,
      ResourceDiscovery discovery,
      ActionOfferValidator offers,
      HomeAssistantActionPolicy policy,
      ActionExecutor executor) {
    this.binding = Objects.requireNonNull(binding);
    configuration = Objects.requireNonNull(control);
    Objects.requireNonNull(readOnly);
    if (!control.binding().project().equals(readOnly.binding().project())
        || control.binding().provider().equals(readOnly.binding().provider())
        || control.prefix().startsWith(readOnly.prefix())
        || readOnly.prefix().startsWith(control.prefix()))
      throw new IllegalArgumentException(
          "Control requires a separate provider and nonoverlapping prefix");
    this.discovery = Objects.requireNonNull(discovery);
    this.offers = Objects.requireNonNull(offers);
    this.policy = Objects.requireNonNull(policy);
    this.executor = Objects.requireNonNull(executor);
    declarations =
        List.of(
            declaration(
                "describe_action",
                "Describe an explicitly permitted fixed action profile, complete target bundle and exact schema; grants no execution.",
                "",
                List.of("alias", "action", "revision")),
            declaration(
                "execute_action",
                "Submit one configured action over the exact full target bundle. Acknowledgement is not observed physical success; UNKNOWN must never be retried automatically.",
                """
          ,"profileFingerprint":{"type":"string","minLength":64,"maxLength":64},
          "service":{"type":"string","minLength":1,"maxLength":256},
          "targets":{"type":"array","items":{"type":"string","minLength":1,"maxLength":256},"minItems":1,"maxItems":20,"uniqueItems":true},
          "parameters":{"type":"object","maxProperties":256,"additionalProperties":{"anyOf":[
             {"type":"string","maxLength":4096},{"type":"number"},{"type":"boolean"}]}}
          """,
                List.of(
                    "alias",
                    "action",
                    "revision",
                    "profileFingerprint",
                    "service",
                    "targets",
                    "parameters")));
  }

  private RelayTools.Declaration declaration(
      String suffix, String description, String extra, List<String> required) {
    try {
      SchemaDefinition schema =
          Json.MAPPER.readValue(
              "{\"type\":\"object\",\"properties\":{"
                  + COMMON
                  + extra
                  + "},\"required\":"
                  + Json.MAPPER.writeValueAsString(required)
                  + ",\"additionalProperties\":false}",
              SchemaDefinition.class);
      int seconds =
          (int) ((configuration.requestTimeout().toNanos() + 999_999_999L) / 1_000_000_000L);
      return new RelayTools.Declaration(
          configuration.prefix() + suffix, description, schema, seconds);
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid built-in action schema", invalid);
    }
  }

  @Override
  public List<RelayTools.Declaration> declarations() {
    return declarations;
  }

  @Override
  public RelayTools.Result execute(RelayTools.Call call, OperationBudget budget)
      throws IOException {
    Objects.requireNonNull(budget).remaining();
    if (!configuration.binding().project().equals(call.project())
        || !configuration.binding().provider().equals(call.provider())
        || !configuration.binding().account().equals(call.account()))
      return rejected("Foreign action routing binding.");
    RelayTools.Declaration declaration =
        declarations.stream()
            .filter(item -> item.name().equals(call.tool()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown HA control tool"));
    if (!"plowshare-tool/2".equals(call.schema())
        || !SchemaCodec.matchesFingerprint(declaration.schema(), call.schemaFingerprint()))
      return rejected("The offered control tool schema is unavailable.");
    final Input input;
    try {
      declaration.validate(call.arguments());
      input = decode(call);
    } catch (IllegalArgumentException invalid) {
      return rejected("HA control input is invalid.");
    }
    ConfiguredAction action =
        discovery.actions(input.reference(), input.revision(), budget).stream()
            .filter(
                value ->
                    value instanceof ConfiguredAction configured
                        && configured.alias().equals(input.action()))
            .map(value -> (ConfiguredAction) value)
            .findFirst()
            .orElse(null);
    if (action == null
        || !action.revision().equals(input.revision())
        || action.profile().targets().isEmpty())
      return rejected("HA action profile is unavailable.");
    long generation = policy.admit(action);
    offers.requireCurrent(input.reference(), action, budget);
    String fingerprint = HomeAssistantActionProfiles.fingerprint(action.profile());
    if (input instanceof Describe) {
      return completed(
          new Offer(
              action.alias(),
              action.profile().service(),
              action.profile().targets(),
              fingerprint,
              HomeAssistantActionProfiles.parameters(action.profile())));
    }
    Execute execute = (Execute) input;
    if (!execute.fingerprint().equals(fingerprint)
        || !execute.service().equals(action.profile().service())
        || !execute.targets().equals(action.profile().targets()))
      return rejected("The exact offered service, profile and full target bundle are required.");
    final ActionExecutor.Request request;
    try {
      request = new ActionExecutor.Request(call.invocationId(), action, execute.parameters());
    } catch (IllegalArgumentException invalid) {
      return rejected("HA action parameters violate the configured schema.");
    }
    ActionExecutor.Receipt receipt =
        executor.execute(
            request,
            () -> {
              budget.remaining();
              policy.require(action, generation);
              offers.requireCurrent(input.reference(), action, budget);
              // Source/catalogue sampling may race an operator revocation; finish the local
              // admission
              // with the same control generation rather than letting an earlier check authorize it.
              policy.require(action, generation);
            },
            budget);
    if (!receipt.invocationId().equals(call.invocationId())
        || !receipt.binding().equals(binding)
        || !receipt.action().equals(action.alias())
        || !receipt.profileFingerprint().equals(fingerprint)
        || !receipt.targets().equals(action.profile().targets()))
      throw new IOException("Foreign action executor receipt; outcome requires inspection");
    String text = Json.MAPPER.writeValueAsString(receipt);
    // A refusal may follow partial work inside a HA service, so retain uncertainty, too.
    RelayTools.State state =
        receipt.delivery() == Delivery.UNKNOWN || receipt.delivery() == Delivery.REFUSED
            ? RelayTools.State.UNKNOWN
            : receipt.delivery() == Delivery.NOT_SUBMITTED
                ? RelayTools.State.REJECTED
                : RelayTools.State.COMPLETED;
    return new RelayTools.Result(state, text);
  }

  private sealed interface Input permits Describe, Execute {
    Reference reference();

    Revision revision();

    String action();
  }

  private record Describe(Reference reference, Revision revision, String action) implements Input {}

  private record Execute(
      Reference reference,
      Revision revision,
      String action,
      String fingerprint,
      String service,
      List<String> targets,
      Map<String, Parameter> parameters)
      implements Input {}

  private record Offer(
      String action,
      String service,
      List<String> targets,
      String profileFingerprint,
      SchemaDefinition parameters) {}

  private Input decode(RelayTools.Call call) {
    Map<String, ToolValue> args = call.arguments();
    Map<String, ToolValue> source = object(args.get("revision"));
    Binding supplied = new Binding(text(source.get("binding")), text(source.get("credential")));
    if (!binding.equals(supplied)) throw new IllegalArgumentException("Foreign credential binding");
    Revision revision =
        new Revision(
            binding,
            text(source.get("policy")),
            text(source.get("epoch")),
            counter(source.get("metadata")),
            counter(source.get("state")));
    Reference reference = new Reference(binding, text(args.get("alias")));
    String action = text(args.get("action"));
    if (call.tool().equals(configuration.prefix() + "describe_action"))
      return new Describe(reference, revision, action);
    String fingerprint = text(args.get("profileFingerprint"));
    if (!fingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid profile fingerprint");
    if (!(args.get("targets") instanceof ToolValue.Array array))
      throw new IllegalArgumentException("Targets required");
    Map<String, Parameter> parameters = new LinkedHashMap<>();
    object(args.get("parameters"))
        .forEach(
            (name, value) -> {
              if (!(value instanceof Parameter parameter))
                throw new IllegalArgumentException("Only primitive parameters supported");
              parameters.put(name, parameter);
            });
    return new Execute(
        reference,
        revision,
        action,
        fingerprint,
        text(args.get("service")),
        array.values().stream().map(HomeAssistantActionTools::text).toList(),
        Map.copyOf(parameters));
  }

  private static long counter(ToolValue value) {
    String text = text(value);
    if (!text.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid revision");
    return Long.parseLong(text);
  }

  private static String text(ToolValue value) {
    if (value instanceof TextParameter text) return text.value();
    throw new IllegalArgumentException("Text required");
  }

  private static Map<String, ToolValue> object(ToolValue value) {
    if (value instanceof ToolValue.ObjectValue object) return object.values();
    throw new IllegalArgumentException("Object required");
  }

  private static RelayTools.Result rejected(String text) {
    return new RelayTools.Result(RelayTools.State.REJECTED, text);
  }

  private static RelayTools.Result completed(Offer offer) throws IOException {
    String text = Json.MAPPER.writeValueAsString(offer);
    return text.length() > 15000
        ? rejected(
            "Configured action profile exceeds the tool response bound; reduce the configured profile.")
        : new RelayTools.Result(RelayTools.State.COMPLETED, text);
  }
}
