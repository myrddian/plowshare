package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.*;
import java.util.*;

/** Immutable usage snapshot contracts; quantities use decimal strings to preserve precision. */
public final class Usage {
  private Usage() {}

  public record Filter(
      String conversation,
      String project,
      String agent,
      String run,
      String orchestration,
      String model,
      String pool,
      String route,
      String scope,
      Instant from,
      Instant to,
      @JsonProperty("group_by") List<String> groupBy,
      String cursor,
      Integer limit) {
    public Filter {
      conversation = ContractValues.optionalIdentity(conversation, "conversation", 512);
      project = ContractValues.optionalIdentity(project, "project", 512);
      agent = ContractValues.optionalIdentity(agent, "agent", 512);
      run = ContractValues.optionalIdentity(run, "run", 512);
      orchestration = ContractValues.optionalIdentity(orchestration, "orchestration", 512);
      model = ContractValues.optionalIdentity(model, "model", 512);
      pool = ContractValues.optionalIdentity(pool, "pool", 512);
      route = ContractValues.optionalIdentity(route, "route", 512);
      if (scope != null && !Set.of("direct", "subtree").contains(scope))
        throw new IllegalArgumentException("invalid usage scope");
      if (groupBy != null) {
        groupBy = ContractValues.list(groupBy, "groupBy", 2);
        if (groupBy.stream().distinct().count() != groupBy.size()
            || !Set.of("day", "model", "pool", "agent", "operation", "project", "run")
                .containsAll(groupBy))
          throw new IllegalArgumentException("unsupported usage grouping");
      }
      cursor = ContractValues.optionalIdentity(cursor, "cursor", 4096);
      if (limit != null && (limit < 1 || limit > 200))
        throw new IllegalArgumentException("usage limit must be 1..200");
      if (from != null
          && to != null
          && (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0))
        throw new IllegalArgumentException("usage range must be positive and at most 366 days");
    }
  }

  public record Resolved(String type, Filter filter) {
    public Resolved {
      if (!Set.of(
              "usage.conversation",
              "usage.project",
              "usage.agent",
              "usage.run",
              "usage.orchestration",
              "usage.models",
              "usage.pools",
              "usage.calls")
          .contains(type)) throw new IllegalArgumentException("unsupported usage report");
      Objects.requireNonNull(filter, "filter");
    }
  }

