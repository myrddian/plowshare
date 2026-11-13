package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectBoardClosingTest {
  private final BoardForum board = mock(BoardForum.class);
  private final BoardRecords records = mock(BoardRecords.class);
  private final BoardPostRepository receipts = mock(BoardPostRepository.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final BoardClosing closing =
      new ProjectBoardClosing(board, records, receipts, members, projects, UnitOfWork.NONE);
  private final BoardClosing.Command command =
      new BoardClosing.Command(
          "destination", "bdt_Topic", "Resolved", List.of(), UUID.randomUUID());

  @Test
  void a_viewer_or_nonmember_cannot_close_or_even_load_the_topic() {
    assertThrows(Board.Refused.class, () -> closing.close("viewer", command));
    verifyNoInteractions(records, receipts, board);
  }

  @Test
  void a_contributor_closes_another_accounts_topic_without_an_opener_seat() {
    when(members.mayWork("destination", "contributor")).thenReturn(true);
    var selected = BoardBridgeFixtures.topic("open");
    var resolution = BoardBridgeFixtures.message("bdm_Resolution", BoardMessage.RESOLUTION);
    when(records.topic("bdt_Topic")).thenReturn(Optional.of(selected));
    when(board.close(any(), eq(selected)))
        .thenReturn(new Board.Closed(BoardBridgeFixtures.topic("closed"), resolution));
    var result = closing.close("contributor", command);
    assertEquals("closed", result.topic().state());
    assertEquals(resolution, result.message());
    verify(board)
        .close(
            new Board.Close(
                "bdt_Topic", BoardMessage.BY_PERSON, "contributor", null, "Resolved", List.of()),
            selected);
    verify(receipts).save(eq("contributor"), eq(command.requestId()), any(), eq(resolution.id()));
  }

  @Test
  void equal_reuse_recovers_the_closed_resolution_without_closing_again() {
    when(members.mayWork("destination", "publisher")).thenReturn(true);
    when(records.topic("bdt_Topic")).thenReturn(Optional.of(BoardBridgeFixtures.topic("closed")));
    when(records.message("bdm_Resolution"))
        .thenReturn(
            Optional.of(BoardBridgeFixtures.message("bdm_Resolution", BoardMessage.RESOLUTION)));
    when(receipts.lockAndRead(eq("publisher"), eq(command.requestId()), any()))
        .thenReturn(Optional.of("bdm_Resolution"));
    assertEquals("bdm_Resolution", closing.close("publisher", command).message().id());
    verifyNoInteractions(board);
    verify(receipts, never()).save(any(), any(), any(), any());
  }

  @Test
  void wrong_project_and_foreign_personal_ownership_are_refused() {
    when(members.mayWork(any(), any())).thenReturn(true);
    var wrong =
        new BoardClosing.Command("other", "bdt_Topic", "Resolved", List.of(), command.requestId());
    when(records.topic("bdt_Topic")).thenReturn(Optional.of(BoardBridgeFixtures.topic("open")));
    assertThrows(Board.Refused.class, () -> closing.close("publisher", wrong));
    when(projects.personalOwner("destination")).thenReturn(Optional.of("owner"));
    assertThrows(Board.Refused.class, () -> closing.close("publisher", command));
    verifyNoInteractions(board, receipts);
  }

  @Test
  void reconciliation_is_read_only_and_does_not_replay_absent_receipts() {
    when(members.mayWork("destination", "publisher")).thenReturn(true);
    when(records.topic("bdt_Topic")).thenReturn(Optional.of(BoardBridgeFixtures.topic("open")));
    assertTrue(closing.find("publisher", command).isEmpty());
    verify(receipts).find(eq("publisher"), eq(command.requestId()), any());
    verify(receipts, never()).lockAndRead(any(), any(), any());
    verifyNoInteractions(board);
  }
}
