package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.ScheduledWork;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Read-only source/effective schedule inspection. Live project roles are enforced by the server.
 */
public final class ScheduleClient {
  private final Plowshare connection;

  public ScheduleClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** Effective projection, including an operational pause independent of source content. */
  public record Schedule(
      String name,
      String cron,
      String zone,
      String emits,
      boolean paused,
      String nextFireAt,
      String definedBy) {
    public Schedule {
      ContractChecks.identity(name, "name");
      ContractChecks.identity(cron, "cron");
      ContractChecks.identity(zone, "zone");
      ContractChecks.identity(emits, "emits");
      ContractChecks.timestamp(nextFireAt, "nextFireAt");
      ContractChecks.identity(definedBy, "definedBy");
    }
  }

  /**
   * Traverse current project-scoped pages, retaining at most 10,000 schedules. Each page checks
   * current authority; concurrent edits do not form an immutable snapshot. Use {@link #listPage}
   * for bounded display reads or larger collections. An exceeded bound fails without a partial
   * list.
   */
  public List<Schedule> list(String project) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> listAsync(project, options));
  }

  /** Traverse source files with the same authority, consistency and 10,000-row bound as list. */
  public List<ScheduledWork.File> files(String project) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> filesAsync(project, options));
  }

  /**
   * Return one immutable page in server name order. The exclusive after key is the last retained
   * schedule name; null starts at the beginning. A null limit means 50, otherwise use 1..100. A
   * full page may have a successor; an empty or short page ends the current traversal.
   */
  public List<Schedule> listPage(String project, String after, Integer limit) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> listPageAsync(project, after, limit, options));
  }

  /**
   * Return one immutable source-file page ordered by unique internalName, including refused files.
   * The exclusive after key is the last retained internalName; null starts at the beginning. A null
   * limit means 50, otherwise use 1..100. Each page rechecks current project authority.
   */
  public List<ScheduledWork.File> filesPage(String project, String after, Integer limit)
      throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> filesPageAsync(project, after, limit, options));
  }

  private <T> List<T> read(
      String operation,
      String project,
      String after,
      Integer limit,
      Class<T> type,
      Function<T, String> key)
      throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(),
        options -> readAsync(operation, project, after, limit, type, key, options));
  }

  private <T> List<T> all(String operation, String project, Class<T> type, Function<T, String> key)
      throws IOException {
    List<T> result = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    String after = null;
    while (true) {
      List<T> page = read(operation, project, after, 100, type, key);
      for (T row : page) {
        if (!keys.add(key.apply(row))) throw new IOException("Schedule traversal did not progress");
        if (result.size() == 10000)
          throw new IOException("Schedule traversal exceeded 10,000 rows; use bounded page reads");
        result.add(row);
      }
      if (page.size() < 100) return List.copyOf(result);
      after = key.apply(page.getLast());
    }
  }

  private static void requireCursor(String value) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 512
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(
          "Schedule cursor must be exact nonblank text of at most 512 characters without controls");
  }

  /** Sequential bounded page composition owns its accumulator; no worker waits for a reply. */
  private <T> java.util.concurrent.CompletionStage<List<T>> allAsync(
      String operation,
      String project,
      Class<T> type,
      Function<T, String> key,
      RequestOptions options) {
    var budget = options == null ? connection.defaultOptions() : options;
    long started = System.nanoTime();
    return allPageAsync(
        operation, project, type, key, budget, started, null, new ArrayList<>(), new HashSet<>());
  }

  private <T> java.util.concurrent.CompletionStage<List<T>> allPageAsync(
      String operation,
      String project,
      Class<T> type,
      Function<T, String> key,
      RequestOptions budget,
      long started,
      String after,
      List<T> result,
      Set<String> keys) {
    long remaining = budget.timeout().toNanos() - (System.nanoTime() - started);
    if (remaining <= 0)
      return java.util.concurrent.CompletableFuture.failedFuture(
          new Plowshare.TransportException(
              Plowshare.Delivery.NOT_SUBMITTED,
              "Schedule traversal deadline expired before next page"));
    return readAsync(
            operation,
            project,
            after,
            100,
            type,
            key,
            new RequestOptions(java.time.Duration.ofNanos(remaining), budget.cancellation()))
        .thenCompose(
            page -> {
              for (T row : page) {
                if (!keys.add(key.apply(row)))
                  return java.util.concurrent.CompletableFuture.failedFuture(
                      new IOException("Schedule traversal did not progress"));
                if (result.size() == 10000)
                  return java.util.concurrent.CompletableFuture.failedFuture(
                      new IOException(
                          "Schedule traversal exceeded 10,000 rows; use bounded page reads"));
                result.add(row);
              }
              if (page.size() < 100)
                return java.util.concurrent.CompletableFuture.completedFuture(List.copyOf(result));
              return allPageAsync(
                  operation,
                  project,
                  type,
                  key,
                  budget,
                  started,
                  key.apply(page.getLast()),
                  result,
                  keys);
            });
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  private <T> java.util.concurrent.CompletionStage<List<T>> readAsync(
      String operation,
      String project,
      String after,
      Integer limit,
      Class<T> type,
      Function<T, String> key,
      RequestOptions options) {

    io.aeyer.plowshare.protocol.ApplicationIdentity.project(project);
    if (after != null) requireCursor(after);
    if (limit != null && (limit < 1 || limit > 100))
      throw new IllegalArgumentException("Schedule page limit must be 1..100");
    int maximum = limit == null ? 50 : limit;
    // The validated page selection becomes transport fields only at this SDK boundary. Omitted
    // controls remain absent rather than explicit JSON null, which the operation contract rejects.
    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("project", project);
    if (after != null) payload.put("after", after);
    if (limit != null) payload.put("limit", limit);
    return AsyncSupport.map(
        connection.requestAsync(operation, payload, options),
        asyncResult -> {
          var value = asyncResult.requirePayload();
          if (!value.isArray() || value.size() > maximum)
            throw new IOException("Unreadable or oversized scoped schedule page");
          List<T> result = new ArrayList<>(value.size());
          Set<String> keys = new HashSet<>();
          for (var row : value) {
            T decoded = SdkJson.decode(SdkJson.mapper(), row, type);
            if (decoded == null) throw new IOException("Unreadable schedule page row");
            String identity = key.apply(decoded);
            try {
              requireCursor(identity);
            } catch (IllegalArgumentException invalid) {
              throw new IOException("Unreadable schedule page key", invalid);
            }
            if (identity.equals(after) || !keys.add(identity))
              throw new IOException("Schedule page repeated its cursor or a key");
            result.add(decoded);
          }
          return List.copyOf(result);
        });
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<Schedule>> listAsync(
      String project, RequestOptions options) {

    return AsyncSupport.map(
        allAsync("schedule.list", project, Schedule.class, Schedule::name, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<List<Schedule>> listAsync(String project) {
    return listAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<ScheduledWork.File>> filesAsync(
      String project, RequestOptions options) {

    return AsyncSupport.map(
        allAsync(
            "schedule.files",
            project,
            ScheduledWork.File.class,
            ScheduledWork.File::internalName,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<List<ScheduledWork.File>> filesAsync(String project) {
    return filesAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<Schedule>> listPageAsync(
      String project, String after, Integer limit, RequestOptions options) {

    return AsyncSupport.map(
        readAsync("schedule.list", project, after, limit, Schedule.class, Schedule::name, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<List<Schedule>> listPageAsync(
      String project, String after, Integer limit) {
    return listPageAsync(project, after, limit, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<ScheduledWork.File>> filesPageAsync(
      String project, String after, Integer limit, RequestOptions options) {

    return AsyncSupport.map(
        readAsync(
            "schedule.files",
            project,
            after,
            limit,
            ScheduledWork.File.class,
            ScheduledWork.File::internalName,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<List<ScheduledWork.File>> filesPageAsync(
      String project, String after, Integer limit) {
    return filesPageAsync(project, after, limit, null);
  }
}
