package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import io.aeyer.plowshare.protocol.transport.SegmentedMessages;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.auth.AdminStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Real event-channel/SDK transport; owning Relay repositories and authentication are tested
 * separately.
 */
class PacketTransportTest {
  // Imported directly by this fixture; keeping it out of component scan preserves BootWiringTest.
  @Import({EventChannelConfig.class, Watchers.class})
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebSocketServletAutoConfiguration.class
  })
  static class Wiring {
    @Bean
    io.aeyer.plowshare.server.access.ProjectAuthorization projectAuthorization() {
      return mock(io.aeyer.plowshare.server.access.ProjectAuthorization.class);
    }

    @Bean
    AdminStore accounts() {
      return mock(AdminStore.class);
    }

    @Bean
    SocketAuthorization socketAuthorization() {
      return new SocketAuthorization(mock(AdminStore.class));
    }

    @Bean
    AtomicInteger publications() {
      return new AtomicInteger();
    }

    @Bean
    ServletServerContainerFactoryBean container() {
      var container = new ServletServerContainerFactoryBean();
      container.setMaxTextMessageBufferSize(1024 * 1024);
      return container;
    }

    @Bean
    FrameRouter router(AtomicInteger publications) {
      return new FrameRouter(
          Map.of(
              "relay.publish",
              (payload, caller) -> {
                var request = Payloads.as(payload, RelayPort.Publish.class, "relay.publish");
                publications.incrementAndGet();
                // The reply deliberately echoes the content: the actual request and response both
                // exceed
                // the unchanged container message limit. The public SDK ignores this future result
                // field.
                return Outcome.ok(
                    Map.of(
                        "requestId",
                        request.requestId(),
                        "project",
                        request.project(),
                        "topic",
                        request.topic(),
                        "position",
                        "1",
                        "publishedAt",
                        Instant.now(),
                        "echo",
                        request.text()));
              }));
    }
  }

  @Test
  void real_server_and_java_sdk_exchange_worst_case_text_as_small_packets_and_keep_legacy_explicit()
      throws Exception {
    try (var context =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            .run("--server.port=0", "--server.address=127.0.0.1")) {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      String origin = "http://127.0.0.1:" + port;
      var count = context.getBean(AtomicInteger.class);
      try (var client =
          Plowshare.connect(origin, "fixture-token", "segmented", Duration.ofSeconds(30), null)) {
        var published =
            new RelayClient(client)
                .publish(
                    new RelayPort.Publish(
                        UUID.randomUUID().toString(),
                        "fixture",
                        "large.events",
                        "\u0001".repeat(RelayPort.DEFAULT_TEXT_BYTES),
                        Instant.now(),
                        null,
                        null,
                        null));
        assertEquals("1", published.position());
        assertEquals(1, count.get(), "one logical publication, regardless of segment count");
      }
      try (var client =
          Plowshare.connect(
              origin,
              "fixture-token",
              "legacy",
              Duration.ofSeconds(10),
              null,
              Plowshare.TransportMode.LEGACY)) {
        new RelayClient(client)
            .publish(
                new RelayPort.Publish(
                    UUID.randomUUID().toString(),
                    "fixture",
                    "large.events",
                    "small",
                    Instant.now(),
                    null,
                    null,
                    null));
        var refused =
            assertThrows(
                Plowshare.TransportException.class,
                () ->
                    new RelayClient(client)
                        .publish(
                            new RelayPort.Publish(
                                UUID.randomUUID().toString(),
                                "fixture",
                                "large.events",
                                "x".repeat(2 * 1024 * 1024),
                                Instant.now(),
                                null,
                                null,
                                null)));
        assertEquals(Plowshare.Delivery.NOT_SUBMITTED, refused.delivery());
        assertEquals(2, count.get(), "legacy oversize refusal must precede dispatch");
      }
    }
  }

  @Test
  void legacy_receive_counts_utf8_bytes_before_domain_dispatch() throws Exception {
    var socket = mock(WebSocketSession.class);
    when(socket.getAttributes()).thenReturn(new java.util.HashMap<>());
    when(socket.getId()).thenReturn("legacy-byte-fixture");
    when(socket.getUri())
        .thenReturn(
            java.net.URI.create("ws://fixture.invalid/v1/events?session=legacy-byte-fixture"));
    when(socket.isOpen()).thenReturn(true);
    var effects = new AtomicInteger();
    var handler =
        new EventChannelHandler(
            new io.aeyer.plowshare.server.session.SessionRegistry(),
            new FrameRouter(
                Map.of(
                    "fixture.effect",
                    (payload, caller) -> {
                      effects.incrementAndGet();
                      return Outcome.ok();
                    })),
            new Watchers());
    handler.afterConnectionEstablished(socket);
    String frame =
        "{\"id\":\"1\",\"type\":\"fixture.effect\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"text\":\""
            + "😀".repeat(300000)
            + "\"}}";
    assertTrue(
        frame.length() < 1024 * 1024, "container character allowance alone would accept this");
    handler.handleTextMessage(socket, new org.springframework.web.socket.TextMessage(frame));
    assertEquals(0, effects.get());
    verify(socket)
        .close(
            org.springframework.web.socket.CloseStatus.BAD_DATA.withReason(
                "invalid packet transport"));
    handler.afterConnectionClosed(socket, org.springframework.web.socket.CloseStatus.BAD_DATA);
  }

  @Test
  void account_revocation_between_ranges_prevents_dispatch_and_disconnect_releases_assembly()
      throws Exception {
    var accounts = mock(AdminStore.class);
    var current = new java.util.concurrent.atomic.AtomicBoolean(true);
    when(accounts.sessionCurrent("fixture", 1L)).thenAnswer(call -> current.get());
    var attributes = new java.util.HashMap<String, Object>();
    attributes.put(EventChannelHandler.HANDLE, "fixture");
    attributes.put("plowshare.sessionVersion", 1L);
    var socket = mock(WebSocketSession.class);
    when(socket.getAttributes()).thenReturn(attributes);
    when(socket.getId()).thenReturn("packet-auth-fixture");
    when(socket.getUri())
        .thenReturn(java.net.URI.create("ws://fixture.invalid/v1/events?session=packet-auth"));
    when(socket.getAcceptedProtocol()).thenReturn(SegmentedMessages.SUBPROTOCOL);
    when(socket.isOpen()).thenReturn(true);
    var effects = new AtomicInteger();
    var handler =
        new EventChannelHandler(
            new io.aeyer.plowshare.server.session.SessionRegistry(),
            new FrameRouter(
                Map.of(
                    "fixture.effect",
                    (payload, caller) -> {
                      effects.incrementAndGet();
                      return Outcome.ok();
                    })),
            new Watchers());
    handler.useSocketAuthorization(new SocketAuthorization(accounts));
    var budget = new PacketBudget(256L * 1024 * 1024);
    handler.usePacketBudget(budget);
    handler.afterConnectionEstablished(socket);
    String inner =
        "{\"id\":\"1\",\"type\":\"fixture.effect\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"text\":\""
            + "x".repeat(70000)
            + "\"}}";
    byte[] bytes = inner.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String hash =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    String id = UUID.randomUUID().toString();
    for (int n = 1; n <= 2; n++) {
      int offset = (n - 1) * SegmentedMessages.CHUNK_BYTES;
      var part =
          new io.aeyer.plowshare.protocol.transport.MessageSegment(
              "transport.segment",
              1,
              id,
              n,
              2,
              offset,
              bytes.length,
              hash,
              java.util.Base64.getEncoder()
                  .encodeToString(
                      java.util.Arrays.copyOfRange(
                          bytes,
                          offset,
                          Math.min(bytes.length, offset + SegmentedMessages.CHUNK_BYTES))));
      handler.handleTextMessage(
          socket,
          new org.springframework.web.socket.TextMessage(
              FrameJson.answering().writeValueAsString(part)));
      assertEquals(0, effects.get(), "partial or revoked transfers must not execute");
      if (n == 1) {
        assertTrue(budget.heldBytes() > 0);
        current.set(false);
      }
    }
    verify(socket)
        .close(
            org.springframework.web.socket.CloseStatus.POLICY_VIOLATION.withReason(
                "Account session revoked"));
    // The real container invokes this callback after closing; the mock makes that lifecycle
    // explicit.
    handler.afterConnectionClosed(
        socket, org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
    assertEquals(0, budget.heldBytes());
  }

  @Test
  void strict_packet_binding_rejects_duplicates_coercion_and_unknown_fields_without_a_credit()
      throws Exception {
    String valid =
        "{\"kind\":\"transport.segment\",\"version\":1,\"transferId\":\"11111111-1111-1111-1111-111111111111\",\"segmentNumber\":1,\"segmentCount\":1,\"byteOffset\":0,\"totalBytes\":1,\"sha256\":\""
            + "559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd"
            + "\",\"data\":\"QQ==\"}";
    for (String invalid :
        new String[] {
          valid + " {}",
          valid.replace("\"version\":1", "\"version\":\"1\""),
          valid.replace("\"version\":1", "\"version\":1.0"),
          valid.replace("\"byteOffset\":0", "\"byteOffset\":null"),
          valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
          valid.replace("{", "{\"extra\":1,")
        }) {
      var socket = mock(WebSocketSession.class);
      var budget = new PacketBudget(256L * 1024 * 1024);
      var transport = new PacketEventTransport(socket, FrameJson.answering(), budget);
      try {
        assertThrows(Exception.class, () -> transport.receive(invalid));
      } finally {
        transport.close();
      }
      verifyNoInteractions(socket);
      assertEquals(0, budget.heldBytes());
    }
  }
}
