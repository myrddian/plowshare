package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldRole;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldSelection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.IdentityKind;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Query;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResolutionStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ValueType;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderConfiguration.Discovery;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HomeAssistantCapabilityCatalogTest {
  @TempDir Path directory;

  @BeforeEach
  void privatePath() throws IOException {
    directory = directory.toRealPath();
  }

  private static final String OWNER = Json.hash("fixture-owner");
  private static final String NAMESPACE = Json.hash("fixture-instance");
  private static final Instant OBSERVED = Instant.parse("2026-11-24T00:00:00Z");

  private static OperationBudget budget() {
    return OperationBudget.start(Duration.ofSeconds(2));
  }

  private static Query query(String text, String capability, FieldRole role) {
    return new Query(
        text,
        Optional.empty(),
        Optional.empty(),
        Optional.ofNullable(capability),
        Optional.ofNullable(role),
        0,
        20,
        Optional.empty());
  }

  static ObjectNode state(String id, String name, String value) {
    var state =
        Json.object()
            .put("entity_id", id)
            .put("state", value)
            .put("last_updated", "2026-11-23T23:59:00Z");
    state.putObject("attributes").put("friendly_name", name);
    return state;
  }

  static ObjectNode registry(String id, String key, String name) {
    return Json.object()
        .put("entity_id", id)
        .put("id", key)
        .put("name", name)
        .put("device_id", "device-fixture")
        .put("area_id", "office-fixture");
  }

  final class Fixture implements AutoCloseable {
    final Discovery policy;
    final HomeAssistantSettings settings;
    final HomeAssistantCapabilityIdentityStore store;
    final HomeAssistantResourceCapabilities catalog;
    ArrayNode states = Json.MAPPER.createArrayNode();
    ArrayNode entities = Json.MAPPER.createArrayNode();
    String epoch = "epoch-a";
    Set<String> exposed = Set.of("climate.office", "sensor.motion_temperature");
    String deviceName = "Climate device";
    String deviceNameByUser;
    String areaName = "Office";
    int collections;

    Fixture(
        String mode,
        String profile,
        Set<String> excluded,
        Set<String> excludedFields,
        List<String> attributes)
        throws IOException {
      policy =
          new Discovery(mode, profile, excluded, excludedFields, directory.resolve("identity"));
      settings =
          new HomeAssistantSettings(
              "https://ha.example.invalid",
              "HA_TOKEN",
              Map.of(
                  "office",
                  new HomeAssistantSettings.Entity("climate.office", attributes),
                  "motion",
                  new HomeAssistantSettings.Entity("sensor.motion_temperature", List.of())),
              Map.of(),
              List.of());
      store = new HomeAssistantCapabilityIdentityStore(policy.identityJournal(), OWNER, NAMESPACE);
      var office = state("climate.office", "Office", "off");
      ((ObjectNode) office.path("attributes"))
          .put("current_temperature", 19.4)
          .put("temperature", 20)
          .put("current_humidity", 57.4)
          .put("humidity", 50);
      var motion = state("sensor.motion_temperature", "Office", "unavailable");
      ((ObjectNode) motion.path("attributes"))
          .put("device_class", "temperature")
          .put("unit_of_measurement", "°C");
      states.add(office).add(motion);
      entities
          .add(registry("climate.office", "registry-office", "Office"))
          .add(registry("sensor.motion_temperature", "registry-motion", "Office"));
      catalog =
          new HomeAssistantCapabilityCatalog(
              new HomeAssistantCapabilitySource() {
                @Override
                public Snapshot snapshot(OperationBudget budget) throws IOException {
                  collections++;
                  var devices =
                      Json.MAPPER
                          .createArrayNode()
                          .add(
                              Json.object()
                                  .put("id", "device-fixture")
                                  .put("name", deviceName)
                                  .put("name_by_user", deviceNameByUser));
                  var areas =
                      Json.MAPPER
                          .createArrayNode()
                          .add(
                              Json.object().put("area_id", "office-fixture").put("name", areaName));
                  var config = Json.object();
                  config.putObject("unit_system").put("temperature", "°C");
                  return HomeAssistantCapabilityWire.snapshot(
                      epoch, policy, settings, store, states, entities, devices, areas, config,
                      exposed, OBSERVED, budget);
                }

                @Override
                public void close() throws IOException {
                  store.close();
                }
              });
    }

    @Override
    public void close() throws IOException {
      catalog.close();
    }
  }

  private Fixture fixture() throws IOException {
    return new Fixture("fixed", "standard_measurements", Set.of(), Set.of(), List.of());
  }

  @Test
  void quantityDiscoveryFindsAttributesAndDoesNotChooseAvailableSourceAmongDuplicateNames()
      throws Exception {
    try (var fixture = fixture()) {
      var result =
          fixture.catalog.resolve(
              query("office temperature", "temperature", FieldRole.CURRENT), budget());
      assertEquals(ResolutionStatus.AMBIGUOUS, result.status());
      assertEquals(2, result.candidates().size());
      assertEquals(1, fixture.collections);
      var climate =
          result.candidates().stream()
              .filter(value -> value.entityId().equals("climate.office"))
              .findFirst()
              .orElseThrow();
      assertEquals("attribute.current_temperature", climate.matchedFields().getFirst().id());
      assertEquals("Office", climate.area().orElseThrow());
      var read =
          fixture.catalog.read(
              new FieldReadRequest(
                  result.revision(),
                  List.of(
                      new FieldSelection(
                          climate.resourceId(), List.of("attribute.current_temperature")))),
              budget());
      assertEquals(2, fixture.collections);
      var reading = read.readings().getFirst();
      assertEquals(FieldRole.CURRENT, reading.field().role());
      assertEquals("°C", reading.field().unit().orElseThrow());
      assertEquals(new NumberParameter(new BigDecimal("19.4")), reading.value().orElseThrow());
      assertEquals(FieldStatus.AVAILABLE, reading.status());
      assertTrue(reading.sourceUpdatedAt().isPresent());
      assertFalse(reading.stale());
    }
  }

  @Test
  void currentAndTargetOnOneResourceAreAmbiguousBeforePagingAndRoleDisambiguates()
      throws Exception {
    try (var fixture = fixture()) {
      var query =
          new Query(
              "",
              Optional.of("climate"),
              Optional.of("Office"),
              Optional.of("temperature"),
              Optional.empty(),
              0,
              1,
              Optional.empty());
      var result = fixture.catalog.resolve(query, budget());
      assertEquals(ResolutionStatus.AMBIGUOUS, result.status());
      assertEquals(1, result.candidates().size());
      assertEquals(1, result.nextOffset());
      var current =
          fixture.catalog.resolve(
              new Query(
                  "",
                  Optional.of("climate"),
                  Optional.of("Office"),
                  Optional.of("temperature"),
                  Optional.of(FieldRole.CURRENT),
                  0,
                  20,
                  Optional.empty()),
              budget());
      assertEquals(ResolutionStatus.RESOLVED, current.status());
      assertEquals(
          "attribute.current_temperature",
          current.candidates().getFirst().matchedFields().getFirst().id());
      var next =
          fixture.catalog.resolve(
              new Query(
                  "",
                  query.domain(),
                  query.area(),
                  query.capability(),
                  query.role(),
                  1,
                  1,
                  Optional.of(result.revision())),
              budget());
      assertEquals(ResolutionStatus.AMBIGUOUS, next.status());
      assertEquals(
          "attribute.temperature", next.candidates().getFirst().matchedFields().getFirst().id());
    }
  }

  @Test
  void renameAcrossRestartRetainsHandleAndSourceLocatorIsCurrent() throws Exception {
    String identity;
    try (var first = fixture()) {
      identity =
          first
              .catalog
              .resolve(
                  new Query(
                      "",
                      Optional.of("climate"),
                      Optional.empty(),
                      Optional.of("temperature"),
                      Optional.of(FieldRole.CURRENT),
                      0,
                      20,
                      Optional.empty()),
                  budget())
              .candidates()
              .getFirst()
              .resourceId();
    }
    try (var later = fixture()) {
      ((ObjectNode) later.entities.get(0)).put("entity_id", "climate.study").put("name", "Study");
      ((ObjectNode) later.states.get(0)).put("entity_id", "climate.study");
      ((ObjectNode) later.states.get(0).path("attributes")).put("friendly_name", "Study");
      var result =
          later.catalog.resolve(query("Study", "temperature", FieldRole.CURRENT), budget());
      assertEquals(ResolutionStatus.RESOLVED, result.status());
      assertEquals(identity, result.candidates().getFirst().resourceId());
      assertEquals("climate.study", result.candidates().getFirst().entityId());
    }
  }

  @Test
  void reusedLocatorDoesNotDiscloseReplacementMetadataOrValuesAfterRestart() throws Exception {
    String identity;
    try (var first = fixture()) {
      identity =
          first
              .catalog
              .resolve(
                  new Query(
                      "",
                      Optional.of("climate"),
                      Optional.empty(),
                      Optional.of("temperature"),
                      Optional.of(FieldRole.CURRENT),
                      0,
                      20,
                      Optional.empty()),
                  budget())
              .candidates()
              .getFirst()
              .resourceId();
    }
    try (var later = fixture()) {
      ((ObjectNode) later.entities.get(0))
          .put("id", "new-registry")
          .put("name", "secret-replacement");
      ((ObjectNode) later.states.get(0).path("attributes"))
          .put("friendly_name", "secret-replacement")
          .put("current_temperature", 99.9);
      var result =
          later.catalog.resolve(query("Office", "temperature", FieldRole.CURRENT), budget());
      var old =
          result.candidates().stream()
              .filter(value -> value.resourceId().equals(identity))
              .findFirst()
              .orElseThrow();
      assertEquals(IdentityKind.MISMATCH, old.identity());
      assertFalse(Json.MAPPER.writeValueAsString(result).contains("secret-replacement"));
      var read =
          later.catalog.read(
              new FieldReadRequest(
                  result.revision(),
                  List.of(new FieldSelection(identity, List.of("attribute.current_temperature")))),
              budget());
      assertEquals(FieldStatus.MISSING, read.readings().getFirst().status());
      assertTrue(read.readings().getFirst().value().isEmpty());
    }
  }

  @Test
  void exclusionFollowsRegistryRenameAcrossRestartInAssistMode() throws Exception {
    try (var first =
        new Fixture(
            "assist_exposed",
            "standard_measurements",
            Set.of("climate.office"),
            Set.of(),
            List.of())) {
      assertEquals(
          1,
          first
              .catalog
              .resolve(query("", "temperature", FieldRole.CURRENT), budget())
              .candidates()
              .size());
    }
    try (var later =
        new Fixture(
            "assist_exposed",
            "standard_measurements",
            Set.of("climate.office"),
            Set.of(),
            List.of())) {
      ((ObjectNode) later.entities.get(0)).put("entity_id", "climate.study");
      ((ObjectNode) later.states.get(0)).put("entity_id", "climate.study");
      later.exposed = Set.of("climate.study", "sensor.motion_temperature");
      var result = later.catalog.resolve(query("", "temperature", FieldRole.CURRENT), budget());
      assertEquals(1, result.candidates().size());
      assertEquals("sensor.motion_temperature", result.candidates().getFirst().entityId());
    }
  }

  @Test
  void locatorOnlyHandlesChangeOnReconnectWithoutPretendingStableIdentity() throws Exception {
    try (var fixture = fixture()) {
      fixture.entities.remove(0);
      var first =
          fixture.catalog.resolve(
              new Query(
                  "",
                  Optional.of("climate"),
                  Optional.empty(),
                  Optional.of("temperature"),
                  Optional.of(FieldRole.CURRENT),
                  0,
                  20,
                  Optional.empty()),
              budget());
      assertEquals(IdentityKind.LOCATOR_ONLY, first.candidates().getFirst().identity());
      fixture.epoch = "epoch-b";
      var second =
          fixture.catalog.resolve(
              new Query(
                  "",
                  Optional.of("climate"),
                  Optional.empty(),
                  Optional.of("temperature"),
                  Optional.of(FieldRole.CURRENT),
                  0,
                  20,
                  Optional.empty()),
              budget());
      assertNotEquals(
          first.candidates().getFirst().resourceId(), second.candidates().getFirst().resourceId());
      assertNotEquals(first.revision(), second.revision());
    }
  }

  @Test
  void explicitProfileDoesNotAutomaticallyExposeClimateAttributesAndExclusionsWin()
      throws Exception {
    try (var fixture =
        new Fixture(
            "fixed",
            "explicit",
            Set.of(),
            Set.of("attribute.current_temperature"),
            List.of("current_temperature"))) {
      assertFalse(
          fixture
              .catalog
              .resolve(query("", "temperature", FieldRole.CURRENT), budget())
              .candidates()
              .stream()
              .anyMatch(value -> value.entityId().equals("climate.office")));
      var result = fixture.catalog.resolve(query("", "humidity", FieldRole.CURRENT), budget());
      assertEquals(ResolutionStatus.NO_MATCH, result.status());
    }
  }

  @Test
  void ordinaryValuesDoNotChangeRevisionButShapeChangesAndReconnectDo() throws Exception {
    try (var fixture = new Fixture("fixed", "explicit", Set.of(), Set.of(), List.of("extra"))) {
      ((ObjectNode) fixture.states.get(0).path("attributes")).put("extra", 12);
      var first = fixture.catalog.resolve(query("extra", null, null), budget());
      assertEquals(
          ValueType.NUMBER, first.candidates().getFirst().matchedFields().getFirst().type());
      ((ObjectNode) fixture.states.get(0).path("attributes")).put("extra", 13);
      var second = fixture.catalog.resolve(query("extra", null, null), budget());
      assertEquals(first.revision(), second.revision());
      ((ObjectNode) fixture.states.get(0).path("attributes")).put("extra", "text-now");
      var third = fixture.catalog.resolve(query("extra", null, null), budget());
      assertNotEquals(second.revision(), third.revision());
      assertEquals(ValueType.TEXT, third.candidates().getFirst().matchedFields().getFirst().type());
      assertThrows(
          HomeAssistantResourceCapabilities.SchemaChangedException.class,
          () ->
              fixture.catalog.read(
                  new FieldReadRequest(
                      second.revision(),
                      List.of(
                          new FieldSelection(
                              third.candidates().getFirst().resourceId(),
                              List.of("attribute.extra")))),
                  budget()));
      ((ObjectNode) fixture.states.get(0)).put("state", "unavailable");
      ((ObjectNode) fixture.states.get(0).path("attributes")).remove("extra");
      var outage = fixture.catalog.resolve(query("extra", null, null), budget());
      assertEquals(third.revision(), outage.revision());
      assertEquals(
          ValueType.TEXT, outage.candidates().getFirst().matchedFields().getFirst().type());
    }
  }

  @Test
  void configuredCustomFieldPermissionsFollowVerifiedRenameInAssistModeButNotNewRegistryIdentity()
      throws Exception {
    try (var first =
        new Fixture("assist_exposed", "explicit", Set.of(), Set.of(), List.of("extra"))) {
      ((ObjectNode) first.states.get(0).path("attributes")).put("extra", 7);
      assertEquals(
          ResolutionStatus.RESOLVED,
          first.catalog.resolve(query("extra", null, null), budget()).status());
    }
    try (var later =
        new Fixture("assist_exposed", "explicit", Set.of(), Set.of(), List.of("extra"))) {
      ((ObjectNode) later.entities.get(0)).put("entity_id", "climate.study");
      ((ObjectNode) later.states.get(0)).put("entity_id", "climate.study");
      ((ObjectNode) later.states.get(0).path("attributes")).put("extra", 8);
      later.exposed = Set.of("climate.study");
      var result = later.catalog.resolve(query("office extra", null, null), budget());
      assertEquals(ResolutionStatus.RESOLVED, result.status());
      assertEquals("climate.study", result.candidates().getFirst().entityId());
    }
  }

  @Test
  void locatorOnlyCustomPermissionDoesNotTransferWhenRegistryIdentityAppears() throws Exception {
    try (var fixture =
        new Fixture("assist_exposed", "explicit", Set.of(), Set.of(), List.of("extra"))) {
      fixture.entities.remove(0);
      ((ObjectNode) fixture.states.get(0).path("attributes")).put("extra", 7);
      var first = fixture.catalog.resolve(query("extra", null, null), budget());
      assertEquals(ResolutionStatus.RESOLVED, first.status());
      fixture.entities.add(registry("climate.office", "new-registered-device", "Office"));
      var later = fixture.catalog.resolve(query("extra", null, null), budget());
      assertEquals(ResolutionStatus.NO_MATCH, later.status());
    }
  }

  @Test
  void excludedReplacementCannotEscapeThroughLaterRenameAndRestart() throws Exception {
    try (var first =
        new Fixture(
            "assist_exposed",
            "standard_measurements",
            Set.of("climate.office"),
            Set.of(),
            List.of())) {
      first.catalog.resolve(query("", "temperature", FieldRole.CURRENT), budget());
      ((ObjectNode) first.entities.get(0)).put("id", "replacement-registry");
      first.catalog.resolve(query("", "temperature", FieldRole.CURRENT), budget());
    }
    try (var later =
        new Fixture(
            "assist_exposed",
            "standard_measurements",
            Set.of("climate.office"),
            Set.of(),
            List.of())) {
      ((ObjectNode) later.entities.get(0))
          .put("entity_id", "climate.study")
          .put("id", "replacement-registry");
      ((ObjectNode) later.states.get(0)).put("entity_id", "climate.study");
      later.exposed = Set.of("climate.study");
      assertEquals(
          ResolutionStatus.NO_MATCH,
          later.catalog.resolve(query("", "temperature", FieldRole.CURRENT), budget()).status());
    }
  }

  @Test
  void temporaryAbsentAttributeKeepsEstablishedSchemaAndReturnsMissingObservation()
      throws Exception {
    try (var fixture = new Fixture("fixed", "explicit", Set.of(), Set.of(), List.of("extra"))) {
      ((ObjectNode) fixture.states.get(0).path("attributes")).put("extra", 7);
      var initial = fixture.catalog.resolve(query("extra", null, null), budget());
      ((ObjectNode) fixture.states.get(0).path("attributes")).remove("extra");
      var later = fixture.catalog.resolve(query("extra", null, null), budget());
      assertEquals(initial.revision(), later.revision());
      var read =
          fixture.catalog.read(
              new FieldReadRequest(
                  initial.revision(),
                  List.of(
                      new FieldSelection(
                          initial.candidates().getFirst().resourceId(),
                          List.of("attribute.extra")))),
              budget());
      assertEquals(FieldStatus.MISSING, read.readings().getFirst().status());
      assertTrue(read.readings().getFirst().value().isEmpty());
    }
  }

  @Test
  void unknownAndUnavailableRemainSeparateAndHiddenReferencesShareSanitizedFailure()
      throws Exception {
    try (var fixture = fixture()) {
      ((ObjectNode) fixture.states.get(1)).put("state", "unknown");
      var resolution =
          fixture.catalog.resolve(query("", "temperature", FieldRole.CURRENT), budget());
      var motion =
          resolution.candidates().stream()
              .filter(value -> value.entityId().startsWith("sensor."))
              .findFirst()
              .orElseThrow();
      var read =
          fixture.catalog.read(
              new FieldReadRequest(
                  resolution.revision(),
                  List.of(new FieldSelection(motion.resourceId(), List.of("state")))),
              budget());
      assertEquals(FieldStatus.UNKNOWN, read.readings().getFirst().status());
      var hidden =
          assertThrows(
              IOException.class,
              () ->
                  fixture.catalog.describe(
                      "r_" + "f".repeat(64), resolution.revision(), 0, 16, budget()));
      var field =
          assertThrows(
              IOException.class,
              () ->
                  fixture.catalog.read(
                      new FieldReadRequest(
                          resolution.revision(),
                          List.of(
                              new FieldSelection(
                                  motion.resourceId(), List.of("attribute.hidden")))),
                      budget()));
      assertEquals(hidden.getMessage(), field.getMessage());
    }
  }

  @Test
  void emptyTextStateIsValidWhileMalformedTimestampBecomesInvalidWithoutValueDisclosure()
      throws Exception {
    var settings =
        new HomeAssistantSettings(
            "https://ha.example.invalid",
            "HA_TOKEN",
            Map.of("note", new HomeAssistantSettings.Entity("input_text.note", List.of())),
            Map.of(),
            List.of());
    var policy =
        new Discovery("fixed", "explicit", Set.of(), Set.of(), directory.resolve("identity"));
    var state = state("input_text.note", "Office note", "");
    var states = Json.MAPPER.createArrayNode().add(state);
    var entities =
        Json.MAPPER
            .createArrayNode()
            .add(registry("input_text.note", "note-registry", "Office note"));
    var empty = Json.MAPPER.createArrayNode();
    try (var store =
        new HomeAssistantCapabilityIdentityStore(policy.identityJournal(), OWNER, NAMESPACE)) {
      var initial =
          HomeAssistantCapabilityWire.snapshot(
              "epoch",
              policy,
              settings,
              store,
              states,
              entities,
              empty,
              empty,
              Json.object(),
              Set.of(),
              OBSERVED,
              budget());
      assertEquals(
          new TextParameter(""),
          initial.observations().getFirst().readings().getFirst().value().orElseThrow());
      state.put("last_updated", false);
      var invalid =
          HomeAssistantCapabilityWire.snapshot(
              "epoch",
              policy,
              settings,
              store,
              states,
              entities,
              empty,
              empty,
              Json.object(),
              Set.of(),
              OBSERVED,
              budget());
      assertTrue(
          invalid.observations().getFirst().readings().stream()
              .allMatch(value -> value.status() == FieldStatus.INVALID));
      assertTrue(
          invalid.observations().getFirst().readings().stream()
              .allMatch(value -> value.value().isEmpty()));
    }
  }

  @Test
  void displayPaddingNormalizesLookupMetadataWithoutChangingIdentityOrInteriorSpaces()
      throws Exception {
    try (var fixture = fixture()) {
      var initial =
          fixture.catalog.resolve(
              new Query(
                  "",
                  Optional.of("climate"),
                  Optional.empty(),
                  Optional.of("temperature"),
                  Optional.of(FieldRole.CURRENT),
                  0,
                  20,
                  Optional.empty()),
              budget());
      String resourceId = initial.candidates().getFirst().resourceId();
      ((ObjectNode) fixture.states.get(0).path("attributes")).put("friendly_name", "  Office  ");
      ((ObjectNode) fixture.entities.get(0))
          .putArray("aliases")
          .add(" Study ")
          .add("Study")
          .add("  ")
          .addNull()
          .add("  The  Study  ");
      fixture.deviceNameByUser = "  House  Thermostat  ";
      fixture.areaName = "  Office  ";
      var resolved =
          fixture.catalog.resolve(query("study", "temperature", FieldRole.CURRENT), budget());
      assertEquals(ResolutionStatus.RESOLVED, resolved.status());
      var resource = resolved.candidates().getFirst();
      assertEquals(resourceId, resource.resourceId());
      assertEquals("climate.office", resource.entityId());
      assertEquals("Office", resource.name());
      assertEquals("House  Thermostat", resource.device().orElseThrow());
      assertEquals("Office", resource.area().orElseThrow());
      assertEquals(List.of("Study", "The  Study", "office"), resource.aliases());
      var read =
          fixture.catalog.read(
              new FieldReadRequest(
                  resolved.revision(),
                  List.of(
                      new FieldSelection(resourceId, List.of("attribute.current_temperature")))),
              budget());
      assertEquals(
          new NumberParameter(new BigDecimal("19.4")),
          read.readings().getFirst().value().orElseThrow());
      assertEquals("climate.office", read.readings().getFirst().entityId());
    }
  }

  @Test
  void blankHumanLabelsUseNextFallbackAndBlankAliasesAreSkipped() throws Exception {
    try (var fixture = fixture()) {
      ((ObjectNode) fixture.states.get(0).path("attributes")).put("friendly_name", "   ");
      ((ObjectNode) fixture.entities.get(0))
          .put("name", " ")
          .put("original_name", "  Registry Office  ");
      ((ObjectNode) fixture.entities.get(0)).putArray("aliases").add(" ").add("  ").addNull();
      fixture.deviceNameByUser = " ";
      fixture.deviceName = "  Device default  ";
      fixture.areaName = " ";
      var resolved =
          fixture.catalog.resolve(
              new Query(
                  "",
                  Optional.of("climate"),
                  Optional.empty(),
                  Optional.of("temperature"),
                  Optional.of(FieldRole.CURRENT),
                  0,
                  20,
                  Optional.empty()),
              budget());
      var resource = resolved.candidates().getFirst();
      assertEquals("Registry Office", resource.name());
      assertEquals("Device default", resource.device().orElseThrow());
      assertTrue(resource.area().isEmpty());
      assertEquals(List.of("office"), resource.aliases());
      ((ObjectNode) fixture.entities.get(0)).put("original_name", " ");
      var fallback =
          fixture.catalog.resolve(query("office", "temperature", FieldRole.CURRENT), budget());
      var current =
          fallback.candidates().stream()
              .filter(value -> value.entityId().equals("climate.office"))
              .findFirst()
              .orElseThrow();
      assertEquals("office", current.name());
      assertEquals(resource.resourceId(), current.resourceId());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "friendlyControl",
        "aliasControl",
        "deviceSeparator",
        "areaOverlong",
        "blankOverlong",
        "registryIdentity",
        "entityIdentity",
        "unitIdentity",
        "capabilityIdentity"
      })
  void displayNormalizationCannotCoerceMalformedIdentitiesOrHideInvalidRawLabels(String malformed)
      throws Exception {
    try (var fixture = fixture()) {
      fixture.catalog.resolve(query("office", "temperature", FieldRole.CURRENT), budget());
      byte[] retained = Files.readAllBytes(directory.resolve("identity/identities.json"));
      switch (malformed) {
        case "friendlyControl" ->
            ((ObjectNode) fixture.states.get(0).path("attributes"))
                .put("friendly_name", "Office\n");
        case "aliasControl" ->
            ((ObjectNode) fixture.entities.get(0)).putArray("aliases").add("Study\t");
        case "deviceSeparator" -> fixture.deviceNameByUser = "Device\u2028";
        case "areaOverlong" -> fixture.areaName = " ".repeat(256) + "Office";
        case "blankOverlong" ->
            ((ObjectNode) fixture.states.get(0).path("attributes"))
                .put("friendly_name", " ".repeat(257));
        case "registryIdentity" ->
            ((ObjectNode) fixture.entities.get(0)).put("id", "registry-office ");
        case "entityIdentity" ->
            ((ObjectNode) fixture.entities.get(0)).put("entity_id", "climate.office ");
        case "unitIdentity" ->
            ((ObjectNode) fixture.states.get(0).path("attributes")).put("temperature_unit", " °C ");
        case "capabilityIdentity" ->
            ((ObjectNode) fixture.states.get(1).path("attributes"))
                .put("device_class", " temperature ");
        default -> throw new AssertionError();
      }
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.catalog.resolve(query("office", "temperature", FieldRole.CURRENT), budget()));
      assertArrayEquals(
          retained, Files.readAllBytes(directory.resolve("identity/identities.json")));
    }
  }
}
