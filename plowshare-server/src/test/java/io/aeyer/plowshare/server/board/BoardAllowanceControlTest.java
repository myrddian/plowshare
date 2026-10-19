package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Controls use the authenticated operator's scoped authority, never the opener's identity. */
class BoardAllowanceControlTest {
  private final BoardStore store = mock(BoardStore.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final BoardTopic root = mock(BoardTopic.class);

  private Board board() {
    when(root.id()).thenReturn("root");
    when(root.root()).thenReturn("root");
    when(root.project()).thenReturn("project");
    when(root.account()).thenReturn("opener");
    when(root.openerKind()).thenReturn(BoardTopic.BY_PERSON);
    when(root.potTotal()).thenReturn(10);
    when(store.topic("root")).thenReturn(Optional.of(root));
    when(store.lockTopic("root")).thenReturn(Optional.of(root));
    when(store.openTree("root")).thenReturn(List.of(root));
    return new Board(
        store,
        ignored -> List.of(),
        mock(ConversationStore.class),
        mock(FiringStore.class),
        ignored -> {},
        UnitOfWork.NONE,
        () -> 10,
        Instant::now,
        members);
  }

  @Test
  void scopedOperatorCanRaiseAnotherAccountsTopicWithoutChangingItsExecutor() {
    Board board = board();
    board.topup("root", 20, "operator");
    verify(members).requireRole("project", "operator", ProjectRole.CONTRIBUTOR);
    verify(store).topup("root", 20, 2);
    assertEquals("opener", root.account());
  }

  @Test
  void readerAndUnrelatedActorsCannotChangeAllowanceOrRetryMembers() {
    Board board = board();
    for (String actor : List.of("reader", "unrelated")) {
      doThrow(new CallerFault("No project write authority"))
          .when(members)
          .requireRole("project", actor, ProjectRole.CONTRIBUTOR);
      assertThrows(CallerFault.class, () -> board.topup("root", 20, actor));
      assertThrows(CallerFault.class, () -> board.retry("root", "critic", actor, 12));
    }
    verify(store, never()).topup(anyString(), anyInt(), anyInt());
    verify(store, never()).post(any());
  }

  @Test
  void publicBoardControlsCannotMutatePrivateMessageInstances() {
    Board board = board();
    when(store.messagingTopic("root")).thenReturn(true);
    assertThrows(Board.Refused.class, () -> board.topup("root", 20, "operator"));
    assertThrows(Board.Refused.class, () -> board.retry("root", "critic", "operator", 12));
    verify(store, never()).topup(anyString(), anyInt(), anyInt());
    verify(store, never()).post(any());
  }

  @Test
  void unlimitedAndExplicitlyClosedTopicsCannotBeToppedUp() {
    Board board = board();
    when(root.potTotal()).thenReturn(null);
    assertThrows(Board.Refused.class, () -> board.topup("root", 20, "operator"));
    when(root.isClosed()).thenReturn(true);
    assertThrows(Board.Refused.class, () -> board.topup("root", 20, "operator"));
    verify(store, never()).topup(anyString(), anyInt(), anyInt());
  }
}
