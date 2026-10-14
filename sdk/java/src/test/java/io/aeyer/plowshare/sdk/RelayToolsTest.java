package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RelayToolsTest {
  static final String ID = "11111111-1111-1111-1111-111111111111";
  static final RelayTools.Binding BINDING =
      new RelayTools.Binding("fixture", "scanner", "provider");
  static final RelayTools.Declaration DECLARATION =
      new RelayTools.Declaration(
          "inspect",
          "Inspect fixture",
          List.of(new RelayTools.Parameter("value", RelayTools.Type.NUMBER, "", true)),
          30);

  static String request() throws IOException {
    return SdkJson.mapper()
        .readTree(Files.readString(Path.of("../../test-support/contracts/relay-tools.json")))
        .path("cases")
        .get(0)
        .path("source")
        .textValue();
  }

  @Test
  void shared_boundaries_and_result_identity() throws Exception {
    var fixture =
        SdkJson.mapper()
            .readTree(Files.readString(Path.of("../../test-support/contracts/relay-tools.json")));
    assertEquals(fixture.path("resultRequestId").textValue(), RelayTools.resultRequestId(ID));
    for (var sample : fixture.path("cases")) {
      boolean accepted = true;
      try {
        DECLARATION.validate(RelayTools.decodeCall(sample.path("source").textValue()).arguments());
      } catch (IllegalArgumentException invalid) {
        accepted = false;
      }
      assertEquals(sample.path("valid").booleanValue(), accepted, sample.path("name").textValue());
    }
  }

  @Test
  void duplicate_delivery_and_lost_publication_never_repeat_handler_or_result() throws Exception {
    var journal = new MemoryJournal();
    var wire = new FixturePorts(request());
    var executions = new AtomicInteger();
    var tools =
        List.of(
            new RelayTools.RegisteredTool(
                DECLARATION,
                call -> {
                  executions.incrementAndGet();
                  return CompletableFuture.completedFuture(
                      new RelayTools.Result(RelayTools.State.COMPLETED, "observed"));
                }));
    var provider = new RelayTools.Provider(wire, BINDING, tools, journal);
    wire.loseReply = true;
    assertThrows(IOException.class, provider::poll);
    assertEquals(RelayTools.Phase.PUBLISHING, journal.all().getFirst().phase());
    var restarted = new RelayTools.Provider(wire, BINDING, tools, journal);
    assertThrows(IOException.class, restarted::poll);
    assertEquals(1, restarted.reconcile());
    assertEquals(1, restarted.poll());
    assertEquals(1, executions.get());
    assertEquals(1, wire.publications);
    assertEquals(RelayTools.Phase.DONE, journal.all().getFirst().phase());
  }

  @Test
  void interrupted_execution_intent_is_unknown_and_never_runs_again() throws Exception {
    var journal = new MemoryJournal();
    journal.save(
        new RelayTools.Receipt(
            ID, request(), RelayTools.Phase.EXECUTING, Optional.empty(), Optional.empty()));
    var executions = new AtomicInteger();
    var wire = new FixturePorts(request());
    var provider =
        new RelayTools.Provider(
            wire,
            BINDING,
            List.of(
                new RelayTools.RegisteredTool(
                    DECLARATION,
                    call -> {
                      executions.incrementAndGet();
                      return CompletableFuture.completedFuture(
                          new RelayTools.Result(RelayTools.State.COMPLETED, "unexpected"));
                    })),
            journal);
    assertEquals(1, provider.poll());
    assertEquals(0, executions.get());
    assertEquals(RelayTools.State.UNKNOWN, journal.all().getFirst().result().orElseThrow().state());
    assertEquals(1, wire.publications);
  }

  @Test
  void catalogue_publish_withdraw_and_lost_reply_do_not_run_or_replay_handlers() throws Exception {
    var large =
        new RelayTools.Declaration(
            DECLARATION.name(),
            DECLARATION.description(),
            java.util.stream.IntStream.range(0, 24)
                .mapToObj(
                    i ->
                        new RelayTools.Parameter(
                            "field_" + i, RelayTools.Type.STRING, "d".repeat(4096), false))
                .toList(),
            30);
    var wire = new FixturePorts(request());
    var calls = new AtomicInteger();
    var provider =
        new RelayTools.Provider(
            wire,
            BINDING,
            List.of(
                new RelayTools.RegisteredTool(
                    large,
                    call -> {
                      calls.incrementAndGet();
                      return CompletableFuture.completedFuture(
                          new RelayTools.Result(RelayTools.State.COMPLETED, "unexpected"));
                    })),
            new MemoryJournal(large));
    provider.publishCatalog(ID);
    assertTrue(wire.published.text().length() > 65536);
    assertEquals("tool.scanner.catalog", wire.published.topic());
    assertNull(wire.published.parentTopic());
    assertEquals(
        "plowshare-tool-catalog/1",
        SdkJson.mapper().readTree(wire.published.text()).path("version").asText());
    assertEquals(1, SdkJson.mapper().readTree(wire.published.text()).path("tools").size());
    provider.withdrawCatalog("22222222-2222-2222-2222-222222222222");
    assertEquals(0, SdkJson.mapper().readTree(wire.published.text()).path("tools").size());
    wire.loseReply = true;
    assertThrows(
        IOException.class, () -> provider.publishCatalog("33333333-3333-3333-3333-333333333333"));
    assertEquals(3, wire.publications);
    assertEquals(0, calls.get());
    assertThrows(IllegalArgumentException.class, () -> provider.publishCatalog("invalid"));
    assertEquals(3, wire.publications);
  }

  @Test
  void nested_handler_receives_exact_values_and_changed_schema_never_executes() throws Exception {
    for (String variant : List.of("canonical", "legacy", "legacy-interrupted")) {
      nestedHandler(variant);
    }
  }

  private void nestedHandler(String variant) throws Exception {
    var json = SdkJson.mapper();
    var fixtures =
        json.readTree(Files.readString(Path.of("../../test-support/contracts/tool-schemas.json")))
            .path("cases");
    var fixture =
        java.util.stream.StreamSupport.stream(fixtures.spliterator(), false)
            .filter(row -> "prefix-provider".equals(row.path("name").textValue()))
            .findFirst()
            .orElseThrow();
    var schema =
        io.aeyer.plowshare.protocol.SchemaCodec.decode(
            json.readValue(fixture.path("schemaSource").textValue(), Map.class));
    var declaration = new RelayTools.Declaration("inspect", "Inspect nested fixture", schema, 30);
    var request = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(request());
    request.put("schema", "plowshare-tool/2");
    request.put("deadline", "2099-01-01T00:00:00Z");
    request.put(
        "schemaFingerprint",
        fixture
            .path(variant.equals("canonical") ? "fingerprint" : "legacyFingerprint")
            .textValue());
    request.set("arguments", json.readTree(fixture.path("argumentsSource").textValue()));
    var executions = new AtomicInteger();
    var tool =
        new RelayTools.RegisteredTool(
            declaration,
            call -> {
              executions.incrementAndGet();
              assertEquals(
                  json.readTree(fixture.path("argumentsSource").textValue()),
                  json.readTree(json.writeValueAsString(call.arguments())));
              return CompletableFuture.completedFuture(
                  new RelayTools.Result(RelayTools.State.COMPLETED, "nested observed"));
            });
    var wire = new FixturePorts(json.writeValueAsString(request));
    var journal = new MemoryJournal(declaration);
    if (variant.equals("legacy-interrupted"))
      journal.save(
          new RelayTools.Receipt(
              ID,
              json.writeValueAsString(request),
              RelayTools.Phase.EXECUTING,
              Optional.empty(),
              Optional.empty()));
    int expected = variant.equals("legacy-interrupted") ? 0 : 1;
    var provider = new RelayTools.Provider(wire, BINDING, List.of(tool), journal);
    provider.publishCatalog(ID);
    assertEquals(
        "plowshare-tool-catalog/2",
        json.readTree(wire.published.text()).path("version").textValue());
    assertEquals(1, provider.poll());
    assertEquals(1, provider.poll());
    assertEquals(expected, executions.get());
    assertEquals(json.writeValueAsString(request), journal.all().getFirst().request());
    if (variant.equals("legacy-interrupted"))
      assertEquals(
          RelayTools.State.UNKNOWN, journal.all().getFirst().result().orElseThrow().state());
    // Changing any annotation must refuse even the historical hash.
    var changedFields = new LinkedHashMap<>(io.aeyer.plowshare.protocol.SchemaCodec.wire(schema));
    changedFields.put("description", "changed");
    var changedSchema = io.aeyer.plowshare.protocol.SchemaCodec.decode(changedFields);
    var changedDeclaration =
        new RelayTools.Declaration("inspect", "Inspect nested fixture", changedSchema, 30);
    var changedTool = new RelayTools.RegisteredTool(changedDeclaration, tool.handler());
    var changed =
        new RelayTools.Provider(
            new FixturePorts(json.writeValueAsString(request)),
            BINDING,
            List.of(changedTool),
            new MemoryJournal(changedDeclaration));
    assertThrows(IllegalArgumentException.class, changed::poll);
    assertEquals(expected, executions.get());
  }

  static final class MemoryJournal implements RelayTools.Journal {
    final RelayTools.Declaration declaration;

    MemoryJournal() {
      this(DECLARATION);
    }

    MemoryJournal(RelayTools.Declaration declaration) {
      this.declaration = declaration;
    }

    final Map<String, RelayTools.Receipt> rows = new LinkedHashMap<>();

    public String identity() {
      return RelayPort.hash(RelayTools.deploymentConfig(BINDING, List.of(declaration)));
    }

    public List<RelayTools.Receipt> all() {
      return List.copyOf(rows.values());
    }

    public void save(RelayTools.Receipt receipt) {
      rows.put(receipt.invocationId(), receipt);
    }
  }

  static final class FixturePorts implements RelayTools.Ports {
    final String source;
    int publications;
    boolean loseReply;
    RelayPort.Publish published;

    FixturePorts(String source) {
      this.source = source;
    }

    public RelayPort.Batch consume(RelayPort.Consume request) {
      var event =
          new RelayLog.Event(
              "1",
              ID,
              "tool-runtime",
              Instant.EPOCH,
              Instant.EPOCH,
              ID,
              "run",
              new RelayLog.Payload("TEXT", source, null, null, null));
      return new RelayPort.Batch(
          request.project(),
          request.topic(),
          request.group(),
          request.consumerId(),
          RelayPort.Status.DATA,
          ID,
          "1",
          "1",
          Instant.now().plusSeconds(30),
          null,
          List.of(event));
    }

    public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) {
      return new RelayPort.Acknowledged(
          request.project(), request.topic(), request.group(), request.batchId(), "1", false);
    }

    public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
      publications++;
      published = request;
      if (loseReply) throw new IOException("Result published but reply lost");
      return new RelayPort.Published(
          request.requestId(), request.project(), request.topic(), "1", Instant.now());
    }

    public RelayLog.Page log(RelayLog.Query query) {
      var event =
          new RelayLog.Event(
              "1",
              published.requestId(),
              RelayPort.publisher(BINDING.account()),
              published.occurredAt(),
              Instant.now(),
              ID,
              ID,
              new RelayLog.Payload("TEXT", published.text(), null, null, null));
      return new RelayLog.Page(
          new RelayLog.Scope(query.project(), false),
          new RelayLog.Topic(query.topic(), "TEXT", "345600", null, "1", "0"),
          query.after(),
          "1",
          null,
          List.of(event),
          List.of(),
          List.of());
    }
  }
}
