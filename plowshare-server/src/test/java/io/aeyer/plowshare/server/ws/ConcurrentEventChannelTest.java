package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.transport.*;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class ConcurrentEventChannelTest {
  static final class Fixture implements AutoCloseable {
    final WebSocketSession socket = mock(WebSocketSession.class);
    final EventChannelHandler handler;
    final SegmentedMessages client;
    final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    final AtomicBoolean open = new AtomicBoolean(true);
    volatile Runnable beforeWrite = () -> {};
    volatile java.util.function.Consumer<String> onReply = ignored -> {};

    Fixture(boolean segmented, FrameRouter router) throws Exception {
      this(segmented, router, ignored -> {});
    }

    /** {@code configure} runs before the connection is established, as startup wiring would. */
    Fixture(
        boolean segmented,
        FrameRouter router,
        java.util.function.Consumer<EventChannelHandler> configure)
        throws Exception {
      var json = FrameJson.answering();
      handler = new EventChannelHandler(new SessionRegistry(), router, new Watchers());
      configure.accept(handler);
      var attributes = new ConcurrentHashMap<String, Object>();
      attributes.put(EventChannelHandler.HANDLE, "alice");
      when(socket.getAttributes()).thenReturn(attributes);
      when(socket.getId()).thenReturn("physical");
      when(socket.getUri())
          .thenReturn(URI.create("ws://example.invalid/v1/events?session=fixture"));
      when(socket.isOpen()).thenAnswer(ignored -> open.get());
      when(socket.getAcceptedProtocol()).thenReturn(segmented ? SegmentedMessages.SUBPROTOCOL : "");
      doAnswer(
              ignored -> {
                open.set(false);
                return null;
              })
          .when(socket)
          .close(any(CloseStatus.class));
      client =
          segmented
              ? new SegmentedMessages(
                  new PacketWire() {
                    public void segment(MessageSegment packet) throws IOException {
                      handler.handleTextMessage(
                          socket, new TextMessage(json.writeValueAsString(packet)));
                    }

                    public void credit(SegmentCredit packet) throws IOException {
                      handler.handleTextMessage(
                          socket, new TextMessage(json.writeValueAsString(packet)));
                    }
                  },
                  new PacketBudget(256L * 1024 * 1024),
                  () -> open.set(false))
              : null;
      doAnswer(
              call -> {
                beforeWrite.run();
                String frame = ((TextMessage) call.getArgument(0)).getPayload();
                if (client == null) {
                  received.add(frame);
                  onReply.accept(frame);
                } else {
                  var body = json.readTree(frame);
                  if (body.path("kind").asText().equals("transport.credit"))
                    client.credit(json.treeToValue(body, SegmentCredit.class));
                  else
                    client
                        .receive(json.treeToValue(body, MessageSegment.class))
                        .ifPresent(
                            reply -> {
                              received.add(reply);
                              onReply.accept(reply);
                            });
                }
                return null;
              })
          .when(socket)
          .sendMessage(any());
      handler.afterConnectionEstablished(socket);
    }

    void send(String id, String type, Map<String, Object> payload) throws IOException {
      String frame =
          FrameJson.answering()
              .writeValueAsString(new Envelope(id, type, Envelope.CURRENT_VERSION, payload));
      if (client == null) handler.handleTextMessage(socket, new TextMessage(frame));
      else client.send(frame);
    }

    String reply() throws Exception {
      String reply = received.poll(5, TimeUnit.SECONDS);
      assertNotNull(reply, "Expected correlated response");
      return reply;
    }

    @Override
    public void close() {
      open.set(false);
      if (client != null) client.close();
      handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
      handler.drainRequests();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void negotiated_mode_correlates_overtaking_replies_on_both_encodings(boolean segmented)
      throws Exception {
    var entered = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    var router =
        new FrameRouter(
            Map.of(
                "information.facets",
                (payload, asking) -> {
                  if (payload.get("slow") == Boolean.TRUE) {
                    entered.complete(null);
                    release.join();
                  }
                  return Outcome.ok(Map.of("done", true));
                }));
    try (var fixture = new Fixture(segmented, router)) {
      fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
      var selection = FrameJson.answering().readTree(fixture.reply());
      assertEquals("select", selection.path("id").asText());
      // The server's own default depth, which is the protocol ceiling (see EventChannelConfig).
      assertEquals(64, selection.path("payload").path("payload").path("maxPending").asInt());
      fixture.send("slow", "information.facets", Map.of("slow", true));
      entered.get(5, TimeUnit.SECONDS);
      fixture.send("fast", "information.facets", Map.of());
      assertEquals("fast", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
      assertFalse(release.isDone());
      release.complete(null);
      assertEquals("slow", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
    } finally {
      release.complete(null);
    }
  }

  @Test
  void the_handshake_reply_carries_the_configured_depth() throws Exception {
    try (var fixture =
        new Fixture(true, new FrameRouter(Map.of()), handler -> handler.useRequestDepth(5))) {
      fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
      var selection = FrameJson.answering().readTree(fixture.reply());
      assertEquals(5, selection.path("payload").path("payload").path("maxPending").asInt());
    }
  }

  @Test
  void request_bounds_are_independent_of_event_read_concurrency() throws Exception {
    var handler =
        new EventChannelHandler(new SessionRegistry(), new FrameRouter(Map.of()), new Watchers());
    var defaults = handler.requestBounds();
    handler.useReadConcurrency(5, 9);
    assertEquals(defaults, handler.requestBounds());
    handler.useRequestBounds(3, 0, 40);
    handler.useReadConcurrency(1, 1);
    assertEquals(new EventChannelHandler.RequestBounds(3, 0, 40), handler.requestBounds());
    assertThrows(IllegalArgumentException.class, () -> handler.useRequestBounds(0, 0, 8));
    assertThrows(IllegalArgumentException.class, () -> handler.useRequestBounds(2, 1, 8));
    assertThrows(IllegalArgumentException.class, () -> handler.useRequestBounds(1, -1, 8));
    assertThrows(IllegalArgumentException.class, () -> handler.useRequestBounds(3, 0, 2));
    try (var fixture = new Fixture(true, new FrameRouter(Map.of()))) {
      assertThrows(IllegalStateException.class, () -> fixture.handler.useRequestBounds(2, 0, 8));
    }
  }

  @Test
  void request_depth_is_validated_and_fixed_once_dispatch_starts() throws Exception {
    var handler =
        new EventChannelHandler(new SessionRegistry(), new FrameRouter(Map.of()), new Watchers());
    assertThrows(IllegalArgumentException.class, () -> handler.useRequestDepth(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> handler.useRequestDepth(ConcurrentDispatch.MAX_PENDING + 1));
    handler.useRequestDepth(ConcurrentDispatch.MAX_PENDING);
    try (var fixture = new Fixture(true, new FrameRouter(Map.of()))) {
      assertThrows(IllegalStateException.class, () -> fixture.handler.useRequestDepth(4));
    }
  }

  @Test
  void queued_protected_reply_rechecks_membership_at_write_without_replaying_handler()
      throws Exception {
    var entered = new CompletableFuture<Void>();
    var result = new CompletableFuture<Outcome>();
    FrameHandler read =
        new FrameHandler() {
          public Outcome handle(Map<String, Object> payload, Asking asking) {
            throw new AssertionError();
          }

          public java.util.concurrent.CompletionStage<Outcome> handleAsync(
              Map<String, Object> payload, Asking asking) {
            entered.complete(null);
            return result;
          }
        };
    var authority = mock(io.aeyer.plowshare.server.access.ProjectAuthorization.class);
    var revoked = new AtomicBoolean();
    // Configure the mock before concurrent calls; changing stubs during delivery races Mockito.
    doAnswer(
            ignored -> {
              if (revoked.get())
                throw new io.aeyer.plowshare.server.faults.CallerFault("Membership revoked");
              return null;
            })
        .when(authority)
        .require(anyString(), any(), anyString());
    var router = new FrameRouter(Map.of("relay.topics", read));
    router.useAuthorization(authority);
    var writing = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    try (var fixture = new Fixture(false, router)) {
      fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
      fixture.reply();
      fixture.beforeWrite =
          () -> {
            writing.complete(null);
            release.join();
          };
      fixture.handler.push(
          "alice", new io.aeyer.plowshare.protocol.AccountEvent.InformationInvalidated());
      writing.get(5, TimeUnit.SECONDS);
      fixture.send("protected", "relay.topics", Map.of("project", "research"));
      entered.get(5, TimeUnit.SECONDS);
      result.complete(Outcome.ok(Map.of("secret", "protected content")));
      revoked.set(true);
      release.complete(null);
      verify(fixture.socket, timeout(5000)).close(any(CloseStatus.class));
      assertTrue(fixture.received.stream().noneMatch(frame -> frame.contains("protected content")));
    } finally {
      release.complete(null);
      result.complete(Outcome.ok());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void compliant_full_window_refills_inside_physical_reply_handoff(boolean segmented)
      throws Exception {
    var answers = new ConcurrentHashMap<Integer, CompletableFuture<Outcome>>();
    for (int n = 0; n < 65; n++) answers.put(n, new CompletableFuture<>());
    var firstEntered = new CompletableFuture<Void>();
    FrameHandler operation =
        new FrameHandler() {
          public Outcome handle(Map<String, Object> payload, Asking asking) {
            throw new AssertionError();
          }

          public java.util.concurrent.CompletionStage<Outcome> handleAsync(
              Map<String, Object> payload, Asking asking) {
            int slot = (Integer) payload.get("slot");
            if (slot == 0) firstEntered.complete(null);
            return answers.get(slot);
          }
        };
    var refilled = new CompletableFuture<Void>();
    var once = new AtomicBoolean();
    try (var fixture =
        new Fixture(segmented, new FrameRouter(Map.of("information.facets", operation)))) {
      fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
      fixture.reply();
      fixture.onReply =
          reply -> {
            try {
              if (FrameJson.answering().readTree(reply).path("id").asText().equals("id-0")
                  && once.compareAndSet(false, true)) {
                // The peer has retired id-0, but server socket.sendMessage()/packet send has not
                // returned yet. This is the exact window in which counting the active-ID map as
                // the pending limit used to close a compliant client.
                fixture.send("refill", "information.facets", Map.of("slot", 64));
                refilled.complete(null);
              }
            } catch (IOException failure) {
              refilled.completeExceptionally(failure);
            }
          };
      fixture.send("id-0", "information.facets", Map.of("slot", 0));
      firstEntered.get(5, TimeUnit.SECONDS);
      for (int n = 1; n < 64; n++) fixture.send("id-" + n, "information.facets", Map.of("slot", n));
      answers.get(0).complete(Outcome.ok(Map.of("slot", 0)));
      refilled.get(5, TimeUnit.SECONDS);
      assertTrue(
          fixture.open.get(), "Refilling the compliant window must not close the connection");
      for (int n = 1; n < 65; n++) answers.get(n).complete(Outcome.ok(Map.of("slot", n)));
      var ids = new java.util.HashSet<String>();
      for (int n = 0; n < 65; n++)
        ids.add(FrameJson.answering().readTree(fixture.reply()).path("id").asText());
      assertEquals(65, ids.size());
      assertTrue(ids.contains("refill"));
      fixture.handler.drainRequests();
      var measured = fixture.handler.dispatchMeasurements();
      assertEquals(0, measured.active());
      assertEquals(0, measured.queued());
      assertEquals(0, measured.requestBytes());
      assertEquals(0, measured.replyBytes());
      assertEquals(0, measured.resourceKeys());
      verify(fixture.socket, never()).close(any(CloseStatus.class));
    } finally {
      answers.values().forEach(answer -> answer.complete(Outcome.ok()));
    }
  }

  @Test
  void unselected_malformed_json_preserves_refusal_and_keeps_connection_usable() throws Exception {
    var router =
        new FrameRouter(Map.of("information.facets", (payload, asking) -> Outcome.ok(Map.of())));
    try (var fixture = new Fixture(false, router)) {
      fixture.handler.handleTextMessage(fixture.socket, new TextMessage("{malformed"));
      assertEquals(
          "BAD_REQUEST",
          FrameJson.answering().readTree(fixture.reply()).path("payload").path("code").asText());
      assertTrue(fixture.open.get());
      fixture.send("next", "information.facets", Map.of());
      assertEquals("next", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
    }
  }

  @Test
  void late_or_repeated_selection_closes_without_domain_execution() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var router =
        new FrameRouter(
            Map.of(
                "information.facets",
                (payload, asking) -> {
                  calls.incrementAndGet();
                  return Outcome.ok(Map.of());
                }));
    try (var fixture = new Fixture(false, router)) {
      fixture.send("first", "information.facets", Map.of());
      fixture.reply();
      fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
      verify(fixture.socket, timeout(5000)).close(any(CloseStatus.class));
      assertEquals(1, calls.get());
    }
    try (var fixture = new Fixture(false, router)) {
      fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
      fixture.reply();
      fixture.send("again", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
      verify(fixture.socket, timeout(5000)).close(any(CloseStatus.class));
      assertEquals(1, calls.get());
    }
  }
}
