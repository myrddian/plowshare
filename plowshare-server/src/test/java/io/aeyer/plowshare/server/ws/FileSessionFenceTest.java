package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.FileSource;
import io.aeyer.plowshare.protocol.Replacement;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.approvals.DirectFileCalls;
import io.aeyer.plowshare.server.approvals.RetainedFileEffects;
import io.aeyer.plowshare.server.approvals.RunCallResumption;
import io.aeyer.plowshare.server.approvals.SuspendedRunCalls;
import io.aeyer.plowshare.server.approvals.ToolEffectReceipt;
import io.aeyer.plowshare.server.files.ClientWork;
import io.aeyer.plowshare.server.files.FileContents;
import io.aeyer.plowshare.server.files.FileMutationObservation;
import io.aeyer.plowshare.server.files.FileReplyTimeoutException;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.files.VerifiedFileMutations;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
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
          TestProjectMembers.noCreation(),
          TestProjectMembers.allRegistered(),
          Duration.ofSeconds(3));

  private WebSocketSession connect(String session, String account) throws Exception {
    return connect(session, account, false);
  }

  private WebSocketSession connect(String session, String account, boolean source)
      throws Exception {
    var socket = mock(WebSocketSession.class);
    when(socket.getUri())
        .thenReturn(
            URI.create(
                "ws://example.invalid/v1/files?session=" + session + (source ? "&source=1" : "")));
    var attributes = new ConcurrentHashMap<String, Object>();
    attributes.put(EventChannelHandler.HANDLE, account);
    when(socket.getAttributes()).thenReturn(attributes);
    when(socket.isOpen()).thenReturn(true);
    files.afterConnectionEstablished(socket);
    return socket;
  }

  private void answerSource(WebSocketSession socket, FileRequest request, Path file)
      throws Exception {
    byte[] content = Files.readAllBytes(file);
    var source =
        request.offset() == null
            ? new FileSource(content.length, FileContents.sha256(content), null, null)
            : new FileSource(
                content.length,
                null,
                request.offset(),
                Base64.getEncoder().encodeToString(content));
    files.handleTextMessage(
        socket, new TextMessage(json.writeValueAsString(FileReply.source(request.id(), source))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void a_direct_edit_with_no_reply_is_unknown_and_never_replayed(
      boolean applied, @TempDir Path root) throws Exception {
    String session = "silent-direct";
    var socket = connect(session, "owner", true);
    var path = Files.writeString(root.resolve("note.txt"), "before\r\n");
    var sent = new AtomicReference<String>();
    var sends = new AtomicInteger();
    doAnswer(
            call -> {
              var request =
                  json.readValue(
                      ((TextMessage) call.getArgument(0)).getPayload(), FileRequest.class);
              if (FileRequest.SOURCE.equals(request.op())) {
                assertNull(sent.get(), "No read-back can settle an edit whose reply was lost");
                answerSource(socket, request, path);
              } else {
                assertEquals(FileRequest.EDIT, request.op());
                sends.incrementAndGet();
                sent.set(request.id());
                if (applied) Files.writeString(path, "after\r\n");
              }
              return null;
            })
        .when(socket)
        .sendMessage(any());

    var store = mock(DirectFileCalls.class);
    var tool = new ToolCall("call", "file_edit", "{}");
    var invocation = "file_original";
    when(store.claimDirect("log", 3, tool, "owner")).thenReturn(invocation);
    var effects =
        new RetainedFileEffects(store::recordEffect, store.claimDirect("log", 3, tool, "owner"));
    var provider =
        new RemoteProvider(files, session, List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)))
            .forReviewedMutation();
    var mutation = new VerifiedFileMutations(provider, effects);
    var timeout =
        assertThrows(FileReplyTimeoutException.class, () -> mutation.edit(path, "before", "after"));
    assertTrue(effects.replyTimedOut(timeout));
    var receipt = new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_REPLY_TIMEOUT, sent.get());
    var ordered = inOrder(store);
    ordered.verify(store).claimDirect("log", 3, tool, "owner");
    ordered
        .verify(store)
        .recordEffect(
            invocation, new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_SENT, sent.get()));
    ordered.verify(store).recordEffect(invocation, receipt);
    assertEquals(ToolEffectReceipt.Effect.UNKNOWN, receipt.effect());

    var replacement = connect(session, "owner", true);
    RunCallResumption.reconcileDirect(
        store,
        new DirectFileCalls.Pending(invocation, tool, receipt, null),
        result -> {
          assertEquals(receipt, result.effectReceipt());
          assertEquals(RunCallResumption.FILE_REPLY_TIMEOUT_RESULT, result.text());
        });
    verify(store).complete(invocation, RunCallResumption.FILE_REPLY_TIMEOUT_RESULT);
    verify(store).acknowledged(invocation);
    assertEquals(1, sends.get());
    verify(replacement, never()).sendMessage(any());
    assertEquals(
        applied ? "after\r\n" : "before\r\n", Files.readString(path, StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void a_direct_edit_verifies_disk_bytes_even_when_the_reply_claims_success(
      boolean applied, @TempDir Path root) throws Exception {
    String session = "verify-direct";
    var socket = connect(session, "owner", true);
    var path = Files.writeString(root.resolve("note.txt"), "before\r\n");
    var facts = Replacement.edit("before\r\n", "before", "after").result(path.toString());
    var id = new AtomicReference<String>();
    doAnswer(
            call -> {
              var request =
                  json.readValue(
                      ((TextMessage) call.getArgument(0)).getPayload(), FileRequest.class);
              if (FileRequest.SOURCE.equals(request.op())) answerSource(socket, request, path);
              else {
                assertEquals(FileRequest.EDIT, request.op());
                id.set(request.id());
                if (applied) Files.writeString(path, "after\r\n");
                files.handleTextMessage(
                    socket,
                    new TextMessage(
                        json.writeValueAsString(FileReply.changed(request.id(), facts))));
              }
              return null;
            })
        .when(socket)
        .sendMessage(any());
    var observations = new ArrayList<FileMutationObservation>();
    var provider =
        new RemoteProvider(files, session, List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)))
            .forReviewedMutation();
    assertEquals(
        facts,
        new VerifiedFileMutations(provider, observations::add)
            .edit(path, "before", "after")
            .facts());
    assertEquals(
        List.of(
            new FileMutationObservation(null, FileMutationObservation.Kind.ATTEMPTED),
            new FileMutationObservation(id.get(), FileMutationObservation.Kind.SENT),
            new FileMutationObservation(
                id.get(),
                applied
                    ? FileMutationObservation.Kind.VERIFIED
                    : FileMutationObservation.Kind.REPLY_UNVERIFIED)),
        observations);
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
  void an_interrupted_bound_mutation_records_its_unknown_outcome_without_replaying()
      throws Exception {
    String session = "mutating";
    var socket = connect(session, "owner");
    var request = FileRequest.delete("original-delete", "/fixture/security.md");
    var replacement = new AtomicReference<WebSocketSession>();
    var work = mock(ClientWork.class);
    when(work.requestBudget(any())).thenAnswer(call -> call.getArgument(0));
    when(work.interrupted(session, request))
        .thenAnswer(
            call -> {
              replacement.set(connect(session, "owner"));
              return true;
            });
    files.useClientWork(work);
    doAnswer(
            call -> {
              files.afterConnectionClosed(socket, CloseStatus.GOING_AWAY);
              return null;
            })
        .when(socket)
        .sendMessage(any());
    var captured = files.bound(session);

    assertThrows(SessionGoneException.class, () -> captured.ask(session, request));

    var ordered = inOrder(work, socket);
    ordered.verify(work).beforeRequest(session);
    ordered.verify(socket).sendMessage(any());
    ordered.verify(work).interrupted(session, request);
    assertNotNull(replacement.get());
    verify(replacement.get(), never()).sendMessage(any());

    var unsent = FileRequest.delete("new-delete", "/fixture/security.md");
    assertThrows(SessionGoneException.class, () -> captured.ask(session, unsent));
    verify(work, never()).interrupted(session, unsent);
    verify(replacement.get(), never()).sendMessage(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void a_sent_mutation_without_a_reply_stays_unknown_through_retained_recovery(boolean peerApplied)
      throws Exception {
    String session = "silent-mutation";
    String approval = "apr_silent";
    var socket = connect(session, "owner");
    var effectsApplied = new AtomicInteger();
    var requestId = new AtomicReference<String>();
    doAnswer(
            invocation -> {
              var frame = (TextMessage) invocation.getArgument(0);
              var request = json.readValue(frame.getPayload(), FileRequest.class);
              assertEquals(FileRequest.DELETE, request.op());
              requestId.set(request.id());
              if (peerApplied) effectsApplied.incrementAndGet();
              return null; // The socket stays open and the peer supplies no reply.
            })
        .when(socket)
        .sendMessage(any());

    var store = mock(SuspendedRunCalls.class);
    when(store.claim(approval)).thenReturn(true);
    var provider =
        new RemoteProvider(files, session, List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)))
            .forReviewedMutation();
    var tool = new ToolCall("tool_original", "file_delete", "{\"path\":\"/fixture/demo.txt\"}");
    var waiting =
        new SuspendedRunCalls.Call(
            approval,
            "conversation",
            session,
            "local",
            tool,
            "waiting",
            "allowed",
            "owner",
            null,
            null);
    var original = new RunCallResumption(store);
    assertThrows(
        IllegalStateException.class,
        () ->
            original.resumeWithReceipt(
                waiting,
                call -> {
                  var effects = new RetainedFileEffects(store, call.approval());
                  var timeout =
                      assertThrows(
                          FileReplyTimeoutException.class,
                          () ->
                              provider
                                  .observeMutation(effects)
                                  .delete(Path.of("/fixture/demo.txt")));
                  assertTrue(effects.replyTimedOut(timeout));
                  return effects.result(RunCallResumption.FILE_REPLY_TIMEOUT_RESULT);
                },
                result -> {
                  assertTrue(result.replyTimedOut());
                  assertEquals(ToolEffectReceipt.Dispatch.SENT, result.effectReceipt().dispatch());
                  assertEquals(ToolEffectReceipt.Effect.UNKNOWN, result.effectReceipt().effect());
                  throw new IllegalStateException("Interrupted before writing Trajectory");
                }));

    var sent = new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_SENT, requestId.get());
    var unknown = new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_REPLY_TIMEOUT, requestId.get());
    var ordered = inOrder(store, socket);
    ordered.verify(store).claim(approval);
    ordered.verify(socket).sendMessage(any());
    ordered.verify(store).recordEffect(approval, sent);
    ordered.verify(store).recordEffect(approval, unknown);
    ordered.verify(store).complete(approval, RunCallResumption.FILE_REPLY_TIMEOUT_RESULT);
    verify(store, never()).acknowledged(approval);

    var replacement = connect(session, "owner");
    var completed =
        new SuspendedRunCalls.Call(
            approval,
            "conversation",
            session,
            "local",
            tool,
            "completed",
            "used",
            "owner",
            null,
            RunCallResumption.FILE_REPLY_TIMEOUT_RESULT,
            "owner",
            true,
            unknown);
    assertTrue(
        new RunCallResumption(store)
            .resumeWithReceipt(
                completed,
                call -> fail("A retained mutation may not be replayed"),
                result -> {
                  assertEquals(unknown, result.effectReceipt());
                  assertEquals(RunCallResumption.FILE_REPLY_TIMEOUT_RESULT, result.text());
                }));
    verify(store).acknowledged(approval);
    verify(store, times(1)).claim(approval);
    verify(socket, times(1)).sendMessage(any());
    verify(replacement, never()).sendMessage(any());
    assertEquals(peerApplied ? 1 : 0, effectsApplied.get());
  }

  @Test
  void a_matching_provider_confirmation_is_recorded_beside_the_original_file_result()
      throws Exception {
    String session = "confirming-mutation";
    var socket = connect(session, "owner");
    Path path = Path.of("/fixture/demo.txt");
    var confirmed = FileResult.deleted(path.toString(), 12, 1);
    var requestId = new AtomicReference<String>();
    doAnswer(
            invocation -> {
              var frame = (TextMessage) invocation.getArgument(0);
              var request = json.readValue(frame.getPayload(), FileRequest.class);
              assertEquals(FileRequest.DELETE, request.op());
              requestId.set(request.id());
              files.handleTextMessage(
                  socket,
                  new TextMessage(
                      json.writeValueAsString(FileReply.changed(request.id(), confirmed))));
              return null;
            })
        .when(socket)
        .sendMessage(any());
    var observed = new ArrayList<FileMutationObservation>();
    var provider =
        new RemoteProvider(files, session, List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)))
            .forReviewedMutation()
            .observeMutation(observed::add);

    assertEquals(confirmed, provider.delete(path).facts());
    assertEquals(
        List.of(
            new FileMutationObservation(requestId.get(), FileMutationObservation.Kind.SENT),
            new FileMutationObservation(requestId.get(), FileMutationObservation.Kind.CONFIRMED)),
        observed);
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
