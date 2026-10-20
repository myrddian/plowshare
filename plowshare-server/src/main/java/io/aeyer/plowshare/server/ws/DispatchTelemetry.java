package io.aeyer.plowshare.server.ws;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Fixed-cardinality, payload-free transport measurements. Never used to decide admission. */
final class DispatchTelemetry {
  record Timing(long count, long totalNanos, long maximumNanos) {}

  record Snapshot(
      long admitted,
      long rejected,
      long duplicateIds,
      long active,
      long queued,
      long requestBytes,
      long replyBytes,
      long observers,
      long resourceKeys,
      long contentions,
      Timing queue,
      Timing execution,
      Timing encoding,
      Timing replyQueue,
      Timing write,
      Timing drain) {}

  static final class Clock {
    private final LongAdder count = new LongAdder();
    private final LongAdder total = new LongAdder();
    private final AtomicLong maximum = new AtomicLong();

    void record(long nanos) {
      long elapsed = Math.max(0, nanos);
      count.increment();
      total.add(elapsed);
      maximum.accumulateAndGet(elapsed, Math::max);
    }

    Timing snapshot() {
      return new Timing(count.sum(), total.sum(), maximum.get());
    }
  }

  final LongAdder admitted = new LongAdder();
  final LongAdder rejected = new LongAdder();
  final LongAdder duplicateIds = new LongAdder();
  final LongAdder active = new LongAdder();
  final LongAdder queued = new LongAdder();
  final LongAdder requestBytes = new LongAdder();
  final LongAdder replyBytes = new LongAdder();
  final LongAdder observers = new LongAdder();
  final LongAdder resourceKeys = new LongAdder();
  final LongAdder contentions = new LongAdder();
  final Clock queue = new Clock();
  final Clock execution = new Clock();
  final Clock encoding = new Clock();
  final Clock replyQueue = new Clock();
  final Clock write = new Clock();
  final Clock drain = new Clock();

  /** Gauges can change between reads; exact capacity ownership remains in the coordinator. */
  Snapshot snapshot() {
    return new Snapshot(
        admitted.sum(),
        rejected.sum(),
        duplicateIds.sum(),
        active.sum(),
        queued.sum(),
        requestBytes.sum(),
        replyBytes.sum(),
        observers.sum(),
        resourceKeys.sum(),
        contentions.sum(),
        queue.snapshot(),
        execution.snapshot(),
        encoding.snapshot(),
        replyQueue.snapshot(),
        write.snapshot(),
        drain.snapshot());
  }
}
