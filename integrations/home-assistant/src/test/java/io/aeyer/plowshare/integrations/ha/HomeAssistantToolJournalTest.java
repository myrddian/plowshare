package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.Delivery;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeAssistantToolJournalTest {
  @TempDir Path directory;
  private static final String IDENTITY = "a".repeat(64);
  private static final String OWNER = "b".repeat(64);

  private static RelayTools.Receipt executing(String id) throws Exception {
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools =
        new HomeAssistantReadTools(
            HomeAssistantReadToolsTest.SOURCE, "house_", resources, resources);
    var original = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    var call =
        new RelayTools.Call(
            original.schema(),
            id,
            original.project(),
            original.provider(),
            original.tool(),
            original.account(),
            original.run(),
            original.call(),
            original.deadline(),
            original.arguments(),
            original.schemaFingerprint());
    return new RelayTools.Receipt(
        id,
        Json.MAPPER.writeValueAsString(call),
        RelayTools.Phase.EXECUTING,
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void exclusiveOwnerAndExactReceiptsSurviveRestartWithoutConfigurationAdoption() throws Exception {
    RelayTools.Receipt receipt = executing(UUID.randomUUID().toString());
    try (var journal = new HomeAssistantToolJournal(directory, IDENTITY, OWNER)) {
      journal.save(receipt);
      assertThrows(
          IOException.class, () -> new HomeAssistantToolJournal(directory, IDENTITY, OWNER));
      assertEquals(receipt, journal.all().getFirst());
    }
    assertThrows(
        IOException.class, () -> new HomeAssistantToolJournal(directory, "c".repeat(64), OWNER));
    assertThrows(
        IOException.class, () -> new HomeAssistantToolJournal(directory, IDENTITY, "d".repeat(64)));
    try (var journal = new HomeAssistantToolJournal(directory, IDENTITY, OWNER)) {
      assertEquals(receipt, journal.all().getFirst());
      assertThrows(UnsupportedOperationException.class, () -> journal.all().clear());
    }
  }

  @Test
  void receiptsWithoutBrokerCompletionProofCannotBeResetOrEvicted() throws Exception {
    var original = executing(UUID.randomUUID().toString());
    var result =
        Optional.of(new RelayTools.Result(RelayTools.State.COMPLETED, "captured observation"));
    try (var journal = new HomeAssistantToolJournal(directory, IDENTITY, OWNER)) {
      journal.save(original);
      var ready =
          new RelayTools.Receipt(
              original.invocationId(),
              original.request(),
              RelayTools.Phase.READY,
              result,
              Optional.empty());
      journal.save(ready);
      var publishing =
          new RelayTools.Receipt(
              original.invocationId(),
              original.request(),
              RelayTools.Phase.PUBLISHING,
              result,
              Optional.of(Instant.now().toString()));
      journal.save(publishing);
      journal.save(ready); // SDK proven-NOT_SUBMITTED recovery is the only backward phase.
      journal.save(publishing);
      var done =
          new RelayTools.Receipt(
              original.invocationId(),
              original.request(),
              RelayTools.Phase.DONE,
              result,
              publishing.occurredAt());
      journal.save(done);
      assertThrows(IOException.class, () -> journal.save(original));
      for (int i = 1; i < HomeAssistantToolJournal.MAX_RECEIPTS; i++)
        journal.save(executing(UUID.randomUUID().toString()));
      assertThrows(IOException.class, () -> journal.save(executing(UUID.randomUUID().toString())));
      assertEquals(done, journal.all().getFirst());
    }
    try (var journal = new HomeAssistantToolJournal(directory, IDENTITY, OWNER)) {
      assertEquals(HomeAssistantToolJournal.MAX_RECEIPTS, journal.all().size());
      assertEquals(RelayTools.Phase.DONE, journal.all().getFirst().phase());
    }
  }

  @Test
  void persistedBoundaryRejectsUnknownFieldsDuplicatesAndScalarCoercion() throws Exception {
    try (var journal = new HomeAssistantToolJournal(directory, IDENTITY, OWNER)) {
      assertTrue(journal.all().isEmpty());
    }
    String valid = Files.readString(directory.resolve("tools.json"));
    Files.writeString(
        directory.resolve("tools.json"),
        valid.substring(0, valid.length() - 1) + ",\"unexpected\":true}");
    assertThrows(IOException.class, () -> new HomeAssistantToolJournal(directory, IDENTITY, OWNER));
    Files.writeString(
        directory.resolve("tools.json"),
        valid.replace("\"version\":2", "\"version\":2,\"version\":2"));
    assertThrows(IOException.class, () -> new HomeAssistantToolJournal(directory, IDENTITY, OWNER));
    Files.writeString(
        directory.resolve("tools.json"), valid.replace("\"version\":2", "\"version\":\"2\""));
    assertThrows(IOException.class, () -> new HomeAssistantToolJournal(directory, IDENTITY, OWNER));
    Files.writeString(directory.resolve("tools.json"), valid);
    try (var journal = new HomeAssistantToolJournal(directory, IDENTITY, OWNER)) {
      assertFalse(journal.confirmed().isPresent());
    }
  }

  @Test
  void sourceCredentialsAndOriginsArePrivateJournalOwnershipInputsNotToolReferences() {
    var binding = new RelayTools.Binding("fixture-project", "fixture-provider", "fixture-account");
    String first =
        HomeAssistantToolProvider.ownerFingerprint(
            "https://plowshare.example.test",
            "https://ha.example.test",
            "fixture-secret-a",
            "policy-a",
            binding);
    String rotated =
        HomeAssistantToolProvider.ownerFingerprint(
            "https://plowshare.example.test",
            "https://ha.example.test",
            "fixture-secret-b",
            "policy-a",
            binding);
    assertFalse(first.equals(rotated));
    assertFalse(first.contains("secret"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HomeAssistantToolProvider.ownerFingerprint(
                "", "https://ha.example.test", "fixture-secret", "policy-a", binding));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HomeAssistantToolProvider.ownerFingerprint(
                "https://person:password@example.test",
                "https://ha.example.test",
                "fixture-secret",
                "policy-a",
                binding));
  }

  @Test
  void acknowledgedUnknownsRetainBoundedFencesAndNeverBecomeCompletedRetirement() throws Exception {
    String generation = UUID.randomUUID().toString();
    try (var journal = new HomeAssistantToolJournal(directory, IDENTITY, OWNER)) {
      for (int index = 1; index <= HomeAssistantToolJournal.MAX_RECEIPTS; index++) {
        var intent = executing(UUID.randomUUID().toString());
        journal.save(intent);
        var call = RelayTools.decodeCall(intent.request());
        var delivery =
            new Delivery(
                intent.invocationId(),
                "tool." + call.provider() + "." + call.tool() + ".request",
                generation,
                Integer.toString(index));
        journal.captureDelivery(delivery);
        journal.acknowledged(delivery);
        var result =
            Optional.of(
                new RelayTools.Result(RelayTools.State.UNKNOWN, "Uncertain retained observation."));
        journal.save(
            new RelayTools.Receipt(
                intent.invocationId(),
                intent.request(),
                RelayTools.Phase.READY,
                result,
                Optional.empty()));
        var occurredAt = Optional.of(Instant.now().toString());
        journal.save(
            new RelayTools.Receipt(
                intent.invocationId(),
                intent.request(),
                RelayTools.Phase.PUBLISHING,
                result,
                occurredAt));
        journal.save(
            new RelayTools.Receipt(
                intent.invocationId(),
                intent.request(),
                RelayTools.Phase.DONE,
                result,
                occurredAt));
      }
      assertEquals(HomeAssistantToolJournal.MAX_RECEIPTS, journal.all().size());
      assertThrows(IOException.class, () -> journal.save(executing(UUID.randomUUID().toString())));
    }
    try (var journal = new HomeAssistantToolJournal(directory, IDENTITY, OWNER)) {
      assertEquals(HomeAssistantToolJournal.MAX_RECEIPTS, journal.all().size());
      assertTrue(
          journal.all().stream()
              .allMatch(value -> value.result().orElseThrow().state() == RelayTools.State.UNKNOWN));
    }
  }
}
