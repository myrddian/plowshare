package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Delivery;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.RelayTools;
import io.aeyer.plowshare.sdk.RequestCancellation;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual SDK provider and private file receipts; external transports/effects are deterministic
 * fakes.
 */
class HomeAssistantActionProviderTest {
  @TempDir Path directory;

  static final class Wire implements HomeAssistantToolTransport {
    RelayTools.Call offered;
    boolean loseBrokerAck;
    boolean loseResultAck;
    int resultWrites;
    final Map<String, List<RelayLog.Event>> events = new HashMap<>();

    @Override
    public RelayPort.Batch consume(
        RelayPort.Consume request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      budget.remaining();
      if (offered == null
          || !request
              .topic()
              .equals("tool." + offered.provider() + "." + offered.tool() + ".request"))
        return new RelayPort.Batch(
            request.project(),
            request.topic(),
            request.group(),
            request.consumerId(),
            RelayPort.Status.EMPTY,
            null,
            null,
            "0",
            null,
            null,
            List.of());
      var event =
          new RelayLog.Event(
              "1",
              offered.invocationId(),
              "tool-runtime",
              Instant.now(),
              Instant.now(),
              offered.invocationId(),
              "fixture-source",
              new RelayLog.Payload(
                  "TEXT", Json.MAPPER.writeValueAsString(offered), null, null, null));
      return new RelayPort.Batch(
          request.project(),
          request.topic(),
          request.group(),
          request.consumerId(),
          RelayPort.Status.DATA,
          offered.invocationId(),
          "1",
          "1",
          Instant.now().plusSeconds(30),
          null,
          List.of(event));
    }

    @Override
    public RelayPort.Acknowledged acknowledge(
        RelayPort.Ack request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      if (loseBrokerAck) throw new IOException("Fixture broker acknowledgement lost");
      return new RelayPort.Acknowledged(
          request.project(), request.topic(), request.group(), request.batchId(), "1", false);
    }

    @Override
    public RelayPort.Published publish(
        RelayPort.Publish request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      var retained = events.computeIfAbsent(request.topic(), ignored -> new ArrayList<>());
      String position = Integer.toString(retained.size() + 1);
      Instant publishedAt = Instant.now();
      retained.add(
          new RelayLog.Event(
              position,
              request.requestId(),
              RelayPort.publisher(HomeAssistantActionToolsTest.CONTROL.binding().account()),
              request.occurredAt(),
              publishedAt,
              request.correlationId(),
              request.parentEventId(),
              new RelayLog.Payload("TEXT", request.text(), null, null, null)));
      if (!request.topic().endsWith(".catalog")) {
        resultWrites++;
        if (loseResultAck)
          throw new IOException("Fixture result acknowledgement lost after publication");
      }
      return new RelayPort.Published(
          request.requestId(), request.project(), request.topic(), position, publishedAt);
    }

    @Override
    public RelayLog.Page log(
        RelayLog.Query request, OperationBudget budget, RequestCancellation cancellation) {
      var retained = events.getOrDefault(request.topic(), List.of());
      var selected =
          retained.stream()
              .filter(event -> Long.parseLong(event.position()) > Long.parseLong(request.after()))
              .toList();
      return new RelayLog.Page(
          new RelayLog.Scope(request.project(), false),
          new RelayLog.Topic(
              request.topic(), "TEXT", "300", null, Integer.toString(retained.size()), "0"),
          request.after(),
          selected.isEmpty() ? request.after() : selected.getLast().position(),
          null,
          selected,
          List.of(),
          List.of());
    }
  }

  private HomeAssistantToolJournal journal(HomeAssistantActionTools tools) throws IOException {
    return new HomeAssistantToolJournal(
        directory,
        HomeAssistantToolProvider.journalIdentity(HomeAssistantActionToolsTest.CONTROL, tools),
        HomeAssistantToolProvider.journalOwner(
            HomeAssistantActionToolsTest.CONTROL, RelayPort.hash("fixture-control-owner")));
  }

  private HomeAssistantToolProvider host(
      HomeAssistantActionTools tools, Wire wire, HomeAssistantToolJournal journal)
      throws IOException {
    return new HomeAssistantToolProvider(
        HomeAssistantActionToolsTest.CONTROL, tools, wire, journal, Instant::now);
  }

