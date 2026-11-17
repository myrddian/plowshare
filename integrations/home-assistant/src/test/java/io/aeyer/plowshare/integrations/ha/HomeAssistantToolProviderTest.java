package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.CatalogueConfirmed;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.CatalogueIntent;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.Delivery;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.RelayTools;
import io.aeyer.plowshare.sdk.RequestCancellation;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeAssistantToolProviderTest {
  private static final RelayTools.Binding BINDING =
      new RelayTools.Binding("fixture-project", "fixture-provider", "fixture-provider-account");
  private static final String OWNER = RelayPort.hash("fixture-owner-a");
  @TempDir Path directory;

  private static final class Wire implements HomeAssistantToolTransport {
    final AtomicReference<Instant> clock;
    final Map<String, List<RelayLog.Event>> logs = new HashMap<>();
    RelayTools.Call offered;
    int catalogueWrites;
    int resultWrites;
    int consumes;
    boolean loseCatalogueReply;
    boolean loseResultReply;
    boolean forbiddenPublisher;
    String generation;
    String offeredPosition = "1";
    String acknowledgementThrough;
    boolean loseAcknowledgement;
    CountDownLatch entered;
    CountDownLatch release;
    OperationBudget firstBudget;
    RequestCancellation firstCancellation;
    OperationBudget acknowledgeBudget;
    OperationBudget resultBudget;

    Wire(AtomicReference<Instant> clock) {
      this.clock = clock;
    }

    @Override
    public RelayPort.Batch consume(
        RelayPort.Consume request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      consumes++;
      if (firstCancellation != cancellation) {
        firstCancellation = cancellation;
        firstBudget = budget;
      } else assertTrue(firstBudget == budget, "A provider pass must not reset SDK-stage budgets");
      if (entered != null) entered.countDown();
      if (release != null) {
        boolean waiting = true;
        while (waiting) {
          try {
            release.await();
            waiting = false;
          } catch (InterruptedException ignored) {
            /* Simulate an uncooperative physical transport. */
          }
        }
      }
      boolean present =
          offered != null
              && request
                  .topic()
                  .equals("tool." + BINDING.provider() + "." + offered.tool() + ".request");
      if (!present)
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
      String source = Json.MAPPER.writeValueAsString(offered);
      var event =
          new RelayLog.Event(
              offeredPosition,
              offered.invocationId(),
              forbiddenPublisher ? "untrusted" : "tool-runtime",
              clock.get(),
              clock.get(),
              offered.invocationId(),
              "fixture-source",
              new RelayLog.Payload("TEXT", source, null, null, null));
      return new RelayPort.Batch(
          request.project(),
          request.topic(),
          request.group(),
          request.consumerId(),
          RelayPort.Status.DATA,
          offered.invocationId(),
          "1",
          offeredPosition,
          clock.get().plusSeconds(30),
          null,
          List.of(event),
          generation);
    }

    @Override
    public RelayPort.Acknowledged acknowledge(
        RelayPort.Ack request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      acknowledgeBudget = budget;
      if (loseAcknowledgement) throw new IOException("Fixture acknowledgement outcome unknown");
      return new RelayPort.Acknowledged(
          request.project(),
          request.topic(),
          request.group(),
          request.batchId(),
          acknowledgementThrough == null ? offeredPosition : acknowledgementThrough,
          false);
    }

    @Override
    public RelayPort.Published publish(
        RelayPort.Publish request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      List<RelayLog.Event> events =
          logs.computeIfAbsent(request.topic(), ignored -> new ArrayList<>());
      String position = Integer.toString(events.size() + 1);
      events.add(
          new RelayLog.Event(
              position,
              request.requestId(),
              RelayPort.publisher(BINDING.account()),
              request.occurredAt(),
              clock.get(),
              request.correlationId(),
              request.parentEventId(),
              new RelayLog.Payload("TEXT", request.text(), null, null, null)));
      boolean catalogue = request.topic().endsWith(".catalog");
      if (!catalogue) resultBudget = budget;
      if (catalogue) catalogueWrites++;
      else resultWrites++;
      if (catalogue && loseCatalogueReply || !catalogue && loseResultReply)
        throw new IOException("Fixture acknowledgement lost after publication");
      return new RelayPort.Published(
          request.requestId(), request.project(), request.topic(), position, clock.get());
    }

    @Override
    public RelayLog.Page log(
        RelayLog.Query request, OperationBudget budget, RequestCancellation cancellation) {
      List<RelayLog.Event> retained = logs.getOrDefault(request.topic(), List.of());
      List<RelayLog.Event> events =
          retained.stream()
              .filter(value -> Long.parseLong(value.position()) > Long.parseLong(request.after()))
              .toList();
      String through = Integer.toString(retained.size());
      return new RelayLog.Page(
          new RelayLog.Scope(request.project(), false),
          new RelayLog.Topic(request.topic(), "TEXT", "300", null, through, "0"),
          request.after(),
          events.isEmpty() ? request.after() : events.getLast().position(),
          null,
          events,
          List.of(),
          List.of());
    }
  }

  private static HomeAssistantToolProvider.Configuration configuration() {
    return new HomeAssistantToolProvider.Configuration(
        BINDING, HomeAssistantReadToolsTest.PREFIX, 10);
  }

  private static HomeAssistantReadTools tools(HomeAssistantReadToolsTest.Resources resources) {
    return new HomeAssistantReadTools(
        HomeAssistantReadToolsTest.SOURCE, HomeAssistantReadToolsTest.PREFIX, resources, resources);
  }

  /** New normal traffic has a distinct invocation and logical call; duplicate tests keep theirs. */
  private static RelayTools.Call newSearchInvocation(HomeAssistantReadTools tools) {
    var template = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    String invocation = UUID.randomUUID().toString();
    return new RelayTools.Call(
        template.schema(),
        invocation,
        template.project(),
        template.provider(),
        template.tool(),
        template.account(),
        template.run(),
        "fixture-call-" + invocation,
        template.deadline(),
        template.arguments(),
        template.schemaFingerprint());
  }

  private HomeAssistantToolJournal journal(
      HomeAssistantToolProvider.Configuration config, HomeAssistantToolHandlers handlers)
      throws IOException {
    return new HomeAssistantToolJournal(
        directory,
        HomeAssistantToolProvider.journalIdentity(config, handlers),
        HomeAssistantToolProvider.journalOwner(config, OWNER));
  }

  @Test
  void explicitLeasePublicationExpiryAndWithdrawalNeverGrantAgentAuthority() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      assertThrows(IOException.class, host::poll);
      assertEquals(0, wire.consumes);
      host.renewCatalogue();
      assertTrue(host.catalogueAvailable());
      assertEquals(0, host.poll());
      assertEquals(4, wire.consumes);
      clock.updateAndGet(value -> value.plusSeconds(11));
      assertFalse(host.catalogueAvailable());
      assertThrows(IOException.class, host::poll);
      host.renewCatalogue();
      assertTrue(host.catalogueAvailable());
      host.withdrawCatalogue();
      assertFalse(host.catalogueAvailable());
      assertThrows(IOException.class, host::poll);
      assertEquals(3, wire.catalogueWrites);
      assertEquals(0, resources.searches);
      var installation = HomeAssistantToolProvider.installation(configuration());
      assertTrue(installation.bindings().isEmpty());
      assertEquals(BINDING.account(), installation.providers().getFirst().account());
      assertEquals("house_", installation.providers().getFirst().prefix());
    }
  }

  @Test
  void uncertainCatalogueIsReconciledByOriginalIdentityWithoutRepublishingAcrossRestart()
      throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    wire.loseCatalogueReply = true;
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      assertThrows(IOException.class, host::renewCatalogue);
      assertFalse(host.catalogueAvailable());
      assertThrows(IOException.class, host::poll);
      assertThrows(IOException.class, host::renewCatalogue);
      assertEquals(1, wire.catalogueWrites);
    }
    wire.loseCatalogueReply = false;
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      assertFalse(host.catalogueAvailable());
      assertEquals(1, host.reconcile());
      assertTrue(host.catalogueAvailable());
      assertEquals(1, wire.catalogueWrites);
      assertEquals(0, host.poll());
      assertEquals(0, resources.searches);
    }
  }

  @Test
  void duplicateAndUncertainResultPublicationNeverRepeatTheResourceHandler() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    wire.offered = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      host.renewCatalogue();
      wire.loseResultReply = true;
      assertThrows(IOException.class, host::poll);
      assertEquals(1, resources.searches);
      assertEquals(1, wire.resultWrites);
      assertThrows(IOException.class, host::poll);
    }
    wire.loseResultReply = false;
    wire.firstBudget = null;
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      assertEquals(1, host.reconcile());
      assertEquals(1, host.poll());
      assertEquals(1, resources.searches);
      assertEquals(1, wire.resultWrites);
    }
  }

  @Test
  void executingRestartReceiptBecomesUnknownWithoutAResourceRead() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    wire.offered = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    try (var retained = journal(configuration(), tools)) {
      retained.save(
          new RelayTools.Receipt(
              wire.offered.invocationId(),
              Json.MAPPER.writeValueAsString(wire.offered),
              RelayTools.Phase.EXECUTING,
              Optional.empty(),
              Optional.empty()));
    }
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      host.renewCatalogue();
      assertEquals(1, host.poll());
      assertEquals(0, resources.searches);
      String result =
          wire.logs.values().stream()
              .flatMap(List::stream)
              .filter(
                  value ->
                      value
                          .eventId()
                          .equals(RelayTools.resultRequestId(wire.offered.invocationId())))
              .findFirst()
              .orElseThrow()
              .payload()
              .text();
      assertEquals("UNKNOWN", Json.MAPPER.readTree(result).path("state").textValue());
    }
  }

  @Test
  void forgedBrokerPublisherNeverReachesAnAgentVisibleResourceHandler() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    wire.offered = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    wire.forbiddenPublisher = true;
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      host.renewCatalogue();
      assertThrows(IOException.class, host::poll);
      assertEquals(0, resources.searches);
      assertEquals(0, wire.resultWrites);
    }
  }

  @Test
  void changedSchemaAndLeaseConfigurationCannotAdoptOldReceiptOwnership() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      host.renewCatalogue();
    }
    HomeAssistantToolHandlers changed =
        new HomeAssistantToolHandlers() {
          @Override
          public List<RelayTools.Declaration> declarations() {
            return tools.declarations().stream()
                .map(
                    value ->
                        new RelayTools.Declaration(
                            value.name(),
                            value.description() + " changed",
                            value.schema(),
                            value.timeoutSeconds()))
                .toList();
          }

          @Override
          public RelayTools.Result execute(RelayTools.Call call, OperationBudget budget)
              throws IOException {
            return tools.execute(call, budget);
          }
        };
    assertThrows(IOException.class, () -> journal(configuration(), changed));
    var differentLease = new HomeAssistantToolProvider.Configuration(BINDING, "house_", 20);
    assertThrows(IOException.class, () -> journal(differentLease, tools));
    assertEquals(1, wire.catalogueWrites);
    assertEquals(0, resources.searches);
  }

  @Test
  void staleOfferedSchemaIsRefusedBeforeExecutionIntentOrHandler() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    var original = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    wire.offered =
        new RelayTools.Call(
            original.schema(),
            original.invocationId(),
            original.project(),
            original.provider(),
            original.tool(),
            original.account(),
            original.run(),
            original.call(),
            original.deadline(),
            original.arguments(),
            "0".repeat(64));
    var retained = journal(configuration(), tools);
    try (var host =
        new HomeAssistantToolProvider(configuration(), tools, wire, retained, clock::get)) {
      host.renewCatalogue();
      assertThrows(IOException.class, host::poll);
      assertTrue(retained.all().isEmpty());
      assertEquals(0, resources.searches);
      assertEquals(0, wire.resultWrites);
    }
  }

  @Test
  void reentrantPassAndSelfCleanupRefuseWithoutBlockingOrCancellingTheOuterWork() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    var current = new AtomicReference<HomeAssistantToolProvider>();
    HomeAssistantToolHandlers reentrant =
        new HomeAssistantToolHandlers() {
          @Override
          public List<RelayTools.Declaration> declarations() {
            return tools.declarations();
          }

          @Override
          public RelayTools.Result execute(RelayTools.Call call, OperationBudget budget)
              throws IOException {
            assertThrows(IOException.class, current.get()::poll);
            assertThrows(IOException.class, current.get()::close);
            return tools.execute(call, budget);
          }
        };
    wire.offered = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), reentrant, wire, journal(configuration(), reentrant), clock::get)) {
      current.set(host);
      host.renewCatalogue();
      assertEquals(1, host.poll());
      assertEquals(1, resources.searches);
      assertEquals(1, wire.resultWrites);
      assertTrue(host.catalogueAvailable());
    }
  }

  @Test
  void expiredPhysicalPassRetainsItsSlotAndJournalUntilItActuallySettles() throws Exception {
    var config =
        new HomeAssistantToolProvider.Configuration(
            BINDING, "house_", 10, Duration.ofMillis(250), Duration.ofMillis(30));
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    var retained = journal(config, tools);
    var host = new HomeAssistantToolProvider(config, tools, wire, retained, clock::get);
    host.renewCatalogue();
    wire.entered = new CountDownLatch(1);
    wire.release = new CountDownLatch(1);
    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = caller.submit(() -> assertThrows(IOException.class, host::poll));
      assertTrue(wire.entered.await(1, TimeUnit.SECONDS));
      pending.get(1, TimeUnit.SECONDS);
      assertThrows(IOException.class, host::poll);
      assertThrows(IOException.class, host::close);
      assertThrows(IOException.class, () -> journal(config, tools));
      wire.release.countDown();
      host.close();
    } finally {
      wire.release.countDown();
      host.close();
    }
    try (var reopened = journal(config, tools)) {
      assertTrue(reopened.all().isEmpty());
    }
  }

  @Test
  void admittedInvocationUsesOneEarlierBudgetThroughAckResourceAndResultPublication()
      throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    var original = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      host.renewCatalogue();
      wire.offered =
          new RelayTools.Call(
              original.schema(),
              original.invocationId(),
              original.project(),
              original.provider(),
              original.tool(),
              original.account(),
              original.run(),
              original.call(),
              clock.get().plusSeconds(1).toString(),
              original.arguments(),
              original.schemaFingerprint());
      assertEquals(1, host.poll());
      assertTrue(resources.lastBudget == wire.acknowledgeBudget);
      assertTrue(resources.lastBudget == wire.resultBudget);
      assertFalse(resources.lastBudget == wire.firstBudget);
      assertTrue(resources.lastBudget.remaining().compareTo(Duration.ofSeconds(1)) <= 0);
    }
  }

  @Test
  void aBlockingInterruptorCannotBlockTheExpiredCallerOrReleaseThePhysicalOwner() throws Exception {
    var config =
        new HomeAssistantToolProvider.Configuration(
            BINDING, "house_", 10, Duration.ofMillis(250), Duration.ofMillis(100));
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    var interruptEntered = new CountDownLatch(1);
    var interruptRelease = new CountDownLatch(1);
    var blockInterrupt = new AtomicBoolean();
    var host =
        new HomeAssistantToolProvider(
            config,
            tools,
            wire,
            journal(config, tools),
            clock::get,
            task -> {
              Thread thread =
                  new Thread(task, "fixture-blocking-interruptor") {
                    @Override
                    public void interrupt() {
                      if (blockInterrupt.get()) {
                        interruptEntered.countDown();
                        boolean waiting = true;
                        while (waiting) {
                          try {
                            interruptRelease.await();
                            waiting = false;
                          } catch (InterruptedException ignored) {
                            /* Model an NIO interruptor doing physical cleanup. */
                          }
                        }
                      }
                      super.interrupt();
                    }
                  };
              thread.setDaemon(true);
              return thread;
            });
    host.renewCatalogue();
    wire.entered = new CountDownLatch(1);
    wire.release = new CountDownLatch(1);
    blockInterrupt.set(true);
    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = caller.submit(() -> assertThrows(IOException.class, host::poll));
      assertTrue(wire.entered.await(1, TimeUnit.SECONDS));
      pending.get(1, TimeUnit.SECONDS);
      assertTrue(interruptEntered.await(1, TimeUnit.SECONDS));
      assertThrows(IOException.class, host::poll);
      assertThrows(IOException.class, host::close);
      // Even after the work itself returns, the old interruption must settle before reuse/close.
      wire.release.countDown();
      assertThrows(IOException.class, host::close);
      assertThrows(IOException.class, () -> journal(config, tools));
      blockInterrupt.set(false);
      interruptRelease.countDown();
      host.close();
    } finally {
      blockInterrupt.set(false);
      wire.release.countDown();
      interruptRelease.countDown();
      host.close();
    }
    try (var reopened = journal(config, tools)) {
      assertTrue(reopened.all().isEmpty());
    }
  }

  @Test
  void anAlreadyInterruptedCallerIsRefusedBeforeAdmissionAndRetainsItsInterruptFlag()
      throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    try (var host =
            new HomeAssistantToolProvider(
                configuration(), tools, wire, journal(configuration(), tools), clock::get);
        var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      caller
          .submit(
              () -> {
                Thread.currentThread().interrupt();
                try {
                  assertThrows(IOException.class, host::renewCatalogue);
                  assertTrue(Thread.currentThread().isInterrupted());
                } finally {
                  Thread.interrupted();
                }
              })
          .get(1, TimeUnit.SECONDS);
      assertEquals(0, wire.catalogueWrites);
      assertFalse(host.catalogueAvailable());
    }
  }

  @Test
  void aBlockedJournalWriteCannotOutliveItsPhysicalOwnershipOrStartTheHandler() throws Exception {
    var config =
        new HomeAssistantToolProvider.Configuration(
            BINDING, "house_", 10, Duration.ofMillis(250), Duration.ofMillis(30));
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    var stored = journal(config, tools);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    HomeAssistantProviderJournal blocking =
        new HomeAssistantProviderJournal() {
          @Override
          public void checkDelivery(Delivery delivery) throws IOException {
            stored.checkDelivery(delivery);
          }

          @Override
          public void captureDelivery(Delivery delivery) throws IOException {
            stored.captureDelivery(delivery);
          }

          @Override
          public void acknowledged(Delivery delivery) throws IOException {
            stored.acknowledged(delivery);
          }

          @Override
          public String identity() {
            return stored.identity();
          }

          @Override
          public List<RelayTools.Receipt> all() throws IOException {
            return stored.all();
          }

          @Override
          public void save(RelayTools.Receipt receipt) throws IOException {
            if (receipt.phase() == RelayTools.Phase.EXECUTING) {
              entered.countDown();
              boolean waiting = true;
              while (waiting) {
                try {
                  release.await();
                  waiting = false;
                } catch (InterruptedException ignored) {
                  /* An fsync can remain physically active. */
                }
              }
            }
            stored.save(receipt);
          }

          @Override
          public Optional<CatalogueIntent> publishing() throws IOException {
            return stored.publishing();
          }

          @Override
          public Optional<CatalogueConfirmed> confirmed() throws IOException {
            return stored.confirmed();
          }

          @Override
          public void beginCatalogue(RelayPort.Publish request, boolean withdrawal)
              throws IOException {
            stored.beginCatalogue(request, withdrawal);
          }

          @Override
          public void confirmCatalogue(RelayPort.Published receipt) throws IOException {
            stored.confirmCatalogue(receipt);
          }

          @Override
          public void notSubmittedCatalogue(String id) throws IOException {
            stored.notSubmittedCatalogue(id);
          }

          @Override
          public void close() throws IOException {
            stored.close();
          }
        };
    var host = new HomeAssistantToolProvider(config, tools, wire, blocking, clock::get);
    wire.offered = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    host.renewCatalogue();
    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = caller.submit(() -> assertThrows(IOException.class, host::poll));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      pending.get(1, TimeUnit.SECONDS);
      assertThrows(IOException.class, host::poll);
      assertEquals(0, resources.searches);
      assertThrows(IOException.class, host::close);
      assertThrows(IOException.class, () -> journal(config, tools));
      release.countDown();
      host.close();
    } finally {
      release.countDown();
      host.close();
    }
    try (var reopened = journal(config, tools)) {
      assertEquals(RelayTools.Phase.EXECUTING, reopened.all().getFirst().phase());
    }
  }

  @Test
  void settledReadsContinueBeyondReceiptCapacityAcrossRestartAndOldPositionsRefuse()
      throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    wire.generation = UUID.randomUUID().toString();
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    RelayTools.Call first = null;
    var invocations = new HashSet<String>();
    for (int stage = 0; stage < 2; stage++) {
      var retained = journal(configuration(), tools);
      try (var host =
          new HomeAssistantToolProvider(configuration(), tools, wire, retained, clock::get)) {
        host.renewCatalogue();
        if (stage > 0) {
          wire.offered = first;
          wire.offeredPosition = "1";
          assertThrows(IOException.class, host::poll);
          assertEquals(40, resources.searches, "Restart cannot repeat the saved first invocation");
        }
        for (int index = stage * 40 + 1; index <= (stage + 1) * 40; index++) {
          wire.offered = newSearchInvocation(tools);
          assertTrue(
              invocations.add(wire.offered.invocationId()),
              "Each new logical invocation must be distinct");
          wire.offeredPosition = Integer.toString(index);
          if (first == null) first = wire.offered;
          assertEquals(1, host.poll());
          assertTrue(retained.all().isEmpty(), "Settled ACK + known result becomes a replay fence");
        }
        wire.offered = first;
        wire.offeredPosition = "1";
        assertThrows(IOException.class, host::poll);
        assertEquals((stage + 1) * 40, resources.searches);
      }
    }
    assertEquals(80, wire.resultWrites);
    assertEquals(80, invocations.size());
    var results = wire.logs.get("tool." + BINDING.provider() + ".house_search.result");
    assertEquals(80, results.size());
    assertEquals(80L, results.stream().map(RelayLog.Event::eventId).distinct().count());
    for (var result : results)
      assertEquals(
          "COMPLETED", Json.MAPPER.readTree(result.payload().text()).path("state").textValue());
  }

  @Test
  void missingOrChangedCursorGenerationCannotReuseARetirementFence() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    String generation = UUID.randomUUID().toString();
    wire.generation = generation;
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    try (var host =
        new HomeAssistantToolProvider(
            configuration(), tools, wire, journal(configuration(), tools), clock::get)) {
      host.renewCatalogue();
      wire.offered = newSearchInvocation(tools);
      assertEquals(1, host.poll());
      wire.offered = newSearchInvocation(tools);
      wire.offeredPosition = "2";
      wire.generation = null;
      assertThrows(IOException.class, host::poll);
      wire.generation = UUID.randomUUID().toString();
      assertThrows(IOException.class, host::poll);
      assertEquals(1, resources.searches);
      wire.generation = generation;
      assertEquals(1, host.poll());
      assertEquals(2, resources.searches);
    }
  }

  @Test
  void missingOrWrongAcknowledgementProofNeverExecutesOrRetiresIntent() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    wire.generation = UUID.randomUUID().toString();
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    var original = HomeAssistantReadToolsTest.call(tools, "search", Map.of());
    wire.offered = original;
    var retained = journal(configuration(), tools);
    try (var host =
        new HomeAssistantToolProvider(configuration(), tools, wire, retained, clock::get)) {
      host.renewCatalogue();
      wire.acknowledgementThrough = "2";
      assertThrows(IOException.class, host::poll);
      assertEquals(RelayTools.Phase.EXECUTING, retained.all().getFirst().phase());
      assertEquals(0, resources.searches);
    }
    retained = journal(configuration(), tools);
    wire.acknowledgementThrough = null;
    wire.loseAcknowledgement = true;
    try (var host =
        new HomeAssistantToolProvider(configuration(), tools, wire, retained, clock::get)) {
      assertThrows(IOException.class, host::poll);
      assertEquals(
          RelayTools.State.UNKNOWN, retained.all().getFirst().result().orElseThrow().state());
      wire.loseAcknowledgement = false;
      assertEquals(1, host.poll());
      assertEquals(1, retained.all().size(), "UNKNOWN remains exceptional even after a known ACK");
      assertEquals(0, resources.searches);
    }
  }

  @Test
  void uncertainResultRetiresOnlyAfterExactReadOnlyReconciliation() throws Exception {
    var clock = new AtomicReference<>(Instant.now());
    var wire = new Wire(clock);
    wire.generation = UUID.randomUUID().toString();
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools = tools(resources);
    wire.offered = newSearchInvocation(tools);
    var retained = journal(configuration(), tools);
    try (var host =
        new HomeAssistantToolProvider(configuration(), tools, wire, retained, clock::get)) {
      host.renewCatalogue();
      wire.loseResultReply = true;
      assertThrows(IOException.class, host::poll);
      assertEquals(RelayTools.Phase.PUBLISHING, retained.all().getFirst().phase());
    }
    retained = journal(configuration(), tools);
    wire.loseResultReply = false;
    try (var host =
        new HomeAssistantToolProvider(configuration(), tools, wire, retained, clock::get)) {
      assertEquals(1, host.reconcile());
      assertTrue(retained.all().isEmpty());
      assertThrows(IOException.class, host::poll);
      assertEquals(1, resources.searches);
      wire.offered = newSearchInvocation(tools);
      wire.offeredPosition = "2";
      assertEquals(1, host.poll());
      assertEquals(2, resources.searches);
      assertEquals(2, wire.resultWrites);
    }
  }
}
