package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReading;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldRole;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldSelection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldSupport;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.IdentityKind;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Query;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResolutionStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResourceSummary;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ValueType;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilityIdentityStore.Anchor;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Plain child-JVM consumer: copied alone, without JUnit, server classes or test helpers. */
public final class HomeAssistantInstalledRuntimeConsumer {
  private HomeAssistantInstalledRuntimeConsumer() {}

  public static void main(String[] args) {
    try {
      require(args.length == 1, "One isolated identity directory required");
      for (String forbidden :
          List.of("org.junit.jupiter.api.Test", "org.springframework.boot.SpringApplication")) {
        try {
          Class.forName(forbidden);
          throw new IllegalStateException("Contaminated runtime classpath");
        } catch (ClassNotFoundException expected) {
          // The consumer intentionally has only the owning application's runtime dependencies.
        }
      }
      consume(Path.of(args[0]));
      System.out.println("PASS_CAPABILITY_RUNTIME");
    } catch (Exception failure) {
      // Bounded classification only; no configuration, payload or private local path is printed.
      System.out.println("FAILED:" + failure.getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static void consume(Path directory) throws IOException {
    String owner = Json.hash("fixture-owner");
    String namespace = Json.hash("fixture-instance");
    String registry = Json.hash("fixture-registry");
    String resource = "r_" + Json.hash(namespace + "\0" + registry);
    var current =
        new FieldDescription(
            "attribute.current_temperature",
            "Current temperature",
            ValueType.NUMBER,
            Optional.of("°C"),
            "temperature",
            FieldRole.CURRENT,
            FieldSupport.SUPPORTED);
    var missing =
        new FieldDescription(
            "attribute.extra",
            "Extra",
            ValueType.UNKNOWN,
            Optional.empty(),
            "unknown",
            FieldRole.UNKNOWN,
            FieldSupport.UNKNOWN);
    var anchor =
        new Anchor(
            "fixed:office",
            registry,
            IdentityKind.REGISTRY,
            "climate.office",
            resource,
            "Office",
            List.of("office"),
            Optional.of("Fixture thermostat"),
            Optional.of("Fixture office"),
            List.of(current, missing));
    try (var store = new HomeAssistantCapabilityIdentityStore(directory, owner, namespace)) {
      store.install(List.of(anchor));
    }
    try (var store = new HomeAssistantCapabilityIdentityStore(directory, owner, namespace);
        var catalog = new HomeAssistantCapabilityCatalog(new RuntimeSource(store))) {
      require(
          store.state().anchors().equals(List.of(anchor)),
          "Optional identity metadata must reopen intact");
      var resolved =
          catalog.resolve(
              new Query(
                  "office",
                  Optional.of("climate"),
                  Optional.of("Fixture office"),
                  Optional.of("temperature"),
                  Optional.of(FieldRole.CURRENT),
                  0,
                  20,
                  Optional.empty()),
              budget());
      require(
          resolved.status() == ResolutionStatus.RESOLVED, "One current temperature must resolve");
      var described = catalog.describe(resource, resolved.revision(), 0, 16, budget());
      require(
          described.fields().equals(List.of(current, missing)),
          "Field Optional metadata must describe intact");
      var read =
          catalog.read(
              new FieldReadRequest(
                  resolved.revision(),
                  List.of(new FieldSelection(resource, List.of(current.id(), missing.id())))),
              budget());
      require(
          read.readings()
              .getFirst()
              .value()
              .orElseThrow()
              .equals(new NumberParameter(new BigDecimal("19.4"))),
          "Typed current temperature must survive the runtime boundary");
      var resolutionJson = Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(resolved));
      var descriptionJson = Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(described));
      var readJson = Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(read));
      require(
          resolutionJson
              .path("candidates")
              .get(0)
              .path("device")
              .asText()
              .equals("Fixture thermostat"),
          "Optional device must encode as a string");
      require(
          descriptionJson.path("resource").path("area").asText().equals("Fixture office"),
          "Optional area must encode as a string");
      require(
          descriptionJson.path("fields").get(0).path("unit").asText().equals("°C"),
          "Optional unit must encode as a string");
      require(
          descriptionJson.path("fields").get(1).path("unit").isNull(),
          "Empty unit must encode as null");
      require(
          readJson.path("readings").get(0).path("value").isNumber(),
          "Optional value must encode as its number");
      require(
          readJson.path("readings").get(1).path("value").isNull(),
          "Missing value must encode as null");
      require(
          readJson.path("readings").get(0).path("sourceUpdatedAt").isTextual(),
          "Optional source timestamp must encode as an ISO string");
      require(
          readJson.path("readings").get(1).path("sourceUpdatedAt").isNull(),
          "Absent source timestamp must encode as null");
    }
  }

  private static OperationBudget budget() {
    return OperationBudget.start(Duration.ofSeconds(2));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  static final class RuntimeSource implements HomeAssistantCapabilitySource {
    private final HomeAssistantCapabilityIdentityStore store;

    RuntimeSource(HomeAssistantCapabilityIdentityStore store) {
      this.store = store;
    }

    @Override
    public Snapshot snapshot(OperationBudget budget) throws IOException {
      budget.remaining();
      Anchor anchor = store.state().anchors().getFirst();
      Instant observed = Instant.parse("2026-11-24T00:00:00Z");
      var resource =
          new ResourceSummary(
              anchor.resourceId(),
              anchor.entityId(),
              anchor.name(),
              anchor.aliases(),
              anchor.device(),
              anchor.area(),
              Availability.AVAILABLE,
              anchor.identity(),
              anchor.fields());
      var readings =
          List.of(
              new FieldReading(
                  anchor.resourceId(),
                  anchor.entityId(),
                  anchor.fields().get(0),
                  FieldStatus.AVAILABLE,
                  Optional.of(new NumberParameter(new BigDecimal("19.4"))),
                  Optional.of(observed),
                  observed,
                  false),
              new FieldReading(
                  anchor.resourceId(),
                  anchor.entityId(),
                  anchor.fields().get(1),
                  FieldStatus.MISSING,
                  Optional.empty(),
                  Optional.empty(),
                  observed,
                  false));
      return new Snapshot(
          "fixture-epoch",
          Json.hash("fixture-policy"),
          observed,
          List.of(new Observation(resource, readings)));
    }

    @Override
    public void close() throws IOException {
      store.close();
    }
  }
}