  public record Report(
      Resolved filters, Aggregate totals, List<Aggregate> groups, String cursor, Health health) {
    public Report {
      Objects.requireNonNull(filters, "filters");
      Objects.requireNonNull(totals, "totals");
      Objects.requireNonNull(health, "health");
      groups = ContractValues.list(groups, "groups", 200);
      cursor = ContractValues.optionalIdentity(cursor, "cursor", 4096);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Aggregate(
      String day,
      String model,
      String pool,
      String agent,
      String operation,
      String project,
      String run,
      String calls,
      String attempts,
      @JsonProperty("incomplete_attempts") String incompleteAttempts,
      @JsonProperty("active_calls") String activeCalls,
      @JsonProperty("failed_attempts") String failedAttempts,
      @JsonProperty("global_calls") String globalCalls,
      @JsonProperty("system_calls") String systemCalls,
      @JsonProperty("legacy_calls") String legacyCalls,
      @JsonProperty("unknown_cost_attempts") String unknownCostAttempts,
      @JsonProperty("input_tokens") String inputTokens,
      @JsonProperty("input_tokens_known") String inputTokensKnown,
      @JsonProperty("output_tokens") String outputTokens,
      @JsonProperty("output_tokens_known") String outputTokensKnown,
      @JsonProperty("provider_total_tokens") String providerTotalTokens,
      @JsonProperty("provider_total_tokens_known") String providerTotalTokensKnown,
      @JsonProperty("cache_read_tokens") String cacheReadTokens,
      @JsonProperty("cache_read_tokens_known") String cacheReadTokensKnown,
      @JsonProperty("cache_write_tokens") String cacheWriteTokens,
      @JsonProperty("cache_write_tokens_known") String cacheWriteTokensKnown,
      @JsonProperty("reasoning_tokens") String reasoningTokens,
      @JsonProperty("reasoning_tokens_known") String reasoningTokensKnown,
      Map<String, String> costs,
      @JsonProperty("usage_complete") boolean usageComplete,
      @JsonProperty("cost_complete") boolean costComplete,
      boolean complete,
      @JsonProperty("ancestor_runs") List<String> ancestorRuns) {
    public Aggregate {
      count(calls, "calls", false);
      count(attempts, "attempts", false);
      count(incompleteAttempts, "incompleteAttempts", false);
      count(activeCalls, "activeCalls", false);
      count(failedAttempts, "failedAttempts", false);
      count(globalCalls, "globalCalls", false);
      count(systemCalls, "systemCalls", false);
      count(legacyCalls, "legacyCalls", false);
      count(unknownCostAttempts, "unknownCostAttempts", false);
      count(inputTokens, "inputTokens", false);
      count(inputTokensKnown, "inputTokensKnown", false);
      count(outputTokens, "outputTokens", false);
      count(outputTokensKnown, "outputTokensKnown", false);
      count(providerTotalTokens, "providerTotalTokens", false);
      count(providerTotalTokensKnown, "providerTotalTokensKnown", false);
      count(cacheReadTokens, "cacheReadTokens", false);
      count(cacheReadTokensKnown, "cacheReadTokensKnown", false);
      count(cacheWriteTokens, "cacheWriteTokens", false);
      count(cacheWriteTokensKnown, "cacheWriteTokensKnown", false);
      count(reasoningTokens, "reasoningTokens", false);
      count(reasoningTokensKnown, "reasoningTokensKnown", false);
      if (day != null) LocalDate.parse(day);
      for (String selector : Arrays.asList(model, pool, agent, operation, project, run))
        ContractValues.optionalIdentity(selector, "group selector", 1024);
      if (costs.size() > 256) throw new IllegalArgumentException("too many currencies");
      costs = Map.copyOf(costs);
      costs.forEach(
          (currency, amount) -> {
            currency(currency);
            decimal(amount, "cost", false);
          });
      if (ancestorRuns != null)
        ancestorRuns =
            ContractValues.list(ancestorRuns, "ancestorRuns", 10000).stream()
                .map(id -> ContractValues.identity(id, "ancestor run", 1024))
                .toList();
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Health(
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          @JsonProperty("tracking_started_at")
          Instant trackingStartedAt,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          @JsonProperty("last_projected_at")
          Instant lastProjectedAt,
      String watermark,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          @JsonProperty("as_of")
          Instant asOf,
      @JsonProperty("historical_usage") String historicalUsage,
      @JsonProperty("capture_enabled") boolean captureEnabled,
      @JsonProperty("pending_events") String pendingEvents,
      @JsonProperty("journal_bytes") String journalBytes,
      @JsonProperty("journal_capacity_bytes") String journalCapacityBytes,
      @JsonProperty("journal_problem") String journalProblem,
      @JsonProperty("projection_lag_millis") String projectionLagMillis,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          @JsonProperty("oldest_pending_at")
          Instant oldestPendingAt,
      @JsonProperty("buffered_terminal_events") Integer bufferedTerminalEvents,
      @JsonProperty("lost_terminal_events") String lostTerminalEvents,
      @JsonProperty("projection_conflict") Boolean projectionConflict,
      @JsonProperty("projection_problem") String projectionProblem) {
    public Health {
      Objects.requireNonNull(asOf);
      historicalUsage = ContractValues.text(historicalUsage, "historicalUsage", 1024, true);
      journalProblem = ContractValues.optionalIdentity(journalProblem, "journalProblem", 128);
      projectionProblem =
          ContractValues.optionalIdentity(projectionProblem, "projectionProblem", 128);
      count(watermark, "watermark", false);
      for (String count :
          new String[] {
            pendingEvents,
            journalBytes,
            journalCapacityBytes,
            projectionLagMillis,
            lostTerminalEvents
          }) count(count, "health count", true);
      if (bufferedTerminalEvents != null && bufferedTerminalEvents < 0)
        throw new IllegalArgumentException("negative terminal buffer");
    }
  }

  public record Updated(String subscription, long revision, Report report) implements ServerPush {
    public Updated {
      java.util.UUID.fromString(subscription);
      if (revision < 1) throw new IllegalArgumentException("invalid snapshot revision");
      Objects.requireNonNull(report);
    }
  }

  public record Closed(String subscription, String code) implements ServerPush {
    public Closed {
      java.util.UUID.fromString(subscription);
      if (!"BAD_REQUEST".equals(code))
        throw new IllegalArgumentException("invalid subscription close code");
    }
  }

  private static void currency(String value) {
    if (value == null || !value.matches("[A-Z]{3}"))
      throw new IllegalArgumentException("invalid cost currency");
    java.util.Currency.getInstance(value);
  }

  private static void count(String value, String field, boolean optional) {
    if (value == null && optional) return;
    if (value == null || !value.matches("[0-9]{1,64}"))
      throw new IllegalArgumentException(field + " must be a nonnegative integer string");
  }

  private static void decimal(String value, String field, boolean optional) {
    if (value == null && optional) return;
    if (value == null || value.length() > 128 || new java.math.BigDecimal(value).signum() < 0)
      throw new IllegalArgumentException(field + " must be a nonnegative decimal string");
  }

  public record Audit(Resolved filters, List<Call> calls, String cursor, Health health) {
    public Audit {
      Objects.requireNonNull(filters, "filters");
      Objects.requireNonNull(health, "health");
      calls = ContractValues.list(calls, "calls", 200);
      cursor = ContractValues.optionalIdentity(cursor, "cursor", 4096);
    }
  }

  public record AttemptPage(
      Resolved filters, String call, List<Attempt> attempts, String cursor, Health health) {
    public AttemptPage {
      Objects.requireNonNull(filters, "filters");
      Objects.requireNonNull(health, "health");
      UUID.fromString(call);
      attempts = ContractValues.list(attempts, "attempts", 200);
      cursor = ContractValues.optionalIdentity(cursor, "cursor", 4096);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Attempt(
      @JsonProperty("attempt_id") String attemptId,
      @JsonProperty("attempt_number") int attemptNumber,
      String outcome,
      @JsonProperty("http_status") Integer httpStatus,
      @JsonProperty("finish_reason") String finishReason,
      @JsonProperty("input_tokens") String inputTokens,
      @JsonProperty("output_tokens") String outputTokens,
      @JsonProperty("provider_total_tokens") String providerTotalTokens,
      @JsonProperty("cache_read_tokens") String cacheReadTokens,
      @JsonProperty("cache_write_tokens") String cacheWriteTokens,
      @JsonProperty("reasoning_tokens") String reasoningTokens,
      @JsonProperty("usage_source") String usageSource,
      @JsonProperty("usage_coverage") String usageCoverage,
      @JsonProperty("cost_kind") String costKind,
      @JsonProperty("cost_amount") String costAmount,
      @JsonProperty("cost_currency") String costCurrency,
      @JsonProperty("price_version") String priceVersion,
      @JsonProperty("first_output_millis") Long firstOutputMillis,
      @JsonProperty("duration_millis") Long durationMillis,
      @JsonProperty("start_accounting_millis") Long startAccountingMillis,
      @JsonProperty("cost_reasons") List<String> costReasons) {
    public Attempt {
      java.util.UUID.fromString(attemptId);
      if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
      for (String quantity :
          new String[] {
            inputTokens,
            outputTokens,
            providerTotalTokens,
            cacheReadTokens,
            cacheWriteTokens,
            reasoningTokens
          }) count(quantity, "tokens", true);
      decimal(costAmount, "costAmount", true);
      if (costCurrency != null) currency(costCurrency);
      if (usageSource != null)
        word(usageSource, "usageSource", Set.of("PROVIDER", "TOKENIZER", "ESTIMATE", "NONE"));
      if (usageCoverage != null)
        word(
            usageCoverage,
            "usageCoverage",
            Set.of("COMPLETE", "PARTIAL", "UNKNOWN", "INVALID", "NOT_APPLICABLE"));
      if (costKind != null)
        word(
            costKind,
            "costKind",
            Set.of("ESTIMATED", "REPORTED", "INCLUDED", "ZERO_RATE", "UNKNOWN", "PARTIAL"));
      if (outcome != null) lifecycle(outcome);
      if (costReasons != null) {
        costReasons = ContractValues.list(costReasons, "costReasons", 32);
        for (String reason : costReasons)
          word(
              reason,
              "costReason",
              Set.of(
                  "MISSING_PRICE",
                  "MISSING_INPUT",
                  "MISSING_OUTPUT",
                  "MISSING_INPUT_RATE",
                  "MISSING_OUTPUT_RATE",
                  "MISSING_CACHE_READ",
                  "MISSING_CACHE_WRITE",
                  "UNKNOWN_CACHE_RELATION",
                  "INVALID_USAGE",
                  "NO_PROVIDER_USAGE",
                  "TIER_INPUT_UNKNOWN"));
      }
      if (httpStatus != null && (httpStatus < 100 || httpStatus > 599))
        throw new IllegalArgumentException("invalid attempt HTTP status");
      finishReason = ContractValues.text(finishReason, "finishReason", 1024, false);
      priceVersion = ContractValues.optionalIdentity(priceVersion, "priceVersion", 1024);
      for (Long timing : new Long[] {firstOutputMillis, durationMillis, startAccountingMillis})
        if (timing != null && timing < 0)
          throw new IllegalArgumentException("negative attempt timing");
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Call(
      @JsonProperty("call_id") String callId,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          @JsonProperty("created_at")
          Instant createdAt,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          @JsonProperty("ended_at")
          Instant endedAt,
      String lifecycle,
      String pool,
      @JsonProperty("wire_model") String wireModel,
      @JsonProperty("model_family") String modelFamily,
      @JsonProperty("billing_route") String billingRoute,
      @JsonProperty("price_version") String priceVersion,
      @JsonProperty("project_id") String projectId,
      String scope,
      @JsonProperty("conversation_id") String conversationId,
      @JsonProperty("run_id") String runId,
      @JsonProperty("orchestration_id") String orchestrationId,
      @JsonProperty("agent_name") String agentName,
      String operation,
      @JsonProperty("turn_ordinal") String turnOrdinal,
      @JsonProperty("step_ordinal") String stepOrdinal,
      @JsonProperty("attempt_count") int attemptCount,
      @JsonProperty("queue_millis") Long queueMillis,
      @JsonProperty("admission_accounting_millis") Long admissionAccountingMillis,
      @JsonProperty("preflight_observation") ContextCount preflightObservation,
      @JsonProperty("attempt_cursor") String attemptCursor,
      List<Attempt> attempts,
      @JsonProperty("attempts_truncated") boolean attemptsTruncated) {
    public Call {
      java.util.UUID.fromString(callId);
      Objects.requireNonNull(createdAt);
      Objects.requireNonNull(lifecycle);
      Usage.lifecycle(lifecycle);
      if (attemptCount < 0
          || queueMillis != null && queueMillis < 0
          || admissionAccountingMillis != null && admissionAccountingMillis < 0)
        throw new IllegalArgumentException("negative call counts");
      count(turnOrdinal, "turnOrdinal", true);
      count(stepOrdinal, "stepOrdinal", true);
      attempts = ContractValues.list(attempts, "attempts", 200);
      for (String identity :
          new String[] {
            pool,
            wireModel,
            modelFamily,
            billingRoute,
            priceVersion,
            projectId,
            scope,
            conversationId,
            runId,
            orchestrationId,
            agentName,
            operation
          }) ContractValues.optionalIdentity(identity, "call selector", 1024);
      attemptCursor = ContractValues.optionalIdentity(attemptCursor, "attemptCursor", 4096);
      if (endedAt != null && endedAt.isBefore(createdAt))
        throw new IllegalArgumentException("call ends before creation");
    }
  }

  /** Preflight context observation, distinct from consumed tokens or booked cost. */
  public record ContextCount(
      String tokens,
      String basis,
      String source,
      String pool,
      String model,
      String revision,
      Instant countedAt,
      long elapsedMillis,
      boolean cached,
      List<String> gaps) {
    public ContextCount {
      count(tokens, "tokens", true);
      word(basis, "basis", Set.of("MEASURED", "ESTIMATED", "UNKNOWN"));
      source = ContractValues.identity(source, "source", 1024);
      pool = ContractValues.optionalIdentity(pool, "pool", 1024);
      model = ContractValues.optionalIdentity(model, "model", 1024);
      revision = ContractValues.optionalIdentity(revision, "revision", 1024);
      Objects.requireNonNull(countedAt, "countedAt");
      if (elapsedMillis < 0) throw new IllegalArgumentException("negative preflight timing");
      gaps = ContractValues.list(gaps, "gaps", 100);
      for (String gap : gaps) ContractValues.identity(gap, "gap", 1024);
    }
  }

  public record Initial(String subscription, long revision, Resolved filters, Report report) {
    public Initial {
      UUID.fromString(subscription);
      if (revision < 0) throw new IllegalArgumentException("negative subscription revision");
      Objects.requireNonNull(filters, "filters");
      Objects.requireNonNull(report, "report");
      if (!filters.equals(report.filters()))
        throw new IllegalArgumentException("subscription report filters differ");
    }
  }

  private static void lifecycle(String value) {
    word(
        value,
        "lifecycle",
        Set.of(
            "QUEUED",
            "RUNNING",
            "SUCCEEDED",
            "REFUSED",
            "FAILED",
            "CANCELLED",
            "INTERRUPTED",
            "NOT_DISPATCHED"));
  }

  private static void word(String value, String field, Set<String> words) {
    if (value == null || !words.contains(value))
      throw new IllegalArgumentException("invalid " + field);
  }
}
