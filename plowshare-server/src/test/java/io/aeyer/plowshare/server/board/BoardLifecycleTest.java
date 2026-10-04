package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class BoardLifecycleTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  BoardFixture f;
  Board board;
  BoardTopic root;

  @BeforeEach
  void fresh() {
    f = new BoardFixture(source);
    board = f.board(BoardFixture.TWO);
    root = f.openByBot(board).topic();
  }

  BoardMessage request(BoardTopic topic, String author, String title) {
    return board
        .post(
            new Board.Post(
                topic.id(),
                BoardMessage.BY_MEMBER,
                author,
                null,
                null,
                BoardMessage.REQUEST,
                title,
                "Research this separately",
                null,
                List.of(),
                false))
        .message();
  }

  BoardTopic child(BoardTopic parent, String author) {
    BoardMessage request = request(parent, author, "Child of " + parent.depth());
    return f.store
        .topic(board.decide(parent.id(), request.id(), true, "Useful", null).child())
        .orElseThrow();
  }

  void idle() {
    f.jdbc.update(
        "UPDATE firings SET status = 'refused', reason = 'test idle' WHERE status = 'queued'");
  }

  int notices(String kind) {
    return f.jdbc.queryForObject(
        "SELECT count(*) FROM board_notices WHERE kind = ?", Integer.class, kind);
  }

  Board.Closed close(BoardTopic topic) {
    return board.close(
        new Board.Close(
            topic.id(),
            BoardMessage.BY_OPENER,
            topic.opener(),
            null,
            "Research complete",
            List.of()));
  }

  @Test
  void approval_seats_the_requester_as_opener_and_shares_the_root_pot() {
    BoardMessage request = request(root, "researcher", "  Deep\n" + "x".repeat(140));
    BoardStore.Decision decision =
        board.decide(root.id(), request.id(), true, "Separate concern", null);
    BoardTopic child = f.store.topic(decision.child()).orElseThrow();
    assertEquals(root.id(), child.root());
    assertEquals(root.id(), child.parent());
    assertEquals(root.account(), child.account());
    assertEquals(1, child.depth());
    assertNull(child.potTotal());
    assertNull(child.originConversation());
    assertEquals(BoardTopic.BY_MEMBER, child.openerKind());
    assertEquals("researcher", child.opener());
    assertEquals(120, child.title().codePointCount(0, child.title().length()));
    assertFalse(child.title().contains("\n"));
    assertEquals(
        List.of("@opener", "critic"),
        f.store.seats(child.id()).stream().map(BoardSeat::occupant).toList());
    assertTrue(
        f.store.messages(root.id()).stream()
            .anyMatch(m -> request.id().equals(m.replyTo()) && m.body().contains(child.id())));
  }

  @Test
  void a_refusal_is_durable_and_cannot_be_decided_twice() {
    BoardMessage request = request(root, "researcher", "No child needed");
    var refusal = board.decide(root.id(), request.id(), false, "Keep it here", null);
    assertFalse(refusal.approved());
    assertNull(refusal.child());
    assertThrows(
        Board.Refused.class, () -> board.decide(root.id(), request.id(), true, "Again", null));
    assertEquals(1, f.store.openTree(root.id()).size());
  }

  @Test
  void a_decision_requires_a_pending_request_on_its_own_topic() {
    BoardMessage other = request(child(root, "researcher"), "critic", "Other request");
    assertThrows(Board.Refused.class, () -> board.decide(root.id(), other.id(), false, "No", null));
    BoardMessage opening = f.store.messages(root.id()).getFirst();
    assertThrows(
        Board.Refused.class, () -> board.decide(root.id(), opening.id(), false, "No", null));
  }

  @Test
  void depth_is_refused_when_requesting_and_rechecked_when_approving() {
    BoardTopic child = child(root, "researcher");
    BoardTopic grandchild = child(child, "critic");
    assertThrows(Board.Refused.class, () -> request(grandchild, "researcher", "Too deep"));
    BoardMessage pending = request(child, "critic", "Pending");
    board.useMaxDepth(() -> 1);
    assertThrows(
        Board.Refused.class, () -> board.decide(child.id(), pending.id(), true, "No", null));
    assertTrue(f.store.decision(pending.id()).isEmpty());
  }

  @Test
  void concurrent_decisions_open_only_one_child() throws Exception {
    BoardMessage request = request(root, "researcher", "One child");
    CountDownLatch start = new CountDownLatch(1);
    java.util.function.Supplier<Boolean> attempt =
        () -> {
          try {
            start.await();
            board.decide(root.id(), request.id(), true, "Approved", null);
            return true;
          } catch (Board.Refused expected) {
            return false;
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        };
    var first = CompletableFuture.supplyAsync(attempt);
    var second = CompletableFuture.supplyAsync(attempt);
    start.countDown();
    assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    assertEquals(2, f.store.openTree(root.id()).size());
  }

  @Test
  void child_resolution_answers_the_parent_request_once_and_wakes_the_requester() {
    BoardTopic child = child(root, "researcher");
    idle();
    String request = f.store.requestForChild(child.id()).orElseThrow();
    var resolution = close(child);
    List<BoardMessage> replies =
        f.store.messages(root.id()).stream()
            .filter(
                m -> request.equals(m.replyTo()) && BoardMessage.BY_HARNESS.equals(m.authorKind()))
            .toList();
    assertEquals(1, replies.size());
    assertTrue(replies.getFirst().body().contains(resolution.resolution().body()));
    assertFalse(f.store.resolutionUndelivered(child.id()));
    var requester = f.store.seat(root.id(), "researcher").orElseThrow();
    assertEquals(
        List.of("conversation:" + requester.conversation()), f.store.queuedWakeTargets(root.id()));
  }

  @Test
  void closing_a_parent_cascades_deepest_first_and_lists_child_documents() {
    BoardTopic child = child(root, "researcher");
    BoardTopic grandchild = child(child, "critic");
    BoardMessage doc =
        board
            .post(
                new Board.Post(
                    grandchild.id(),
                    BoardMessage.BY_MEMBER,
                    "researcher",
                    null,
                    null,
                    BoardMessage.DOCUMENT,
                    "Evidence",
                    "Important findings",
                    null,
                    List.of(),
                    false))
            .message();
    List<String> closed = new CopyOnWriteArrayList<>();
    board.whenClosed(closed::add);
    close(root);
    assertEquals(List.of(grandchild.id(), child.id(), root.id()), closed);
    assertTrue(f.store.openTree(root.id()).isEmpty());
    BoardMessage resolution =
        f.store.message(f.store.topic(grandchild.id()).orElseThrow().resolution()).orElseThrow();
    assertTrue(resolution.body().contains("closed with its parent"));
    assertTrue(resolution.body().contains(doc.id()));
    assertTrue(f.store.queuedWakeTargets(root.id()).isEmpty());
    assertThrows(Board.Refused.class, () -> request(child, "critic", "After close"));
  }

  @Test
  void ancestor_reads_have_independent_watermarks_and_never_read_siblings() {
    BoardTopic child = child(root, "researcher");
    BoardTopic sibling = child(root, "critic");
    assertFalse(board.read(child.id(), "critic", root.id()).isEmpty());
    assertTrue(board.read(child.id(), "critic", root.id()).isEmpty());
    assertNull(f.store.seat(root.id(), "critic").orElseThrow().seenThrough());
    assertThrows(Board.Refused.class, () -> board.read(child.id(), "critic", sibling.id()));
    assertThrows(Board.Refused.class, () -> board.read(root.id(), "critic", child.id()));
  }

  @Test
  void quiet_notifies_once_until_new_activity_and_waits_for_children() {
    BoardTopic child = child(root, "researcher");
    idle();
    board.settled(root.id());
    assertEquals(1, notices("quiet"));
    assertNull(f.store.topic(root.id()).orElseThrow().quietNotifiedAt());
    idle();
    board.settled(root.id());
    assertEquals(1, notices("quiet"));
    board.post(
        new Board.Post(
            child.id(),
            BoardMessage.BY_MEMBER,
            "critic",
            null,
            null,
            BoardMessage.POST,
            null,
            "More findings",
            null,
            List.of(),
            false));
    idle();
    board.settled(root.id());
    assertEquals(2, notices("quiet"));
    close(child);
    idle();
    board.settled(root.id());
    assertEquals(3, notices("quiet"));
    assertNotNull(f.store.topic(root.id()).orElseThrow().quietNotifiedAt());
  }

  @Test
  void queued_started_and_external_continuations_prevent_quiet_notices() {
    board.settled(root.id());
    assertEquals(0, notices("quiet"));
    f.jdbc.update(
        "UPDATE firings SET status = 'started', job_id = 'test', started_at = now() WHERE status = 'queued'");
    board.settled(root.id());
    assertEquals(0, notices("quiet"));
    f.jdbc.update(
        "UPDATE firings SET status = 'refused', reason = 'idle', job_id = NULL, started_at = NULL WHERE status = 'started'");
    board.useRunning(topic -> true);
    board.settled(root.id());
    assertEquals(0, notices("quiet"));
    board.useRunning(topic -> false);
    board.settled(root.id());
    assertEquals(1, notices("quiet"));
  }

  @Test
  void exhaustion_notifies_every_opener_once_refuses_members_and_topup_reopens() {
    BoardTopic child = child(root, "researcher");
    f.store.spend(root.id(), 18);
    board.settled(root.id());
    assertEquals(BoardTopic.EXHAUSTED, f.store.topic(root.id()).orElseThrow().state());
    assertEquals(2, notices("exhausted"));
    for (String target : f.store.queuedWakeTargets(root.id())) {
      assertTrue(
          f.store
              .seatByConversation(target.substring("conversation:".length()))
              .orElseThrow()
              .isOpener());
    }
    board.settled(root.id());
    assertEquals(2, notices("exhausted"));
    assertThrows(Board.Refused.class, () -> board.topup(root.id(), 20));
    BoardTopic topped = board.topup(child.id(), 30);
    assertEquals(root.id(), topped.id());
    assertEquals(30, topped.potTotal());
    assertEquals(18, topped.potSpent());
    assertEquals(BoardTopic.OPEN, topped.state());
    assertTrue(
        f.store.queuedWakeTargets(root.id()).stream()
            .anyMatch(
                t ->
                    !f.store
                        .seatByConversation(t.substring("conversation:".length()))
                        .orElseThrow()
                        .isOpener()));
    f.store.spend(root.id(), 9);
    board.settled(root.id());
    assertEquals(4, notices("exhausted"));
  }

  @Test
  void spending_the_final_reserve_harness_closes_the_tree_with_documents() {
    BoardTopic child = child(root, "researcher");
    BoardMessage doc =
        board
            .post(
                new Board.Post(
                    root.id(),
                    BoardMessage.BY_MEMBER,
                    "critic",
                    null,
                    null,
                    BoardMessage.DOCUMENT,
                    "Findings",
                    "Conclusions",
                    null,
                    List.of(),
                    false))
            .message();
    f.store.spend(root.id(), 20);
    board.settled(root.id());
    assertTrue(f.store.topic(root.id()).orElseThrow().isClosed());
    assertTrue(f.store.topic(child.id()).orElseThrow().isClosed());
    String resolution =
        f.store.message(f.store.topic(root.id()).orElseThrow().resolution()).orElseThrow().body();
    assertTrue(resolution.contains("closed by the harness: budget exhausted"));
    assertTrue(resolution.contains(doc.id()));
    assertThrows(Board.Refused.class, () -> board.topup(root.id(), 40));
  }

  @Test
  void notices_to_person_openers_stay_owed_for_inbox_delivery() {
    BoardTopic person =
        board
            .open(
                new Board.Open(
                    Home.of("payments"),
                    "Person topic",
                    "L",
                    "Question",
                    "enzo",
                    BoardTopic.BY_PERSON,
                    "enzo",
                    null,
                    20))
            .topic();
    idle();
    board.settled(person.id());
    assertEquals(1, f.store.owedNotices().size());
    assertEquals(person.id(), f.store.owedNotices().getFirst().topic());
    board.settled(person.id());
    assertEquals(1, f.store.owedNotices().size());
  }

  @Test
  void nested_board_writes_dispatch_only_after_the_outer_commit_and_never_on_rollback()
      throws Exception {
    List<String> drains = new CopyOnWriteArrayList<>();
    Board nested =
        new Board(
            f.store,
            project -> BoardFixture.TWO,
            f.conversations,
            f.firings,
            target -> {
              assertTrue(f.firings.targetsWaiting().contains(target));
              drains.add(target);
            },
            f.work,
            () -> 10,
            f.clock);
    f.work.inTransaction(
        () -> {
          nested.open(
              new Board.Open(
                  Home.of("payments"),
                  "Nested",
                  "L",
                  "Question",
                  "enzo",
                  BoardTopic.BY_PERSON,
                  "enzo",
                  null,
                  20));
          assertTrue(drains.isEmpty());
          return null;
        });
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (drains.size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(2, drains.size());
    drains.clear();
    assertThrows(
        IllegalStateException.class,
        () ->
            f.work.inTransaction(
                () -> {
                  nested.open(
                      new Board.Open(
                          Home.of("payments"),
                          "Rolled back",
                          "L",
                          "Question",
                          "enzo",
                          BoardTopic.BY_PERSON,
                          "enzo",
                          null,
                          20));
                  throw new IllegalStateException("rollback");
                }));
    assertTrue(drains.isEmpty());
    assertFalse(f.store.openTopics().stream().anyMatch(t -> t.title().equals("Rolled back")));
  }

  @Test
  void a_first_alert_creates_a_missing_member_seat_and_bad_kinds_are_service_refusals() {
    f.jdbc.update("DELETE FROM firings WHERE topic = ?", root.id());
    f.jdbc.update("DELETE FROM board_seats WHERE topic = ? AND occupant = 'critic'", root.id());
    var posted =
        board.post(
            new Board.Post(
                root.id(),
                BoardMessage.BY_MEMBER,
                "critic",
                null,
                null,
                BoardMessage.POST,
                null,
                "Alert",
                null,
                List.of(),
                true));
    assertFalse(posted.alertRefused());
    assertEquals(1, f.store.seat(root.id(), "critic").orElseThrow().alertsUsed());
    for (String kind : new String[] {"unknown", "resolution", "pass"}) {
      assertThrows(
          Board.Refused.class,
          () ->
              board.post(
                  new Board.Post(
                      root.id(),
                      BoardMessage.BY_MEMBER,
                      "critic",
                      null,
                      null,
                      kind,
                      null,
                      "Body",
                      null,
                      List.of(),
                      false)));
    }
    assertThrows(
        Board.Refused.class,
        () ->
            board.post(
                new Board.Post(
                    root.id(),
                    "fake",
                    "critic",
                    null,
                    null,
                    BoardMessage.POST,
                    null,
                    "Body",
                    null,
                    List.of(),
                    false)));
  }

  @Test
  void delegated_descendants_inherit_the_root_account_and_the_seat_share() {
    BoardTopic child = child(root, "researcher");
    BoardSeat opener = f.store.seat(child.id(), BoardSeat.OPENER).orElseThrow();
    var delegate =
        f.conversations.log(
            Origin.DELEGATION, Home.of("payments"), "critic", opener.conversation(), null);
    var descendant =
        f.conversations.log(
            Origin.DELEGATION, Home.of("payments"), "researcher", delegate.id(), null);
    var shares = BoardConfig.shareOf(f.store, f.conversations);
    var share = new SwarmScheduler.Share(root.account(), child.id(), BoardSeat.OPENER);
    assertEquals(
        java.util.Optional.of(share),
        shares.apply(new RunExtras.Context(null, opener.conversation(), null, null)));
    assertEquals(
        java.util.Optional.of(share),
        shares.apply(new RunExtras.Context(null, delegate.id(), null, null)));
    assertEquals(
        java.util.Optional.of(share),
        shares.apply(new RunExtras.Context(null, descendant.id(), null, null)));
  }

  @Test
  void member_openers_are_mentioned_through_their_opener_seat_and_never_alert_themselves() {
    BoardTopic child = child(root, "researcher");
    idle();
    var mention =
        board.post(
            new Board.Post(
                child.id(),
                BoardMessage.BY_MEMBER,
                "critic",
                null,
                null,
                BoardMessage.POST,
                null,
                "Please close",
                null,
                List.of("researcher"),
                false));
    assertEquals(
        List.of(BoardSeat.OPENER), mention.woken().stream().map(WakeRules.Wake::occupant).toList());
    assertTrue(f.store.seat(child.id(), "researcher").isEmpty());
    var alert =
        board.post(
            new Board.Post(
                child.id(),
                BoardMessage.BY_OPENER,
                "researcher",
                null,
                null,
                BoardMessage.POST,
                null,
                "Everyone look",
                null,
                List.of(),
                true));
    assertEquals(List.of("critic"), alert.woken().stream().map(WakeRules.Wake::occupant).toList());
    var repeated =
        board.post(
            new Board.Post(
                child.id(),
                BoardMessage.BY_OPENER,
                "researcher",
                null,
                null,
                BoardMessage.POST,
                null,
                "Second alert",
                null,
                List.of(),
                true));
    assertTrue(repeated.alertRefused(), "a member opener bypassed its one-alert quota");
    assertFalse(repeated.message().alert());
    assertEquals(1, f.store.seat(child.id(), BoardSeat.OPENER).orElseThrow().alertsUsed());
    board.read(child.id(), BoardSeat.OPENER);
    idle();
    board.post(
        new Board.Post(
            child.id(),
            BoardMessage.BY_MEMBER,
            "critic",
            null,
            null,
            BoardMessage.POST,
            null,
            "Mention again",
            null,
            List.of("researcher"),
            false));
    idle();
    assertTrue(board.reowe(child.id(), BoardSeat.OPENER));
  }

  @Test
  void finished_firings_and_failed_harness_notes_do_not_keep_a_topic_active_or_loop_quiet() {
    f.jdbc.update(
        "UPDATE firings SET status = 'started', job_id = 'test', started_at = now(),"
            + " finished_at = now() WHERE status = 'queued'");
    board.settled(root.id());
    assertEquals(1, notices("quiet"));
    idle();
    board.note(root.id(), "opener failed");
    board.settled(root.id());
    assertEquals(1, notices("quiet"));
  }

  @Test
  void delegated_continuations_draw_and_settle_the_root_pot_and_stop_when_closed() {
    BoardTopic child = child(root, "researcher");
    BoardPot pot = new BoardPot(f.store);
    BoardDelegates delegates =
        new BoardDelegates(
            f.store, board, pot, f.conversations, () -> 4, target -> {}, conversation -> false);
    var parent =
        f.conversations
            .find(f.store.seat(child.id(), BoardSeat.OPENER).orElseThrow().conversation())
            .orElseThrow();
    var allowance = delegates.lease(parent);
    assertEquals(4, allowance.budget().limit());
    allowance.budget().trySpend();
    assertEquals(
        1,
        f.store.topic(root.id()).orElseThrow().potSpent(),
        "a resumed delegate's charge is durable before it finishes");
    idle();
    board.settled(root.id());
    assertEquals(0, notices("quiet"));
    close(child);
    assertTrue(allowance.budget().exhausted());
    allowance.settled().run();
    assertEquals(1, f.store.topic(root.id()).orElseThrow().potSpent());
    assertEquals(0, pot.leased(root.id()));
    assertThrows(
        io.aeyer.plowshare.server.agents.Turn.Refused.class, () -> delegates.lease(parent));
  }

  @Test
  void concurrent_quiet_checks_notify_only_once() throws Exception {
    idle();
    var first = CompletableFuture.runAsync(() -> board.settled(root.id()));
    var second = CompletableFuture.runAsync(() -> board.settled(root.id()));
    first.get(10, TimeUnit.SECONDS);
    second.get(10, TimeUnit.SECONDS);
    assertEquals(1, notices("quiet"));
  }

  @Test
  void approval_racing_parent_close_cannot_leave_an_open_child() throws Exception {
    BoardMessage request = request(root, "researcher", "Concurrent child");
    CountDownLatch start = new CountDownLatch(1);
    var decision =
        CompletableFuture.runAsync(
            () -> {
              try {
                start.await();
                board.decide(root.id(), request.id(), true, "Approve", null);
              } catch (Board.Refused expected) {
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
    var closing =
        CompletableFuture.runAsync(
            () -> {
              try {
                start.await();
                close(root);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
    start.countDown();
    decision.get(10, TimeUnit.SECONDS);
    closing.get(10, TimeUnit.SECONDS);
    assertTrue(f.store.openTree(root.id()).isEmpty());
    assertTrue(f.store.queuedWakeTargets(root.id()).isEmpty());
  }

  @Test
  void http_and_frame_topups_share_absolute_ceiling_validation() {
    var controller = new io.aeyer.plowshare.server.api.BoardController(board);
    var frames = new io.aeyer.plowshare.server.ws.BoardFrames(board).frames();
    var handler = frames.get(io.aeyer.plowshare.server.ws.FrameTypes.BOARD_TOPUP);
    assertThrows(
        Board.Refused.class,
        () ->
            controller.topup(
                root.id(), new io.aeyer.plowshare.server.api.BoardController.Topup(null)));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> handler.handle(java.util.Map.of("topic", root.id()), null));
    assertEquals(
        30,
        controller
            .topup(root.id(), new io.aeyer.plowshare.server.api.BoardController.Topup(30))
            .potTotal());
    handler.handle(java.util.Map.of("topic", root.id(), "maxModelCalls", 40), null);
    assertEquals(40, f.store.topic(root.id()).orElseThrow().potTotal());
    assertThrows(
        Board.Refused.class,
        () -> handler.handle(java.util.Map.of("topic", root.id(), "maxModelCalls", 30), null));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"quiet", "exhausted"})
  void capped_or_completed_notices_do_not_replay_but_interrupted_notices_recover_once(String kind) {
    idle();
    if (kind.equals("exhausted")) {
      f.store.spend(root.id(), 18);
    }
    board.settled(root.id());
    BoardMessage notice = f.store.messages(root.id()).getLast();
    String wake =
        f.jdbc.queryForObject(
            "SELECT wake FROM board_notices WHERE message = ?", String.class, notice.id());
    assertFalse(board.reowe(root.id(), BoardSeat.OPENER), "cap re-owed the quiet notice");
    f.firings.claimStart(wake, f.clock.get());
    f.firings.finish(wake, f.clock.get());
    assertFalse(
        board.reowe(root.id(), BoardSeat.OPENER, true), "completed notice replayed at boot");
    f.jdbc.update(
        "UPDATE firings SET reason = 'the server restarted during this run' WHERE id = ?", wake);
    assertTrue(board.reowe(root.id(), BoardSeat.OPENER, true));
    String recovered =
        f.jdbc.queryForObject(
            "SELECT wake FROM board_notices WHERE message = ?", String.class, notice.id());
    assertNotEquals(wake, recovered);
    f.firings.claimStart(recovered, f.clock.get());
    f.firings.finish(recovered, f.clock.get());
    assertFalse(
        board.reowe(root.id(), BoardSeat.OPENER, true),
        "an older interrupted firing replayed the completed retry");
    assertEquals(1, notices(kind));
  }
}
