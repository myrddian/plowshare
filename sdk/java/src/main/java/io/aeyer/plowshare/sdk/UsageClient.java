package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Usage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated usage snapshots and socket-owned subscriptions. No HTTP fallback or mutation
 * replay.
 */
public final class UsageClient {
  public enum ReportType {
    CONVERSATION("usage.conversation"),
    PROJECT("usage.project"),
    AGENT("usage.agent"),
    RUN("usage.run"),
    ORCHESTRATION("usage.orchestration"),
    MODELS("usage.models"),
    POOLS("usage.pools");
    private final String operation;

    ReportType(String operation) {
      this.operation = operation;
    }
  }

  private static final ObjectMapper JSON = SdkJson.mapper();
  private static final TypeReference<LinkedHashMap<String, Object>> FIELDS =
      new TypeReference<>() {};
  private final Plowshare connection;

  public UsageClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  public Usage.Report report(ReportType type, Usage.Filter filter) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> reportAsync(type, filter, options));
  }

  public Usage.Audit calls(Usage.Filter filter) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> callsAsync(filter, options));
  }

  public Usage.AttemptPage attempts(Usage.Filter filter, UUID call, String cursor)
      throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> attemptsAsync(filter, call, cursor, options));
  }

  public Usage.Initial subscribe(ReportType type, Usage.Filter filter) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> subscribeAsync(type, filter, options));
  }

  public void unsubscribe(UUID subscription) throws IOException {
    AsyncSupport.await(
        connection.defaultOptions(), options -> unsubscribeAsync(subscription, options));
  }

  private static LinkedHashMap<String, Object> fields(Usage.Filter filter) {
    return JSON.convertValue(Objects.requireNonNull(filter, "filter"), FIELDS);
  }

  /**
   * The server resolves defaults, but may never answer for a different explicitly selected scope.
   */
  private static void selected(String operation, Usage.Filter requested, Usage.Resolved actual)
      throws IOException {
    if (!operation.equals(actual.type())) throw new IOException("foreign usage report type");
    Usage.Filter received = actual.filter();
    same(requested.conversation(), received.conversation(), "conversation");
    same(requested.project(), received.project(), "project");
    same(requested.agent(), received.agent(), "agent");
    same(requested.run(), received.run(), "run");
    same(requested.orchestration(), received.orchestration(), "orchestration");
    same(requested.model(), received.model(), "model");
    same(requested.pool(), received.pool(), "pool");
    same(requested.route(), received.route(), "route");
    same(requested.scope(), received.scope(), "scope");
    same(requested.from(), received.from(), "from");
    same(requested.to(), received.to(), "to");
    same(requested.groupBy(), received.groupBy(), "group_by");
    same(requested.cursor(), received.cursor(), "cursor");
    same(requested.limit(), received.limit(), "limit");
  }

  private static <T> void same(T requested, T actual, String field) throws IOException {
    if (requested != null && !requested.equals(actual))
      throw new IOException("foreign usage report selection: " + field);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Usage.Report> reportAsync(
      ReportType type, Usage.Filter filter, RequestOptions options) {

    Objects.requireNonNull(type, "type");
    return AsyncSupport.map(
        connection.requestAsync(type.operation, fields(filter), Usage.Report.class, options),
        asyncResult -> {
          var report = asyncResult;
          selected(type.operation, filter, report.filters());
          return report;
        });
  }

  public java.util.concurrent.CompletionStage<Usage.Report> reportAsync(
      ReportType type, Usage.Filter filter) {
    return reportAsync(type, filter, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Usage.Audit> callsAsync(
      Usage.Filter filter, RequestOptions options) {

    return AsyncSupport.map(
        connection.requestAsync("usage.calls", fields(filter), Usage.Audit.class, options),
        asyncResult -> {
          var audit = asyncResult;
          selected("usage.calls", filter, audit.filters());
          return audit;
        });
  }

  public java.util.concurrent.CompletionStage<Usage.Audit> callsAsync(Usage.Filter filter) {
    return callsAsync(filter, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Usage.AttemptPage> attemptsAsync(
      Usage.Filter filter, UUID call, String cursor, RequestOptions options) {

    Objects.requireNonNull(call, "call");
    var fields = fields(filter);
    fields.put("call", call.toString());
    fields.put(
        "attempt_cursor",
        cursor == null ? null : ContractChecks.identity(cursor, "attempt cursor"));
    return AsyncSupport.map(
        connection.requestAsync("usage.calls", fields, Usage.AttemptPage.class, options),
        asyncResult -> {
          var page = asyncResult;
          selected("usage.calls", filter, page.filters());
          if (!call.toString().equals(page.call()))
            throw new IOException("foreign usage call receipt");
          return page;
        });
  }

  public java.util.concurrent.CompletionStage<Usage.AttemptPage> attemptsAsync(
      Usage.Filter filter, UUID call, String cursor) {
    return attemptsAsync(filter, call, cursor, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Usage.Initial> subscribeAsync(
      ReportType type, Usage.Filter filter, RequestOptions options) {

    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(filter, "filter");
    if (filter.cursor() != null)
      throw new IllegalArgumentException("usage subscriptions cannot have a page cursor");
    var fields = fields(filter);
    fields.put("report_type", type.operation);
    return AsyncSupport.map(
        connection.requestAsync("usage.subscribe", fields, Usage.Initial.class, options),
        asyncResult -> {
          var initial = asyncResult;
          selected(type.operation, filter, initial.filters());
          return initial;
        });
  }

  public java.util.concurrent.CompletionStage<Usage.Initial> subscribeAsync(
      ReportType type, Usage.Filter filter) {
    return subscribeAsync(type, filter, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Void> unsubscribeAsync(
      UUID subscription, RequestOptions options) {

    Objects.requireNonNull(subscription, "subscription");
    return AsyncSupport.map(
        connection.requestAsync(
            "usage.unsubscribe", Map.of("subscription", subscription.toString()), options),
        asyncResult -> {
          var reply = asyncResult;
          if (!reply.successful())
            throw new IOException("usage unsubscribe refused: " + reply.code());

          return null;
        });
  }

  public java.util.concurrent.CompletionStage<Void> unsubscribeAsync(UUID subscription) {
    return unsubscribeAsync(subscription, null);
  }
}
