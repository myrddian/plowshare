package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.llm.dispatch.InferenceObserver;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InferenceTelemetryTest {
  @TempDir Path directory;
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerInferenceTelemetry telemetry = new MicrometerInferenceTelemetry(registry);

  @AfterEach
  void closeRegistry() {
    registry.close();
  }

  private InferenceObserver begin(AccountingRecorder recorder) {
    var prices = mock(InferencePrices.class);
    when(prices.routeFor("private-pool"))
        .thenReturn(new PricingCatalog.Route("private-billing", Map.of()));
    return new DurableInferenceAccounting(recorder, prices, AccountingFixtures.CLOCK, telemetry)
        .begin(
            "private-pool",
            "private-model",
            "private-specifier",
            Lane.CHAT,
            UsageAttribution.system(null, UsageAttribution.Operation.AGENT_CHAT));
  }

  @Test
  void finalUsagePerAttemptCountsRetriesWithoutDoubleCountingStreamingUpdatesOrRepeatedFinishes() {
    try (var journal = journal(channel -> channel.force(true))) {
      var call = begin(new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10));
      assertEquals(1, active());
      call.queued();
      call.started();
      var first = call.attempt();
      first.usage(TokenUsage.of(10, 2, 12));
      first.usage(TokenUsage.of(12, 3, 15));
      first.finished(CallLifecycle.FAILED);
      first.finished(CallLifecycle.FAILED);
      var second = call.attempt();
      second.output();
      second.usage(
          TokenUsage.measured(
              new UsageObservation(
                  20L,
                  4L,
                  24L,
                  8L,
                  null,
                  2L,
                  UsageObservation.Source.PROVIDER,
                  UsageObservation.CacheRelation.READ_SUBSET_OF_INPUT,
                  UsageObservation.VERSION,
                  Map.of(),
                  List.of())));
      second.finished(CallLifecycle.SUCCEEDED);
      call.finished(CallLifecycle.SUCCEEDED);
      call.finished(CallLifecycle.SUCCEEDED);
      assertEquals(0, active());
      assertEquals(32, token("input"));
      assertEquals(7, token("output"));
      assertEquals(39, token("provider_total"));
      assertEquals(8, token("cache_read"));
      assertEquals(2, token("reasoning"));
      assertEquals(2, counter("tokens.measured", "kind", "input"));
      assertEquals(1, counter("attempts", "outcome", "failed"));
      assertEquals(1, counter("attempts", "outcome", "succeeded"));
      assertEquals(1, counter("calls", "outcome", "succeeded"));
      assertEquals(
          2,
          registry
              .get("plowshare.inference.attempt.duration")
              .tags(tags())
              .functionTimer()
              .count());
      assertEquals(
          1,
          registry.get("plowshare.inference.queue.duration").tags(tags()).functionTimer().count());
      assertEquals(
          1, registry.get("plowshare.inference.first.output").tags(tags()).functionTimer().count());
      assertTrue(call.capture().durable());
      assertTrue(
          registry.getMeters().stream()
              .flatMap(m -> m.getId().getTags().stream())
              .noneMatch(tag -> tag.getValue().startsWith("private-")));
    }
  }

  @Test
  void unknownInvalidAndEstimatedUsageRemainVisibleWithoutInventingMeasuredTokens() {
    var call = telemetry.admitted(Lane.CHAT, UsageAttribution.Operation.AGENT_CHAT);
    call.attemptFinished(CallLifecycle.FAILED, UsageObservation.UNKNOWN, 1, null);
    call.attemptFinished(
        CallLifecycle.SUCCEEDED,
        new UsageObservation(
            1L,
            1L,
            99L,
            null,
            null,
            null,
            UsageObservation.Source.PROVIDER,
            UsageObservation.CacheRelation.UNSPECIFIED,
            UsageObservation.VERSION,
            Map.of(),
            List.of()),
        1,
        null);
    call.attemptFinished(
        CallLifecycle.SUCCEEDED,
        new UsageObservation(
            50L,
            2L,
            52L,
            null,
            null,
            null,
            UsageObservation.Source.ESTIMATE,
            UsageObservation.CacheRelation.UNSPECIFIED,
            UsageObservation.VERSION,
            Map.of(),
            List.of()),
        1,
        null);
    call.attemptFinished(
        CallLifecycle.SUCCEEDED, TokenUsage.of(0, null, null).observation(), 1, null);
    call.finished(CallLifecycle.SUCCEEDED, null);
    assertEquals(0, token("input"));
    assertEquals(0, token("output"));
    assertEquals(1, counter("tokens.measured", "kind", "input"), "Explicit zero is measured");
    assertEquals(0, counter("tokens.measured", "kind", "output"), "Unknown is not a measured zero");
    assertEquals(1, coverage("unknown", "none"));
    assertEquals(1, coverage("invalid", "provider"));
    assertEquals(1, coverage("complete", "estimate"));
    assertEquals(1, coverage("partial", "provider"));
  }

  @Test
  void embeddingInputDoesNotInventOutputAndLargeCountersDoNotWrapNegative() {
    var call = telemetry.admitted(Lane.EMBEDDING, UsageAttribution.Operation.EMBEDDING_QUERY);
    var usage =
        new UsageObservation(
            Long.MAX_VALUE,
            null,
            null,
            null,
            null,
            null,
            UsageObservation.Source.PROVIDER,
            UsageObservation.CacheRelation.UNSPECIFIED,
            UsageObservation.VERSION,
            Map.of(),
            List.of());
    call.attemptFinished(CallLifecycle.SUCCEEDED, usage, 1, null);
    call.attemptFinished(CallLifecycle.SUCCEEDED, usage, 1, null);
    call.finished(CallLifecycle.SUCCEEDED, null);
    assertEquals(
        2.0 * Long.MAX_VALUE,
        registry
            .get("plowshare.inference.tokens")
            .tags("lane", "embedding", "operation", "embedding_query", "kind", "input")
            .functionCounter()
            .count());
    assertEquals(
        0,
        registry
            .get("plowshare.inference.tokens.measured")
            .tags("lane", "embedding", "operation", "embedding_query", "kind", "output")
            .functionCounter()
            .count());
    assertEquals(
        2,
        registry
            .get("plowshare.inference.usage")
            .tags(
                "lane",
                "embedding",
                "operation",
                "embedding_query",
                "coverage",
                "complete",
                "source",
                "provider")
            .functionCounter()
            .count());
  }

  @Test
  void bufferedTerminalMetadataIsCountedOnceAndFlushingItDoesNotReplayTelemetry() {
    var fail = new AtomicBoolean();
    try (var journal =
        journal(
            channel -> {
              if (fail.getAndSet(false)) throw new IOException("injected terminal fsync failure");
              channel.force(true);
            })) {
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      var call = begin(recorder);
      var attempt = call.attempt();
      attempt.usage(TokenUsage.of(10, 2, 12));
      fail.set(true);
      attempt.finished(CallLifecycle.SUCCEEDED);
      call.finished(CallLifecycle.SUCCEEDED);
      assertFalse(call.capture().durable());
      assertTrue(recorder.health().bufferedTerminalEvents() > 0);
      assertEquals(10, token("input"));
      recorder.flushPending();
      assertEquals(0, recorder.health().bufferedTerminalEvents());
      assertEquals(10, token("input"));
      assertEquals(1, counter("attempts", "outcome", "succeeded"));
      assertEquals(0, active());
    }
  }

  @Test
  void aCallWithoutAnAttemptIsNotDispatchedAndRefusedAdmissionDoesNotCountAsAdmitted() {
    try (var journal = journal(channel -> channel.force(true))) {
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      var call = begin(recorder);
      call.finished(CallLifecycle.CANCELLED);
      assertEquals(1, counter("calls", "outcome", "not_dispatched"));
      assertEquals(
          0,
          registry
              .get("plowshare.inference.attempt.duration")
              .tags(tags())
              .functionTimer()
              .count());
      assertEquals(0, active());
      var refusing = mock(AccountingRecorder.class);
      when(refusing.begin(any()))
          .thenThrow(new AccountingUnavailableException(AccountingJournal.Problem.IO_ERROR));
      assertThrows(AccountingUnavailableException.class, () -> begin(refusing));
      assertEquals(
          1, registry.get("plowshare.inference.admitted").tags(tags()).functionCounter().count());
    }
  }

  private AccountingJournal journal(AccountingJournal.Sync sync) {
    return new AccountingJournal(
        directory, 1024 * 1024, 8192, Duration.ofSeconds(1), AccountingFixtures.MAPPER, sync);
  }

  private static String[] tags() {
    return new String[] {"lane", "chat", "operation", "agent_chat"};
  }

  private double active() {
    return registry.get("plowshare.inference.active").tags(tags()).gauge().value();
  }

  private double token(String kind) {
    return counter("tokens", "kind", kind);
  }

  private double counter(String suffix, String key, String value) {
    return registry
        .get("plowshare.inference." + suffix)
        .tags(tags())
        .tag(key, value)
        .functionCounter()
        .count();
  }

  private double coverage(String coverage, String source) {
    return registry
        .get("plowshare.inference.usage")
        .tags(tags())
        .tags("coverage", coverage, "source", source)
        .functionCounter()
        .count();
  }
}
