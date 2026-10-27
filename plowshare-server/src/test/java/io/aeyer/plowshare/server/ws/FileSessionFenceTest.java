package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Timeout(10)
class FileSessionFenceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final FileChannelHandler files =
      new FileChannelHandler(
          new SessionRegistry(),
          new PresenceRegistry(),
          mock(ProjectRoots.class),
          TestProjectMembers.allowed(),
          Duration.ofSeconds(3));

  private WebSocketSession connect(String session, String account) throws Exception {
    var socket = mock(WebSocketSession.class);
    when(socket.getUri())
        .thenReturn(URI.create("ws://example.invalid/v1/files?session=" + session));
    var attributes = new ConcurrentHashMap<String, Object>();
    attributes.put(EventChannelHandler.HANDLE, account);
    when(socket.getAttributes()).thenReturn(attributes);
    when(socket.isOpen()).thenReturn(true);
    files.afterConnectionEstablished(socket);
    return socket;
  }

  private void answerRoots(WebSocketSession socket) throws Exception {
    doAnswer(
            call -> {
              FileRequest request =
                  json.readValue(
                      ((TextMessage) call.getArgument(0)).getPayload(), FileRequest.class);
              files.handleTextMessage(
                  socket,
                  new TextMessage(
                      json.writeValueAsString(
                          FileReply.listed(request.id(), List.of("/selected-root")))));
              return null;
            })
        .when(socket)
        .sendMessage(any());
  }

  @Test
  void the_snapshot_fence_preserves_authorization_refusals_without_reading_files()
      throws Exception {
    var socket = connect("authorized-session", "owner");
    var refused = new io.aeyer.plowshare.server.faults.CallerFault("conversation is not owned");
    var called = new AtomicInteger();
    var wrapped =
        new ContextInputHandler(
            (payload, asking) -> {
              called.incrementAndGet();
              throw refused;
            },
            files);
    assertSame(
        refused,
        assertThrows(
            io.aeyer.plowshare.server.faults.CallerFault.class,
            () -> wrapped.handle(Map.of(), new Asking("authorized-session", "other-account"))));
    assertEquals(1, called.get());
    verify(socket, never()).sendMessage(any());
  }

  @Test
  void mutation_handlers_do_not_acquire_context_read_fences_or_get_replayed() {
    SessionChannel forbidden =
        new SessionChannel() {
          @Override
          public SessionChannel bound(String session) {
            throw new AssertionError("mutation fenced");
          }

          @Override
          public FileReply ask(String session, FileRequest request) {
            throw new AssertionError("file request");
          }
        };
    var calls = new AtomicInteger();
    try (var context = new GenericApplicationContext()) {
      context.registerBean(SessionChannel.class, () -> forbidden);
      context.refresh();
      FrameArea area =
          () ->
              Map.of(
                  FrameTypes.CONVERSATION_RESUME,
                  (payload, asking) -> {
                    calls.incrementAndGet();
                    return Outcome.ok(Map.of("started", true));
                  });
      var router =
          new FrameRoutingConfig()
              .frameRouter(
                  List.of(area),
                  null,
                  ReadAdmission.direct(),
                  context.getBeanProvider(SessionChannel.class));
      assertEquals(
          Code.OK,
          router
              .route(
                  new Envelope(
                      "mutation",
                      FrameTypes.CONVERSATION_RESUME,
                      Envelope.CURRENT_VERSION,
                      Map.of()),
                  new Asking("session", "owner"))
              .code());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void a_bound_channel_never_crosses_a_replacement_or_another_session() throws Exception {
    var first = connect("owner-session", "owner");
    answerRoots(first);
    SessionChannel captured = files.bound("owner-session");
    assertEquals(
        List.of("/selected-root"),
        captured.ask("owner-session", FileRequest.roots("first")).paths());
    assertThrows(
        IllegalArgumentException.class,
        () -> captured.ask("other-session", FileRequest.roots("cross-session")));
    var replacement = connect("owner-session", "owner");
    answerRoots(replacement);
    assertThrows(
        SessionGoneException.class, () -> captured.ask("owner-session", FileRequest.roots("old")));
    assertThrows(SessionGoneException.class, () -> captured.requireCurrent("owner-session"));
    verify(replacement, never()).sendMessage(any());
    files.afterConnectionClosed(first, CloseStatus.GOING_AWAY);
    assertEquals(
        List.of("/selected-root"),
        files.bound("owner-session").ask("owner-session", FileRequest.roots("fresh")).paths());
  }

  @Test
  void a_different_account_cannot_replace_the_connection_held_by_a_fence() throws Exception {
    var owner = connect("owned", "owner");
    answerRoots(owner);
    var captured = files.bound("owned");
    var intruder = connect("owned", "intruder");
    verify(intruder).close(any());
    captured.requireCurrent("owned");
    assertEquals(
        List.of("/selected-root"), captured.ask("owned", FileRequest.roots("still-owned")).paths());
    verify(intruder, never()).sendMessage(any());
  }

  @Test
  void server_only_reads_accept_an_unchanged_absence_but_do_not_follow_a_new_connection()
      throws Exception {
    var absent = files.bound("not-attached");
    absent.requireCurrent("not-attached");
    assertFalse(absent.sources("not-attached"));
    assertEquals("server result", files.readSnapshot("not-attached", () -> "server result"));
    assertThrows(
        SessionGoneException.class,
        () -> absent.ask("not-attached", FileRequest.roots("no-files")));
    connect("not-attached", "owner");
    assertThrows(SessionGoneException.class, () -> absent.requireCurrent("not-attached"));
  }

  @Test
  void closing_an_outstanding_rule_read_refuses_promptly_without_waiting_for_the_deadline()
      throws Exception {
    var socket = connect("waiting", "owner");
    var sent = new CountDownLatch(1);
    doAnswer(
            call -> {
              sent.countDown();
              return null;
            })
        .when(socket)
        .sendMessage(any());
    var captured = files.bound("waiting");
    var pending =
        CompletableFuture.supplyAsync(() -> captured.ask("waiting", FileRequest.roots("pending")));
    assertTrue(sent.await(1, TimeUnit.SECONDS));
    files.afterConnectionClosed(socket, CloseStatus.GOING_AWAY);
    var failed = assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
    assertInstanceOf(SessionGoneException.class, failed.getCause());
  }

  @Test
  void a_last_reply_followed_by_takeover_before_return_is_not_published() throws Exception {
    var first = connect("last-reply", "owner");
    doAnswer(
            call -> {
              var request =
                  json.readValue(
                      ((TextMessage) call.getArgument(0)).getPayload(), FileRequest.class);
              files.handleTextMessage(
                  first,
                  new TextMessage(
                      json.writeValueAsString(
                          FileReply.listed(request.id(), List.of("/old-root")))));
              connect("last-reply", "owner");
              return null;
            })
        .when(first)
        .sendMessage(any());
    assertThrows(
        SessionGoneException.class,
        () -> files.bound("last-reply").ask("last-reply", FileRequest.roots("last")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "conversation.context",
        "conversation.projection",
        "conversation.context.count",
        "conversation.context.snapshot"
      })
  void the_production_context_routes_discard_snapshots_across_takeover_without_replaying(
      String type) throws Exception {
    connect("context-session", "owner");
    var calls = new AtomicInteger();
    FrameHandler read =
        (payload, asking) -> {
          assertEquals("owner", asking.requireHandle(type));
          calls.incrementAndGet();
          try {
            connect("context-session", "owner");
          } catch (Exception failed) {
            throw new AssertionError(failed);
          }
          return Outcome.ok(Map.of("privateInput", "old connection"));
        };
    try (var context = new GenericApplicationContext()) {
      context.registerBean(SessionChannel.class, () -> files);
      context.refresh();
      var router =
          new FrameRoutingConfig()
              .frameRouter(
                  List.of(() -> Map.of(type, read)),
                  null,
                  ReadAdmission.direct(),
                  context.getBeanProvider(SessionChannel.class));
      var answer =
          router.route(
              new Envelope("context-read", type, Envelope.CURRENT_VERSION, Map.of()),
              new Asking("context-session", "owner"));
      assertEquals(Code.ARCHIVE_UNAVAILABLE, answer.code());
      assertTrue(answer.said().contains("Restore the owning file connection"));
      assertEquals(1, calls.get());
    }
  }
}
