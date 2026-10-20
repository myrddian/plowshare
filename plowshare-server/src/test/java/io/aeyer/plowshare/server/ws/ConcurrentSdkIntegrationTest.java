package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ConversationGrowth;
import io.aeyer.plowshare.protocol.ServerPush;
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
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.context.annotation.Import;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A real embedded event server plus the public typed SDK. Persistence and authority are mocked;
 * this establishes negotiated execution/correlation/notification behavior, not database latency or
 * the production authentication gate. The fixture filter supplies a fixed authenticated subject.
 */
class ConcurrentSdkIntegrationTest {
  private static ConfigurableApplicationContext context;
  private static String origin;
  private static State state;

  @BeforeAll
  static void start() {
    context =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.profiles.active=concurrent-sdk-integration");
    origin = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    state = context.getBean(State.class);
  }

  @AfterAll
  static void stop() {
    if (state != null) state.release.countDown();
    if (context != null) context.close();
  }

  @BeforeEach
  void reset() {
    state.reset();
    clearInvocations(
        context.getBean(ScheduleStore.class), context.getBean(ProjectAuthorization.class));
  }

  @ParameterizedTest
  @EnumSource(Plowshare.TransportMode.class)
  void heldRepositoryReadDoesNotBlockSecondTypedReplyOrNotification(Plowshare.TransportMode mode)
      throws Exception {
    var hint = new CompletableFuture<ServerPush>();
    String session = UUID.randomUUID().toString();
    try (var connection =
        Plowshare.connectAsync(
                origin,
                "fixture",
                session,
                Duration.ofSeconds(10),
                hint::complete,
                mode,
                Plowshare.DispatchMode.CONCURRENT)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS)) {
      var client = new ScheduleClient(connection);
      var first = client.listPageAsync("fixture", null, 50).toCompletableFuture();
      assertTrue(state.entered.await(5, TimeUnit.SECONDS));
      var second = client.listPageAsync("fixture", null, 50).toCompletableFuture();
      context
          .getBean(EventChannelHandler.class)
          .tell(session, new ConversationGrowth("cnv_fixture", 1));
      assertEquals(new ConversationGrowth("cnv_fixture", 1), hint.get(5, TimeUnit.SECONDS));
      assertEquals("schedule-2", second.get(5, TimeUnit.SECONDS).getFirst().name());
      assertFalse(
          first.isDone(), "The second response must arrive while the first read remains held");
      state.release.countDown();
      assertEquals("schedule-1", first.get(5, TimeUnit.SECONDS).getFirst().name());
      verify(context.getBean(ScheduleStore.class), times(2))
          .page(eq("alice"), eq("fixture"), any(SchedulePage.class));
      verify(context.getBean(ProjectAuthorization.class), atLeast(4))
          .require(eq("schedule.list"), any(AccessRequest.class), eq("alice"));
      connection.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } finally {
      state.release.countDown();
    }
  }

  private static final class State {
    final AtomicInteger calls = new AtomicInteger();
    volatile CountDownLatch entered;
    volatile CountDownLatch release;

    void reset() {
      calls.set(0);
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
    }

    List<ScheduleRecord> read() throws InterruptedException {
      int ordinal = calls.incrementAndGet();
      if (ordinal == 1) {
        entered.countDown();
        if (!release.await(10, TimeUnit.SECONDS))
          throw new IllegalStateException("Held test read expired");
      }
      return List.of(
          new ScheduleRecord(
              "schedule-" + ordinal,
              "0 0 9 * * *",
              "UTC",
              "tick",
              false,
              Instant.parse("2026-10-20T00:00:00Z"),
              "alice"));
    }
  }

  @Configuration
  @org.springframework.context.annotation.Profile("concurrent-sdk-integration")
  @Import({EventChannelConfig.class, Watchers.class})
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
    AdminStore accounts() {
      var accounts = mock(AdminStore.class);
      when(accounts.sessionCurrent("alice", 1L)).thenReturn(true);
      return accounts;
    }

    @Bean
    SocketAuthorization socketAuthorization(AdminStore accounts) {
      return new SocketAuthorization(accounts);
    }

    @Bean
    ProjectAuthorization projectAuthorization() {
      var authorization = mock(ProjectAuthorization.class);
      when(authorization.allowed(anyString(), any(AccessRequest.class), nullable(String.class)))
          .thenReturn(true);
      return authorization;
    }

    @Bean
    io.aeyer.plowshare.server.requests.RequestProjects requestProjects() {
      return io.aeyer.plowshare.server.requests.RequestProjects.EXPLICIT_ONLY;
    }

    @Bean
    ScheduleStore schedules(State state) {
      var store = mock(ScheduleStore.class);
      when(store.page(eq("alice"), eq("fixture"), any(SchedulePage.class)))
          .thenAnswer(ignored -> state.read());
      return store;
    }

    @Bean
    FrameRouter router(ScheduleStore schedules) {
      var definitions = mock(ScheduleDefinitions.class);
      return new FrameRouter(
          Map.of("schedule.list", new ScheduleListHandler(schedules, () -> definitions)));
    }

    @Bean
    OncePerRequestFilter fixtureIdentity() {
      return new OncePerRequestFilter() {
        @Override
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
