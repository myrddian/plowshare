package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReading;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldRole;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldSupport;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.IdentityKind;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Query;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ReadResult;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Resolution;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResolutionStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResourceSummary;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ValueType;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceCapabilities.SchemaChangedException;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.ToolValue;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HomeAssistantCapabilityToolsTest {
  private static final String PREFIX = "ha_read_";
  private static final String REVISION = "a".repeat(64);
  private static final String RESOURCE = "r_" + "b".repeat(64);
  private static final Instant OBSERVED = Instant.parse("2026-11-24T00:00:00Z");
  private static final FieldDescription CURRENT =
      new FieldDescription(
          "attribute.current_temperature",
          "Current temperature",
          ValueType.NUMBER,
          Optional.of("°C"),
          "temperature",
          FieldRole.CURRENT,
          FieldSupport.SUPPORTED);

  private static ResourceSummary resource(List<FieldDescription> fields) {
    return new ResourceSummary(
        RESOURCE,
        "climate.office",
        "Office",
        List.of("Office AC"),
        Optional.of("Office air conditioner"),
        Optional.of("Office"),
        Availability.AVAILABLE,
        IdentityKind.REGISTRY,
        fields);
  }

  private static final class Capabilities implements HomeAssistantResourceCapabilities {
    Query query;
    FieldReadRequest read;
    OperationBudget budget;
    int calls;
    boolean unavailable;
    boolean schemaChanged;
    boolean oversized;

    @Override
    public Resolution resolve(Query query, OperationBudget budget) throws IOException {
      this.query = query;
      capture(budget);
      // The capability owner, not the SDK codec, computes whole-result ambiguity. Preserve it
      // even when the returned page has a single candidate.
      return new Resolution(
          ResolutionStatus.AMBIGUOUS, REVISION, OBSERVED, List.of(resource(List.of(CURRENT))), 1);
    }

    @Override
    public Description describe(
        String resourceId, String revision, int offset, int limit, OperationBudget budget)
        throws IOException {
      capture(budget);
      assertEquals(RESOURCE, resourceId);
      assertEquals(REVISION, revision);
      return new Description(
          REVISION, OBSERVED, resource(List.of(CURRENT)), List.of(CURRENT), null);
    }

    @Override
    public ReadResult read(FieldReadRequest request, OperationBudget budget) throws IOException {
      this.read = request;
      capture(budget);
      if (oversized) {
        var first =
            new FieldDescription(
                "state",
                "State",
                ValueType.TEXT,
                Optional.empty(),
                "unknown",
                FieldRole.UNKNOWN,
                FieldSupport.SUPPORTED);
        var second =
            new FieldDescription(
                "attribute.note",
                "Note",
                ValueType.TEXT,
                Optional.empty(),
                "unknown",
                FieldRole.UNKNOWN,
                FieldSupport.SUPPORTED);
        return new ReadResult(
            REVISION,
            OBSERVED,
            List.of(
                new FieldReading(
                    RESOURCE,
                    "climate.office",
                    first,
                    FieldStatus.AVAILABLE,
                    Optional.of(new TextParameter("\n".repeat(4096))),
                    Optional.empty(),
                    OBSERVED,
                    false),
                new FieldReading(
                    RESOURCE,
                    "climate.office",
                    second,
                    FieldStatus.AVAILABLE,
                    Optional.of(new TextParameter("\n".repeat(4096))),
                    Optional.empty(),
                    OBSERVED,
                    false)));
      }
      return new ReadResult(
          REVISION,
          OBSERVED,
          List.of(
              new FieldReading(
                  RESOURCE,
                  "climate.office",
                  CURRENT,
                  FieldStatus.AVAILABLE,
                  Optional.of(new NumberParameter(new BigDecimal("19.4"))),
                  Optional.of(OBSERVED.minusSeconds(5)),
                  OBSERVED,
                  false)));
    }

    private void capture(OperationBudget budget) throws IOException {
      calls++;
      this.budget = budget;
      if (schemaChanged) throw new SchemaChangedException();
      if (unavailable) throw new IOException("private-token private-origin private-journal");
    }

    @Override
    public void close() {}
  }

  private static HomeAssistantCapabilityTools tools(Capabilities source) {
    return new HomeAssistantCapabilityTools(PREFIX, source, Duration.ofSeconds(2));
  }

  private static RelayTools.Call call(
      HomeAssistantToolHandlers tools, String suffix, Map<String, ToolValue> arguments) {
    var declaration =
        tools.declarations().stream()
            .filter(value -> value.name().equals(PREFIX + suffix))
            .findFirst()
            .orElseThrow();
    return new RelayTools.Call(
        "plowshare-tool/2",
        "11111111-1111-1111-1111-111111111111",
        "fixture-project",
        "fixture-provider",
        declaration.name(),
        "fixture-caller",
        "fixture-job",
        "fixture-call",
        Instant.now().plusSeconds(60).toString(),
        arguments,
        SchemaCodec.fingerprint(declaration.schema()));
  }

  private static OperationBudget budget() {
    return OperationBudget.start(Duration.ofSeconds(2));
  }

  private static Map<String, ToolValue> selection(String... fields) {
    return Map.of(
        "revision",
        new TextParameter(REVISION),
        "selections",
        new ToolValue.Array(
            List.of(
                new ToolValue.ObjectValue(
                    Map.of(
                        "resourceId",
                        new TextParameter(RESOURCE),
                        "fields",
                        new ToolValue.Array(
                            Arrays.stream(fields).<ToolValue>map(TextParameter::new).toList()))))));
  }

  @Test
  void typedResolutionPreservesWholeSetAmbiguityAndFieldMeaning() throws Exception {
    var source = new Capabilities();
    var tools = tools(source);
    var budget = budget();
    var result =
        tools.execute(
            call(
                tools,
                "resolve",
                Map.of(
                    "text",
                    new TextParameter("office temperature"),
                    "role",
                    new TextParameter("CURRENT"),
                    "capability",
                    new TextParameter("temperature"),
                    "limit",
                    new NumberParameter(BigDecimal.ONE))),
            budget);
    assertEquals(RelayTools.State.COMPLETED, result.state());
    var json = Json.parse(result.text());
    assertEquals("AMBIGUOUS", json.path("status").textValue());
    assertEquals(1, json.path("candidates").size());
    assertEquals(
        "CURRENT",
        json.path("candidates").get(0).path("matchedFields").get(0).path("role").textValue());
    assertFalse(json.toString().contains("19.4"));
    assertEquals("office temperature", source.query.text());
    assertEquals(Optional.of(FieldRole.CURRENT), source.query.role());
    assertSame(budget, source.budget);
  }

  @Test
  void fieldReadRetainsPrimitiveValueUnitRoleAndSourceTimes() throws Exception {
    var source = new Capabilities();
    var tools = tools(source);
    var result = tools.execute(call(tools, "read_fields", selection(CURRENT.id())), budget());
    assertEquals(RelayTools.State.COMPLETED, result.state());
    var row = Json.parse(result.text()).path("readings").get(0);
    assertEquals(new BigDecimal("19.4"), row.path("value").decimalValue());
    assertEquals("CURRENT", row.path("field").path("role").textValue());
    assertEquals("°C", row.path("field").path("unit").textValue());
    assertEquals("climate.office", row.path("entityId").textValue());
    assertEquals(OBSERVED.minusSeconds(5).toString(), row.path("sourceUpdatedAt").textValue());
    assertEquals(REVISION, source.read.revision());
    assertEquals(List.of(CURRENT.id()), source.read.selections().getFirst().fields());
  }

  @Test
  void malformedNestedObjectsReferencesAndDuplicatesRejectBeforeResourceWork() throws Exception {
    var source = new Capabilities();
    var tools = tools(source);
    var extra = new LinkedHashMap<>(selection(CURRENT.id()));
    extra.put("policy", new TextParameter("caller-policy"));
    for (var invalid : List.of(extra, selection(CURRENT.id(), CURRENT.id()), selection("anything")))
      assertEquals(
          RelayTools.State.REJECTED,
          tools.execute(call(tools, "read_fields", invalid), budget()).state());
    assertEquals(
        RelayTools.State.REJECTED,
        tools
            .execute(
                call(
                    tools,
                    "read_fields",
                    Map.of(
                        "revision",
                        new TextParameter(REVISION),
                        "selections",
                        new TextParameter("[]"))),
                budget())
            .state());
    assertEquals(
        RelayTools.State.REJECTED,
        tools
            .execute(
                call(tools, "resolve", Map.of("offset", new NumberParameter(BigDecimal.ONE))),
                budget())
            .state());
    assertEquals(
        RelayTools.State.REJECTED,
        tools
            .execute(
                call(tools, "resolve", Map.of("domain", new TextParameter(" climate"))), budget())
            .state());
    assertEquals(
        RelayTools.State.REJECTED,
        tools
            .execute(
                call(
                    tools,
                    "describe_fields",
                    Map.of(
                        "resourceId",
                        new TextParameter("climate.office"),
                        "revision",
                        new TextParameter(REVISION))),
                budget())
            .state());
    assertEquals(
        RelayTools.State.REJECTED,
        tools
            .execute(
                call(tools, "resolve", Map.of("revision", new TextParameter("g".repeat(64)))),
                budget())
            .state());
    assertEquals(
        RelayTools.State.REJECTED,
        tools
            .execute(
                call(
                    tools,
                    "describe_fields",
                    Map.of(
                        "resourceId",
                        new TextParameter("r_" + "g".repeat(64)),
                        "revision",
                        new TextParameter(REVISION))),
                budget())
            .state());
    assertEquals(0, source.calls);
  }

  @Test
  void totalFieldLimitIsEnforcedAcrossResourceSelectionsBeforeIo() throws Exception {
    var source = new Capabilities();
    var tools = tools(source);
    var first =
        new ToolValue.ObjectValue(
            Map.of(
                "resourceId",
                new TextParameter(RESOURCE),
                "fields",
                new ToolValue.Array(
                    IntStream.range(0, 20)
                        .<ToolValue>mapToObj(index -> new TextParameter("attribute.field_" + index))
                        .toList())));
    var second =
        new ToolValue.ObjectValue(
            Map.of(
                "resourceId",
                new TextParameter("r_" + "c".repeat(64)),
                "fields",
                new ToolValue.Array(List.of(new TextParameter("state")))));
    assertEquals(
        RelayTools.State.REJECTED,
        tools
            .execute(
                call(
                    tools,
                    "read_fields",
                    Map.of(
                        "revision",
                        new TextParameter(REVISION),
                        "selections",
                        new ToolValue.Array(List.of(first, second)))),
                budget())
            .state());
    assertEquals(0, source.calls);
  }

  @Test
  void schemaFingerprintAndExpiredOwnerPreventResourceReads() throws Exception {
    var source = new Capabilities();
    var tools = tools(source);
    var normal = call(tools, "read_fields", selection(CURRENT.id()));
    var wrong =
        new RelayTools.Call(
            normal.schema(),
            normal.invocationId(),
            normal.project(),
            normal.provider(),
            normal.tool(),
            normal.account(),
            normal.run(),
            normal.call(),
            normal.deadline(),
            normal.arguments(),
            "f".repeat(64));
    assertEquals(RelayTools.State.REJECTED, tools.execute(wrong, budget()).state());
    var expired = OperationBudget.start(Duration.ofNanos(1));
    assertThrows(IOException.class, () -> tools.execute(normal, expired));
    assertEquals(0, source.calls);
  }

  @Test
  void observationFailuresAndOversizedResultsAreExplicitAndSanitized() throws Exception {
    var source = new Capabilities();
    var tools = tools(source);
    source.unavailable = true;
    var unavailable = tools.execute(call(tools, "read_fields", selection(CURRENT.id())), budget());
    assertEquals(RelayTools.State.REJECTED, unavailable.state());
    assertFalse(unavailable.text().contains("private-"));
    source.unavailable = false;
    source.oversized = true;
    var oversized =
        tools.execute(call(tools, "read_fields", selection("state", "attribute.note")), budget());
    assertEquals(RelayTools.State.REJECTED, oversized.state());
    assertTrue(oversized.text().contains("narrow"));
  }

  @Test
  void verifiedSchemaChangeAsksForDiscoveryWithoutClaimingACauseOrRepeatingTheRead()
      throws Exception {
    var source = new Capabilities();
    var tools = tools(source);
    source.schemaChanged = true;
    var changed = tools.execute(call(tools, "read_fields", selection(CURRENT.id())), budget());
    assertEquals(RelayTools.State.REJECTED, changed.state());
    assertTrue(changed.text().contains("schema changed"));
    assertTrue(changed.text().contains("resolve fresh"));
    assertEquals(1, source.calls);
    assertTrue(source.query == null, "The tool must not automatically resolve or repeat work");
    source.schemaChanged = false;
    source.unavailable = true;
    var unknown = tools.execute(call(tools, "read_fields", selection(CURRENT.id())), budget());
    assertFalse(unknown.text().contains("schema changed"));
    assertFalse(unknown.text().contains("private-"));
    assertEquals(2, source.calls);
  }

  @Test
  void capabilityModePublishesOnlyHealthAndNewReadToolsAndRejectsLegacyDataBypass()
      throws Exception {
    var legacyResources = new HomeAssistantReadToolsTest.Resources();
    var legacy =
        new HomeAssistantReadTools(
            HomeAssistantReadToolsTest.SOURCE, PREFIX, legacyResources, legacyResources);
    var source = new Capabilities();
    var tools = tools(source).withHealth(legacy);
    assertEquals(
        List.of(
            "ha_read_health", "ha_read_resolve", "ha_read_describe_fields", "ha_read_read_fields"),
        tools.declarations().stream().map(RelayTools.Declaration::name).toList());
    assertEquals(
        legacy.declarations().stream()
            .filter(value -> value.name().endsWith("health"))
            .findFirst()
            .orElseThrow(),
        tools.declarations().getFirst());
    for (String suffix : List.of("search", "describe", "read", "list_actions", "diagnose"))
      assertEquals(
          RelayTools.State.REJECTED,
          tools.execute(call(legacy, suffix, Map.of()), budget()).state());
    assertEquals(0, source.calls);
    assertEquals(0, legacyResources.reads);
    assertEquals(0, legacyResources.searches);
    assertEquals(
        RelayTools.State.COMPLETED,
        tools.execute(call(tools, "health", Map.of()), budget()).state());
  }
}
