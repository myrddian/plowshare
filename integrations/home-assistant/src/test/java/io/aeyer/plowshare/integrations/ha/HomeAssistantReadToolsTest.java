package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ActionDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Page;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadBatch;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Registries;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.RegistryAvailability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.State;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.ToolSchemas;
import io.aeyer.plowshare.protocol.ToolValue;
import io.aeyer.plowshare.sdk.RelayTools;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HomeAssistantReadToolsTest {
  static final Binding SOURCE = new Binding("fixture-house", "credential-generation-a");
  static final Revision REVISION = new Revision(SOURCE, "policy-a", "epoch-a", 4, 8);
  static final String PREFIX = "house_";
  static final String INVOCATION = "11111111-1111-1111-1111-111111111111";

  static final class Resources implements ResourceDiscovery, ResourceReader {
    int searches;
    int reads;
    Search lastSearch;
    ReadRequest lastRead;
    OperationBudget lastBudget;
    boolean oversized;

    @Override
    public Page search(Search search, OperationBudget budget) {
      searches++;
      lastSearch = search;
      lastBudget = budget;
      return new Page(REVISION, List.of(description("first")), Optional.empty());
    }

    @Override
    public Optional<Description> describe(
        Reference reference, Revision revision, OperationBudget budget) {
      return reference.alias().equals("first")
          ? Optional.of(description("first"))
          : Optional.empty();
    }

    @Override
    public List<ActionDescription> actions(
        Reference reference, Revision revision, OperationBudget budget) {
      return List.of(
          new ConfiguredAction(
              "lighting",
              REVISION,
              new HomeAssistantSettings.Action(
                  "light.turn_on", List.of("light.first"), Map.of(), List.of(), false)));
    }

    @Override
    public ReadBatch read(ReadRequest request, OperationBudget budget) {
      reads++;
      lastRead = request;
      lastBudget = budget;
      return new ReadBatch(
          REVISION,
          request.references().stream()
              .map(
                  reference ->
                      new State(
                          reference,
                          Availability.AVAILABLE,
                          Optional.of(oversized ? "\n".repeat(4096) : "21"),
                          Map.of(
                              "unit",
                              new TextParameter("C"),
                              "already_permitted_other",
                              new TextParameter("selected-by-host")),
                          Set.of("nested"),
                          Optional.empty(),
                          Instant.EPOCH,
                          false))
              .toList());
    }
  }

  static Description description(String alias) {
    return new Description(
        new Reference(SOURCE, alias),
        "sensor." + alias,
        alias,
        Optional.empty(),
        Optional.empty(),
        Set.of("unit", "nested"),
        new Registries(
            RegistryAvailability.AVAILABLE,
            RegistryAvailability.DENIED,
            RegistryAvailability.DENIED,
            RegistryAvailability.AVAILABLE));
  }

  static Map<String, ToolValue> revision() {
    return Map.of(
        "binding",
        new TextParameter(SOURCE.binding()),
        "credential",
        new TextParameter(SOURCE.credentialContext()),
        "policy",
        new TextParameter(REVISION.policy()),
        "epoch",
        new TextParameter(REVISION.epoch()),
        "metadata",
        new TextParameter("4"),
        "state",
        new TextParameter("8"));
  }

  static RelayTools.Call call(
      HomeAssistantReadTools tools, String suffix, Map<String, ToolValue> arguments) {
    var declaration =
        tools.declarations().stream()
            .filter(value -> value.name().equals(PREFIX + suffix))
            .findFirst()
            .orElseThrow();
    return new RelayTools.Call(
        "plowshare-tool/2",
        INVOCATION,
        "fixture-project",
        "fixture-provider",
        declaration.name(),
        "fixture-caller",
        "job-fixture",
        "call-fixture",
        Instant.now().plusSeconds(60).toString(),
        arguments,
        SchemaCodec.fingerprint(declaration.schema()));
  }

  static OperationBudget budget() {
    return OperationBudget.start(Duration.ofSeconds(2));
  }

  @Test
  void offeredRevisionsAreInlineObjectsAndEncodedStringsRemainInvalid() throws Exception {
    var resources = new Resources();
    var tools = new HomeAssistantReadTools(SOURCE, PREFIX, resources, resources);
    var revision = new ToolValue.ObjectValue(revision());
    for (var declaration : tools.declarations()) {
      var schema = Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(declaration.schema()));
      assertFalse(schema.has("$defs"));
      assertFalse(schema.toString().contains("\"$ref\""));
      var arguments = new LinkedHashMap<String, ToolValue>();
      if (declaration.name().equals(PREFIX + "search")) {
        assertEquals(
            "object",
            schema
                .path("properties")
                .path("cursor")
                .path("properties")
                .path("revision")
                .path("type")
                .textValue());
        arguments.put(
            "cursor",
            new ToolValue.ObjectValue(
                Map.of("revision", revision, "offset", new NumberParameter(BigDecimal.ZERO))));
        ToolSchemas.validate(declaration.schema(), new ToolValue.ObjectValue(arguments));
        arguments.put(
            "cursor",
            new ToolValue.ObjectValue(
                Map.of(
                    "revision", new TextParameter(Json.MAPPER.writeValueAsString(revision)),
                    "offset", new NumberParameter(BigDecimal.ZERO))));
      } else {
        assertEquals("object", schema.path("properties").path("revision").path("type").textValue());
        if (declaration.name().equals(PREFIX + "read")) {
          arguments.put("aliases", new ToolValue.Array(List.of(new TextParameter("first"))));
        } else {
          arguments.put("alias", new TextParameter("first"));
        }
        arguments.put("revision", revision);
        ToolSchemas.validate(declaration.schema(), new ToolValue.ObjectValue(arguments));
        arguments.put("revision", new TextParameter(Json.MAPPER.writeValueAsString(revision)));
      }
      assertThrows(
          IllegalArgumentException.class,
          () -> ToolSchemas.validate(declaration.schema(), new ToolValue.ObjectValue(arguments)));
    }
    assertEquals(0, resources.reads);
    assertEquals(0, resources.searches);
  }

  @Test
  void schemasArePrefixedClosedAndNeverEnableExecution() throws Exception {
    var source = new Resources();
    var tools = new HomeAssistantReadTools(SOURCE, PREFIX, source, source);
    assertEquals(
        List.of("house_search", "house_describe", "house_read", "house_list_actions"),
        tools.declarations().stream().map(RelayTools.Declaration::name).toList());
    assertThrows(
        IllegalArgumentException.class,
        () -> new HomeAssistantReadTools(SOURCE, "house__", source, source));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tools.execute(
                call(tools, "search", Map.of("target", new TextParameter("private"))), budget()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tools.execute(
                call(tools, "search", Map.of("limit", new TextParameter("2"))), budget()));
    assertThrows(
        IllegalArgumentException.class,
        () -> tools.execute(call(tools, "search", Map.of("area", new ToolValue.Null())), budget()));
    assertEquals(0, source.searches);
    var result =
        tools.execute(
            call(
                tools,
                "search",
                Map.of(
                    "text",
                    new TextParameter("temperature"),
                    "limit",
                    new NumberParameter(BigDecimal.valueOf(2)))),
            budget());
    assertEquals(RelayTools.State.COMPLETED, result.state());
    assertEquals("temperature", source.lastSearch.text());
    assertEquals(2, source.lastSearch.limit());
    var json = Json.MAPPER.readTree(result.text());
    assertEquals(SOURCE.credentialContext(), json.path("revision").path("credential").textValue());
    assertEquals("4", json.path("revision").path("metadata").textValue());
    assertEquals("first", json.path("resources").get(0).path("alias").textValue());
  }

  @Test
  void foreignCredentialRevisionAndNonCanonicalCountersRefuseBeforeResourceReads()
      throws Exception {
    var source = new Resources();
    var tools = new HomeAssistantReadTools(SOURCE, PREFIX, source, source);
    var foreign = new LinkedHashMap<>(revision());
    foreign.put("credential", new TextParameter("different-credential"));
    var rejected =
        tools.execute(
            call(
                tools,
                "read",
                Map.of(
                    "aliases",
                    new ToolValue.Array(List.of(new TextParameter("first"))),
                    "revision",
                    new ToolValue.ObjectValue(foreign))),
            budget());
    assertEquals(RelayTools.State.REJECTED, rejected.state());
    assertEquals(0, source.reads);
    foreign.put("credential", new TextParameter(SOURCE.credentialContext()));
    foreign.put("metadata", new TextParameter("04"));
    assertEquals(
        RelayTools.State.REJECTED,
        tools
            .execute(
                call(
                    tools,
                    "describe",
                    Map.of(
                        "alias",
                        new TextParameter("first"),
                        "revision",
                        new ToolValue.ObjectValue(foreign))),
                budget())
            .state());
  }

  @Test
  void freshBatchHasOneReadAndProjectsOnlyExplicitAlreadyPermittedAttributes() throws Exception {
    var source = new Resources();
    var tools = new HomeAssistantReadTools(SOURCE, PREFIX, source, source);
    var result =
        tools.execute(
            call(
                tools,
                "read",
                Map.of(
                    "aliases",
                    new ToolValue.Array(
                        List.of(new TextParameter("first"), new TextParameter("second"))),
                    "revision",
                    new ToolValue.ObjectValue(revision()),
                    "attributes",
                    new ToolValue.Array(
                        List.of(new TextParameter("unit"), new TextParameter("nested"))))),
            budget());
    assertEquals(1, source.reads);
    assertEquals(
        List.of("first", "second"),
        source.lastRead.references().stream().map(Reference::alias).toList());
    var states = Json.MAPPER.readTree(result.text()).path("states");
    assertEquals("C", states.get(0).path("attributes").path("unit").textValue());
    assertFalse(result.text().contains("selected-by-host"));
    assertEquals("nested", states.get(0).path("unsupportedAttributes").get(0).textValue());
  }

  @Test
  void oversizedObservationsAreRefusedWithoutTruncationOrAnotherRead() throws Exception {
    var source = new Resources();
    source.oversized = true;
    var tools = new HomeAssistantReadTools(SOURCE, PREFIX, source, source);
    var result =
        tools.execute(
            call(
                tools,
                "read",
                Map.of(
                    "aliases",
                    new ToolValue.Array(
                        List.of(
                            new TextParameter("first"),
                            new TextParameter("second"),
                            new TextParameter("third"))),
                    "revision",
                    new ToolValue.ObjectValue(revision()))),
            budget());
    assertEquals(RelayTools.State.REJECTED, result.state());
    assertTrue(result.text().contains("narrow"));
    assertEquals(1, source.reads);
  }

  @Test
  void descriptionAndActionOffersAreEvidenceOnly() throws Exception {
    var source = new Resources();
    var tools = new HomeAssistantReadTools(SOURCE, PREFIX, source, source);
    var result =
        tools.execute(
            call(
                tools,
                "describe",
                Map.of(
                    "alias",
                    new TextParameter("first"),
                    "revision",
                    new ToolValue.ObjectValue(revision()))),
            budget());
    assertEquals(2, Json.MAPPER.readTree(result.text()).path("attributes").size());
    var missing =
        tools.execute(
            call(
                tools,
                "describe",
                Map.of(
                    "alias",
                    new TextParameter("absent-or-denied"),
                    "revision",
                    new ToolValue.ObjectValue(revision()))),
            budget());
    assertTrue(Json.MAPPER.readTree(missing.text()).path("resource").isNull());
    var actions =
        tools.execute(
            call(
                tools,
                "list_actions",
                Map.of(
                    "alias",
                    new TextParameter("first"),
                    "revision",
                    new ToolValue.ObjectValue(revision()))),
            budget());
    assertEquals(
        "light.turn_on",
        Json.MAPPER.readTree(actions.text()).path("actions").get(0).path("service").textValue());
    assertEquals(0, source.reads);
  }
}
