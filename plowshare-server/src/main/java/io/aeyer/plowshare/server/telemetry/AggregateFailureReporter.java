package io.aeyer.plowshare.server.telemetry;

import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fixed in-memory counters with rate-limited, message-free stack diagnostics. */
public final class AggregateFailureReporter implements FailureReporter {
  private static final Logger log = LoggerFactory.getLogger(AggregateFailureReporter.class);

  private enum Kind {
    SESSION_GONE,
    READ_UNAVAILABLE,
    READ_TIMEOUT,
    READ_INVALIDATED,
    READ_INTERRUPTED,
    READ_CLOSED,
    READ_CANCELLED,
    WORKSPACE_UNAVAILABLE,
    ARCHIVE_UNAVAILABLE,
    INFERENCE,
    OTHER
  }

  private static final class Total {
    final LongAdder count = new LongAdder();
    boolean logged;
    long lastLog;

    synchronized boolean shouldLog(long now, long interval) {
      if (logged && now - lastLog < interval) return false;
      logged = true;
      lastLog = now;
      return true;
    }
  }

  private final EnumMap<Source, EnumMap<Kind, Total>> totals = new EnumMap<>(Source.class);
  private final long logIntervalNanos;
  private final LongSupplier ticker;

  public AggregateFailureReporter(Duration logInterval, LongSupplier ticker) {
    logIntervalNanos = logInterval.toNanos();
    if (logIntervalNanos <= 0)
      throw new IllegalArgumentException("Failure log interval must be positive");
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    for (var source : Source.values()) {
      var kinds = new EnumMap<Kind, Total>(Kind.class);
      for (var kind : Kind.values()) kinds.put(kind, new Total());
      totals.put(source, kinds);
    }
  }

  /** Composition-time registration only; execution threads update totals without backend calls. */
  public void bind(MeterRegistry registry) {
    totals.forEach(
        (source, kinds) ->
            kinds.forEach(
                (kind, total) ->
                    FunctionCounter.builder("plowshare.failures", total.count, LongAdder::sum)
                        .description(
                            "Failures observed at handling boundaries; not unique incidents")
                        .tag("source", source.name().toLowerCase(Locale.ROOT))
                        .tag("kind", kind.name().toLowerCase(Locale.ROOT))
                        .register(registry)));
  }

  @Override
  public void report(Source source, Throwable failure) {
    Kind kind = kind(failure);
    Total total = totals.get(source).get(kind);
    total.count.increment();
    if (total.shouldLog(ticker.getAsLong(), logIntervalNanos)) {
      // Throwable messages can contain session identities, filesystem paths, SQL or credentials.
      // Keep the thrown type and bounded code locations, but never pass the throwable to SLF4J.
      log.error(
          "Server failure source={} kind={} exception={} observed={} stack={}",
          source,
          kind,
          failure.getClass().getSimpleName(),
          total.count.sum(),
          diagnostic(failure));
    }
  }

  private static Kind kind(Throwable failure) {
    if (failure instanceof SessionGoneException) return Kind.SESSION_GONE;
    if (failure instanceof ReadUnavailableException) return Kind.READ_UNAVAILABLE;
    if (failure instanceof ReadFailedException read)
      return switch (read.reason()) {
        case DEADLINE_EXCEEDED -> Kind.READ_TIMEOUT;
        case INVALIDATED -> Kind.READ_INVALIDATED;
        case INTERRUPTED -> Kind.READ_INTERRUPTED;
        case CLOSED -> Kind.READ_CLOSED;
        case QUERY_CANCELLED -> Kind.READ_CANCELLED;
      };
    if (failure instanceof WorkspaceUnavailableException) return Kind.WORKSPACE_UNAVAILABLE;
    if (failure instanceof ArchiveUnavailableException) return Kind.ARCHIVE_UNAVAILABLE;
    if (failure instanceof LlmException || failure instanceof EmbeddingException)
      return Kind.INFERENCE;
    return Kind.OTHER;
  }

  private static String diagnostic(Throwable failure) {
    var text = new StringBuilder();
    Throwable current = failure;
    for (int cause = 0; current != null && cause < 4; cause++) {
      if (cause > 0) text.append(" caused by ");
      text.append(current.getClass().getName());
      var stack = current.getStackTrace();
      for (int frame = 0; frame < Math.min(stack.length, 12); frame++) {
        var location = stack[frame];
        text.append(" at ")
            .append(location.getClassName())
            .append('.')
            .append(location.getMethodName())
            .append(':')
            .append(location.getLineNumber());
      }
      current = current.getCause();
    }
    return text.toString();
  }
}
