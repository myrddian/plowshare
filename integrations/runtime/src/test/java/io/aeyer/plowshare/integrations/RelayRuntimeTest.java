package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.IntegrationPayload;
import io.aeyer.plowshare.protocol.IntegrationPayload.Arguments;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayRuntimeTest {
  @TempDir Path directory;
  private static final Instant NOW = Instant.parse("2026-11-13T00:00:00.123456789Z");

  private Configuration.Binding binding(String name) {
    return RelayRuntimeFixtures.binding(
        new Configuration.Binding(
            name,
            "fake",
            "home",
            new IntegrationFixtures.Settings(),
            Map.of(),
            Set.of(name),
            null,
            name + "-fingerprint"));
  }

  private Configuration configuration(Configuration.Binding... bindings) {
    var values = new LinkedHashMap<String, Configuration.Binding>();
    for (var binding : bindings) values.put(binding.name(), binding);
    return new Configuration(
        "https://plowshare.example.invalid", "PLOWSHARE_TOKEN", directory, values);
  }

  private IntegrationRuntime runtime(
      Configuration config,
      Journal journal,
      Map<String, IntegrationAdapter> adapters,
      Gateway gateway) {
    return new IntegrationRuntime(
        config, adapters, gateway, journal, new ScriptHost(Duration.ofSeconds(5)), () -> NOW);
  }

  private static String action(String binding) {
    return "{\"schema\":\"plowshare-integration/1\",\"binding\":\""
        + binding
        + "\",\"operation\":\"actions.execute\",\"arguments\":{\"action\":\"notify\",\"parameters\":{}}}";
  }

  @Test
  void capture_and_execution_intent_precede_io_and_lost_result_is_read_only_reconciled()
      throws Exception {
    var gateway = new RelayRuntimeFixtures.Gateway();
    gateway.request("house", action("house"));
    gateway.losePublish = true;
    var binding = binding("house");
    var config = configuration(binding);
    var adapter = new IntegrationRuntimeTest.FakeAdapter();
    try (var journal = new Journal(directory);
        var runtime = runtime(config, journal, Map.of("house", adapter), gateway)) {
      runtime.start();
      runtime.tick();
      assertEquals(1, adapter.actions);
      assertEquals("1", journal.relayCheckpoint("house").orElseThrow().position());
      var record = journal.entries().getFirst().getValue();
      assertEquals("REPORTING", record.status());
      assertEquals(123456000, record.relay().publication().occurredAt().getNano());
      assertEquals(null, record.relay().publication().parentTopic());
      assertFalse(runtime.relayAttention().isEmpty());
    }
    try (var journal = new Journal(directory);
        var runtime = runtime(config, journal, Map.of("house", adapter), gateway)) {
      runtime.start();
      runtime.tick();
      runtime.tick();
      assertEquals("DONE", journal.entries().getFirst().getValue().status());
      assertEquals(1, adapter.actions);
      assertEquals(1, gateway.publications);
      assertTrue(runtime.relayAttention().isEmpty());
    }
  }

  @Test
  void retained_capture_survives_source_expiry_and_interrupted_action_is_never_repeated()
      throws Exception {
    var gateway = new RelayRuntimeFixtures.Gateway();
    var event = gateway.request("house", action("house"));
    var b = binding("house");
    var source =
        new RelayRequests.Source(
            "home",
            b.relay().requests(),
            b.relay().requestGeneration(),
            UUID.fromString(event.eventId()),
            "1",
            event.publisher(),
            event.occurredAt(),
            event.payload().text(),
            event.causation());
    var draft = new IntegrationContracts.Draft();
    draft.kind = "relay";
    draft.binding = "house";
    draft.fingerprint = b.fingerprint();
    draft.status = "DISPATCHED";
    draft.relay =
        new RelayRequests.Delivery(
            source,
            IntegrationCodec.read(source.text(), IntegrationPayload.Request.class),
            null,
            null);
    var adapter = new IntegrationRuntimeTest.FakeAdapter();
    try (var journal = new Journal(directory)) {
      journal.captureRelay(
          "house",
          null,
          new RelayRequests.Checkpoint(b.fingerprint(), "1"),
          Map.of("relay:fixture", draft.build()));
    }
    gateway.events.remove(b.relay().requests());
    try (var journal = new Journal(directory);
        var runtime = runtime(configuration(b), journal, Map.of("house", adapter), gateway)) {
      runtime.start();
      runtime.tick();
      var record = journal.entries().getFirst().getValue();
      assertEquals("UNKNOWN", record.state());
      assertEquals("DONE", record.status());
      assertEquals(0, adapter.actions);
      assertEquals(1, gateway.publications);
      assertNotNull(
          Json.parse(record.relay().publication().text()).path("sourceEvent").textValue());
    }
  }

  @Test
  void replaced_topics_and_unconfirmed_binding_do_not_starve_other_bindings() throws Exception {
    var gateway = new RelayRuntimeFixtures.Gateway();
    gateway.request("house", action("house"));
    gateway.request("other", action("other"));
    gateway.generations.put("house.returns", Json.identity("replacement").toString());
    var house = new IntegrationRuntimeTest.FakeAdapter();
    var other = new IntegrationRuntimeTest.FakeAdapter();
    try (var journal = new Journal(directory);
        var runtime =
            runtime(
                configuration(binding("house"), binding("other")),
                journal,
                Map.of("house", house, "other", other),
                gateway)) {
      runtime.start();
      runtime.tick();
      assertEquals(0, house.actions);
      assertEquals(1, other.actions);
      assertEquals(Set.of("house"), runtime.relayAttention().keySet());
      gateway.generations.put("house.requests", Json.identity("replacement-source").toString());
      gateway.generations.remove("house.returns");
      runtime.tick();
      assertEquals(0, house.actions);
      assertEquals(1, other.actions);
    }
  }

  @Test
  void null_unknown_or_exhausted_cause_and_scope_overrides_are_durable_no_effect_refusals()
      throws Exception {
    for (var cause :
        new RelayCausation[] {
          null, RelayCausation.unknown("legacy"), new RelayCausation("root", "parent", 8)
        }) {
      var gateway = new RelayRuntimeFixtures.Gateway();
      var original = gateway.request("house", action("house"));
      gateway.events.put(
          "house.requests",
          List.of(
              new RelayLog.Event(
                  original.position(),
                  original.eventId(),
                  original.publisher(),
                  original.occurredAt(),
                  original.publishedAt(),
                  null,
                  null,
                  original.payload(),
                  cause)));
      var adapter = new IntegrationRuntimeTest.FakeAdapter();
      Path path =
          directory.resolve(
              cause == null ? "missing" : cause.depth() < 0 ? "unknown" : "exhausted");
      try (var journal = new Journal(path);
          var runtime =
              runtime(
                  configuration(binding("house")), journal, Map.of("house", adapter), gateway)) {
        runtime.start();
        runtime.tick();
        assertEquals(0, adapter.actions);
        assertEquals("REJECTED", journal.entries().getFirst().getValue().state());
        assertEquals("DONE", journal.entries().getFirst().getValue().status());
      }
    }
    var gateway = new RelayRuntimeFixtures.Gateway();
    gateway.request("house", action("other"));
    var adapter = new IntegrationRuntimeTest.FakeAdapter();
    try (var journal = new Journal(directory.resolve("scope"));
        var runtime =
            runtime(configuration(binding("house")), journal, Map.of("house", adapter), gateway)) {
      runtime.start();
      runtime.tick();
      assertEquals(0, adapter.actions);
      assertEquals("REJECTED", journal.entries().getFirst().getValue().state());
    }
  }

  @Test
  void complete_oversized_evidence_stays_in_journal_and_return_is_explicitly_rejected()
      throws Exception {
    var gateway = new RelayRuntimeFixtures.Gateway();
    gateway.request("house", action("house"));
    var adapter =
        new IntegrationRuntimeTest.FakeAdapter() {
          @Override
          public Result execute(String operation, Arguments arguments) {
            actions++;
            return Result.diagnostic("COMPLETED", "漢".repeat(30000));
          }
        };
    try (var journal = new Journal(directory);
        var runtime =
            runtime(configuration(binding("house")), journal, Map.of("house", adapter), gateway)) {
      runtime.start();
      runtime.tick();
      var record = journal.entries().getFirst().getValue();
      assertTrue(record.result().diagnostic().getBytes(StandardCharsets.UTF_8).length > 65536);
      var body =
          Json.parse(Json.parse(record.relay().publication().text()).path("body").textValue());
      assertEquals("REJECTED", body.path("state").textValue());
      assertEquals("COMPLETED", body.path("effectState").textValue());
      assertTrue(body.path("refusal").textValue().contains("RESULT_TOO_LARGE"));
      assertEquals(1, adapter.actions);
    }
  }

  @Test
  void interrupted_return_search_resumes_durable_progress_beyond_one_pass_without_republishing()
      throws Exception {
    class InterruptedGateway extends RelayRuntimeFixtures.Gateway {
      int failAt = -1;
      int calls;
      final List<RelayLog.Query> searches = new ArrayList<>();

      @Override
      public RelayLog.Page log(RelayLog.Query query) throws IOException {
        if (query.topic().equals("house.returns") && query.limit() == 100) {
          searches.add(query);
          if (++calls == failAt) throw new IOException("Interrupted read-only search");
        }
        return super.log(query);
      }
    }
    var gateway = new InterruptedGateway();
    gateway.request("house", action("house"));
    gateway.losePublish = true;
    var adapter = new IntegrationRuntimeTest.FakeAdapter();
    var config = configuration(binding("house"));
    try (var journal = new Journal(directory);
        var runtime = runtime(config, journal, Map.of("house", adapter), gateway)) {
      runtime.start();
      runtime.tick();
      assertEquals(1, adapter.actions);
      assertEquals("REPORTING", journal.entries().getFirst().getValue().status());
    }
    var expected = gateway.events.get("house.returns").getFirst();
    List<RelayLog.Event> busy = new ArrayList<>();
    for (int index = 1; index <= 3600; index++)
      busy.add(
          new RelayLog.Event(
              Integer.toString(index),
              Json.identity("noise-" + index).toString(),
              RelayRuntimeFixtures.PUBLISHER,
              NOW,
              NOW,
              null,
              null,
              new RelayLog.Payload("TEXT", "Unrelated retained result", null, null, null)));
    busy.add(
        new RelayLog.Event(
            "3601",
            expected.eventId(),
            expected.publisher(),
            expected.occurredAt(),
            expected.publishedAt(),
            expected.correlationId(),
            expected.causationId(),
            expected.payload(),
            expected.causation()));
    gateway.events.put("house.returns", busy);
    gateway.failAt = 3;
    try (var journal = new Journal(directory);
        var runtime = runtime(config, journal, Map.of("house", adapter), gateway)) {
      runtime.start();
      runtime.tick();
      assertEquals("200", journal.entries().getFirst().getValue().relay().returnAfter());
      assertFalse(runtime.relayAttention().isEmpty());
    }
    gateway.failAt = -1;
    gateway.searches.clear();
    try (var journal = new Journal(directory);
        var runtime = runtime(config, journal, Map.of("house", adapter), gateway)) {
      runtime.start();
      runtime.tick();
      assertEquals("200", gateway.searches.getFirst().after());
      assertEquals("3400", journal.entries().getFirst().getValue().relay().returnAfter());
      assertEquals("REPORTING", journal.entries().getFirst().getValue().status());
      runtime.tick();
      assertEquals("DONE", journal.entries().getFirst().getValue().status());
      assertEquals(1, gateway.publications);
      assertEquals(1, adapter.actions);
      assertTrue(runtime.relayAttention().isEmpty());
    }
  }

  @Test
  void malformed_persisted_relay_phases_refuse_reopen_before_adapter_or_gateway_activity()
      throws Exception {
    var gateway = new RelayRuntimeFixtures.Gateway();
    var event = gateway.request("house", action("house"));
    var binding = binding("house");
    var source =
        new RelayRequests.Source(
            "home",
            binding.relay().requests(),
            binding.relay().requestGeneration(),
            UUID.fromString(event.eventId()),
            event.position(),
            event.publisher(),
            event.occurredAt(),
            event.payload().text(),
            event.causation());
    var command = IntegrationCodec.read(source.text(), IntegrationPayload.Request.class);
    var draft = new IntegrationContracts.Draft();
    draft.kind = "relay";
    draft.binding = "house";
    draft.fingerprint = binding.fingerprint();
    draft.status = "QUEUED";
    draft.relay = new RelayRequests.Delivery(source, command, null, null);
    var queued = (ObjectNode) IntegrationCodec.tree(draft.build());
    var ready = queued.deepCopy();
    ready.put("state", "COMPLETED");
    ready.set(
        "result",
        IntegrationCodec.tree(
            IntegrationAdapter.Result.diagnostic("COMPLETED", "Retained full result").data()));
    UUID mutation = UUID.randomUUID();
    var publication =
        new RelayPort.Publish(
            mutation.toString(),
            "home",
            binding.relay().returns(),
            "{\"schema\":\"plowshare-message-return/1\",\"requestId\":\""
                + mutation
                + "\",\"sourceEvent\":\""
                + source.event()
                + "\",\"body\":\"Retained result\",\"finalReply\":true}",
            NOW,
            source.event().toString(),
            null,
            null);
    var reporting = ready.deepCopy();
    reporting.put("status", "REPORTING");
    reporting.set(
        "relay",
        IntegrationCodec.tree(new RelayRequests.Delivery(source, command, publication, "0")));
    Map<String, ObjectNode> invalid = new LinkedHashMap<>();
    invalid.put("reporting-without-publication", ready.deepCopy().put("status", "REPORTING"));
    invalid.put("done-without-publication", ready.deepCopy().put("status", "DONE"));
    invalid.put("state-without-result", queued.deepCopy().put("state", "COMPLETED"));
    var missingState = ready.deepCopy();
    missingState.remove("state");
    invalid.put("result-without-state", missingState);
    invalid.put("dispatched-with-result", ready.deepCopy().put("status", "DISPATCHED"));
    invalid.put("queued-with-publication", reporting.deepCopy().put("status", "QUEUED"));
    invalid.put("dispatched-with-publication", reporting.deepCopy().put("status", "DISPATCHED"));
    var missingResult = reporting.deepCopy();
    missingResult.remove(List.of("state", "result"));
    invalid.put("reporting-without-outcome", missingResult);
    invalid.put("done-without-outcome", missingResult.deepCopy().put("status", "DONE"));
    var invalidSource =
        new RelayRequests.Source(
            source.project(),
            source.topic(),
            source.generation(),
            source.event(),
            source.position(),
            source.publisher(),
            source.occurredAt(),
            action("other"),
            source.causation());
    var missingRequest = queued.deepCopy();
    missingRequest.set(
        "relay",
        IntegrationCodec.tree(new RelayRequests.Delivery(invalidSource, null, null, null)));
    invalid.put(
        "dispatched-without-request", missingRequest.deepCopy().put("status", "DISPATCHED"));
    var impossibleSuccess = ready.deepCopy();
    impossibleSuccess.set(
        "relay",
        IntegrationCodec.tree(new RelayRequests.Delivery(invalidSource, null, null, null)));
    invalid.put("success-without-request", impossibleSuccess);
    for (String status : List.of("FAILED", "REJECTED", "UNKNOWN"))
      invalid.put("unsupported-" + status, queued.deepCopy().put("status", status));
    var changedText = queued.deepCopy();
    ((ObjectNode) changedText.path("relay").path("source")).put("text", action("other"));
    invalid.put("source-text-disagrees-with-command", changedText);
    var changedRequest = queued.deepCopy();
    ((ObjectNode) changedRequest.path("relay").path("request").path("arguments"))
        .put("action", "forged");
    invalid.put("command-disagrees-with-source-text", changedRequest);
    var changedPublicationProject = reporting.deepCopy();
    ((ObjectNode) changedPublicationProject.path("relay").path("publication"))
        .put("project", "foreign");
    invalid.put("publication-project-disagrees-with-source", changedPublicationProject);
    for (var entry : invalid.entrySet()) {
      Path path = directory.resolve(entry.getKey());
      try (var journal = new Journal(path)) {
        // Deliberately corrupt persistence at its raw fixture boundary. Startup must decode the
        // entire inventory before any valid pending entry, connection or adapter can act.
        journal.write("relay:malformed", entry.getValue());
      }
      class ObservedGateway extends RelayRuntimeFixtures.Gateway {
        int network;

        @Override
        public RelayLog.Page log(RelayLog.Query query) throws IOException {
          network++;
          return super.log(query);
        }

        @Override
        public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
          network++;
          return super.publish(request);
        }
      }
      var observed = new ObservedGateway();
      var adapter = new IntegrationRuntimeTest.FakeAdapter();
      try (var journal = new Journal(path);
          var runtime =
              runtime(configuration(binding), journal, Map.of("house", adapter), observed)) {
        assertThrows(IOException.class, runtime::start, entry.getKey());
        assertNull(adapter.listener, entry.getKey());
        assertEquals(0, adapter.actions, entry.getKey());
        assertEquals(0, observed.network, entry.getKey());
      }
    }
    Map<String, ObjectNode> foreign = new LinkedHashMap<>();
    for (var coordinate :
        Map.of(
                "project",
                "foreign",
                "topic",
                "foreign.requests",
                "generation",
                UUID.randomUUID().toString(),
                "publisher",
                RelayPort.publisher("foreign"))
            .entrySet()) {
      var record = queued.deepCopy();
      ((ObjectNode) record.path("relay").path("source"))
          .put(coordinate.getKey(), coordinate.getValue());
      foreign.put("foreign-source-" + coordinate.getKey(), record);
    }
    var changedPublicationTopic = reporting.deepCopy();
    ((ObjectNode) changedPublicationTopic.path("relay").path("publication"))
        .put("topic", "foreign.returns");
    foreign.put("foreign-publication-topic", changedPublicationTopic);
    for (var entry : foreign.entrySet()) {
      Path path = directory.resolve(entry.getKey());
      try (var journal = new Journal(path)) {
        journal.write("relay:foreign", entry.getValue());
      }
      int[] network = {0};
      var observed =
          new RelayRuntimeFixtures.Gateway() {
            @Override
            public RelayLog.Page log(RelayLog.Query query) throws IOException {
              network[0]++;
              return super.log(query);
            }

            @Override
            public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
              network[0]++;
              return super.publish(request);
            }
          };
      var adapter = new IntegrationRuntimeTest.FakeAdapter();
      try (var journal = new Journal(path);
          var runtime =
              runtime(configuration(binding), journal, Map.of("house", adapter), observed)) {
        runtime.start();
        runtime.tick();
        assertEquals(Set.of("house"), runtime.relayAttention().keySet(), entry.getKey());
        assertEquals(0, network[0], entry.getKey());
        assertEquals(0, adapter.actions, entry.getKey());
        assertEquals(0, adapter.reads, entry.getKey());
      }
    }
    // Valid interrupted states stay recoverable. A retained outcome must be published without
    // another action; an invalid pre-effect request must become a safe refusal without execution.
    var unknown = ready.deepCopy().put("state", "UNKNOWN");
    unknown.set(
        "result",
        IntegrationCodec.tree(
            IntegrationAdapter.Result.diagnostic("UNKNOWN", "Retained uncertainty").data()));
    for (var entry :
        Map.of("ready", ready, "unknown-ready", unknown, "unvalidated", missingRequest)
            .entrySet()) {
      Path path = directory.resolve(entry.getKey());
      var observed = new RelayRuntimeFixtures.Gateway();
      var adapter = new IntegrationRuntimeTest.FakeAdapter();
      try (var journal = new Journal(path)) {
        journal.write("relay:recoverable", entry.getValue());
      }
      try (var journal = new Journal(path);
          var runtime =
              runtime(configuration(binding), journal, Map.of("house", adapter), observed)) {
        runtime.start();
        runtime.tick();
        var recovered = journal.entries().getFirst().getValue();
        assertEquals("DONE", recovered.status());
        assertEquals(
            entry.getKey().equals("unvalidated")
                ? "REJECTED"
                : entry.getValue().path("state").asText(),
            recovered.state());
        assertEquals(0, adapter.actions);
        assertEquals(1, observed.publications);
      }
    }
  }

  @Test
  void decimal_parameters_survive_capture_and_reopen_without_rounding_source_changes_or_replay()
      throws Exception {
    int index = 0;
    for (String literal : List.of("22.0", "1234567890.123456789012345678")) {
      Path path = directory.resolve("decimal-" + index++);
      String text =
          action("house")
              .replace("\"parameters\":{}", "\"parameters\":{\"level\":" + literal + "}");
      var expected = new BigDecimal(literal);
      List<BigDecimal> observed = new ArrayList<>();
      var adapter =
          new IntegrationRuntimeTest.FakeAdapter() {
            @Override
            public Result execute(String operation, Arguments arguments) {
              if (!(arguments.parameters().get("level") instanceof NumberParameter number))
                throw new AssertionError("Expected a typed decimal parameter");
              observed.add(number.value());
              return super.execute(operation, arguments);
            }
          };
      var gateway = new RelayRuntimeFixtures.Gateway();
      gateway.request("house", text);
      gateway.losePublish = true;
      var config = configuration(binding("house"));
      try (var journal = new Journal(path);
          var runtime = runtime(config, journal, Map.of("house", adapter), gateway)) {
        runtime.start();
        runtime.tick();
        assertEquals(1, adapter.actions);
        assertEquals(0, expected.compareTo(observed.getFirst()));
        assertEquals("REPORTING", journal.entries().getFirst().getValue().status());
      }
      try (var journal = new Journal(path);
          var runtime = runtime(config, journal, Map.of("house", adapter), gateway)) {
        runtime.start();
        runtime.tick();
        var retained = journal.entries().getFirst().getValue();
        assertEquals("DONE", retained.status());
        assertEquals(text, retained.relay().source().text());
        var parameter = retained.relay().request().arguments().parameters().get("level");
        assertTrue(parameter instanceof NumberParameter);
        assertEquals(0, expected.compareTo(((NumberParameter) parameter).value()));
        assertEquals(1, observed.size());
        assertEquals(1, adapter.actions);
        assertEquals(1, gateway.publications);
        var corrupted = (ObjectNode) IntegrationCodec.tree(retained);
        ((ObjectNode) corrupted.path("relay").path("request").path("arguments").path("parameters"))
            .put("level", expected.add(BigDecimal.ONE));
        Path changed = directory.resolve("changed-decimal-" + index);
        try (var invalid = new Journal(changed)) {
          invalid.write("relay:changed-number", corrupted);
        }
        var untouched = new IntegrationRuntimeTest.FakeAdapter();
        var unused = new RelayRuntimeFixtures.Gateway();
        try (var invalid = new Journal(changed);
            var refused = runtime(config, invalid, Map.of("house", untouched), unused)) {
          assertThrows(IOException.class, refused::start);
          assertNull(untouched.listener);
          assertEquals(0, untouched.actions);
          assertEquals(0, unused.publications);
        }
      }
    }
  }
}
