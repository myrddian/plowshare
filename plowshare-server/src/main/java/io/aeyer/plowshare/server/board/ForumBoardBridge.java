package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
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
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final UnitOfWork work;
  private final BoardNativeRequests nativeRequests;

  public ForumBoardBridge(
      BoardForum board,
      BoardRecords records,
      BoardPostRepository receipts,
      BoardBridgeRepository replies,
      BoardClosing closing,
      SwarmCatalog swarms,
      Relay relay,
      ProjectMembers members,
      ProjectWorkspaces projects,
      UnitOfWork work) {
    this(
        board,
        records,
        receipts,
        replies,
        closing,
        swarms,
        relay,
        members,
        projects,
        work,
        BoardNativeRequests.NONE);
  }

  public ForumBoardBridge(
      BoardForum board,
      BoardRecords records,
      BoardPostRepository receipts,
      BoardBridgeRepository replies,
      BoardClosing closing,
      SwarmCatalog swarms,
      Relay relay,
      ProjectMembers members,
      ProjectWorkspaces projects,
      UnitOfWork work,
      BoardNativeRequests nativeRequests) {
    this.nativeRequests = Objects.requireNonNull(nativeRequests);
    this.board = Objects.requireNonNull(board);
    this.records = Objects.requireNonNull(records);
    this.receipts = Objects.requireNonNull(receipts);
    this.replies = Objects.requireNonNull(replies);
    this.closing = Objects.requireNonNull(closing);
    this.swarms = Objects.requireNonNull(swarms);
    this.relay = Objects.requireNonNull(relay);
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    this.work = Objects.requireNonNull(work);
  }

  @Override
  public void require(Command command) {
    Objects.requireNonNull(command);
    replyDestination(command);
    if (command.target() instanceof Open open) {
      authorize(command, open.project());
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
          // The same canonical advisory lock guards native refusal intent. A refused export
          // cannot later acquire a forum effect through another consumer group or target.
          if (command.actor() instanceof Agent agent && nativeRequests.refused(agent))
            throw new Board.Refused("This native board request already has a refusal outcome");
          var reply = replyDestination(command);
          BoardMessage message;
          String topic;
          switch (command.target()) {
            case Open open -> {
              authorize(command, open.project());
              var opened =
                  board.open(
                      new Board.Open(
                          Home.of(open.project()),
                          Board.fold(command.payload().body(), BoardTopic.TITLE_MAX),
                          Board.fold(command.payload().body(), BoardTopic.LABEL_MAX),
                          command.payload().body(),
                          command.account(),
                          command.actor() instanceof Agent
                              ? BoardTopic.BY_AGENT
                              : BoardTopic.BY_PERSON,
                          author(command),
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
                              authorKind(command),
                              author(command),
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
                          authorKind(command),
                          author(command),
                          null,
                          command.payload().body(),
                          List.of()),
                      selected);
              message = closed.resolution();
              topic = closed.topic().id();
            }
          }
          receipts.save(command.account(), command.requestId(), action, message.id());
          if (reply.isPresent())
            replies.bind(
                new BoardBridgeRepository.Binding(
                    command.requestId(),
                    command.account(),
                    message.id(),
                    topic,
                    reply.orElseThrow(),
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
    requireSourceProject(command, topic.project());
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
    if (authorizeClose) authorizeAccount(command, topic.project());
    return topic;
  }

  /** A route can never turn source-project authority into a destination-project grant. */
  private void requireSourceProject(Command command, String project) {
    Long selected = projects.id(project);
    if (selected == null || selected.longValue() != command.sourceProject())
      throw new Board.Refused("The forum destination must belong to the source project");
  }

  private void authorize(Command command, String project) {
    requireSourceProject(command, project);
    authorizeAccount(command, project);
  }

  private void authorizeAccount(Command command, String project) {
    var owner = projects.personalOwner(project);
    if (!members.mayWork(project, command.account())
        || owner.isPresent() && !owner.get().equals(command.account()))
      throw new Board.Refused("A forum bridge write requires project write permission");
  }

  private void requireOpen(BoardTopic topic, boolean allowance) {
    if (topic.isClosed()) throw new Board.Refused("The configured forum topic is closed");
    if (allowance) {
      var root = topic.isRoot() ? topic : records.topic(topic.root()).orElseThrow();
      if (root.exhausted(false)) throw new Board.Refused("The forum topic allowance is exhausted");
    }
  }

  private Optional<BoardBridgeRepository.ReplyDestination> replyDestination(Command command) {
    if (command.actor() instanceof Agent agent) {
      if (command.payload().replyTo() != null)
        throw new Board.Refused("Execution board replies use their retained native requester");
      return Optional.of(new BoardBridgeRepository.NativeDestination(agent.sourceEvent()));
    }
    if (command.payload().replyTo() == null) return Optional.empty();
    var topic =
        relay.topic(new Relay.TopicKey(command.sourceProject(), command.payload().replyTo()));
    if (topic.kind() != RelayPayload.Kind.TEXT || topic.generation() == null)
      throw new Board.Refused("The reply destination must be an existing versioned TEXT topic");
    return Optional.of(
        new BoardBridgeRepository.TopicDestination(
            command.sourceProject(), command.payload().replyTo(), topic.generation()));
  }

  private static String author(Command command) {
    return command.actor() instanceof Agent agent ? agent.definition() : command.account();
  }

  private static String authorKind(Command command) {
    return command.actor() instanceof Agent ? BoardMessage.BY_AGENT : BoardMessage.BY_PERSON;
  }

  private Receipt receipt(String messageId) {
    var message =
        records
            .message(messageId)
            .orElseThrow(() -> new IllegalStateException("No retained board receipt message"));
    return new Receipt(message.topic(), message.id());
  }

  private static BoardPostRepository.Action action(Command command, BoardTopic selected) {
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
    var intent =
        new BoardPostRepository.Bridge(
            operation,
            command.sourceProject(),
            command.sourceEvent(),
            target,
            project,
            command.payload().body(),
            command.payload().replyTo());
    return command.actor() instanceof Agent agent
        ? new BoardPostRepository.BridgeExecution(intent, agent.definition(), agent.sourceEvent())
        : intent;
  }
}
