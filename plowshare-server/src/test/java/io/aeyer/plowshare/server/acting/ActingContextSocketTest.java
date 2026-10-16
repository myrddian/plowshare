package io.aeyer.plowshare.server.acting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ActingContext;
import io.aeyer.plowshare.sdk.ActingClient;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.ws.*;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;

/** Real SDK/packet/socket/router path with mocked persistence, never a PostgreSQL test. */
class ActingContextSocketTest {
  @Configuration(proxyBeanMethods = false)
  @Import({EventChannelConfig.class, Watchers.class})
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebSocketServletAutoConfiguration.class
  })
  static class Wiring {
    @Bean
    SocketAuthorization socketAuthorization() {
      var authorization = mock(SocketAuthorization.class);
      when(authorization.attach(any())).thenReturn(true);
      when(authorization.current(any())).thenReturn(true);
      return authorization;
    }

    @Bean
    io.aeyer.plowshare.server.access.ProjectAuthorization projectAuthorization() {
      return mock(io.aeyer.plowshare.server.access.ProjectAuthorization.class);
    }

    @Bean
    Filter fixtureAuthenticatedActor(BoundActingContextsTest fixture) {
      // Authentication is covered by the production AuthFilter suites. This transport fixture
      // supplies the principal that the real handshake interceptor must carry onto the socket.
      return (request, response, chain) -> {
        request.setAttribute(AuthFilter.HANDLE_ATTRIBUTE, fixture.actor);
        chain.doFilter(request, response);
      };
    }
  }

  @Test
  void sdk_opens_reads_closes_and_reconnects_without_inheriting_contexts() throws Exception {
    var fixture = new BoundActingContextsTest();
    fixture.setup();
    var router = spy(fixture.router);
    try (var server =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            .initializers(
                context -> {
                  context.getBeanFactory().registerSingleton("fixture", fixture);
                  context.getBeanFactory().registerSingleton("frameRouter", router);
                })
            .run("--server.port=0", "--server.address=127.0.0.1")) {
      int port = ((WebServerApplicationContext) server).getWebServer().getPort();
      String origin = "http://127.0.0.1:" + port;
      ActingContext.Selection retained;
      try (var wire =
          Plowshare.connect(
              origin, "fixture", "socket-subject", Duration.ofSeconds(5), push -> {})) {
        var client = new ActingClient(wire);
        var opened = client.open(fixture.request());
        var selection = new ActingContext.Selection(opened.context());
        assertEquals("alice", opened.subject());
        assertEquals("answer", client.turns(selection, "alice-history").getFirst().answer());
        client.close(selection);
        assertThrows(IOException.class, () -> client.turns(selection, "alice-history"));
        retained = new ActingContext.Selection(client.open(fixture.request()).context());
      }
      verify(router, timeout(5000)).disconnected(anyString());
      try (var wire =
          Plowshare.connect(
              origin, "fixture", "socket-subject", Duration.ofSeconds(5), push -> {})) {
        assertThrows(
            IOException.class, () -> new ActingClient(wire).turns(retained, "alice-history"));
      }
    }
  }

  @Test
  void sdk_turn_receipt_survives_socket_loss_and_new_context_recovers_without_resubmission()
      throws Exception {
    var fixture = new BoundActingContextsTest();
    fixture.setup();
    var rows = new PersonalActingWorkTest.Store();
    var retained =
        new java.util.concurrent.atomic.AtomicReference<
            io.aeyer.plowshare.server.agents.RunAdmission>();
    var submissions = new java.util.concurrent.atomic.AtomicInteger();
    var definition =
        new io.aeyer.plowshare.server.agents.AgentDefinition(
            "chat",
            "chat",
            "fast",
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            4,
            8,
            "Answer",
            true,
            false);
    var work =
        new PersonalActingWork(
            rows,
            fixture.policy,
            (subject, agent) -> definition,
            (conversation, agent, task, admission) -> {
              submissions.incrementAndGet();
              retained.set(admission);
              admission.started().accept("job_ABC123");
            },
            rows.transactions,
            50,
            mock(io.aeyer.plowshare.server.access.ResourceScopeRepository.class));
    var router =
        spy(
            new FrameRouter(
                new ActingFrames(fixture.contexts).frames(),
                new ActingRequests(
                    fixture.contexts,
                    new AuthorizedActingHistory(fixture.resources, fixture.turns, fixture.policy),
                    work,
                    null)));
    var request = java.util.UUID.randomUUID();
    try (var server =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            .initializers(
                context -> {
                  context.getBeanFactory().registerSingleton("fixture", fixture);
                  context.getBeanFactory().registerSingleton("frameRouter", router);
                })
            .run("--server.port=0", "--server.address=127.0.0.1")) {
      String origin =
          "http://127.0.0.1:" + ((WebServerApplicationContext) server).getWebServer().getPort();
      ActingClient.TurnReceipt receipt;
      try (var wire =
          Plowshare.connect(
              origin, "fixture", "socket-subject", Duration.ofSeconds(5), push -> {})) {
        var client = new ActingClient(wire);
        var selected = new ActingContext.Selection(client.open(fixture.request()).context());
        assertThrows(
            IllegalArgumentException.class,
            () -> client.start(selected, java.util.UUID.randomUUID(), "bad name", "Hello", null));
        assertThrows(
            IllegalArgumentException.class,
            () -> client.start(selected, java.util.UUID.randomUUID(), "chat", "Hello", "other"));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                client.start(
                    selected, java.util.UUID.randomUUID(), "chat", "界".repeat(30000), null));
        receipt = client.start(selected, request, "chat", "Hello", null);
        assertEquals("STARTED", receipt.state());
        assertEquals(receipt, client.receipt(selected, request));
        assertThrows(
            IOException.class, () -> client.start(selected, request, "chat", "Changed", null));
      }
      verify(router, timeout(5000)).disconnected(anyString());
      retained.get().check().run(); // Socket/context lifetime is not the job's authority lifetime.
      try (var wire =
          Plowshare.connect(
              origin, "fixture", "socket-subject", Duration.ofSeconds(5), push -> {})) {
        var client = new ActingClient(wire);
        var selected = new ActingContext.Selection(client.open(fixture.request()).context());
        assertEquals(receipt, client.receipt(selected, request));
        retained.get().ended().run();
        assertEquals("FINISHED", client.receipt(selected, request).state());
        client.close(selected);
        assertThrows(IOException.class, () -> client.receipt(selected, request));
      }
      assertEquals(1, submissions.get());
    }
  }
}
