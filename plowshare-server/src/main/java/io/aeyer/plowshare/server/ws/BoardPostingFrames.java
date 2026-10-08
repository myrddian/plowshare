package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.board.Board;
import io.aeyer.plowshare.server.board.BoardMessage;
import io.aeyer.plowshare.server.board.BoardPostRepository;
import io.aeyer.plowshare.server.board.BoardStore;
import io.aeyer.plowshare.server.board.BoardTopic;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Account-owned person posts. Repeated request IDs never post or wake twice. */
@Component
public class BoardPostingFrames implements FrameArea {
  private final Board board;
  private final BoardStore store;
  private final ProjectMembers members;
  private final UnitOfWork work;
  private final BoardPostRepository receipts;

  public BoardPostingFrames(
      Board board,
      BoardStore store,
      ProjectMembers members,
      UnitOfWork work,
      BoardPostRepository receipts) {
    this.board = board;
    this.store = store;
    this.members = members;
    this.work = work;
    this.receipts = receipts;
  }

  public record Receipt(String requestId, BoardMessage message) {}

  public record OpenReceipt(String requestId, BoardTopic topic, BoardMessage message) {}

  public record RetryReceipt(String requestId, String member, int maxTurns, BoardMessage message) {}

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.BOARD_POST,
        this::post,
        FrameTypes.BOARD_OPEN,
        this::open,
        FrameTypes.BOARD_RETRY,
        this::retry);
  }

  Outcome retry(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.BOARD_RETRY);
    String project =
        Payloads.required(payload, "project", FrameTypes.BOARD_RETRY, "the selected project");
    String topic = Payloads.required(payload, "topic", FrameTypes.BOARD_RETRY, "the topic ID");
    String member =
        Payloads.required(payload, "member", FrameTypes.BOARD_RETRY, "the failed member");
    UUID request;
    try {
      request =
          UUID.fromString(
              Payloads.required(
                  payload, "requestId", FrameTypes.BOARD_RETRY, "a stable request UUID"));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("board.retry needs requestId as a UUID");
    }
    Object raw = payload.get("maxTurns");
    if (!(raw instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() != n.intValue()
        || n.intValue() < 1) {
      throw new CallerFault("maxTurns must be a positive whole number of model steps.");
    }
    int maxTurns = ((Number) raw).intValue();
    var identity = new BoardPostRepository.Retry(project, topic, member, maxTurns);
    return work.inTransaction(
        () -> {
          if (!members.mayWork(project, account))
            throw new CallerFault("This account is not a member of the selected project.");
          store
              .topic(topic)
              .filter(row -> account.equals(row.account()) && project.equals(row.project()))
              .orElseThrow(
                  () ->
                      new CallerFault(
                          "Choose a topic owned by this account in the selected project."));
          var prior = receipts.lockAndRead(account, request, identity);
          if (prior.isPresent()) {
            var message = store.message(prior.orElseThrow()).orElseThrow();
            return Outcome.ok(new RetryReceipt(request.toString(), member, maxTurns, message));
          }
          var retried = board.retry(topic, member, account, maxTurns);
          receipts.save(account, request, identity, retried.message().id());
          return Outcome.ok(
              new RetryReceipt(request.toString(), member, maxTurns, retried.message()));
        });
  }

  Outcome open(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.BOARD_OPEN);
    String project =
        Payloads.required(payload, "project", FrameTypes.BOARD_OPEN, "the selected project");
    String title = Payloads.required(payload, "title", FrameTypes.BOARD_OPEN, "the topic title");
    String label = Payloads.required(payload, "label", FrameTypes.BOARD_OPEN, "the topic label");
    String body = Payloads.required(payload, "body", FrameTypes.BOARD_OPEN, "the opening message");
    if (body.length() > 16000)
      throw new CallerFault("Opening messages must fit within 16,000 characters.");
    UUID request;
    try {
      request =
          UUID.fromString(
              Payloads.required(
                  payload, "requestId", FrameTypes.BOARD_OPEN, "a stable request UUID"));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("board.open needs requestId as a UUID");
    }
    Integer budget = null;
    Object rawBudget = payload.get("maxModelCalls");
    if (rawBudget != null) {
      if (!(rawBudget instanceof Number n)
          || !Double.isFinite(n.doubleValue())
          || n.doubleValue() != n.intValue()
          || n.intValue() < 2)
        throw new CallerFault("maxModelCalls must be a whole number of at least two.");
      budget = ((Number) rawBudget).intValue();
    }
    if (!java.util.Set.of(
            "project", "title", "label", "body", "requestId", "maxModelCalls", "swarm")
        .containsAll(payload.keySet()))
      throw new CallerFault("board.open contains an unknown field");
    String swarm = null;
    if (payload.containsKey("swarm")) {
      swarm = Payloads.required(payload, "swarm", FrameTypes.BOARD_OPEN, "the selected swarm type");
      io.aeyer.plowshare.server.board.SwarmSelection.requireName(swarm);
    }
    var identity = new BoardPostRepository.Open(project, title, label, body, budget, swarm);
    String selectedSwarm = swarm;
    Integer requestedBudget = budget;
    return work.inTransaction(
        () -> {
          if (!members.mayWork(project, account))
            throw new CallerFault("This account is not a member of the selected project.");
          var prior = receipts.lockAndRead(account, request, identity);
          if (prior.isPresent()) {
            var message = store.message(prior.orElseThrow()).orElseThrow();
            return Outcome.ok(
                new OpenReceipt(
                    request.toString(), store.topic(message.topic()).orElseThrow(), message));
          }
          var opened =
              board.open(
                  new Board.Open(
                      Home.of(project),
                      title,
                      label,
                      body,
                      account,
                      BoardTopic.BY_PERSON,
                      account,
                      null,
                      requestedBudget,
                      selectedSwarm));
          receipts.save(account, request, identity, opened.opening().id());
          return Outcome.ok(new OpenReceipt(request.toString(), opened.topic(), opened.opening()));
        });
  }

  Outcome post(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.BOARD_POST);
    String project =
        Payloads.required(payload, "project", FrameTypes.BOARD_POST, "the selected project");
    String topic = Payloads.required(payload, "topic", FrameTypes.BOARD_POST, "the selected topic");
    String body = Payloads.required(payload, "body", FrameTypes.BOARD_POST, "the message to post");
    if (body.length() > 16000)
      throw new CallerFault("Board posts must fit within 16,000 characters.");
    UUID request;
    try {
      request =
          UUID.fromString(
              Payloads.required(
                  payload, "requestId", FrameTypes.BOARD_POST, "a stable request UUID"));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("board.post needs requestId as a UUID");
    }
    var identity = new BoardPostRepository.Post(project, topic, body);
    return work.inTransaction(
        () -> {
          var selected =
              store
                  .topic(topic)
                  .filter(row -> account.equals(row.account()) && project.equals(row.project()))
                  .orElseThrow(
                      () ->
                          new CallerFault(
                              "Choose a topic owned by this account in the selected project."));
          if (!members.mayWork(project, account))
            throw new CallerFault("This account is not a member of the selected project.");
          var prior = receipts.lockAndRead(account, request, identity);
          if (prior.isPresent()) {
            return Outcome.ok(
                new Receipt(request.toString(), store.message(prior.orElseThrow()).orElseThrow()));
          }
          var posted =
              board.post(
                  new Board.Post(
                      selected.id(),
                      BoardMessage.BY_PERSON,
                      account,
                      null,
                      null,
                      BoardMessage.POST,
                      null,
                      body,
                      null,
                      List.of(),
                      false));
          receipts.save(account, request, identity, posted.message().id());
          return Outcome.ok(new Receipt(request.toString(), posted.message()));
        });
  }
}
