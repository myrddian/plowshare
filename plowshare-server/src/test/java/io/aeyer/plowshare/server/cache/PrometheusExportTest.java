package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.JobTelemetry;
import io.aeyer.plowshare.server.agents.MicrometerJobTelemetry;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import io.aeyer.plowshare.server.llm.accounting.InferenceTelemetry;
import io.aeyer.plowshare.server.llm.accounting.MicrometerInferenceTelemetry;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Real actuator HTTP wiring on ephemeral local ports, with no datasource or Plowshare runtime. */
class PrometheusExportTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(
      exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
  static class ExportApplication {
    @Bean
    JobTelemetry jobTelemetry(MeterRegistry registry) {
      var telemetry = new MicrometerJobTelemetry(registry);
      telemetry.started().finished(Outcome.Ending.ANSWERED);
      return telemetry;
    }

    @Bean
    InferenceTelemetry inferenceTelemetry(MeterRegistry registry) {
      var telemetry = new MicrometerInferenceTelemetry(registry);
      var call = telemetry.admitted(Lane.CHAT, UsageAttribution.Operation.AGENT_CHAT);
      call.attemptFinished(
          CallLifecycle.SUCCEEDED, TokenUsage.of(100, 20, 120).observation(), 10, 2L);
      call.finished(CallLifecycle.SUCCEEDED, 1L);
      return telemetry;
    }

    @Bean
    ProjectionTelemetry telemetry(MeterRegistry registry) {
      var telemetry = new MicrometerProjectionTelemetry(registry);
      var signals = mock(ProjectionChangeSignals.class);
      when(signals.healthy()).thenReturn(true);
      when(signals.deliveredThrough()).thenAnswer(call -> OptionalLong.of(System.nanoTime()));
      when(signals.reconnectAttempts()).thenReturn(2L);
      telemetry.observeListener(
          signals,
          new ProjectionNotificationProperties(
              true, Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(3)));
      return telemetry;
    }
  }

  @Test
  void operatorCanExposePrometheusOnASeparateManagementListener() throws Exception {
    var app = new SpringApplication(ExportApplication.class);
    app.setWebApplicationType(WebApplicationType.SERVLET);
    try (var context =
        (ServletWebServerApplicationContext)
            app.run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--management.server.port=0",
                "--management.server.address=127.0.0.1",
                "--management.endpoints.web.exposure.include=prometheus")) {
      int apiPort = context.getWebServer().getPort();
      int managementPort =
          context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
      assertNotEquals(apiPort, managementPort);
      try (var client = HttpClient.newHttpClient()) {
        assertEquals(404, get(client, apiPort).statusCode());
        var scrape = get(client, managementPort);
        assertEquals(200, scrape.statusCode());
        assertTrue(
            scrape.headers().firstValue("content-type").orElseThrow().contains("text/plain"));
        assertTrue(scrape.body().contains("plowshare_projection_listener_ready 1.0"));
        assertTrue(scrape.body().contains("plowshare_projection_listener_reconnects_total 2.0"));
        assertTrue(scrape.body().contains("plowshare_projection_listener_proof_age_seconds"));
        assertTrue(scrape.body().contains("plowshare_jobs_active 0.0"));
        assertTrue(
            scrape.body().contains("plowshare_jobs_completed_total{ending=\"answered\"} 1.0"));
        assertTrue(scrape.body().contains("plowshare_jobs_duration_seconds_sum"));
        assertTrue(
            scrape
                .body()
                .lines()
                .anyMatch(
                    line ->
                        line.startsWith("plowshare_inference_tokens_total{")
                            && line.contains("kind=\"input\"")
                            && line.contains("operation=\"agent_chat\"")
                            && line.endsWith(" 100.0")));
        assertTrue(scrape.body().contains("plowshare_inference_attempt_duration_seconds_count"));
      }
    }
  }

  @Test
  void bundledRegistryDoesNotExposePrometheusByDefault() throws Exception {
    var app = new SpringApplication(ExportApplication.class);
    app.setWebApplicationType(WebApplicationType.SERVLET);
    try (var context =
        (ServletWebServerApplicationContext)
            app.run("--server.port=0", "--server.address=127.0.0.1")) {
      assertNotNull(context.getBean(PrometheusMeterRegistry.class));
      try (var client = HttpClient.newHttpClient()) {
        assertEquals(404, get(client, context.getWebServer().getPort()).statusCode());
      }
    }
  }

  private static HttpResponse<String> get(HttpClient client, int port) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
