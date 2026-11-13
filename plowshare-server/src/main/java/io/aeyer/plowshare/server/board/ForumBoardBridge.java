package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The bridge's owning transaction. Publication authority is established by its Relay receiver;
 * posting does not mint membership or a forum seat. Existing board APIs retain ordering and wakes.
 */
public final class ForumBoardBridge implements BoardBridge {
  private final BoardForum board;
  private final BoardRecords records;
  private final BoardPostRepository receipts;
  private final BoardBridgeRepository replies;
  private final BoardClosing closing;
  private final SwarmCatalog swarms;
  private final Relay relay;
  private final UnitOfWork work;

  public ForumBoardBridge(
      BoardForum board,
      BoardRecords records,
      BoardPostRepository receipts,
      BoardBridgeRepository replies,
      BoardClosing closing,
      SwarmCatalog swarms,
      Relay relay,
      UnitOfWork work) {
    this.board = Objects.requireNonNull(board);
    this.records = Objects.requireNonNull(records);
    this.receipts = Objects.requireNonNull(receipts);
    this.replies = Objects.requireNonNull(replies);
    this.closing = Objects.requireNonNull(closing);
    this.swarms = Objects.requireNonNull(swarms);
    this.relay = Objects.requireNonNull(relay);
    this.work = Objects.requireNonNull(work);
  }

  @Override
  public void require(Command command) {
    Objects.requireNonNull(command);
    replyTopic(command);
    if (command.target() instanceof Open open) {
      if (swarms.select(open.project(), open.swarm()).members().isEmpty())
        throw new Board.Refused("The configured swarm is unavailable");
    } else {
      BoardTopic topic = selected(command, true);
      requireOpen(topic, command.target() instanceof Post);
    }
  }

  @Override
  public Receipt accept(Command command) {
    return work.inTransaction(
        () -> {
          // Resolve the forum snapshot once for scope/identity; board methods take their own state
          // lock before effects. A repeated receipt is recovered even if its topic has since
          // closed.
          BoardTopic selected = command.target() instanceof Open ? null : selected(command, true);
          var action = action(command, selected);
          var prior = receipts.lockAndRead(command.account(), command.requestId(), action);
          if (prior.isPresent()) return receipt(prior.get());
          replyTopic(command);
          BoardMessage message;
          String topic;
          switch (command.target()) {
            case Open open -> {
              var opened =
                  board.open(
                      new Board.Open(
                          Home.of(open.project()),
                          Board.fold(command.payload().body(), BoardTopic.TITLE_MAX),
                          Board.fold(command.payload().body(), BoardTopic.LABEL_MAX),
                          command.payload().body(),
                          command.account(),
                          BoardTopic.BY_PERSON,
                          command.account(),
                          null,
                          null,
                          open.swarm()));
              message = opened.opening();
              topic = opened.topic().id();
            }
            case Post ignored -> {
              requireOpen(Objects.requireNonNull(selected), true);
              message =
                  board
                      .post(
                          new Board.Post(
                              selected.id(),
                              BoardMessage.BY_PERSON,
                              command.account(),
                              null,
                              null,
                              BoardMessage.POST,
                              null,
                              command.payload().body(),
                              null,
                              List.of(),
                              false))
                      .message();
              topic = selected.id();
            }
            case Close ignored -> {
              var closed =
                  board.close(
                      new Board.Close(
                          selected.id(),
                          BoardMessage.BY_PERSON,
                          command.account(),
                          null,
                          command.payload().body(),
                          List.of()),
                      selected);
              message = closed.resolution();
              topic = closed.topic().id();
            }
          }
          receipts.save(command.account(), command.requestId(), action, message.id());
          if (command.payload().replyTo() != null)
            replies.bind(
                new BoardBridgeRepository.Binding(
                    command.requestId(),
                    command.account(),
                    message.id(),
                    topic,
                    command.sourceProject(),
                    command.payload().replyTo(),
                    command.sourceEvent(),
                    command.causation()));
          return new Receipt(topic, message.id());
        });
  }

  @Override
  public Optional<Receipt> inspect(Command command) {
    BoardTopic selected = command.target() instanceof Open ? null : selected(command, false);
    return receipts
        .find(command.account(), command.requestId(), action(command, selected))
        .map(this::receipt);
  }

  private BoardTopic selected(Command command, boolean authorizeClose) {
    String id =
        switch (command.target()) {
          case Post post -> post.topic();
          case Close close -> close.topic();
          case Open ignored -> throw new IllegalArgumentException("Opening has no selected topic");
        };
    BoardTopic topic =
        records
            .topic(id)
            .orElseThrow(() -> new Board.Refused("No forum topic at the configured destination"));
    if (authorizeClose && command.target() instanceof Close)
      return closing.require(
          command.account(),
          new BoardClosing.Command(
              topic.project(),
              topic.id(),
              command.payload().body(),
              List.of(),
              command.requestId()),
          topic);
    if (records.messagingTopic(topic.id()))
      throw new Board.Refused("Messaging topics are not forum destinations");
    return topic;
  }

  private void requireOpen(BoardTopic topic, boolean allowance) {
    if (topic.isClosed()) throw new Board.Refused("The configured forum topic is closed");
    if (allowance) {
      var root = topic.isRoot() ? topic : records.topic(topic.root()).orElseThrow();
      if (root.exhausted(false)) throw new Board.Refused("The forum topic allowance is exhausted");
    }
  }

  private void replyTopic(Command command) {
    if (command.payload().replyTo() != null
        && relay
                .topic(new Relay.TopicKey(command.sourceProject(), command.payload().replyTo()))
                .kind()
            != RelayPayload.Kind.TEXT)
      throw new Board.Refused("The reply destination must be an existing TEXT topic");
  }

  private Receipt receipt(String messageId) {
    var message =
        records
            .message(messageId)
            .orElseThrow(() -> new IllegalStateException("No retained board receipt message"));
    return new Receipt(message.topic(), message.id());
  }

  private static BoardPostRepository.Bridge action(Command command, BoardTopic selected) {
    String operation =
        switch (command.target()) {
          case Open ignored -> "board.open";
          case Post ignored -> "board.post";
          case Close ignored -> "board.close";
        };
    String target =
        switch (command.target()) {
          case Open open -> open.swarm();
          case Post post -> post.topic();
          case Close close -> close.topic();
        };
    String project = command.target() instanceof Open open ? open.project() : selected.project();
    return new BoardPostRepository.Bridge(
        operation,
        command.sourceProject(),
        command.sourceEvent(),
        target,
        project,
        command.payload().body(),
        command.payload().replyTo());
  }
}
