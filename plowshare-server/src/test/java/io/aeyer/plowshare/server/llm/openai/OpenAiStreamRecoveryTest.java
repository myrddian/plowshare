package io.aeyer.plowshare.server.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.InferenceCapture;
import io.aeyer.plowshare.server.llm.dispatch.InferenceObserver;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real HTTP recovery with a model-free endpoint and separately observed upstream attempts. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class OpenAiStreamRecoveryTest {
  private static int unusedPort() throws Exception {
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static OpenAiTransport transport(int port) {
    var props = new PoolProperties();
    props.setName("fixture");
    props.setBaseUrl("http://127.0.0.1:" + port + "/v1");
    props.setMaxStreamDuration(Duration.ofSeconds(3));
    return new OpenAiTransport(props, new ObjectMapper());
  }

  private static final class Observed implements InferenceObserver {
    final List<CallLifecycle> outcomes = new ArrayList<>();
    int attempts;
    Runnable afterFailure = () -> {};

    public boolean enabled() {
      return true;
    }

    public void queued() {}

    public void started() {}

    public void finished(CallLifecycle outcome) {}

    public InferenceCapture capture() {
      return null;
    }

    public Attempt attempt() {
      attempts++;
      return new Attempt() {
        public void response(Integer status, String id) {}

        public void usage(io.aeyer.plowshare.server.llm.dispatch.TokenUsage usage) {}

        public void reason(String reason) {}

        public void output() {}

        public void finished(CallLifecycle outcome) {
          outcomes.add(outcome);
          if (outcome == CallLifecycle.FAILED) afterFailure.run();
        }
      };
    }
  }

  private static void stream(
      OpenAiTransport transport, Observed observer, AtomicBoolean cancelled, Duration timeout) {
    transport.stream(
        "fixture",
        ChatMessage.conversation(null, "hello"),
        Sampling.NONE,
        List.of(),
        Deltas.DISCARDING,
        cancelled::get,
        null,
        observer,
        timeout);
  }

  @Test
  void successive_streams_open_separate_connections_on_their_first_attempt() throws Exception {
    try (var server = new MockWebServer()) {
      String answer =
          "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
      for (int i = 0; i < 2; i++) {
        server.enqueue(
            new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(answer));
      }
      server.start();
      try (var transport = transport(server.getPort())) {
        for (int i = 0; i < 2; i++) {
          var observer = new Observed();
          stream(transport, observer, new AtomicBoolean(), null);
          assertEquals(1, observer.attempts);
          assertEquals(List.of(CallLifecycle.SUCCEEDED), observer.outcomes);
          // MockWebServer numbers requests within a socket. Zero means a new connection;
          // another POST on the same keep-alive socket would have sequence number one.
          assertEquals(0, server.takeRequest(1, TimeUnit.SECONDS).getSequenceNumber());
        }
      }
    }
  }

  @Test
  void a_failed_connect_gets_a_fresh_separately_accounted_attempt() throws Exception {
    int port = unusedPort();
    var observer = new Observed();
    try (var server = new MockWebServer();
        var transport = transport(port)) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setBody(
                  "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
      observer.afterFailure =
          () -> {
            try {
              server.start(port);
            } catch (java.io.IOException failure) {
              throw new AssertionError(failure);
            }
          };
      stream(transport, observer, new AtomicBoolean(), null);
      assertEquals(2, observer.attempts);
      assertEquals(List.of(CallLifecycle.FAILED, CallLifecycle.SUCCEEDED), observer.outcomes);
      assertEquals(1, server.getRequestCount());
      assertTrue(server.takeRequest().getBody().readUtf8().contains("hello"));
    }
  }

  @Test
  void recovery_is_bounded_and_exposes_the_http_phase_and_cause() throws Exception {
    var observer = new Observed();
    try (var transport = transport(unusedPort())) {
      var failure =
          assertThrows(
              LlmTransportException.class,
              () -> stream(transport, observer, new AtomicBoolean(), null));
      assertEquals(2, observer.attempts);
      assertEquals(List.of(CallLifecycle.FAILED, CallLifecycle.FAILED), observer.outcomes);
      assertTrue(
          failure
              .getMessage()
              .contains("HTTP phase: connection; attempt: 2; cause: ConnectException"));
      assertEquals(1, failure.getSuppressed().length);
    }
  }

  @Test
  void cancellation_between_attempts_does_not_send_a_second_request() throws Exception {
    var cancelled = new AtomicBoolean();
    var observer = new Observed();
    observer.afterFailure = () -> cancelled.set(true);
    try (var transport = transport(unusedPort())) {
      assertThrows(
          CallerAbandonedException.class, () -> stream(transport, observer, cancelled, null));
      assertEquals(1, observer.attempts);
    }
  }

  @Test
  void recovery_cannot_restart_the_original_deadline() throws Exception {
    var observer = new Observed();
    observer.afterFailure =
        () -> {
          try {
            Thread.sleep(100);
          } catch (InterruptedException failure) {
            throw new AssertionError(failure);
          }
        };
    try (var transport = transport(unusedPort())) {
      var failure =
          assertThrows(
              LlmTransportException.class,
              () -> stream(transport, observer, new AtomicBoolean(), Duration.ofMillis(50)));
      assertEquals(1, observer.attempts);
      assertTrue(failure.getMessage().contains("50ms"));
    }
  }

  @Test
  void a_disconnect_after_receiving_the_post_is_not_replayed_even_without_a_response()
      throws Exception {
    var observer = new Observed();
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      server.enqueue(new MockResponse().setResponseCode(503).setBody("must not be requested"));
      server.start();
      try (var transport = transport(server.getPort())) {
        var failure =
            assertThrows(
                LlmTransportException.class,
                () -> stream(transport, observer, new AtomicBoolean(), null));
        assertEquals(1, observer.attempts);
        assertEquals(List.of(CallLifecycle.FAILED), observer.outcomes);
        assertEquals(1, server.getRequestCount());
        assertTrue(server.takeRequest().getBody().readUtf8().contains("hello"));
        assertTrue(failure.getMessage().contains("HTTP phase: request_body; attempt: 1"));
      }
    }
  }

  @Test
  void an_http_refusal_is_not_retried() throws Exception {
    var observer = new Observed();
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(503).setBody("busy"));
      server.start();
      try (var transport = transport(server.getPort())) {
        assertThrows(
            LlmTransportException.class,
            () -> stream(transport, observer, new AtomicBoolean(), null));
        assertEquals(1, observer.attempts);
        assertEquals(1, server.getRequestCount());
      }
    }
  }
}
