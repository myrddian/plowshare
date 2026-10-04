package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aeyer.plowshare.server.llm.accounting.AccountingEvent.AttemptFinished;
import io.aeyer.plowshare.server.llm.accounting.AccountingEvent.AttemptStarted;
import io.aeyer.plowshare.server.llm.accounting.AccountingEvent.CallCreated;
import io.aeyer.plowshare.server.llm.accounting.AccountingEvent.CallFinished;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent projection of atomic call/attempt metadata, independent of transcript transactions.
 */
public final class AccountingStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final ObjectMapper mapper;
  private final Clock clock;

  public AccountingStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      ObjectMapper mapper,
      Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.mapper =
        mapper
            .copy()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Return means committed. The journal acknowledgement must happen afterwards, outside this
   * transaction.
   */
  public void project(UUID journalId, List<AccountingJournal.Entry> entries) {
    if (entries.isEmpty()) {
      return;
    }
    transaction.executeWithoutResult(
        status -> {
          registerJournal(journalId, entries.getFirst().event().at());
          long watermark =
              jdbc.queryForObject(
                  """
                    SELECT projected_sequence FROM inference_accounting_journals WHERE journal_id = ? FOR UPDATE
                    """,
                  Long.class,
                  journalId);
          for (AccountingJournal.Entry entry : List.copyOf(entries)) {
            AccountingEvent event = entry.event();
            String body = json(event);
            List<Boolean> same =
                jdbc.query(
                    """
                        SELECT journal_id = ? AND jsonb_strip_nulls(event_data) = jsonb_strip_nulls(?::jsonb)
                        FROM inference_accounting_events WHERE event_id = ?
                        """,
                    (row, number) -> row.getBoolean(1),
                    journalId,
                    body,
                    event.eventId());
            if (!same.isEmpty()) {
              require(same.getFirst(), "conflicting replay identity");
              require(
                  entry.sequence() <= watermark || entry.sequence() == watermark + 1,
                  "journal replay sequence gap");
            } else {
              require(entry.sequence() == watermark + 1, "journal projection sequence gap");
              apply(journalId, event);
              jdbc.update(
                  """
                            INSERT INTO inference_accounting_events(event_id, journal_id, journal_sequence,
                                call_id, call_sequence, event_data) VALUES (?, ?, ?, ?, ?, ?::jsonb)
                            """,
                  event.eventId(),
                  journalId,
                  entry.sequence(),
                  event.callId(),
                  event.callSequence(),
                  body);
            }
            watermark = Math.max(watermark, entry.sequence());
          }
          jdbc.update(
              """
                    UPDATE inference_accounting_journals SET projected_sequence = ?, last_projected_at = ?,
                        tracking_started_at = LEAST(tracking_started_at, ?) WHERE journal_id = ?
                    """,
              watermark,
              sql(clock.instant()),
              sql(entries.getFirst().event().at()),
              journalId);
        });
  }

  /** Register capture activation even when the first inference has not happened yet. */
  public void trackingStarted(UUID id, Instant first) {
    transaction.executeWithoutResult(status -> registerJournal(id, first));
  }

  private void registerJournal(UUID id, Instant first) {
    jdbc.update(
        """
                INSERT INTO inference_accounting_journals(journal_id, tracking_started_at)
                VALUES (?, ?) ON CONFLICT (journal_id) DO NOTHING
                """,
        id,
        sql(first));
  }

  private void apply(UUID journalId, AccountingEvent event) {
    if (event.payload() instanceof CallCreated created) {
      create(journalId, event, created);
      return;
    }
    CallState call = call(event.callId());
    require(call.instanceId().equals(event.instanceId()), "call instance mismatch");
    require(event.callSequence() == call.sequence() + 1, "call sequence gap");
    require(!call.lifecycle().terminal(), "call already ended");
    require(
        !event.at().truncatedTo(java.time.temporal.ChronoUnit.MICROS).isBefore(call.lastEventAt()),
        "call timestamps reversed");
    if (event.payload() instanceof AttemptStarted started) {
      require(started.attemptNumber() == call.attempts() + 1, "attempt number gap");
      require(openAttempts(event.callId()) == 0, "attempt already running");
      jdbc.update(
          """
                    INSERT INTO inference_attempts(attempt_id, call_id, attempt_number, started_at, outcome)
                    VALUES (?, ?, ?, ?, 'RUNNING')
                    """,
          started.attemptId(),
          event.callId(),
          started.attemptNumber(),
          sql(event.at()));
      jdbc.update(
          """
                    UPDATE inference_calls SET lifecycle = 'RUNNING', attempt_count = attempt_count + 1 WHERE call_id = ?
                    """,
          event.callId());
    } else if (event.payload() instanceof AttemptFinished finished) {
      require(
          Objects.equals(call.priceVersion(), finished.cost().priceVersion()),
          "attempt price snapshot mismatch");
      int changed = finishAttempt(event.callId(), event.at(), finished, call.lane());
      require(changed == 1, "attempt absent, ended or timestamp reversed");
    } else if (event.payload() instanceof CallFinished finished) {
      require(openAttempts(event.callId()) == 0, "cannot finish a call with a running attempt");
      require(
          (call.attempts() == 0) == (finished.outcome() == CallLifecycle.NOT_DISPATCHED),
          "not-dispatched outcome must agree with zero attempts");
      if (finished.outcome() == CallLifecycle.SUCCEEDED) {
        String latest =
            jdbc.queryForObject(
                """
                        SELECT outcome FROM inference_attempts WHERE call_id = ? ORDER BY attempt_number DESC LIMIT 1
                        """,
                String.class,
                event.callId());
        require(
            CallLifecycle.SUCCEEDED.name().equals(latest),
            "successful call needs a successful final attempt");
      }
      jdbc.update(
          "UPDATE inference_calls SET lifecycle = ?, ended_at = ?, queue_millis = ?, admission_accounting_millis = ?, preflight_observation = ?::jsonb WHERE call_id = ?",
          finished.outcome().name(),
          sql(event.at()),
          finished.queueMillis(),
          finished.admissionAccountingMillis(),
          finished.preflight() == null ? null : json(finished.preflight()),
          event.callId());
    }
    jdbc.update(
        "UPDATE inference_calls SET last_call_sequence = ?, last_event_at = ? WHERE call_id = ?",
        event.callSequence(),
        sql(event.at()),
        event.callId());
  }

  private CallState call(UUID id) {
    List<CallState> rows =
        jdbc.query(
            """
                SELECT instance_id, last_call_sequence, lifecycle, last_event_at, attempt_count, price_version, lane
                FROM inference_calls WHERE call_id = ? FOR UPDATE
                """,
            (row, number) ->
                new CallState(
                    row.getObject(1, UUID.class),
                    row.getLong(2),
                    CallLifecycle.valueOf(row.getString(3)),
                    row.getObject(4, OffsetDateTime.class).toInstant(),
                    row.getInt(5),
                    row.getString(6),
                    Lane.valueOf(row.getString(7))),
            id);
    require(rows.size() == 1, "call is absent");
    return rows.getFirst();
  }

  private int openAttempts(UUID call) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM inference_attempts WHERE call_id = ? AND outcome = 'RUNNING'",
        Integer.class,
        call);
  }

  private void create(UUID journalId, AccountingEvent event, CallCreated created) {
    jdbc.update(
        """
                INSERT INTO inference_accounting_instances(instance_id, journal_id, started_at)
                VALUES (?, ?, ?) ON CONFLICT (instance_id) DO NOTHING
                """,
        event.instanceId(),
        journalId,
        sql(event.at()));
    Boolean live =
        jdbc.queryForObject(
            """
                SELECT journal_id = ? AND ended_at IS NULL FROM inference_accounting_instances WHERE instance_id = ?
                """,
            Boolean.class,
            journalId,
            event.instanceId());
    require(Boolean.TRUE.equals(live), "instance belongs to another journal or has ended");
    RateCard price = created.price();
    String version = price == null ? null : price.version();
    if (price != null) {
      String body = json(price);
      jdbc.update(
          """
                    INSERT INTO inference_pricing_versions(version, billing_route, wire_model, currency, mode, rate_card)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (version) DO NOTHING
                    """,
          version,
          price.billingRoute(),
          price.model(),
          price.currency(),
          price.mode().name(),
          body);
      Boolean equal =
          jdbc.queryForObject(
              "SELECT rate_card = ?::jsonb FROM inference_pricing_versions WHERE version = ?",
              Boolean.class,
              body,
              version);
      require(Boolean.TRUE.equals(equal), "price version contents changed");
    }
    UsageAttribution owner = created.attribution();
    jdbc.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                    INSERT INTO inference_calls(call_id, instance_id, attribution, account_handle, project_id, scope,
                        conversation_id, root_conversation_id, ancestor_conversation_ids,
                        run_id, parent_run_id, root_run_id, ancestor_run_ids,
                        orchestration_id, root_orchestration_id, ancestor_orchestration_ids,
                        agent_name, turn_ordinal, step_ordinal, operation, attribution_status,
                        specifier, pool, wire_model, model_family, billing_route, price_version, lane,
                        created_at, last_event_at, lifecycle, last_call_sequence)
                    VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', 1)
                    """);
          int column = 1;
          statement.setObject(column++, event.callId());
          statement.setObject(column++, event.instanceId());
          statement.setString(column++, json(owner));
          statement.setString(column++, owner.accountHandle());
          statement.setString(column++, owner.projectId());
          statement.setString(column++, owner.scope().name());
          statement.setString(column++, owner.conversations().id());
          statement.setString(column++, owner.conversations().rootId());
          statement.setArray(
              column++,
              connection.createArrayOf("text", owner.conversations().ancestors().toArray()));
          statement.setString(column++, owner.runs().id());
          statement.setString(column++, owner.runs().parentId());
          statement.setString(column++, owner.runs().rootId());
          statement.setArray(
              column++, connection.createArrayOf("text", owner.runs().ancestors().toArray()));
          statement.setString(column++, owner.orchestrations().id());
          statement.setString(column++, owner.orchestrations().rootId());
          statement.setArray(
              column++,
              connection.createArrayOf("text", owner.orchestrations().ancestors().toArray()));
          statement.setString(column++, owner.agentName());
          statement.setObject(column++, owner.turnOrdinal());
          statement.setObject(column++, owner.stepOrdinal());
          statement.setString(column++, owner.operation().name());
          statement.setString(column++, owner.status().name());
          statement.setString(column++, created.specifier());
          statement.setString(column++, created.pool());
          statement.setString(column++, created.wireModel());
          statement.setString(column++, created.modelFamily());
          statement.setString(column++, created.billingRoute());
          statement.setString(column++, version);
          statement.setString(column++, created.lane().name());
          statement.setObject(column++, sql(event.at()));
          statement.setObject(column, sql(event.at()));
          return statement;
        });
  }

  private int finishAttempt(UUID call, Instant at, AttemptFinished finished, Lane lane) {
    UsageObservation usage = finished.usage();
    CostResult cost = finished.cost();
    return jdbc.update(
        """
                UPDATE inference_attempts SET ended_at = ?, outcome = ?, provider_request_id = ?, http_status = ?,
                    finish_reason = ?, observation = ?::jsonb, input_tokens = ?, output_tokens = ?,
                    provider_total_tokens = ?, cache_read_tokens = ?, cache_write_tokens = ?, reasoning_tokens = ?,
                    usage_source = ?, usage_coverage = ?, normalization_version = ?, cost_result = ?::jsonb,
                    cost_kind = ?, cost_amount = ?, cost_currency = ?, price_version = ?, first_output_millis = ?, duration_millis = ?,
                    start_accounting_millis = ?
                WHERE attempt_id = ? AND call_id = ? AND outcome = 'RUNNING' AND started_at <= ?
                """,
        sql(at),
        finished.outcome().name(),
        finished.providerRequestId(),
        finished.httpStatus(),
        finished.finishReason().name(),
        json(usage),
        usage.inputTokens(),
        usage.outputTokens(),
        usage.providerTotalTokens(),
        usage.cacheReadTokens(),
        usage.cacheWriteTokens(),
        usage.reasoningTokens(),
        usage.source().name(),
        usage.coverage(lane).name(),
        usage.normalizationVersion(),
        json(cost),
        cost.kind().name(),
        cost.amount(),
        cost.currency(),
        cost.priceVersion(),
        finished.firstOutputMillis(),
        finished.durationMillis(),
        finished.startAccountingMillis(),
        finished.attemptId(),
        call,
        sql(at));
  }

  /**
   * Called only after replay has drained. Exclusive journal locking proves other instances of this
   * same journal are dead; another server's journal is never interrupted here.
   */
  public int recoverPreviousInstances(UUID journalId, UUID currentInstance) {
    return Objects.requireNonNull(
        transaction.execute(
            status -> {
              registerJournal(journalId, clock.instant());
              jdbc.queryForObject(
                  "SELECT projected_sequence FROM inference_accounting_journals WHERE journal_id = ? FOR UPDATE",
                  Long.class,
                  journalId);
              List<InterruptedAttempt> running =
                  jdbc.query(
                      """
                    SELECT a.attempt_id, a.call_id, c.lane, p.rate_card::text, a.started_at
                    FROM inference_attempts a JOIN inference_calls c USING(call_id)
                    JOIN inference_accounting_instances i ON i.instance_id = c.instance_id
                    LEFT JOIN inference_pricing_versions p ON p.version = c.price_version
                    WHERE i.journal_id = ? AND i.instance_id <> ? AND a.outcome = 'RUNNING'
                    """,
                      (row, number) ->
                          new InterruptedAttempt(
                              row.getObject(1, UUID.class),
                              row.getObject(2, UUID.class),
                              Lane.valueOf(row.getString(3)),
                              row.getString(4),
                              row.getObject(5, OffsetDateTime.class).toInstant()),
                      journalId,
                      currentInstance);
              for (InterruptedAttempt attempt : running) {
                RateCard price = attempt.price() == null ? null : readPrice(attempt.price());
                CostResult cost =
                    price == null
                        ? CostResult.unpriced()
                        : price.quote(UsageObservation.UNKNOWN, attempt.lane());
                Instant ended =
                    clock.instant().isBefore(attempt.started())
                        ? attempt.started()
                        : clock.instant();
                finishAttempt(
                    attempt.callId(),
                    ended,
                    new AttemptFinished(
                        attempt.attemptId(),
                        CallLifecycle.INTERRUPTED,
                        UsageObservation.UNKNOWN,
                        cost,
                        null,
                        null,
                        AccountingEvent.FinishReason.UNKNOWN,
                        null,
                        java.time.Duration.between(attempt.started(), ended).toMillis()),
                    attempt.lane());
              }
              int changed =
                  jdbc.update(
                      """
                    UPDATE inference_calls c SET lifecycle = CASE WHEN attempt_count = 0 THEN 'NOT_DISPATCHED' ELSE 'INTERRUPTED' END,
                        ended_at = GREATEST(last_event_at, ?)
                    FROM inference_accounting_instances i
                    WHERE c.instance_id = i.instance_id AND i.journal_id = ? AND i.instance_id <> ? AND c.ended_at IS NULL
                    """,
                      sql(clock.instant()),
                      journalId,
                      currentInstance);
              jdbc.update(
                  """
                    UPDATE inference_accounting_instances SET ended_at = GREATEST(started_at, ?)
                    WHERE journal_id = ? AND instance_id <> ? AND ended_at IS NULL
                    """,
                  sql(clock.instant()),
                  journalId,
                  currentInstance);
              return changed;
            }));
  }

  private RateCard readPrice(String body) {
    try {
      return mapper.readValue(body, RateCard.class);
    } catch (JsonProcessingException invalid) {
      throw new ProjectionConflictException("stored price cannot be decoded");
    }
  }

  public long watermark(UUID journalId) {
    List<Long> rows =
        jdbc.query(
            "SELECT projected_sequence FROM inference_accounting_journals WHERE journal_id = ?",
            (row, number) -> row.getLong(1),
            journalId);
    return rows.isEmpty() ? 0 : rows.getFirst();
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException invalid) {
      throw new ProjectionConflictException("accounting metadata cannot be encoded");
    }
  }

  private static OffsetDateTime sql(Instant instant) {
    return instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new ProjectionConflictException(message);
    }
  }

  private record CallState(
      UUID instanceId,
      long sequence,
      CallLifecycle lifecycle,
      Instant lastEventAt,
      int attempts,
      String priceVersion,
      Lane lane) {}

  private record InterruptedAttempt(
      UUID attemptId, UUID callId, Lane lane, String price, Instant started) {}

  /** Safe semantic failure; the projector reports degradation instead of skipping this event. */
  public static final class ProjectionConflictException extends RuntimeException {
    ProjectionConflictException(String message) {
      super(message);
    }
  }
}
