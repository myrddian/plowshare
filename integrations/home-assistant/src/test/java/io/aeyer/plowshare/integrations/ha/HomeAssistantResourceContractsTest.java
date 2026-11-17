package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.ha.HomeAssistantDiscoveryPolicy.Metadata;
import io.aeyer.plowshare.integrations.ha.HomeAssistantDiscoveryPolicy.Selection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Cursor;
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
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.UnsupportedAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.UnsupportedSelector;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.Parameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HomeAssistantResourceContractsTest {
  @Test
  void childBudgetNeverResetsItsParentsDeadlineAndRefusesInvalidCaps() throws Exception {
    OperationBudget parent = OperationBudget.start(Duration.ofSeconds(2));
    assertSame(parent, parent.capped(Duration.ofDays(1)));
    OperationBudget child = parent.capped(Duration.ofSeconds(1));
    assertSame(child, child.capped(Duration.ofDays(1)));
    assertTrue(child.remaining().compareTo(Duration.ofSeconds(1)) <= 0);
    assertThrows(
        IOException.class,
        () -> OperationBudget.start(Duration.ofNanos(1)).capped(Duration.ofDays(1)).remaining());
    assertThrows(IllegalArgumentException.class, () -> parent.capped(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> parent.capped(Duration.ofNanos(-1)));
    assertThrows(IllegalArgumentException.class, () -> parent.capped(Duration.ofDays(2)));
  }

  private static final Binding BINDING = new Binding("house", "credential-generation-a");
  private static final Reference OFFICE = new Reference(BINDING, "office.temperature");
  private static final Revision REVISION = new Revision(BINDING, "policy-a", "epoch-a", 1, 2);
  private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
  private static final Registries REGISTRIES =
      new Registries(
          RegistryAvailability.AVAILABLE, RegistryAvailability.AVAILABLE,
          RegistryAvailability.AVAILABLE, RegistryAvailability.AVAILABLE);

  @Test
  void filtersBeforePagingAndCountsWithoutDisclosingDeniedMetadata() {
    HomeAssistantDiscoveryPolicy policy = policy();
    List<Description> input =
        List.of(
            description(OFFICE, "sensor.office_temperature", "Temperature"),
            description(new Reference(BINDING, "private"), "sensor.private", "Private"));
    List<Description> authorized =
        input.stream().map(policy::project).flatMap(Optional::stream).toList();
    Page page = new Page(REVISION, authorized, Optional.empty());
    assertEquals(1, page.resources().size());
    assertEquals("office.temperature", page.resources().getFirst().name());
    assertEquals(Optional.empty(), page.resources().getFirst().area());
    assertEquals(Optional.empty(), page.resources().getFirst().device());
    assertEquals(Set.of("unit_of_measurement"), page.resources().getFirst().readableAttributes());
    assertFalse(page.toString().contains("Private"));
    assertTrue(policy.actions(new Reference(BINDING, "private"), REVISION).isEmpty());
  }

  @Test
  void displayNamesAreNotResourceIdentityAndRenameRequiresExplicitPolicyRevision() {
    Reference second = new Reference(BINDING, "other.temperature");
    Description first = description(OFFICE, "sensor.office_temperature", "Temperature");
    Description other = description(second, "sensor.other_temperature", "Temperature");
    assertEquals(2, new Page(REVISION, List.of(first, other), Optional.empty()).resources().size());
    Description renamed = description(OFFICE, "sensor.renamed_temperature", "Temperature");
    assertTrue(policy().project(renamed).isEmpty());
    HomeAssistantDiscoveryPolicy updated =
        new HomeAssistantDiscoveryPolicy(
            BINDING,
            "policy-b",
            Map.of(
                OFFICE.alias(),
                new Selection(
                    "sensor.renamed_temperature", Set.of(), new Metadata(true, false, false))),
            Map.of());
    assertEquals(OFFICE, updated.project(renamed).orElseThrow().reference());
    assertTrue(updated.project(first).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> updated.requireRevision(REVISION));
  }

  @Test
  void missingRegistriesAreExplicitAndCannotFabricateRelationships() {
    Registries missing =
        new Registries(
            RegistryAvailability.UNSUPPORTED,
            RegistryAvailability.DENIED,
            RegistryAvailability.UNSUPPORTED,
            RegistryAvailability.AVAILABLE);
    Description source =
        new Description(
            OFFICE,
            "sensor.office_temperature",
            "Temperature",
            Optional.empty(),
            Optional.empty(),
            Set.of(),
            missing);
    assertEquals(RegistryAvailability.DENIED, source.registries().devices());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Description(
                OFFICE,
                "sensor.office_temperature",
                "Temperature",
                Optional.of("private-device"),
                Optional.empty(),
                Set.of(),
                missing));
  }

  @Test
  void selectedPrimitivesUnsupportedAttributesMissingAndUnavailableStayDistinct() {
    State source =
        new State(
            OFFICE,
            Availability.UNAVAILABLE,
            Optional.of("unavailable"),
            Map.of(
                "unit_of_measurement",
                new TextParameter("°C"),
                "private",
                new TextParameter("secret")),
            Set.of("private_nested"),
            Optional.of(NOW),
            NOW,
            true);
    State selected = policy().project(source, REVISION);
    assertEquals(Availability.UNAVAILABLE, selected.availability());
    assertTrue(selected.stale());
    assertEquals(Map.of("unit_of_measurement", new TextParameter("°C")), selected.attributes());
    assertTrue(selected.unsupportedAttributes().isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> Parameter.fromWire(Map.of("nested", "value")));
    State missing =
        new State(
            OFFICE,
            Availability.MISSING,
            Optional.empty(),
            Map.of(),
            Set.of(),
            Optional.empty(),
            NOW,
            false);
    assertEquals(Availability.MISSING, policy().project(missing, REVISION).availability());
    assertEquals(List.of(missing), new ReadBatch(REVISION, List.of(missing)).states());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new State(
                OFFICE,
                Availability.AVAILABLE,
                Optional.empty(),
                Map.of(),
                Set.of(),
                Optional.empty(),
                NOW,
                false));
  }

  @Test
  void selectedUnsupportedAttributeNamesRemainVisibleWithoutPayloads() {
    HomeAssistantDiscoveryPolicy policy =
        new HomeAssistantDiscoveryPolicy(
            BINDING,
            "policy-a",
            Map.of(
                OFFICE.alias(),
                new Selection(
                    "sensor.office_temperature",
                    Set.of("forecast"),
                    new Metadata(false, false, false))),
            Map.of());
    State source =
        new State(
            OFFICE,
            Availability.AVAILABLE,
            Optional.of("22"),
            Map.of(),
            Set.of("forecast", "private_nested"),
            Optional.of(NOW),
            NOW,
            false);
    assertEquals(Set.of("forecast"), policy.project(source, REVISION).unsupportedAttributes());
  }

  @Test
  void credentialBindingAndPolicyIdentityPreventCrossSnapshotReuse() {
    Binding otherCredential = new Binding("house", "credential-generation-b");
    Reference other = new Reference(otherCredential, OFFICE.alias());
    assertTrue(policy().selection(other).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> new ReadRequest(REVISION, List.of(other)));
    assertThrows(
        IllegalArgumentException.class,
        () -> policy().requireRevision(new Revision(otherCredential, "policy-a", "epoch-a", 1, 2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> policy().requireRevision(new Revision(BINDING, "revoked-policy", "epoch-a", 1, 2)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policy().require(new ReadRequest(REVISION, List.of(new Reference(BINDING, "denied")))));
  }

  @Test
  void actionDescriptionsExposeOnlyFixedReviewedPrimitiveProfiles() {
    HomeAssistantSettings.Action action =
        new HomeAssistantSettings.Action(
            "light.turn_on",
            List.of("sensor.office_temperature"),
            Map.of(
                "brightness",
                new HomeAssistantSettings.ParameterPolicy(
                    "integer", BigDecimal.ZERO, BigDecimal.valueOf(255), null, null)),
            List.of(),
            false);
    HomeAssistantDiscoveryPolicy policy =
        new HomeAssistantDiscoveryPolicy(
            BINDING,
            "policy-a",
            Map.of(
                OFFICE.alias(),
                new Selection(
                    "sensor.office_temperature", Set.of(), new Metadata(false, false, false))),
            Map.of("office.on", action));
    ConfiguredAction offered =
        assertInstanceOf(ConfiguredAction.class, policy.actions(OFFICE, REVISION).getFirst());
    offered.profile().validate(Map.of("brightness", new NumberParameter(BigDecimal.valueOf(100))));
    assertThrows(
        IllegalArgumentException.class,
        () -> offered.profile().validate(Map.of("brightness", new TextParameter("arbitrary"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> offered.profile().validate(Map.of("target", new TextParameter("another-entity"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HomeAssistantSettings.ParameterPolicy("object", null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HomeAssistantSettings.Action(
                "light.turn_on", List.of(), Map.of(), List.of(), true));
    assertEquals(
        UnsupportedSelector.DYNAMIC_TARGET,
        new UnsupportedAction("area.lights", UnsupportedSelector.DYNAMIC_TARGET).reason());
  }

  @Test
  void maximumLegacyAttributeSelectionPreservesImplicitAndExplicitUnits() {
    List<String> attributes =
        IntStream.range(0, 256).mapToObj(index -> "attribute_" + index).toList();
    assertMaximumAttributeProjection(attributes, 257);
    List<String> explicitUnit = new ArrayList<>(attributes);
    explicitUnit.set(255, "unit_of_measurement");
    assertMaximumAttributeProjection(explicitUnit, 256);
    List<String> excessive = new ArrayList<>(attributes);
    excessive.add("extra");
    assertThrows(
        IllegalArgumentException.class,
        () -> new HomeAssistantSettings.Entity("sensor.office_temperature", excessive));
  }

  @Test
  void mixedTargetActionIsOmittedWithoutDisclosingOrNarrowingItsTargets() {
    Reference office = new Reference(BINDING, "office.light");
    Reference privateRoom = new Reference(BINDING, "private.light");
    HomeAssistantSettings.Action mixed =
        new HomeAssistantSettings.Action(
            "light.turn_on",
            List.of("light.office", "light.private_bedroom"),
            Map.of(),
            List.of(),
            false);
    HomeAssistantDiscoveryPolicy policy =
        new HomeAssistantDiscoveryPolicy(
            BINDING,
            "policy-a",
            Map.of(
                office.alias(),
                new Selection("light.office", Set.of(), new Metadata(false, false, false))),
            Map.of("all.on", mixed));
    assertTrue(policy.selection(privateRoom).isEmpty());
    assertTrue(policy.actions(office, REVISION).isEmpty());
    assertTrue(policy.actions(privateRoom, REVISION).isEmpty());
    assertTrue(policy.actions(new Reference(BINDING, "absent"), REVISION).isEmpty());
    assertEquals(List.of("light.office", "light.private_bedroom"), mixed.targets());

    HomeAssistantDiscoveryPolicy bothDisclosed =
        new HomeAssistantDiscoveryPolicy(
            BINDING,
            "policy-a",
            Map.of(
                office.alias(),
                new Selection("light.office", Set.of(), new Metadata(false, false, false)),
                privateRoom.alias(),
                new Selection(
                    "light.private_bedroom", Set.of(), new Metadata(false, false, false))),
            Map.of("all.on", mixed));
    ConfiguredAction offered =
        assertInstanceOf(
            ConfiguredAction.class, bothDisclosed.actions(office, REVISION).getFirst());
    assertEquals(mixed.targets(), offered.profile().targets());
  }

  @Test
  void batchesCursorsSelectionsAndMetadataHaveFiniteValidatedBounds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Search("", Optional.empty(), Optional.empty(), 101, Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Search("x".repeat(257), Optional.empty(), Optional.empty(), 1, Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Search("", Optional.of("bad.domain"), Optional.empty(), 1, Optional.empty()));
    assertThrows(IllegalArgumentException.class, () -> new ReadRequest(REVISION, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new ReadRequest(REVISION, List.of(OFFICE, OFFICE)));
    assertThrows(IllegalArgumentException.class, () -> new Cursor(REVISION, 257));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Page(
                REVISION,
                List.of(),
                Optional.of(new Cursor(new Revision(BINDING, "policy-b", "epoch-a", 1, 2), 1))));
    List<Reference> original = new ArrayList<>(List.of(OFFICE));
    ReadRequest request = new ReadRequest(REVISION, original);
    original.clear();
    assertEquals(List.of(OFFICE), request.references());
    assertThrows(IllegalArgumentException.class, () -> OperationBudget.start(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> new Reference(BINDING, " "));
  }

  private static HomeAssistantDiscoveryPolicy policy() {
    HomeAssistantSettings settings =
        new HomeAssistantSettings(
            "https://ha.example.test",
            "HA_TOKEN",
            Map.of(
                OFFICE.alias(),
                new HomeAssistantSettings.Entity("sensor.office_temperature", List.of())),
            Map.of(),
            List.of());
    return HomeAssistantDiscoveryPolicy.fromSettings(BINDING, "policy-a", settings);
  }

  private static void assertMaximumAttributeProjection(
      List<String> attributes, int effectiveCount) {
    HomeAssistantSettings.Entity entity =
        new HomeAssistantSettings.Entity("sensor.office_temperature", attributes);
    HomeAssistantSettings settings =
        new HomeAssistantSettings(
            "https://ha.example.test",
            "HA_TOKEN",
            Map.of(OFFICE.alias(), entity),
            Map.of(),
            List.of());
    HomeAssistantDiscoveryPolicy policy =
        HomeAssistantDiscoveryPolicy.fromSettings(BINDING, "policy-a", settings);
    Set<String> effective = entity.selectedAttributes();
    assertEquals(effectiveCount, effective.size());
    Description metadata =
        new Description(
            OFFICE,
            entity.entity(),
            "Temperature",
            Optional.empty(),
            Optional.empty(),
            effective,
            REGISTRIES);
    assertEquals(effective, policy.project(metadata).orElseThrow().readableAttributes());
    Map<String, Parameter> values =
        effective.stream()
            .collect(
                Collectors.toUnmodifiableMap(name -> name, name -> new TextParameter("value")));
    State state =
        new State(
            OFFICE,
            Availability.AVAILABLE,
            Optional.of("22"),
            values,
            Set.of(),
            Optional.of(NOW),
            NOW,
            false);
    assertEquals(values, policy.project(state, REVISION).attributes());
    assertTrue(policy.project(state, REVISION).attributes().containsKey("unit_of_measurement"));
  }

  private static Description description(Reference reference, String entity, String name) {
    return new Description(
        reference,
        entity,
        name,
        Optional.of("device-a"),
        Optional.of("area-a"),
        Set.of("unit_of_measurement", "private"),
        REGISTRIES);
  }
}
