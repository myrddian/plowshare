package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.transport.SegmentedMessages;
import io.aeyer.plowshare.server.SchedulingConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;

/** Native control frames over the existing mock-persistence event/file production wiring. */
class WebSocketKeepAliveSocketTest {
  private static final class Peer implements WebSocket.Listener {
    final CountDownLatch pings = new CountDownLatch(2);
    final AtomicReference<Throwable> failed = new AtomicReference<>();
    final AtomicReference<Integer> closed = new AtomicReference<>();

    @Override
    public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
      pings.countDown();
      // Exercise the platform's ordinary Ping/Pong handling, without application messages.
      return WebSocket.Listener.super.onPing(socket, message);
    }

    @Override
    public void onError(WebSocket socket, Throwable failure) {
      failed.set(failure);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
      closed.set(code);
      return WebSocket.Listener.super.onClose(socket, code, reason);
    }
  }

  @Test
  void idle_native_clients_answer_probes_on_segmented_events_and_files() throws Exception {
    var sockets = new ArrayList<WebSocket>();
    try (var context =
        new SpringApplicationBuilder(FileChannelTest.Wiring.class, SchedulingConfig.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--plowshare.transport.keepalive.interval=PT1S",
                "--plowshare.transport.keepalive.pong-timeout=PT2S",
                "--plowshare.transport.keepalive.check-interval=PT1S")) {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      try (var http = HttpClient.newHttpClient()) {
        try {
          var peers = List.of(new Peer(), new Peer());
          var events =
              http.newWebSocketBuilder()
                  .subprotocols(SegmentedMessages.SUBPROTOCOL)
                  .buildAsync(
                      URI.create(
                          "ws://127.0.0.1:"
                              + port
                              + EventChannelHandler.PATH
                              + "?session=keepalive-fixture"),
                      peers.get(0))
                  .get(5, TimeUnit.SECONDS);
          sockets.add(events);
          assertEquals(SegmentedMessages.SUBPROTOCOL, events.getSubprotocol());
          sockets.add(
              http.newWebSocketBuilder()
                  .buildAsync(
                      URI.create(
                          "ws://127.0.0.1:"
                              + port
                              + FileChannelHandler.PATH
                              + "?session=keepalive-fixture"),
                      peers.get(1))
                  .get(5, TimeUnit.SECONDS));
          for (var peer : peers) {
            assertTrue(
                peer.pings.await(8, TimeUnit.SECONDS),
                "both channels must receive repeated native pings");
            assertNull(peer.failed.get());
            assertNull(peer.closed.get(), "matching native pongs keep the idle channel open");
          }
          for (var socket : sockets) assertFalse(socket.isOutputClosed());
        } finally {
          for (var socket : sockets) socket.abort();
        }
      }
    }
  }
}
