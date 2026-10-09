package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.relay.RelayPorts;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

class PacketBudgetConfigurationTest {
  @Test
  void complete_event_decoder_accepts_fifty_mib_before_typed_relay_dispatch() throws Exception {
    var ports = mock(RelayPorts.class);
    when(ports.publish(eq("reader"), any()))
        .thenAnswer(
            invocation -> {
              RelayPort.Publish request = invocation.getArgument(1);
              assertEquals(RelayPort.MAX_TEXT_BYTES, request.text().length());
              return new RelayPort.Published(
                  request.requestId(), request.project(), request.topic(), "1", Instant.now());
            });
    var raw =
        FrameJson.answering()
            .writeValueAsString(
                new Envelope(
                    "fixture-message",
                    "relay.publish",
                    Envelope.CURRENT_VERSION,
                    Map.of(
                        "requestId",
                        "11111111-1111-1111-1111-111111111111",
                        "project",
                        "fixture",
                        "topic",
                        "large.events",
                        "occurredAt",
                        "2026-10-10T00:00:00Z",
                        "text",
                        "x".repeat(RelayPort.MAX_TEXT_BYTES))));
    var router = new FrameRouter(new RelayPortFrames(ports).frames());
    assertEquals(Code.OK, router.route(raw, new Asking("fixture", "reader")).code());
    verify(ports).publish(eq("reader"), any());
  }

  @Test
  void default_budget_supports_the_portable_ceiling_and_explicit_undersizing_fails() {
    var config =
        new EventChannelConfig(
            new DefaultListableBeanFactory().getBeanProvider(FrameRouter.class), new Watchers());
    assertEquals(2L * 1024 * 1024 * 1024, config.resolvedPacketMemoryBytes());
    ReflectionTestUtils.setField(config, "packetMemoryBytes", 256L * 1024 * 1024);
    assertThrows(IllegalArgumentException.class, config::resolvedPacketMemoryBytes);
    ReflectionTestUtils.setField(config, "packetMemoryBytes", 1024L * 1024 * 1024);
    assertEquals(1024L * 1024 * 1024, config.resolvedPacketMemoryBytes());
  }
}
