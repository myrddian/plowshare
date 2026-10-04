package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.board.BoardMessage;
import io.aeyer.plowshare.server.board.BoardSeat;
import io.aeyer.plowshare.server.board.BoardStore;
import io.aeyer.plowshare.server.board.BoardTopic;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Read-only, account-scoped board and scheduler inspection. */
@Component
public class BoardInspectionFrames implements FrameArea {
  private final BoardStore store;
  private final SwarmScheduler scheduler;

  public BoardInspectionFrames(BoardStore store, SwarmScheduler scheduler) {
    this.store = store;
    this.scheduler = scheduler;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.BOARD_TOPICS,
        this::topics,
        FrameTypes.BOARD_MESSAGES,
        this::messages,
        FrameTypes.SWARM_STATUS,
        this::swarm);
  }

  public record Topics(List<BoardStore.Summary> topics, boolean more, int offset) {}

  public record SeatView(
      BoardSeat seat,
      String state,
      String job,
      String reason,
      Integer position,
      Long waitedMillis,
      boolean overdue) {}

  public record Messages(
      BoardTopic topic,
      BoardTopic root,
      List<SeatView> seats,
      List<BoardMessage> messages,
      List<BoardStore.Decision> decisions) {}

  public record Ready(
      String topic,
      String member,
      String specifier,
      int position,
      long waitedMillis,
      boolean overdue) {}

  public record Swarm(
      List<SwarmScheduler.PoolUse> pools,
      List<Ready> ready,
      List<BoardStore.Summary> topics,
      List<SeatView> seats,
      boolean more) {}

  record ListBody(String project, Integer offset, Integer limit) {}

  Outcome topics(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.BOARD_TOPICS);
    ListBody body = Payloads.as(payload, ListBody.class, FrameTypes.BOARD_TOPICS);
    int limit = body.limit() == null ? 200 : body.limit();
    int offset = body.offset() == null ? 0 : body.offset();
    if (limit < 1 || limit > 200 || offset < 0) {
      throw new CallerFault("board.topics takes a limit from 1 to 200 and a nonnegative offset.");
    }
    List<BoardStore.Summary> rows =
        store.summaries(account, body.project(), false, offset, limit + 1);
    return Outcome.ok(new Topics(rows.stream().limit(limit).toList(), rows.size() > limit, offset));
  }

  Outcome messages(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.BOARD_MESSAGES);
    String id =
        Payloads.required(payload, "topic", FrameTypes.BOARD_MESSAGES, "the topic to inspect");
    BoardTopic topic =
        store
            .topic(id)
            .filter(t -> account.equals(t.account()) && !store.messagingTopic(id))
            .orElseThrow(() -> new CallerFault("No topic with that id is owned by this account."));
    BoardTopic root = store.topic(topic.root()).orElseThrow();
    var snapshot = scheduler.snapshot();
    return Outcome.ok(
        new Messages(
            topic,
            root,
            store.inspectSeats(account, id, false).stream().map(s -> view(s, snapshot)).toList(),
            store.messages(id),
            store.decisions(id)));
  }

  Outcome swarm(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.SWARM_STATUS);
    var snapshot = scheduler.snapshot();
    var topics = store.summaries(account, null, true, 0, 201);
    return Outcome.ok(
        new Swarm(
            snapshot.pools(),
            snapshot.ready().stream()
                .filter(w -> account.equals(w.share().account()))
                .map(
                    w ->
                        new Ready(
                            w.share().topic(),
                            w.share().member(),
                            w.specifier(),
                            w.position(),
                            w.waited().toMillis(),
                            w.overdue()))
                .toList(),
            topics.stream().limit(200).toList(),
            store.inspectSeats(account, null, true).stream().map(s -> view(s, snapshot)).toList(),
            topics.size() > 200));
  }

  private static SeatView view(BoardStore.SeatActivity activity, SwarmScheduler.Snapshot snapshot) {
    BoardSeat seat = activity.seat();
    var waiting =
        snapshot.ready().stream()
            .filter(
                w ->
                    w.share().topic().equals(seat.topic())
                        && w.share().member().equals(seat.occupant()))
            .findFirst()
            .orElse(null);
    String state;
    if (waiting != null) state = "ready";
    else if ("started".equals(activity.wake())) state = "running";
    else if (BoardTopic.CLOSED.equals(activity.topicState())) state = "closed";
    else if (seat.passed()) state = "passed";
    else if (seat.failedEnding() != null) state = "failed";
    else if (activity.remaining() <= (seat.isOpener() ? 0 : activity.reserve())) state = "held";
    else if ("queued".equals(activity.wake())) state = "owed";
    else if (seat.silentWakes() > 0) state = "silent";
    else if ("refused".equals(activity.wake())) state = "blocked";
    else state = "idle";
    return new SeatView(
        seat,
        state,
        activity.job(),
        activity.reason(),
        waiting == null ? null : waiting.position(),
        waiting == null ? null : waiting.waited().toMillis(),
        waiting != null && waiting.overdue());
  }
}
