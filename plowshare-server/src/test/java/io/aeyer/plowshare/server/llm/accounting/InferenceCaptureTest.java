package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.OpenAiCompatible;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.PriceProperties;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real dispatcher, pool, transport, journal, and PostgreSQL; only the inference server is a
 * fixture.
 */
@Testcontainers
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class InferenceCaptureTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource database;
  private JdbcTemplate jdbc;
  private AccountingStore store;
  @TempDir Path directory;
  private static final String CHAT =
      """
            {"id":"completion_fixture","choices":[{"message":{"content":"answer-kept"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}
            """;

  @BeforeAll
  static void migrate() {
    database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(database).load().migrate();
  }

  @BeforeEach
  void fresh() {
    jdbc = new JdbcTemplate(database);
    jdbc.execute(
        "TRUNCATE inference_accounting_events, inference_attempts, inference_calls, inference_pricing_versions, inference_accounting_instances, inference_accounting_journals");
    store =
        new AccountingStore(
            jdbc,
            new DataSourceTransactionManager(database),
            AccountingFixtures.MAPPER,
            AccountingFixtures.CLOCK);
  }

  @Test
  void
      chat_and_embedding_capture_actual_routes_and_prices_and_project_after_restart_without_inference_replay()
          throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(json(CHAT).setHeader("x-request-id", "req_chat"));
      server.enqueue(
          json(
              "{\"data\":[{\"embedding\":[0.1,0.2]}],\"usage\":{\"prompt_tokens\":5,\"total_tokens\":5}}"));
      UUID chatId;
      UUID embeddingId;
      try (var harness = wire(server, null, 1)) {
        var chat = harness.dispatcher.complete(chat());
        var embedding =
            harness.dispatcher.embed(
                new EmbeddingRequest(
                    "model",
                    List.of("private-embedding-content"),
                    null,
                    owner().withOperation(UsageAttribution.Operation.EMBEDDING_WRITE)));
        chatId = chat.capture().callId();
        embeddingId = embedding.capture().callId();
        assertTrue(chat.capture().durable());
        assertTrue(embedding.capture().durable());
        assertEquals("answer-kept", chat.content());
        assertEquals("pool", chat.servedBy().pool());
        assertEquals(8, harness.journal.health().pendingEvents());
        for (var entry : harness.journal.readBatch(100)) {
          String body =
              AccountingFixtures.MAPPER
                  .copy()
                  .findAndRegisterModules()
                  .writeValueAsString(entry.event());
          assertFalse(body.contains("private-prompt-content"));
          assertFalse(body.contains("private-embedding-content"));
          assertFalse(body.contains("answer-kept"));
        }
      }
      try (var journal = journal(null)) {
        var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
        try (var projector =
            new AccountingProjector(journal, store, recorder, AccountingFixtures.CLOCK)) {
          assertTrue(projector.projectOnce());
          assertEquals(0, journal.health().pendingEvents());
          assertEquals(2, count("inference_calls"));
          assertEquals(
              List.of("SUCCEEDED", "SUCCEEDED"),
              jdbc.queryForList("SELECT lifecycle FROM inference_calls", String.class));
          assertEquals(
              100L,
              jdbc.queryForObject(
                  "SELECT input_tokens FROM inference_attempts WHERE call_id = ?",
                  Long.class,
                  chatId));
          assertEquals(
              5L,
              jdbc.queryForObject(
                  "SELECT input_tokens FROM inference_attempts WHERE call_id = ?",
                  Long.class,
                  embeddingId));
          assertNull(
              jdbc.queryForObject(
                  "SELECT output_tokens FROM inference_attempts WHERE call_id = ?",
                  Long.class,
                  embeddingId));
          assertEquals(
              "req_chat",
              jdbc.queryForObject(
                  "SELECT provider_request_id FROM inference_attempts WHERE call_id = ?",
                  String.class,
                  chatId));
          assertEquals(
              0,
              new BigDecimal("0.000185")
                  .compareTo(
                      jdbc.queryForObject(
                          "SELECT sum(cost_amount) FROM inference_attempts", BigDecimal.class)));
          assertEquals(
              2,
              jdbc.queryForObject(
                  "SELECT count(*) FROM inference_calls WHERE project_id = 'project-snapshot' AND 'r-no-call' = ANY(ancestor_run_ids)",
                  Integer.class));
          assertEquals(
              2,
              jdbc.queryForObject(
                  "SELECT count(*) FROM inference_calls WHERE queue_millis IS NOT NULL AND admission_accounting_millis IS NOT NULL",
                  Integer.class));
          assertEquals(
              2,
              jdbc.queryForObject(
                  "SELECT count(*) FROM inference_attempts WHERE start_accounting_millis IS NOT NULL",
                  Integer.class));
        }
      }
      assertEquals(2, server.getRequestCount());
    }
  }

  @Test
  void streaming_snapshots_replace_each_other_and_final_empty_choices_usage_is_retained()
      throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 1)) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"content":"hello"}}],"usage":{"prompt_tokens":10,"completion_tokens":1,"total_tokens":11}}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}

                    data: [DONE]

                    """));
      var result = harness.dispatcher.stream(chat(), Deltas.DISCARDING);
      project(harness);
      assertTrue(result.capture().durable());
      assertEquals("hello", result.content());
      assertEquals(
          100L, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
      assertEquals(
          20L, jdbc.queryForObject("SELECT output_tokens FROM inference_attempts", Long.class));
      assertNotNull(
          jdbc.queryForObject("SELECT first_output_millis FROM inference_attempts", Long.class));
      assertEquals(1, count("inference_attempts"));
    }
  }

  @Test
  void explicit_io_retry_keeps_the_unknown_first_attempt_and_original_price() throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 2)) {
      server.enqueue(
          json(CHAT + " ".repeat(1000))
              .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
      server.enqueue(json(CHAT));
      var result = harness.dispatcher.complete(chat());
      project(harness);
      assertTrue(result.capture().durable());
      assertEquals(2, server.getRequestCount());
      assertEquals(2, count("inference_attempts"));
      assertEquals(
          List.of("UNKNOWN", "COMPLETE"),
          jdbc.queryForList(
              "SELECT usage_coverage FROM inference_attempts ORDER BY attempt_number",
              String.class));
      assertEquals(
          List.of("FAILED", "SUCCEEDED"),
          jdbc.queryForList(
              "SELECT outcome FROM inference_attempts ORDER BY attempt_number", String.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_attempts WHERE cost_amount IS NULL", Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(DISTINCT price_version) FROM inference_attempts", Integer.class));
    }
  }

  @Test
  void a_503_with_retry_after_zero_cannot_trigger_an_invisible_http_retry() throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 3)) {
      server.enqueue(json("{}").setResponseCode(503).setHeader("Retry-After", "0"));
      server.enqueue(json(CHAT));
      assertThrows(LlmTransportException.class, () -> harness.dispatcher.complete(chat()));
      project(harness);
      assertEquals(1, server.getRequestCount());
      assertEquals(1, count("inference_attempts"));
      assertEquals(
          503, jdbc.queryForObject("SELECT http_status FROM inference_attempts", Integer.class));
      assertEquals(
          "FAILED", jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
      assertNull(
          jdbc.queryForObject("SELECT cost_amount FROM inference_attempts", BigDecimal.class));
    }
  }

  @Test
  void redirects_and_refusals_do_not_replay_inference_or_store_credential_echoes()
      throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 3)) {
      harness.properties.setApiKey("fake-accounting-secret");
      server.enqueue(
          json("fake-accounting-secret")
              .setResponseCode(307)
              .setHeader("Location", server.url("/other"))
              .setHeader("x-request-id", "fake-accounting-secret"));
      server.enqueue(json(CHAT));
      assertThrows(LlmTransportException.class, () -> harness.dispatcher.complete(chat()));
      project(harness);
      assertEquals(1, server.getRequestCount());
      assertEquals(
          "REFUSED", jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
      assertNull(
          jdbc.queryForObject("SELECT provider_request_id FROM inference_attempts", String.class));
      assertFalse(
          jdbc.queryForObject(
                  "SELECT string_agg(event_data::text, '') FROM inference_accounting_events",
                  String.class)
              .contains("fake-accounting-secret"));
    }
  }

  @Test
  void invalid_embedding_vectors_still_keep_the_reported_consumption() throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 1)) {
      server.enqueue(json("{\"data\":[],\"usage\":{\"prompt_tokens\":5,\"total_tokens\":5}}"));
      assertThrows(
          LlmTransportException.class,
          () ->
              harness.dispatcher.embed(
                  new EmbeddingRequest("model", List.of("private"), null, owner())));
      project(harness);
      assertEquals(
          "FAILED", jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
      assertEquals(
          5L, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
      assertEquals(
          "COMPLETE",
          jdbc.queryForObject("SELECT usage_coverage FROM inference_attempts", String.class));
    }
  }

  @Test
  void cancellation_after_output_preserves_the_last_available_usage_and_stops_a_quiet_stream()
      throws Exception {
    var cancelled = new AtomicBoolean();
    try (var server = new MockWebServer();
        var harness = wire(server, null, 1)) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}

                    data: {"choices":[{"delta":{"content":"hello"}}]}

                    """));
      assertThrows(
          CallerAbandonedException.class,
          () -> harness.dispatcher.stream(chat(), delta -> cancelled.set(true), cancelled::get));
      project(harness);
      assertEquals(
          "CANCELLED", jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
      assertEquals(
          "CANCELLED", jdbc.queryForObject("SELECT outcome FROM inference_attempts", String.class));
      assertEquals(
          100L, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
    }
  }

  @Test
  void terminal_disk_failure_preserves_the_answer_and_only_replays_metadata() throws Exception {
    var fail = new AtomicBoolean();
    try (var server = new MockWebServer();
        var harness =
            wire(
                server,
                channel -> {
                  if (fail.getAndSet(false)) {
                    throw new java.io.IOException("injected terminal storage failure");
                  }
                  channel.force(true);
                },
                1)) {
      server.setDispatcher(
          new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              fail.set(true); // Admission and attempt start are already durable when HTTP arrives.
              return json(CHAT);
            }
          });
      var result = harness.dispatcher.complete(chat());
      assertEquals("answer-kept", result.content());
      assertFalse(result.capture().durable());
      assertEquals(2, harness.recorder.health().bufferedTerminalEvents());
      assertThrows(LlmException.class, () -> harness.dispatcher.complete(chat()));
      harness.recorder.flushPending();
      project(harness);
      assertEquals(1, count("inference_calls"));
      assertEquals(1, count("inference_attempts"));
      assertEquals(
          100L, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void a_full_journal_refuses_model_admission_before_any_http_request() throws Exception {
    try (var server = new MockWebServer();
        var journal =
            new AccountingJournal(
                directory, 4096, 1024, Duration.ofSeconds(1), AccountingFixtures.MAPPER)) {
      while (true) {
        try {
          journal.append(AccountingFixtures.created());
        } catch (AccountingUnavailableException full) {
          assertEquals(AccountingJournal.Problem.FULL, full.problem());
          break;
        }
      }
      var properties = properties(server);
      var settings = new LlmProperties();
      settings.setPools(List.of(properties));
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      var accounting =
          new DurableInferenceAccounting(
              recorder, PricingCatalog.from(settings), AccountingFixtures.CLOCK);
      try (var dispatcher =
          new LlmDispatcher(List.of(pool(properties)), null, type -> "", accounting)) {
        var refusal =
            assertThrows(AccountingUnavailableException.class, () -> dispatcher.complete(chat()));
        assertEquals(AccountingJournal.Problem.FULL, refusal.problem());
        assertEquals(0, server.getRequestCount());
      }
    }
  }

  @Test
  void an_uncertain_attempt_start_refuses_upstream_and_latches_admission_until_recovery()
      throws Exception {
    var fail = new AtomicBoolean();
    try (var server = new MockWebServer();
        var journal =
            journal(
                channel -> {
                  if (fail.get()) {
                    throw new java.io.IOException("injected attempt-start storage failure");
                  }
                  channel.force(true);
                })) {
      var properties = properties(server);
      var settings = new LlmProperties();
      settings.setPools(List.of(properties));
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      var accounting =
          new DurableInferenceAccounting(
              recorder, PricingCatalog.from(settings), AccountingFixtures.CLOCK);
      InferenceAccounting gated =
          (pool, model, specifier, lane, owner) -> {
            var observer = accounting.begin(pool, model, specifier, lane, owner);
            fail.set(
                true); // Call admission succeeded; only the worker's attempt-start append fails.
            return observer;
          };
      try (var dispatcher = new LlmDispatcher(List.of(pool(properties)), null, type -> "", gated)) {
        assertThrows(AccountingUnavailableException.class, () -> dispatcher.complete(chat()));
        assertEquals(1, recorder.health().lostTerminalEvents());
        fail.set(false);
        assertThrows(AccountingUnavailableException.class, () -> dispatcher.complete(chat()));
        assertEquals(0, server.getRequestCount());
      }
    }
  }

  @Test
  void queued_expiry_has_no_attempt_and_separate_queue_duration() throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 1);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.enqueue(json(CHAT).setHeadersDelay(400, TimeUnit.MILLISECONDS));
      var first = executor.submit(() -> harness.dispatcher.complete(chat()));
      assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
      assertThrows(
          LlmSaturatedException.class,
          () -> harness.dispatcher.complete(chat().withBudget(Duration.ofMillis(20))));
      assertTrue(first.get(3, TimeUnit.SECONDS).capture().durable());
      project(harness);
      assertEquals(2, count("inference_calls"));
      assertEquals(1, count("inference_attempts"));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_calls WHERE lifecycle = 'NOT_DISPATCHED' AND attempt_count = 0 AND queue_millis >= 20",
              Integer.class));
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void an_interrupted_caller_does_not_abandon_the_worker_that_records_upstream_completion()
      throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 1)) {
      server.enqueue(json(CHAT).setHeadersDelay(200, TimeUnit.MILLISECONDS));
      var failure = new AtomicReference<Throwable>();
      Thread caller =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      harness.dispatcher.complete(chat());
                    } catch (Throwable thrown) {
                      failure.set(thrown);
                    }
                  });
      assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
      caller.interrupt();
      caller.join(1000);
      assertFalse(caller.isAlive());
      assertInstanceOf(LlmException.class, failure.get());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (harness.journal.health().pendingEvents() < 4 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(4, harness.journal.health().pendingEvents());
      project(harness);
      assertEquals(
          "SUCCEEDED", jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
      assertEquals(
          100L, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
    }
  }

  @Test
  void missing_and_invalid_usage_keep_usable_content_without_claiming_free_complete_calls()
      throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 1)) {
      server.enqueue(
          json(
              "{\"choices\":[{\"message\":{\"content\":\"answer-kept\"},\"finish_reason\":\"stop\"}]}"));
      server.enqueue(json(CHAT.replace("\"prompt_tokens\":100", "\"prompt_tokens\":-1")));
      assertEquals("answer-kept", harness.dispatcher.complete(chat()).content());
      assertEquals("answer-kept", harness.dispatcher.complete(chat()).content());
      project(harness);
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_calls WHERE lifecycle = 'SUCCEEDED'", Integer.class));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_attempts WHERE cost_amount IS NULL", Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_attempts WHERE usage_coverage = 'UNKNOWN'",
              Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_attempts WHERE usage_coverage = 'INVALID'",
              Integer.class));
    }
  }

  @Test
  void stream_timeout_records_an_unknown_failed_attempt_without_retry() throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 3)) {
      harness.properties.setMaxStreamDuration(Duration.ofMillis(80));
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      assertThrows(
          LlmTransportException.class, () -> harness.dispatcher.stream(chat(), Deltas.DISCARDING));
      project(harness);
      assertEquals(1, count("inference_attempts"));
      assertEquals(
          "FAILED", jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
      assertEquals(
          "UNKNOWN",
          jdbc.queryForObject("SELECT usage_coverage FROM inference_attempts", String.class));
      assertNull(
          jdbc.queryForObject("SELECT cost_amount FROM inference_attempts", BigDecimal.class));
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void a_pinned_stream_uses_its_actual_pool_billing_route_instead_of_another_pools_price()
      throws Exception {
    try (var server = new MockWebServer();
        var journal = journal(null)) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}

                    data: [DONE]

                    """));
      var first = properties(server);
      var second = properties(server);
      second.setName("alternate");
      second.setBillingRoute("alternate-route");
      var settings = new LlmProperties();
      settings.setPools(List.of(first, second));
      settings.setPricing(Map.of("contract", price()));
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      var accounting =
          new DurableInferenceAccounting(
              recorder, PricingCatalog.from(settings), AccountingFixtures.CLOCK);
      try (var dispatcher =
          new LlmDispatcher(List.of(pool(first), pool(second)), null, type -> "", accounting)) {
        var result = dispatcher.streamOn("alternate", chat(), Deltas.DISCARDING, () -> false);
        assertEquals("alternate", result.servedBy().pool());
        store.project(journal.journalId(), journal.readBatch(100));
        assertEquals(
            "alternate-route",
            jdbc.queryForObject("SELECT billing_route FROM inference_calls", String.class));
        assertEquals(
            "UNKNOWN",
            jdbc.queryForObject("SELECT cost_kind FROM inference_attempts", String.class));
        assertTrue(
            jdbc.queryForObject("SELECT cost_result::text FROM inference_attempts", String.class)
                .contains("MISSING_PRICE"));
        assertNull(jdbc.queryForObject("SELECT price_version FROM inference_calls", String.class));
        assertEquals(
            100L, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
      }
    }
  }

  @Test
  void spring_enabled_wiring_replaces_the_noop_ledger_and_captures_production_calls()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(json(CHAT));
      new org.springframework.boot.test.context.runner.ApplicationContextRunner()
          .withConfiguration(
              org.springframework.boot.autoconfigure.AutoConfigurations.of(
                  org.springframework.boot.autoconfigure.context
                      .ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(
              io.aeyer.plowshare.server.llm.LlmConfig.class,
              AccountingConfig.class,
              NoOpTokenLedger.class)
          .withBean(
              InferencePrices.class,
              () -> {
                var settings = new LlmProperties();
                settings.setPools(List.of(properties(server)));
                return PricingCatalog.from(settings);
              })
          .withBean(
              com.fasterxml.jackson.databind.ObjectMapper.class, () -> AccountingFixtures.MAPPER)
          .withBean(
              io.aeyer.plowshare.server.data.DataLayout.class,
              () -> new io.aeyer.plowshare.server.data.DataLayout(directory).initialise())
          .withBean(JdbcTemplate.class, () -> jdbc)
          .withBean(
              org.springframework.transaction.PlatformTransactionManager.class,
              () -> new DataSourceTransactionManager(database))
          .withPropertyValues(
              "plowshare.llm.accounting.enabled=true",
              "plowshare.llm.embedding-model=model",
              "plowshare.llm.embedding-dim=2",
              "plowshare.llm.embedding-max-input-tokens=1536",
              "plowshare.llm.default-context-length=64000",
              "plowshare.llm.pools[0].name=pool",
              "plowshare.llm.pools[0].models[0]=model",
              "plowshare.llm.pools[0].base-url="
                  + server.url("/v1").newBuilder().host("127.0.0.1").build())
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                assertEquals(0, context.getBeansOfType(NoOpTokenLedger.class).size());
                var result = context.getBean(LlmDispatcher.class).complete(chat());
                assertTrue(result.capture().durable());
                assertTrue(context.getBean(AccountingProjector.class).projectOnce());
                assertEquals(
                    "SUCCEEDED",
                    jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
                assertEquals(
                    "UNKNOWN",
                    jdbc.queryForObject("SELECT cost_kind FROM inference_attempts", String.class));
                assertTrue(
                    jdbc.queryForObject(
                            "SELECT cost_result::text FROM inference_attempts", String.class)
                        .contains("MISSING_PRICE"));
              });
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void native_prefill_stream_records_its_stats_without_a_second_sse_attempt() throws Exception {
    try (var server = new MockWebServer();
        var journal = journal(null)) {
      server.enqueue(new MockResponse().withWebSocketUpgrade(nativeNode(true, true)));
      var properties = properties(server);
      try (var base = new OpenAiCompatible(properties, AccountingFixtures.MAPPER)) {
        var socket =
            new io.aeyer.plowshare.server.llm.lmstudio.LmStudioSocket(
                properties, AccountingFixtures.MAPPER, base, base);
        var provider = new io.aeyer.plowshare.server.llm.lmstudio.LmStudio(base, socket);
        var observer = observer(journal, properties);
        var result =
            provider.stream(
                "model",
                chat().messages(),
                Sampling.NONE,
                List.of(),
                Deltas.DISCARDING,
                () -> false,
                ignored -> {},
                observer);
        observer.finished(CallLifecycle.SUCCEEDED);
        assertEquals("native-answer", result.content());
        store.project(journal.journalId(), journal.readBatch(100));
        assertEquals(1, count("inference_attempts"));
        assertEquals(
            100L, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
        assertEquals(
            "STOP",
            jdbc.queryForObject("SELECT finish_reason FROM inference_attempts", String.class));
        assertNotNull(
            jdbc.queryForObject("SELECT first_output_millis FROM inference_attempts", Long.class));
        assertEquals(
            "COMPLETE",
            jdbc.queryForObject("SELECT usage_coverage FROM inference_attempts", String.class));
        assertEquals(1, server.getRequestCount()); // One WebSocket handshake; no SSE fallback.
      }
    }
  }

  @Test
  void native_disconnect_keeps_unknown_consumption_but_an_auth_refusal_has_no_inference_attempt()
      throws Exception {
    try (var server = new MockWebServer();
        var journal = journal(null)) {
      server.enqueue(new MockResponse().withWebSocketUpgrade(nativeNode(true, false)));
      server.enqueue(new MockResponse().withWebSocketUpgrade(nativeNode(false, false)));
      var properties = properties(server);
      try (var base = new OpenAiCompatible(properties, AccountingFixtures.MAPPER)) {
        var socket =
            new io.aeyer.plowshare.server.llm.lmstudio.LmStudioSocket(
                properties, AccountingFixtures.MAPPER, base, base);
        var first = observer(journal, properties);
        assertThrows(
            LlmTransportException.class,
            () ->
                socket.stream(
                    "model",
                    chat().messages(),
                    Sampling.NONE,
                    List.of(),
                    Deltas.DISCARDING,
                    () -> false,
                    ignored -> {},
                    first));
        first.finished(CallLifecycle.FAILED);
        var second = observer(journal, properties);
        assertThrows(
            LlmTransportException.class,
            () ->
                socket.stream(
                    "model",
                    chat().messages(),
                    Sampling.NONE,
                    List.of(),
                    Deltas.DISCARDING,
                    () -> false,
                    ignored -> {},
                    second));
        second.finished(CallLifecycle.FAILED);
        store.project(journal.journalId(), journal.readBatch(100));
        assertEquals(2, count("inference_calls"));
        assertEquals(1, count("inference_attempts"));
        assertEquals(
            "UNKNOWN",
            jdbc.queryForObject("SELECT usage_coverage FROM inference_attempts", String.class));
        assertNull(
            jdbc.queryForObject("SELECT cost_amount FROM inference_attempts", BigDecimal.class));
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM inference_calls WHERE lifecycle = 'NOT_DISPATCHED'",
                Integer.class));
      }
    }
  }

  private InferenceObserver observer(AccountingJournal journal, PoolProperties properties) {
    var settings = new LlmProperties();
    settings.setPools(List.of(properties));
    settings.setPricing(Map.of("contract", price()));
    var accounting =
        new DurableInferenceAccounting(
            new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10),
            PricingCatalog.from(settings),
            AccountingFixtures.CLOCK);
    var observer = accounting.begin("pool", "model", "model", Lane.CHAT, owner());
    observer.queued();
    observer.started();
    return observer;
  }

  private static okhttp3.WebSocketListener nativeNode(boolean authorized, boolean success) {
    return new okhttp3.WebSocketListener() {
      private int received;

      @Override
      public void onMessage(okhttp3.WebSocket socket, String text) {
        if (++received == 1) {
          socket.send("{\"success\":" + authorized + "}");
          return;
        }
        if (received == 2) {
          socket.send(
              "{\"type\":\"channelSend\",\"message\":{\"type\":\"fragment\",\"fragment\":{\"content\":\"native-answer\",\"reasoningType\":\"none\"}}}");
          if (!success) {
            socket.close(1011, null);
            return;
          }
          socket.send(
              "{\"type\":\"channelSend\",\"message\":{\"type\":\"success\",\"stats\":{\"stopReason\":\"eosFound\",\"promptTokensCount\":100,\"predictedTokensCount\":20,\"totalTokensCount\":120}}}");
          socket.send("{\"type\":\"channelClose\"}");
        }
      }

      @Override
      public void onClosing(okhttp3.WebSocket socket, int code, String reason) {
        socket.close(code, null);
      }
    };
  }

  @Test
  void preflight_is_saved_separately_and_failure_keeps_actual_content_and_consumption()
      throws Exception {
    try (var server = new MockWebServer();
        var harness = wire(server, null, 1)) {
      harness
          .properties
          .getCounting()
          .setUrl(server.url("/tokenize").newBuilder().host("127.0.0.1").build().toString());
      harness.properties.getCounting().setAutomatic(true);
      server.enqueue(json("{\"count\":99,\"max_model_len\":8192,\"tokens\":[]}"));
      server.enqueue(json(CHAT));
      var result = harness.dispatcher.complete(chat());
      assertEquals("answer-kept", result.content());
      server.enqueue(json("{\"count\":-1}"));
      server.enqueue(json(CHAT));
      var afterFailure =
          harness.dispatcher.complete(
              chat()
                  .withMessages(
                      java.util.List.of(
                          io.aeyer.plowshare.server.llm.dispatch.ChatMessage.user(
                              "different private prompt"))));
      assertEquals("answer-kept", afterFailure.content());
      project(harness);
      assertEquals(2, count("inference_calls"));
      assertEquals(2, count("inference_attempts"));
      assertEquals(
          200L,
          jdbc.queryForObject("SELECT sum(input_tokens) FROM inference_attempts", Long.class));
      assertEquals(
          List.of("MEASURED", "UNKNOWN"),
          jdbc
              .queryForList(
                  "SELECT preflight_observation->>'basis' FROM inference_calls ORDER BY last_call_sequence,call_id",
                  String.class)
              .stream()
              .sorted()
              .toList());
      assertEquals(
          "99",
          jdbc.queryForObject(
              "SELECT preflight_observation->>'tokens' FROM inference_calls WHERE preflight_observation->>'basis'='MEASURED'",
              String.class));
      assertEquals(4, server.getRequestCount());
    }
  }

  @Test
  void immutable_admission_prices_survive_rate_edits_and_retries_while_new_calls_use_new_versions()
      throws Exception {
    try (var server = new MockWebServer();
        var journal = journal(null)) {
      var properties = properties(server);
      var bound = price();
      var settings = new LlmProperties();
      settings.setPools(List.of(properties));
      settings.setPricing(Map.of("contract", bound));
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      var old =
          new DurableInferenceAccounting(
                  recorder, PricingCatalog.from(settings), AccountingFixtures.CLOCK)
              .begin("pool", "model", "model", Lane.CHAT, owner());
      bound.setRevision("changed-price");
      bound.setRatesPerMillion(Map.of("input", new BigDecimal("2"), "output", new BigDecimal("8")));
      var fresh =
          new DurableInferenceAccounting(
                  recorder, PricingCatalog.from(settings), AccountingFixtures.CLOCK)
              .begin("pool", "model", "model", Lane.CHAT, owner());
      var measured = new TokenUsage(100, 20, 120, null);
      var first = old.attempt();
      first.succeeded(measured, "stop");
      var retried = old.attempt();
      retried.succeeded(measured, "stop");
      old.finished(CallLifecycle.SUCCEEDED);
      var next = fresh.attempt();
      next.succeeded(measured, "stop");
      fresh.finished(CallLifecycle.SUCCEEDED);
      store.project(journal.journalId(), journal.readBatch(100));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(DISTINCT price_version) FROM inference_calls", Integer.class));
      assertEquals(
          List.of(
              new BigDecimal("0.000180"), new BigDecimal("0.000180"), new BigDecimal("0.000360")),
          jdbc.queryForList(
              "SELECT cost_amount FROM inference_attempts ORDER BY cost_amount", BigDecimal.class));
      assertEquals(0, server.getRequestCount());
    }
  }

  private Harness wire(MockWebServer server, AccountingJournal.Sync sync, int retries) {
    var properties = properties(server);
    properties.setRetryMaxAttempts(retries);
    var price = price();
    var settings = new LlmProperties();
    settings.setPools(List.of(properties));
    settings.setPricing(Map.of("contract", price));
    var journal = journal(sync);
    var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
    var accounting =
        new DurableInferenceAccounting(
            recorder, PricingCatalog.from(settings), AccountingFixtures.CLOCK);
    var pool =
        new LlmPool(
            "pool",
            List.of("model"),
            Map.of(),
            1,
            1,
            Duration.ofSeconds(2),
            new OpenAiCompatible(properties, AccountingFixtures.MAPPER));
    var dispatcher =
        new LlmDispatcher(
            List.of(pool),
            entry -> fail("durable capture must not double-book the legacy ledger"),
            type -> "",
            accounting);
    return new Harness(properties, journal, recorder, dispatcher);
  }

  private static PoolProperties properties(MockWebServer server) {
    var properties = new PoolProperties();
    properties.setName("pool");
    properties.setModels(List.of("model"));
    properties.setBillingRoute("local");
    properties.setBaseUrl(server.url("/v1").newBuilder().host("127.0.0.1").build().toString());
    properties.setRetryInitialBackoff(Duration.ofMillis(1));
    return properties;
  }

  private static PriceProperties price() {
    var price = new PriceProperties();
    price.setRevision("local-v1");
    price.setBillingRoute("local");
    price.setModel("model");
    price.setCurrency("USD");
    price.setRatesPerMillion(Map.of("input", BigDecimal.ONE, "output", new BigDecimal("4")));
    return price;
  }

  private static LlmPool pool(PoolProperties properties) {
    return new LlmPool(
        properties.getName(),
        List.of("model"),
        Map.of(),
        1,
        1,
        Duration.ofSeconds(2),
        new OpenAiCompatible(properties, AccountingFixtures.MAPPER));
  }

  private AccountingJournal journal(AccountingJournal.Sync sync) {
    return sync == null
        ? new AccountingJournal(
            directory, 1024 * 1024, 8192, Duration.ofSeconds(1), AccountingFixtures.MAPPER)
        : new AccountingJournal(
            directory, 1024 * 1024, 8192, Duration.ofSeconds(1), AccountingFixtures.MAPPER, sync);
  }

  private void project(Harness harness) {
    store.project(harness.journal.journalId(), harness.journal.readBatch(100));
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private static MockResponse json(String body) {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
  }

  private static MockResponse sse(String body) {
    return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
  }

  private static UsageAttribution owner() {
    return AccountingFixtures.metadata().attribution();
  }

  private static ChatRequest chat() {
    return ChatRequest.of("model", null, "private-prompt-content").withAttribution(owner());
  }

  private record Harness(
      PoolProperties properties,
      AccountingJournal journal,
      AccountingRecorder recorder,
      LlmDispatcher dispatcher)
      implements AutoCloseable {
    @Override
    public void close() {
      dispatcher.close();
      journal.close();
    }
  }
}
