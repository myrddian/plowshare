package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Delivery;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Effect;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.NativeRequest;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Receipt;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ActionDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Page;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import io.aeyer.plowshare.protocol.IntegrationPayload.ActionContext;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.ToolValue;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HomeAssistantActionToolsTest {
  static final Binding BINDING = new Binding("fixture-house", "credential-a");
  static final Revision REVISION = new Revision(BINDING, "policy-a", "epoch-a", 2, 3);
  static final HomeAssistantSettings.Action PROFILE =
      new HomeAssistantSettings.Action(
          "light.turn_on",
          List.of("light.first", "light.second"),
          Map.of(
              "brightness",
              new HomeAssistantSettings.ParameterPolicy(
                  "integer", BigDecimal.ZERO, new BigDecimal("255"), null, null),
              "mode",
              new HomeAssistantSettings.ParameterPolicy(
                  "string",
                  null,
                  null,
                  10,
                  List.of(new TextParameter("normal"), new TextParameter("quiet")))),
          List.of("brightness"),
          false);
  static final ConfiguredAction ACTION = new ConfiguredAction("lighting", REVISION, PROFILE);
  static final HomeAssistantToolProvider.Configuration READ =
      new HomeAssistantToolProvider.Configuration(
          new RelayTools.Binding("fixture-project", "fixture-read", "fixture-account"),
          "house_read_",
          30);
  static final HomeAssistantToolProvider.Configuration CONTROL =
      new HomeAssistantToolProvider.Configuration(
          new RelayTools.Binding("fixture-project", "fixture-control", "fixture-account"),
          "house_control_",
          30);

  static final class Discovery implements ResourceDiscovery, ActionOfferValidator {
    ConfiguredAction offered = ACTION;
    boolean stale;
    int guards;

    @Override
    public Page search(Search search, OperationBudget budget) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Description> describe(
        Reference reference, Revision revision, OperationBudget budget) {
      return Optional.empty();
    }

    @Override
    public List<ActionDescription> actions(
        Reference reference, Revision revision, OperationBudget budget) {
      return reference.binding().equals(BINDING) && reference.alias().equals("first")
          ? List.of(offered)
          : List.of();
    }

    @Override
    public void requireCurrent(
        Reference reference, ConfiguredAction offered, OperationBudget budget) throws IOException {
      budget.remaining();
      guards++;
      if (stale || !offered.equals(this.offered))
        throw new IOException("Fixture current offer revoked");
    }
  }

  static final class Executor implements ActionExecutor {
    int effects;
    Delivery delivery = Delivery.ACKNOWLEDGED;
    Runnable queued = () -> {};
    Request received;
    OperationBudget receivedBudget;

    @Override
    public Receipt execute(Request request, Guard guard, OperationBudget budget)
        throws IOException {
      received = request;
      receivedBudget = budget;
      queued.run();
      guard.require();
      effects++;
      return receipt(request, delivery);
    }
  }

  static Receipt receipt(ActionExecutor.Request request, Delivery delivery) {
    return new Receipt(
        request.invocationId(),
        request.action().revision().binding(),
        request.action().alias(),
        HomeAssistantActionProfiles.fingerprint(request.action().profile()),
        request.action().profile().targets(),
        delivery,
        delivery == Delivery.ACKNOWLEDGED
            ? Effect.NOT_OBSERVED
            : delivery == Delivery.NOT_SUBMITTED ? Effect.NOT_STARTED : Effect.UNKNOWN,
        delivery == Delivery.NOT_SUBMITTED
            ? Optional.empty()
            : Optional.of(new NativeRequest("epoch-a", 41)),
        delivery == Delivery.ACKNOWLEDGED
            ? Optional.of(new ActionContext("causal-id", "parent-id"))
            : Optional.empty(),
        Instant.now());
  }

  static HomeAssistantActionTools tools(
      Discovery discovery, HomeAssistantActionPolicy policy, ActionExecutor executor) {
    return new HomeAssistantActionTools(
        BINDING, CONTROL, READ, discovery, discovery, policy, executor);
  }

  static HomeAssistantActionPolicy policy() {
    return new HomeAssistantActionPolicy(BINDING, Map.of("lighting", PROFILE));
  }

  static OperationBudget budget() {
    return OperationBudget.start(Duration.ofSeconds(2));
  }

  static Map<String, ToolValue> input() {
    Map<String, ToolValue> revision = new LinkedHashMap<>();
    revision.put("binding", new TextParameter(BINDING.binding()));
    revision.put("credential", new TextParameter(BINDING.credentialContext()));
    revision.put("policy", new TextParameter(REVISION.policy()));
    revision.put("epoch", new TextParameter(REVISION.epoch()));
    revision.put("metadata", new TextParameter("2"));
    revision.put("state", new TextParameter("3"));
    Map<String, ToolValue> values = new LinkedHashMap<>();
    values.put("alias", new TextParameter("first"));
    values.put("action", new TextParameter("lighting"));
    values.put("revision", new ToolValue.ObjectValue(revision));
    values.put(
        "profileFingerprint", new TextParameter(HomeAssistantActionProfiles.fingerprint(PROFILE)));
    values.put("service", new TextParameter(PROFILE.service()));
    values.put(
        "targets",
        new ToolValue.Array(
            PROFILE.targets().stream().<ToolValue>map(TextParameter::new).toList()));
    values.put(
        "parameters",
        new ToolValue.ObjectValue(
            Map.of("brightness", new NumberParameter(new BigDecimal("100")))));
    return values;
  }

  static RelayTools.Call call(
      HomeAssistantActionTools tools, String suffix, Map<String, ToolValue> input) {
    var declaration =
        tools.declarations().stream()
            .filter(item -> item.name().endsWith(suffix))
            .findFirst()
            .orElseThrow();
    return new RelayTools.Call(
        "plowshare-tool/2",
        UUID.randomUUID().toString(),
        CONTROL.binding().project(),
        CONTROL.binding().provider(),
        declaration.name(),
        CONTROL.binding().account(),
        "fixture-run",
        "fixture-call",
        Instant.now().plusSeconds(30).toString(),
        input,
        SchemaCodec.fingerprint(declaration.schema()));
  }

  @Test
  void acknowledgementIsAReceiptWithUnobservedEffectAndPreservedCausality() throws Exception {
    var discovery = new Discovery();
    var executor = new Executor();
    var tools = tools(discovery, policy(), executor);
    var budget = budget();
    var call = call(tools, "execute_action", input());
    var result = tools.execute(call, budget);
    assertEquals(RelayTools.State.COMPLETED, result.state());
    assertEquals(1, executor.effects);
    var body = Json.parse(result.text());
    assertEquals("ACKNOWLEDGED", body.path("delivery").asText());
    assertEquals("NOT_OBSERVED", body.path("effect").asText());
    assertEquals(call.invocationId(), body.path("invocationId").asText());
    assertEquals(41, body.path("nativeRequest").path("id").asInt());
    assertEquals("causal-id", body.path("context").path("id").asText());
    assertTrue(executor.receivedBudget == budget);
    assertEquals(2, discovery.guards);
  }

  @Test
  void fullBundleCannotBeNarrowedBroadenedReorderedOrReplaced() throws Exception {
    var executor = new Executor();
    var tools = tools(new Discovery(), policy(), executor);
    for (List<String> targets :
        List.of(
            List.of("light.first"),
            List.of("light.first", "light.second", "light.third"),
            List.of("light.second", "light.first"),
            List.of("light.third", "light.second"))) {
      var input = input();
      input.put(
          "targets",
          new ToolValue.Array(targets.stream().<ToolValue>map(TextParameter::new).toList()));
      assertEquals(
          RelayTools.State.REJECTED,
          tools.execute(call(tools, "execute_action", input), budget()).state());
    }
    assertEquals(0, executor.effects);
  }

  @Test
  void invalidRequiredUnknownTypeBoundsEnumAndNestedValuesNeverAct() throws Exception {
    var executor = new Executor();
    var tools = tools(new Discovery(), policy(), executor);
    List<Map<String, ToolValue>> invalid =
        List.of(
            Map.of(),
            Map.of("unknown", new TextParameter("x")),
            Map.of("brightness", new TextParameter("100")),
            Map.of("brightness", new NumberParameter(new BigDecimal("256"))),
            Map.of("brightness", new NumberParameter(new BigDecimal("1.5"))),
            Map.of(
                "brightness",
                new NumberParameter(BigDecimal.ONE),
                "mode",
                new TextParameter("unsafe")),
            Map.of(
                "brightness", new ToolValue.ObjectValue(Map.of("nested", new TextParameter("x")))));
    for (var parameters : invalid) {
      var input = input();
      input.put("parameters", new ToolValue.ObjectValue(parameters));
      assertEquals(
          RelayTools.State.REJECTED,
          tools.execute(call(tools, "execute_action", input), budget()).state());
    }
    assertEquals(0, executor.effects);
  }

  @Test
  void staleFingerprintOrCredentialNeverActs() throws Exception {
    var executor = new Executor();
    var tools = tools(new Discovery(), policy(), executor);
    var input = input();
    input.put("profileFingerprint", new TextParameter("0".repeat(64)));
    assertEquals(
        RelayTools.State.REJECTED,
        tools.execute(call(tools, "execute_action", input), budget()).state());
    input = input();
    var revision = new LinkedHashMap<>(((ToolValue.ObjectValue) input.get("revision")).values());
    revision.put("credential", new TextParameter("credential-other"));
    input.put("revision", new ToolValue.ObjectValue(revision));
    assertEquals(
        RelayTools.State.REJECTED,
        tools.execute(call(tools, "execute_action", input), budget()).state());
    assertEquals(0, executor.effects);
    input = input();
    input.put("service", new TextParameter("light.turn_off"));
    assertEquals(
        RelayTools.State.REJECTED,
        tools.execute(call(tools, "execute_action", input), budget()).state());
    assertEquals(0, executor.effects);
  }

  @Test
  void queuedPolicyRevocationAndMetadataInvalidationPreventTheEffect() {
    for (boolean revokePolicy : List.of(true, false)) {
      var discovery = new Discovery();
      var policy = policy();
      var executor = new Executor();
      var tools = tools(discovery, policy, executor);
      executor.queued =
          () -> {
            if (revokePolicy) policy.disable();
            else discovery.stale = true;
          };
      assertThrows(
          IOException.class, () -> tools.execute(call(tools, "execute_action", input()), budget()));
      assertEquals(0, executor.effects);
    }
  }

  @Test
  void changedSchemaUnderSameAliasAndPolicyLabelCannotReinterpretAnOffer() {
    var discovery = new Discovery();
    var policy = policy();
    var executor = new Executor();
    var tools = tools(discovery, policy, executor);
    var changed =
        new HomeAssistantSettings.Action(
            "light.turn_off", PROFILE.targets(), PROFILE.parameters(), PROFILE.required(), false);
    assertFalse(
        HomeAssistantActionProfiles.fingerprint(changed)
            .equals(HomeAssistantActionProfiles.fingerprint(PROFILE)));
    executor.queued = () -> discovery.offered = new ConfiguredAction("lighting", REVISION, changed);
    assertThrows(
        IOException.class, () -> tools.execute(call(tools, "execute_action", input()), budget()));
    assertEquals(0, executor.effects);
  }

  @Test
  void unknownAndServiceRefusalNeverBecomeSuccessfulEffects() throws Exception {
    for (Delivery delivery : List.of(Delivery.UNKNOWN, Delivery.REFUSED)) {
      var executor = new Executor();
      executor.delivery = delivery;
      var tools = tools(new Discovery(), policy(), executor);
      var result = tools.execute(call(tools, "execute_action", input()), budget());
      assertEquals(RelayTools.State.UNKNOWN, result.state());
      assertEquals("UNKNOWN", Json.parse(result.text()).path("effect").asText());
      assertEquals(1, executor.effects);
    }
  }

  @Test
  void controlRequiresItsOwnProviderAndNonoverlappingPrefix() {
    var discovery = new Discovery();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HomeAssistantActionTools(
                BINDING, READ, READ, discovery, discovery, policy(), new Executor()));
    var overlap =
        new HomeAssistantToolProvider.Configuration(CONTROL.binding(), "house_read_control_", 30);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HomeAssistantActionTools(
                BINDING, overlap, READ, discovery, discovery, policy(), new Executor()));
    assertEquals(
        6,
        new HomeAssistantReadTools(
                BINDING,
                READ.prefix(),
                discovery,
                (request, budget) -> {
                  throw new IOException();
                })
            .declarations()
            .size());
  }

  @Test
  void describeExposesExactProfileAndAllTargetsWithoutExecuting() throws Exception {
    var executor = new Executor();
    var tools = tools(new Discovery(), policy(), executor);
    var input = input();
    input.keySet().retainAll(List.of("alias", "action", "revision"));
    var result = tools.execute(call(tools, "describe_action", input), budget());
    var body = Json.parse(result.text());
    assertEquals(RelayTools.State.COMPLETED, result.state());
    assertEquals(2, body.path("targets").size());
    assertEquals(
        HomeAssistantActionProfiles.fingerprint(PROFILE), body.path("profileFingerprint").asText());
    assertEquals(0, executor.effects);
    assertEquals(
        "integer",
        body.path("parameters").path("properties").path("brightness").path("type").asText());
  }

  @Test
  void targetlessProfilesAndForeignPoliciesAreRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HomeAssistantActionPolicy(
                BINDING,
                Map.of(
                    "global",
                    new HomeAssistantSettings.Action(
                        "light.turn_on", List.of(), Map.of(), List.of(), false))));
    var foreign =
        new HomeAssistantActionPolicy(
            new Binding("fixture-house", "credential-other"), Map.of("lighting", PROFILE));
    assertThrows(IOException.class, () -> foreign.admit(ACTION));
  }
}
