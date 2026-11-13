package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Publishes immutable full-text answers, never unrelated history, with stable per-answer identity.
 */
public final class RelayBoardReplies implements BoardReplyPublications {
  private final BoardBridgeRepository replies;
  private final Relay relay;
  private final UnitOfWork work;
  private final Duration backoff;
  private final Supplier<Instant> clock;

  public RelayBoardReplies(BoardBridgeRepository replies, Relay relay, UnitOfWork work) {
    this(replies, relay, work, Duration.ofSeconds(30), Instant::now);
  }

  public RelayBoardReplies(
      BoardBridgeRepository replies,
      Relay relay,
      UnitOfWork work,
      Duration backoff,
      Supplier<Instant> clock) {
    this.replies = Objects.requireNonNull(replies);
    this.relay = Objects.requireNonNull(relay);
    this.work = Objects.requireNonNull(work);
    this.backoff = Objects.requireNonNull(backoff);
    this.clock = Objects.requireNonNull(clock);
    if (backoff.isNegative() || backoff.isZero())
      throw new IllegalArgumentException("Reply backoff must be positive");
  }

  @Override
  public boolean publishNext() {
    try {
      return work.inTransaction(
          () -> {
            var pending = replies.pending();
            if (pending.isEmpty()) return false;
            var next = pending.get();
            try {
              var topic = new Relay.TopicKey(next.replyProject(), next.replyTopic());
              // Publication and outbox removal share the same database transaction. A failure
              // commits
              // neither; retrying this internal append cannot repeat a forum mutation or external
              // effect.
              relay.publish(
                  topic,
                  new Relay.Draft(
                      "board.reply:"
                          + RelayPort.hash(
                              next.requestId() + ":" + next.message() + ":" + next.terminal()),
                      "board:bridge",
                      next.postedAt(),
                      null,
                      null,
                      new RelayPayload.Text(BoardBridgeCodec.reply(next)),
                      next.causation()));
              replies.published(next);
              return true;
            } catch (RuntimeException failure) {
              throw new AppendFailed(next, failure);
            }
          });
    } catch (AppendFailed failure) {
      // The append transaction has rolled back and released its locks. A separate transaction
      // delays only this request; an unavailable destination cannot starve unrelated replies.
      work.inTransaction(
          () -> {
            replies.defer(failure.pending, clock.get().plus(backoff));
            return true;
          });
      throw failure;
    }
  }

  private static final class AppendFailed extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final BoardBridgeRepository.Pending pending;

    AppendFailed(BoardBridgeRepository.Pending pending, RuntimeException cause) {
      super("Board reply publication remains pending", cause);
      this.pending = pending;
    }
  }
}