  @Test
  void persistedFullIntentPrecedesOneFakeEffectAndDuplicateRestartDoesNotReplay() throws Exception {
    var executor = new HomeAssistantActionToolsTest.Executor();
    var tools =
        HomeAssistantActionToolsTest.tools(
            new HomeAssistantActionToolsTest.Discovery(),
            HomeAssistantActionToolsTest.policy(),
            executor);
    var wire = new Wire();
    wire.offered =
        HomeAssistantActionToolsTest.call(
            tools, "execute_action", HomeAssistantActionToolsTest.input());
    var firstJournal = journal(tools);
    executor.queued =
        () -> {
          try {
            var receipt = firstJournal.all().getFirst();
            assertEquals(RelayTools.Phase.EXECUTING, receipt.phase());
            var retained = RelayTools.decodeCall(receipt.request());
            assertEquals(wire.offered.invocationId(), retained.invocationId());
            assertEquals(
                HomeAssistantActionToolsTest.input().get("service"),
                retained.arguments().get("service"));
            assertEquals(
                HomeAssistantActionToolsTest.input().get("targets"),
                retained.arguments().get("targets"));
          } catch (IOException failure) {
            throw new AssertionError(failure);
          }
        };
    try (var host = host(tools, wire, firstJournal)) {
      host.renewCatalogue();
      assertEquals(1, host.poll());
      assertEquals(1, executor.effects);
      host.poll();
      assertEquals(1, executor.effects);
    }
    executor.queued =
        () -> {
          throw new AssertionError("A completed original invocation was replayed");
        };
    try (var host = host(tools, wire, journal(tools))) {
      host.poll();
      assertEquals(1, executor.effects);
      assertEquals(1, wire.resultWrites);
    }
  }

  @Test
  void unknownNativeOutcomeSurvivesRestartAndIsNeverRetiredOrReexecuted() throws Exception {
    var executor = new HomeAssistantActionToolsTest.Executor();
    executor.delivery = Delivery.UNKNOWN;
    var tools =
        HomeAssistantActionToolsTest.tools(
            new HomeAssistantActionToolsTest.Discovery(),
            HomeAssistantActionToolsTest.policy(),
            executor);
    var wire = new Wire();
    wire.offered =
        HomeAssistantActionToolsTest.call(
            tools, "execute_action", HomeAssistantActionToolsTest.input());
    try (var host = host(tools, wire, journal(tools))) {
      host.renewCatalogue();
      host.poll();
      assertEquals(1, executor.effects);
    }
    var retained = journal(tools);
    try (var host = host(tools, wire, retained)) {
      host.poll();
      assertEquals(1, executor.effects);
      assertEquals(
          RelayTools.State.UNKNOWN, retained.all().getFirst().result().orElseThrow().state());
      assertEquals(wire.offered.invocationId(), retained.all().getFirst().invocationId());
    }
  }

  @Test
  void lostBrokerAcknowledgementRetainsIntentWithoutEverCallingTheActuator() throws Exception {
    var executor = new HomeAssistantActionToolsTest.Executor();
    var tools =
        HomeAssistantActionToolsTest.tools(
            new HomeAssistantActionToolsTest.Discovery(),
            HomeAssistantActionToolsTest.policy(),
            executor);
    var wire = new Wire();
    wire.offered =
        HomeAssistantActionToolsTest.call(
            tools, "execute_action", HomeAssistantActionToolsTest.input());
    wire.loseBrokerAck = true;
    try (var host = host(tools, wire, journal(tools))) {
      host.renewCatalogue();
      assertThrows(IOException.class, host::poll);
      assertEquals(0, executor.effects);
    }
    wire.loseBrokerAck = false;
    var retained = journal(tools);
    try (var host = host(tools, wire, retained)) {
      host.poll();
      assertEquals(0, executor.effects);
      assertEquals(
          RelayTools.State.UNKNOWN, retained.all().getFirst().result().orElseThrow().state());
    }
  }

  @Test
  void lostResultAcknowledgementReconcilesOriginalPublicationWithoutRepeatingEffect()
      throws Exception {
    var executor = new HomeAssistantActionToolsTest.Executor();
    var tools =
        HomeAssistantActionToolsTest.tools(
            new HomeAssistantActionToolsTest.Discovery(),
            HomeAssistantActionToolsTest.policy(),
            executor);
    var wire = new Wire();
    wire.offered =
        HomeAssistantActionToolsTest.call(
            tools, "execute_action", HomeAssistantActionToolsTest.input());
    wire.loseResultAck = true;
    try (var host = host(tools, wire, journal(tools))) {
      host.renewCatalogue();
      assertThrows(IOException.class, host::poll);
      assertEquals(1, executor.effects);
      assertEquals(1, wire.resultWrites);
    }
    wire.loseResultAck = false;
    try (var host = host(tools, wire, journal(tools))) {
      host.reconcile();
      host.poll();
      assertEquals(1, executor.effects);
      assertEquals(1, wire.resultWrites);
    }
  }
}
