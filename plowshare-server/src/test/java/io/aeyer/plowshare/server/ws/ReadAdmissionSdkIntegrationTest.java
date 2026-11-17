package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.ScheduleClient;
import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.events.ScheduleDefinitions;
import io.aeyer.plowshare.server.events.SchedulePage;
import io.aeyer.plowshare.server.events.ScheduleRecord;
import io.aeyer.plowshare.server.events.ScheduleStore;
import io.aeyer.plowshare.server.reads.BoundedReadAdmission;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadCapacityProperties;
import io.aeyer.plowshare.server.reads.ReadSchedulingProperties;
import io.aeyer.plowshare.server.requests.RequestProjects;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

/** Real WebSockets/current public SDK; repositories are mocked and no PostgreSQL is started. */
class ReadAdmissionSdkIntegrationTest {
  private static ConfigurableApplicationContext context;
  private static State state;
  private static String origin;

  @BeforeAll
  static void start() {
    context =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.profiles.active=read-admission-sdk");
    state = context.getBean(State.class);
    origin = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
  }

  @AfterAll
  static void stop() {
    if (state != null) state.releaseSend.countDown();
    if (context != null) context.close();
  }

  @BeforeEach
  void reset() {
    state.reset();
  }

  @ParameterizedTest
  @EnumSource(Plowshare.TransportMode.class)
  void capacityIsPacedUnderTheSameIdAndRunsOnceEvenWhileTheRefusalSendIsStillHeld(
      Plowshare.TransportMode mode) throws Exception {
    var admission = context.getBean(BoundedReadAdmission.class);
    var held = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
    state.releaseSeat = held::close;
    try (held;
        var connection =
            Plowshare.connectAsync(
                    origin,
                    "fixture",
                    UUID.randomUUID().toString(),
                    Duration.ofSeconds(10),
                    ignored -> {},
                    mode,
                    Plowshare.DispatchMode.CONCURRENT)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)) {
      try {
        var result =
            new ScheduleClient(connection).listPageAsync("fixture", null, 50).toCompletableFuture();
        assertTrue(state.refusalSent.await(5, TimeUnit.SECONDS));
        assertEquals(0, state.callsAtRefusal.get());
        assertFalse(result.isDone(), "The waiting SDK absorbs capacity flow control");
        assertTrue(
            state.resubmitted.await(5, TimeUnit.SECONDS),
            "Same-ID SDK resubmission arrives while the earlier physical send remains held");
        if (mode == Plowshare.TransportMode.LEGACY)
          assertTrue(
              state.entered.await(5, TimeUnit.SECONDS),
              "Legacy retry executes before the earlier send returns");
        assertTrue(state.ids.size() >= 2, "SDK submitted the original request again");
        assertEquals(1, Set.copyOf(state.ids).size(), "Resubmission retains the original ID");
        assertFalse(result.isDone(), "Successful output remains queued behind the held send");
        state.releaseSend.countDown();
        assertEquals("schedule-once", result.get(5, TimeUnit.SECONDS).getFirst().name());
        assertEquals(1, state.calls.get());
        verifyNoInteractions(context.getBean(FailureReporter.class));
      } finally {
        state.releaseSend.countDown();
      }
    }
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }

  private static final class State {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger callsAtRefusal = new AtomicInteger();
    final CopyOnWriteArrayList<String> ids = new CopyOnWriteArrayList<>();
    final AtomicBoolean hold = new AtomicBoolean();
    volatile CountDownLatch refusalSent;
    volatile CountDownLatch releaseSend;
    volatile CountDownLatch entered;
    volatile CountDownLatch resubmitted;
    volatile Runnable releaseSeat;

    void reset() {
      calls.set(0);
      callsAtRefusal.set(-1);
      ids.clear();
      hold.set(true);
      refusalSent = new CountDownLatch(1);
      releaseSend = new CountDownLatch(1);
      entered = new CountDownLatch(1);
      resubmitted = new CountDownLatch(1);
      releaseSeat = () -> {};
    }
  }

  private static JsonNode applicationFrame(WebSocketMessage<?> message) throws IOException {
    if (!(message instanceof TextMessage text)) return null;
    var frame = FrameJson.answering().readTree(text.getPayload());
    if (frame.path("kind").asText().equals("transport.segment")) {
      if (frame.path("segmentCount").asInt() != 1) return null;
      String body =
          new String(
              Base64.getDecoder().decode(frame.path("data").asText()), StandardCharsets.UTF_8);
      return FrameJson.answering().readTree(body);
    }
    return frame;
  }

  /**
   * Holds the real server's send only after bytes reached the client, without changing runtime
   * code.
   */
  private static final class HeldSend extends WebSocketHandlerDecorator
      implements SubProtocolCapable {
    private final EventChannelHandler channel;
    private final State state;
    private final ConcurrentMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    HeldSend(EventChannelHandler channel, State state) {
      super(channel);
      this.channel = channel;
      this.state = state;
    }

    public List<String> getSubProtocols() {
      return channel.getSubProtocols();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
      var wrapped =
          new WebSocketSessionDecorator(session) {
            @Override
            public void sendMessage(WebSocketMessage<?> message) throws IOException {
              var frame = applicationFrame(message);
              boolean hold =
                  frame != null
                      && frame.path("payload").path("code").asText().equals("CAPACITY_EXHAUSTED")
                      && state.hold.compareAndSet(true, false);
              if (hold) {
                state.callsAtRefusal.set(state.calls.get());
                state.releaseSeat.run();
              }
              super.sendMessage(message);
              if (hold) {
                state.refusalSent.countDown();
                try {
                  if (!state.releaseSend.await(5, TimeUnit.SECONDS))
                    throw new IOException("Fixture send wait expired");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IOException(interrupted);
                }
              }
            }
          };
      sessions.put(session.getId(), wrapped);
      channel.afterConnectionEstablished(wrapped);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message)
        throws Exception {
      var frame = applicationFrame(message);
      if (frame != null && frame.path("type").asText().equals("schedule.list")) {
        state.ids.add(frame.path("id").asText());
        if (state.ids.size() >= 2) state.resubmitted.countDown();
      }
      channel.handleMessage(sessions.get(session.getId()), message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable failure) throws Exception {
      channel.handleTransportError(sessions.getOrDefault(session.getId(), session), failure);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
        throws Exception {
      channel.afterConnectionClosed(sessions.getOrDefault(session.getId(), session), status);
      sessions.remove(session.getId());
    }
  }

  @Configuration
  @Profile("read-admission-sdk")
  @EnableWebSocket
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebSocketServletAutoConfiguration.class
  })
  static class Wiring {
    @Bean
    State state() {
      return new State();
    }

    @Bean
    FailureReporter failures() {
      return mock(FailureReporter.class);
    }

    @Bean
    AdminStore accounts() {
      var accounts = mock(AdminStore.class);
      when(accounts.sessionCurrent("alice", 1L)).thenReturn(true);
      return accounts;
    }

    @Bean
    ProjectAuthorization authorization() {
      var authorization = mock(ProjectAuthorization.class);
      when(authorization.allowed(anyString(), any(AccessRequest.class), nullable(String.class)))
          .thenReturn(true);
      return authorization;
    }

    @Bean
    RequestProjects requestProjects() {
      return RequestProjects.EXPLICIT_ONLY;
    }

    @Bean
    SocketAuthorization socketAuthorization(AdminStore accounts) {
      return new SocketAuthorization(accounts);
    }

    @Bean
    BoundedReadAdmission admission() {
      return new BoundedReadAdmission(
          new ReadCapacityProperties(1, 1, 2, 1, 8, 2),
          new ReadSchedulingProperties(64, 4, 2000, 2000, 8, 4, 1));
    }

    @Bean
    FrameRouter router(State state, BoundedReadAdmission admission, FailureReporter failures) {
      var store = mock(ScheduleStore.class);
      when(store.page(eq("alice"), eq("fixture"), any(SchedulePage.class)))
          .thenAnswer(
              call -> {
                state.calls.incrementAndGet();
                state.entered.countDown();
                return List.of(
                    new ScheduleRecord(
                        "schedule-once",
                        "0 0 9 * * *",
                        "UTC",
                        "tick",
                        false,
                        Instant.parse("2026-10-20T00:00:00Z"),
                        "alice"));
              });
      var definitions = mock(ScheduleDefinitions.class);
      return new FrameRouter(
          Map.of("schedule.list", new ScheduleListHandler(store, () -> definitions)),
          null,
          admission,
          failures);
    }

    @Bean
    EventChannelHandler channel(FrameRouter router) {
      return new EventChannelHandler(new SessionRegistry(), router, new Watchers());
    }

    @Bean
    WebSocketConfigurer sockets(EventChannelHandler channel, State state) {
      return registry ->
          registry
              .addHandler(new HeldSend(channel, state), EventChannelHandler.PATH)
              .addInterceptors(new HandleInterceptor());
    }

    @Bean
    OncePerRequestFilter identity() {
      return new OncePerRequestFilter() {
        protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
          if (!"Bearer fixture".equals(request.getHeader("Authorization"))) {
            response.sendError(401);
            return;
          }
          request.setAttribute(AuthFilter.HANDLE_ATTRIBUTE, "alice");
          request.setAttribute("plowshare.sessionVersion", 1L);
          chain.doFilter(request, response);
        }
      };
    }
  }
}
