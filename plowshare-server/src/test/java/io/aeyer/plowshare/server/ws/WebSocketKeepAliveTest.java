package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

class WebSocketKeepAliveTest {
  private static final WebSocketKeepAliveProperties SETTINGS =
      new WebSocketKeepAliveProperties(
          true, Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5));

  private static final class Channel extends AbstractWebSocketHandler
      implements SubProtocolCapable {
    WebSocketSession attached;
    WebSocketSession detached;

    @Override
    public List<String> getSubProtocols() {
      return List.of("plowshare-segments-v1");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
      attached = session;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
      detached = session;
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicLong time = new AtomicLong();
    final WebSocketSession socket = mock(WebSocketSession.class);
    final List<WebSocketMessage<?>> messages = new ArrayList<>();
    final ScheduledWebSocketKeepAlive owner =
        new ScheduledWebSocketKeepAlive(SETTINGS, time::get, Runnable::run);
    final Channel channel = new Channel();
    final WebSocketHandler handler = owner.decorate(channel);

    Fixture() throws Exception {
      when(socket.getId()).thenReturn("fixture-socket");
      when(socket.getAttributes()).thenReturn(new ConcurrentHashMap<>());
      when(socket.isOpen()).thenReturn(true);
      doAnswer(
              call -> {
                messages.add(call.getArgument(0));
                return null;
              })
          .when(socket)
          .sendMessage(any());
      handler.afterConnectionEstablished(socket);
    }

    void at(long seconds) {
      time.set(Duration.ofSeconds(seconds).toNanos());
      owner.check();
    }

    PingMessage ping() {
      return assertInstanceOf(PingMessage.class, messages.getLast());
    }

    @Override
    public void close() {
      owner.close();
    }
  }

  @Test
  void idle_probe_matching_pong_and_another_probe_preserve_protocol_and_attachment()
      throws Exception {
    try (var f = new Fixture()) {
      assertEquals(
          List.of("plowshare-segments-v1"), ((SubProtocolCapable) f.handler).getSubProtocols());
      assertNotSame(f.socket, f.channel.attached);
      assertSame(f.socket.getAttributes(), f.channel.attached.getAttributes());
      f.at(29);
      assertTrue(f.messages.isEmpty());
      f.at(30);
      assertEquals(Long.BYTES, f.ping().getPayload().remaining());
      f.handler.handleMessage(f.socket, new PongMessage(f.ping().getPayload()));
      f.at(59);
      assertEquals(1, f.messages.size());
      f.at(60);
      assertEquals(2, f.messages.size());
      verify(f.socket, never()).close(any(CloseStatus.class));
      f.handler.afterConnectionClosed(f.socket, CloseStatus.NORMAL);
      assertSame(f.channel.attached, f.channel.detached);
      f.at(300);
      assertEquals(2, f.messages.size());
    }
  }

  @Test
  void ordinary_activity_defers_idle_probe_but_does_not_acknowledge_an_outstanding_ping()
      throws Exception {
    try (var f = new Fixture()) {
      f.time.set(Duration.ofSeconds(25).toNanos());
      f.channel.attached.sendMessage(new TextMessage("ordinary reply"));
      f.at(30);
      assertEquals(1, f.messages.size());
      f.at(55);
      assertInstanceOf(PingMessage.class, f.messages.getLast());
      f.time.set(Duration.ofSeconds(80).toNanos());
      f.handler.handleMessage(f.socket, new TextMessage("still not a pong"));
      f.at(85);
      verify(f.socket)
          .close(
              CloseStatus.SESSION_NOT_RELIABLE.withReason(
                  "WebSocket heartbeat pong deadline expired"));
    }
  }

  @Test
  void wrong_or_malformed_pongs_cannot_keep_a_dead_connection_alive() throws Exception {
    for (var payload : List.of(ByteBuffer.allocate(0), ByteBuffer.allocate(8).putLong(0, 999))) {
      try (var f = new Fixture()) {
        f.at(30);
        f.handler.handleMessage(f.socket, new PongMessage(payload));
        f.at(59);
        verify(f.socket, never()).close(any(CloseStatus.class));
        f.at(60);
        f.at(90);
        verify(f.socket, times(1))
            .close(
                CloseStatus.SESSION_NOT_RELIABLE.withReason(
                    "WebSocket heartbeat pong deadline expired"));
        assertEquals(1, f.messages.size());
      }
    }
  }

  @Test
  void ping_never_overlaps_or_queues_behind_a_blocked_channel_write() throws Exception {
    try (var f = new Fixture()) {
      var writing = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      doAnswer(
              call -> {
                writing.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
              })
          .when(f.socket)
          .sendMessage(any(TextMessage.class));
      var writer =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      f.channel.attached.sendMessage(new TextMessage("busy"));
                    } catch (IOException failure) {
                      throw new AssertionError(failure);
                    }
                  });
      try {
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        f.at(30);
        f.at(35);
        assertTrue(f.messages.isEmpty());
      } finally {
        release.countDown();
        assertTrue(writer.join(Duration.ofSeconds(5)));
      }
      f.at(65);
      assertInstanceOf(PingMessage.class, f.messages.getLast());
    }
  }

  @Test
  void shutdown_or_peer_close_fences_queued_probes_and_keeps_only_one_task_per_socket()
      throws Exception {
    var tasks = new ArrayList<Runnable>();
    var time = new AtomicLong();
    var owner = new ScheduledWebSocketKeepAlive(SETTINGS, time::get, tasks::add);
    var socket = mock(WebSocketSession.class);
    when(socket.getId()).thenReturn("queued-fixture");
    when(socket.getAttributes()).thenReturn(new ConcurrentHashMap<>());
    var handler = owner.decorate(new Channel());
    handler.afterConnectionEstablished(socket);
    time.set(Duration.ofSeconds(30).toNanos());
    for (int i = 0; i < 100; i++) owner.check();
    assertEquals(1, tasks.size());
    owner.close();
    owner.close();
    assertEquals(2, tasks.size());
    for (var task : tasks) task.run();
    verify(socket, never()).sendMessage(any());
    verify(socket, times(1)).close(CloseStatus.GOING_AWAY);
    owner.check();
    assertEquals(2, tasks.size());
  }

  @Test
  void ping_write_failure_closes_once_without_replay() throws Exception {
    try (var f = new Fixture()) {
      doThrow(new IOException("fixture write failed")).when(f.socket).sendMessage(any());
      f.at(30);
      f.at(90);
      verify(f.socket, times(1)).sendMessage(any(PingMessage.class));
      verify(f.socket, times(1))
          .close(CloseStatus.SESSION_NOT_RELIABLE.withReason("WebSocket heartbeat write failed"));
    }
  }

  @Test
  void configuration_rejects_invalid_or_unobservable_deadlines_and_can_disable_probes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WebSocketKeepAliveProperties(
                true, Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(5)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WebSocketKeepAliveProperties(
                true, Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(5)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WebSocketKeepAliveProperties(
                true, null, Duration.ofSeconds(30), Duration.ofSeconds(5)));
    var disabled =
        new ScheduledWebSocketKeepAlive(
            new WebSocketKeepAliveProperties(
                false, SETTINGS.interval(), SETTINGS.pongTimeout(), SETTINGS.checkInterval()),
            System::nanoTime,
            Runnable::run);
    var channel = new Channel();
    assertSame(channel, disabled.decorate(channel));
    disabled.close();
  }
}
