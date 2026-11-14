package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.llm.dispatch.Lane;
import io.aeyer.plowshare.server.telemetry.DurationTotals;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/** Fixed enum labels and pull-based totals: inference threads never call a metrics backend. */
public final class MicrometerInferenceTelemetry implements InferenceTelemetry {
  private static final String PREFIX = "plowshare.inference";

  private enum TokenKind {
    INPUT(UsageObservation::inputTokens),
    OUTPUT(UsageObservation::outputTokens),
    PROVIDER_TOTAL(UsageObservation::providerTotalTokens),
    CACHE_READ(UsageObservation::cacheReadTokens),
    CACHE_WRITE(UsageObservation::cacheWriteTokens),
    REASONING(UsageObservation::reasoningTokens);

    final Function<UsageObservation, Long> value;

    TokenKind(Function<UsageObservation, Long> value) {
      this.value = value;
    }
  }

  private static final class Group {
    final LongAdder admitted = new LongAdder();
    final AtomicLong active = new AtomicLong();
    final EnumMap<CallLifecycle, LongAdder> calls = new EnumMap<>(CallLifecycle.class);
    final EnumMap<CallLifecycle, LongAdder> attempts = new EnumMap<>(CallLifecycle.class);
    final EnumMap<TokenKind, DoubleAdder> tokens = new EnumMap<>(TokenKind.class);
    final EnumMap<TokenKind, LongAdder> measured = new EnumMap<>(TokenKind.class);
    final EnumMap<UsageObservation.Coverage, EnumMap<UsageObservation.Source, LongAdder>> usage =
        new EnumMap<>(UsageObservation.Coverage.class);
    final DurationTotals callTime = new DurationTotals();
    final DurationTotals attemptTime = new DurationTotals();
    final DurationTotals queueTime = new DurationTotals();
    final DurationTotals firstOutput = new DurationTotals();

    Group(MeterRegistry registry, Tags tags) {
      counter(registry, ".admitted", tags, admitted);
      Gauge.builder(PREFIX + ".active", active, AtomicLong::get).tags(tags).register(registry);
      for (var outcome : CallLifecycle.values()) {
        if (!outcome.terminal()) continue;
        var call = new LongAdder();
        calls.put(outcome, call);
        counter(registry, ".calls", tags.and("outcome", label(outcome)), call);
        if (outcome == CallLifecycle.NOT_DISPATCHED) continue;
        var attempt = new LongAdder();
        attempts.put(outcome, attempt);
        counter(registry, ".attempts", tags.and("outcome", label(outcome)), attempt);
      }
      for (var kind : TokenKind.values()) {
        var amount = new DoubleAdder();
        tokens.put(kind, amount);
        var observations = new LongAdder();
        measured.put(kind, observations);
        var kindTags = tags.and("kind", label(kind));
        FunctionCounter.builder(PREFIX + ".tokens", amount, DoubleAdder::sum)
            .description(
                "Valid provider-reported token quantities; kinds overlap and must not be summed")
            .tags(kindTags)
            .register(registry);
        counter(registry, ".tokens.measured", kindTags, observations);
      }
      for (var coverage : UsageObservation.Coverage.values()) {
        var sources =
            new EnumMap<UsageObservation.Source, LongAdder>(UsageObservation.Source.class);
        usage.put(coverage, sources);
        for (var source : UsageObservation.Source.values()) {
          var count = new LongAdder();
          sources.put(source, count);
          counter(
              registry,
              ".usage",
              tags.and("coverage", label(coverage), "source", label(source)),
              count);
        }
      }
      timer(registry, ".duration", tags, callTime);
      timer(registry, ".attempt.duration", tags, attemptTime);
      timer(registry, ".queue.duration", tags, queueTime);
      timer(registry, ".first.output", tags, firstOutput);
    }
  }

  private final EnumMap<Lane, EnumMap<UsageAttribution.Operation, Group>> groups =
      new EnumMap<>(Lane.class);

  public MicrometerInferenceTelemetry(MeterRegistry registry) {
    for (var lane : Lane.values()) {
      var operations =
          new EnumMap<UsageAttribution.Operation, Group>(UsageAttribution.Operation.class);
      groups.put(lane, operations);
      for (var operation : UsageAttribution.Operation.values())
        operations.put(
            operation,
            new Group(registry, Tags.of("lane", lane.wireName(), "operation", label(operation))));
    }
  }

  @Override
  public Call admitted(Lane lane, UsageAttribution.Operation operation) {
    var group = groups.get(lane).get(operation);
    group.admitted.increment();
    group.active.incrementAndGet();
    long started = System.nanoTime();
    return new Call() {
      private boolean ended;

      public synchronized void attemptFinished(
          CallLifecycle outcome,
          UsageObservation usage,
          long durationMillis,
          Long firstOutputMillis) {
        if (ended) return;
        group.attempts.get(outcome).increment();
        group.attemptTime.recordSeconds(durationMillis / 1000.0);
        if (firstOutputMillis != null) group.firstOutput.recordSeconds(firstOutputMillis / 1000.0);
        var coverage = usage.coverage(lane);
        group.usage.get(coverage).get(usage.source()).increment();
        // Unknown is not zero. Invalid observations remain visible in coverage, and estimates or
        // tokenizer preflight must never be added to the provider's measured spending.
        if (coverage == UsageObservation.Coverage.INVALID
            || usage.source() != UsageObservation.Source.PROVIDER) return;
        for (var kind : TokenKind.values()) {
          var value = kind.value.apply(usage);
          if (value == null) continue;
          group.tokens.get(kind).add(value.doubleValue());
          group.measured.get(kind).increment();
        }
      }

      public synchronized void finished(CallLifecycle outcome, Long queueMillis) {
        if (ended) return;
        group.calls.get(outcome).increment();
        if (queueMillis != null) group.queueTime.recordSeconds(queueMillis / 1000.0);
        group.callTime.recordSeconds(Math.max(0, System.nanoTime() - started) / 1e9);
        group.active.decrementAndGet();
        ended = true;
      }
    };
  }

  private static String label(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private static void counter(MeterRegistry registry, String suffix, Tags tags, LongAdder count) {
    FunctionCounter.builder(PREFIX + suffix, count, LongAdder::sum).tags(tags).register(registry);
  }

  private static void timer(
      MeterRegistry registry, String suffix, Tags tags, DurationTotals totals) {
    FunctionTimer.builder(
            PREFIX + suffix,
            totals,
            DurationTotals::count,
            DurationTotals::seconds,
            TimeUnit.SECONDS)
        .tags(tags)
        .register(registry);
  }
}
