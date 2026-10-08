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

  static final class MemoryJournal implements RelayTools.Journal {
    final Map<String, RelayTools.Receipt> rows = new LinkedHashMap<>();

    public String identity() {
      return RelayPort.hash(RelayTools.deploymentConfig(BINDING, List.of(DECLARATION)));
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
