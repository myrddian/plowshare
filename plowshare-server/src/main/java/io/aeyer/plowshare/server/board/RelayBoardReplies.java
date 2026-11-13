package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes immutable full-text answers, never unrelated history, with stable per-answer identity.
 */
public final class RelayBoardReplies implements BoardReplyPublications {
  private static final Logger LOG = LoggerFactory.getLogger(RelayBoardReplies.class);
  private final BoardBridgeRepository replies;
  private final Relay relay;
  private final BoardNativeRequests nativeRequests;
  private final UnitOfWork work;
  private final Duration backoff;
  private final Supplier<Instant> clock;
  private final AtomicLong turns = new AtomicLong();

  public RelayBoardReplies(BoardBridgeRepository replies, Relay relay, UnitOfWork work) {
    this(replies, relay, work, Duration.ofSeconds(30), Instant::now);
  }

  public RelayBoardReplies(
      BoardBridgeRepository replies,
      Relay relay,
      UnitOfWork work,
      Duration backoff,
      Supplier<Instant> clock) {
    this(replies, relay, work, backoff, clock, BoardNativeRequests.NONE);
  }

  public RelayBoardReplies(
      BoardBridgeRepository replies,
      Relay relay,
      UnitOfWork work,
      Duration backoff,
      Supplier<Instant> clock,
      BoardNativeRequests nativeRequests) {
    this.nativeRequests = Objects.requireNonNull(nativeRequests);
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
    boolean nativeFirst = (turns.getAndIncrement() & 1) == 0;
    if (nativeFirst && nativeRequests.publishNextOutcome()) return true;
    if (publishBoardNext()) return true;
    return !nativeFirst && nativeRequests.publishNextOutcome();
  }

  private boolean publishBoardNext() {
    try {
      return work.inTransaction(
          () -> {
            var pending = replies.pending();
            if (pending.isEmpty()) return false;
            var next = pending.get();
            try {
              if (next.destination() instanceof BoardBridgeRepository.NativeDestination) {
                var result = nativeRequests.reply(next);
                if (result == BoardNativeRequests.Reply.PUBLISHED) replies.published(next);
                else if (result == BoardNativeRequests.Reply.UNAVAILABLE)
                  replies.undeliverable(next, BoardBridgeRepository.Destination.NATIVE_UNAVAILABLE);
                else throw new IllegalStateException("Native board reply returned no outcome");
                return true;
              }
              var destination = (BoardBridgeRepository.TopicDestination) next.destination();
              if (destination.generation() == null) {
                replies.undeliverable(next, BoardBridgeRepository.Destination.UNVERIFIED);
                return true;
              }
              var topic = new Relay.TopicKey(destination.project(), destination.topic());
              // Publication and outbox removal share the same database transaction. A failure
              // commits
              // neither; retrying this internal append cannot repeat a forum mutation or external
              // effect.
              var result =
                  relay.publish(
                      topic,
                      destination.generation(),
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
              if (result instanceof Relay.DestinationUnavailable unavailable) {
                replies.undeliverable(
                    next,
                    switch (unavailable.reason()) {
                      case MISSING -> BoardBridgeRepository.Destination.MISSING;
                      case REPLACED -> BoardBridgeRepository.Destination.REPLACED;
                      case INVALID_KIND -> BoardBridgeRepository.Destination.INVALID_KIND;
                    });
              } else if (result instanceof Relay.Appended) {
                replies.published(next);
              } else {
                throw new IllegalStateException("Relay append returned no outcome");
              }
              return true;
            } catch (RuntimeException failure) {
              throw new AppendFailed(next, failure);
            }
          });
    } catch (AppendFailed failure) {
      LOG.warn(
          "Board reply failure category=APPEND_FAILED request={} project={} topic={} message={}; debt remains pending",
          failure.pending.requestId(),
          failure.pending.destination()
                  instanceof BoardBridgeRepository.TopicDestination destination
              ? destination.project()
              : "native",
          failure.pending.destination()
                  instanceof BoardBridgeRepository.TopicDestination destination
              ? destination.topic()
              : ((BoardBridgeRepository.NativeDestination) failure.pending.destination())
                  .sourceEvent(),
          failure.pending.message());
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
