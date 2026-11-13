package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.util.Objects;
import java.util.Optional;

/** Reuses forum tree closure and receipt storage; permission depends on role, never the opener. */
public final class ProjectBoardClosing implements BoardClosing {
  private final BoardForum board;
  private final BoardRecords records;
  private final BoardPostRepository receipts;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final UnitOfWork work;

  public ProjectBoardClosing(
      BoardForum board,
      BoardRecords records,
      BoardPostRepository receipts,
      ProjectMembers members,
      ProjectWorkspaces projects,
      UnitOfWork work) {
    this.board = Objects.requireNonNull(board);
    this.records = Objects.requireNonNull(records);
    this.receipts = Objects.requireNonNull(receipts);
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    this.work = Objects.requireNonNull(work);
  }

  @Override
  public Receipt close(String account, Command command) {
    return work.inTransaction(
        () -> {
          BoardTopic topic = require(account, command);
          var action = action(command);
          var prior = receipts.lockAndRead(account, command.requestId(), action);
          if (prior.isPresent()) {
            // Another equal request may have committed while this caller waited for the receipt
            // lock. Refresh only this path so its response contains the committed closed state.
            var committed = records.topic(topic.id()).orElseThrow();
            return receipt(command, committed, prior.get());
          }
          var closed =
              board.close(
                  new Board.Close(
                      topic.id(),
                      BoardMessage.BY_PERSON,
                      account,
                      null,
                      command.body(),
                      command.cites()),
                  topic);
          receipts.save(account, command.requestId(), action, closed.resolution().id());
          return new Receipt(command.requestId().toString(), closed.topic(), closed.resolution());
        });
  }

  @Override
  public Optional<Receipt> find(String account, Command command) {
    BoardTopic topic = require(account, command);
    return receipts
        .find(account, command.requestId(), action(command))
        .map(message -> receipt(command, topic, message));
  }

  @Override
  public BoardTopic require(String account, Command command) {
    authorize(account, command);
    return selected(
        command,
        records
            .topic(command.topic())
            .orElseThrow(() -> new Board.Refused("No forum topic in the selected project")));
  }

  @Override
  public BoardTopic require(String account, Command command, BoardTopic selected) {
    authorize(account, command);
    return selected(command, selected);
  }

  private void authorize(String account, Command command) {
    RelayPort.identity(account);
    Objects.requireNonNull(command);
    var owner = projects.personalOwner(command.project());
    if (!members.mayWork(command.project(), account)
        || owner.isPresent() && !owner.get().equals(account))
      throw new Board.Refused("Closing a board topic requires project write permission");
  }

  private BoardTopic selected(Command command, BoardTopic selected) {
    if (!command.topic().equals(selected.id()) || !command.project().equals(selected.project()))
      throw new Board.Refused("No forum topic in the selected project");
    return selected;
  }

  private Receipt receipt(Command command, BoardTopic topic, String message) {
    var resolution =
        records
            .message(message)
            .filter(
                value ->
                    value.topic().equals(topic.id())
                        && BoardMessage.RESOLUTION.equals(value.kind()))
            .orElseThrow(
                () -> new IllegalStateException("Board close receipt has no retained resolution"));
    return new Receipt(command.requestId().toString(), topic, resolution);
  }

  private static BoardPostRepository.Close action(Command command) {
    return new BoardPostRepository.Close(
        command.project(), command.topic(), command.body(), command.cites());
  }
}
