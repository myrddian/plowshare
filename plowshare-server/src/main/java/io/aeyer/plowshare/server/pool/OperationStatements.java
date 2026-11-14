package io.aeyer.plowshare.server.pool;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Counts how many JDBC statements one operation executes and records the total as {@value #METER}
 * when its scope closes.
 *
 * <p>The count is per thread: {@link #open} starts a window on the calling thread, {@link
 * MicrometerJdbcExecutionTrace} adds every statement that thread executes, and closing the scope
 * records the number executed since it opened. Scopes nest; an outer scope also counts what an
 * inner one counted, because it measures the whole operation. Statements executed outside any scope
 * count toward nothing. <b>Work a tool hands to another thread is not counted in its operation.</b>
 * This is telemetry that answers "how many round trips does a tool call, job step or socket request
 * cost"; it is not an operation context and carries no authority or identity.
 *
 * <p>The meter is tagged only by {@link Kind}, a fixed set, and uses a percentile histogram so the
 * tail of statements per operation is visible. Until a registry is bound scopes are no-ops; every
 * {@link MicrometerJdbcExecutionTrace} constructed binds its registry, so the latest-constructed
 * trace's registry wins. Scopes are for try-with-resources on the thread that opened them and must
 * be closed in reverse order of opening.
 */
public final class OperationStatements {
  public static final String METER = "plowshare.operation.statements";

  /** What kind of operation a scope measures; its lower-case name is the {@code kind} tag. */
  public enum Kind {
    TOOL_CALL,
    JOB_STEP,
    SOCKET_REQUEST
  }

  /** An open counting window. {@link #close} records the count and never throws. */
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  private static final Scope NOOP = () -> {};

  /** Statements executed on this thread while at least one scope is open. */
  private static final class Counter {
    long total;
    int open;
  }

  private static final ThreadLocal<Counter> COUNTER = ThreadLocal.withInitial(Counter::new);

  /** The summaries of the bound registry, or null before one is bound. */
  private static volatile Map<Kind, DistributionSummary> summaries;

  private OperationStatements() {}

  /**
   * Starts counting this thread's statements for one operation of {@code kind}.
   *
   * @return a scope to close when the operation ends; a no-op when no registry is bound
   */
  public static Scope open(Kind kind) {
    Objects.requireNonNull(kind);
    var sink = summaries;
    if (sink == null) return NOOP;
    var counter = COUNTER.get();
    counter.open++;
    var start = counter.total;
    return () -> {
      counter.open--;
      sink.get(kind).record(counter.total - start);
    };
  }

  /** Counts one statement on the calling thread if a scope is open. */
  static void statement() {
    var counter = COUNTER.get();
    if (counter.open > 0) counter.total++;
  }

  /** Binds the registry that scopes record into; the latest binding wins. */
  static void bind(MeterRegistry registry) {
    Objects.requireNonNull(registry);
    var bound = new EnumMap<Kind, DistributionSummary>(Kind.class);
    for (var kind : Kind.values()) {
      bound.put(
          kind,
          DistributionSummary.builder(METER)
              .description("JDBC statements executed per operation")
              .baseUnit("statements")
              .publishPercentileHistogram()
              .tag("kind", kind.name().toLowerCase(Locale.ROOT))
              .register(registry));
    }
    summaries = bound;
  }

  /** Removes the binding so scopes become no-ops; for tests. */
  static void unbind() {
    summaries = null;
  }
}
