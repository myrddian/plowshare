package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.agents.Outcome;
import java.util.Optional;

/** Persistent turn numbering and transcript evidence; callers retain conversation authority. */
public interface TurnJournal extends TurnHistoryRepository {
  /** Reserves a unique ordinal before execution; interrupted reservations are never reused. */
  int reserve(String conversation);

  /** Atomically reserves and retains exact turn ancestry; never writes a conversation-wide root. */
  default int reserve(String conversation, RelayCausation causation) {
    if (causation != null)
      throw new UnsupportedOperationException("Causal turn reservations are unavailable");
    return reserve(conversation);
  }

  /** Records the ending at its reserved place, regardless of other turns' completion order. */
  TurnRecord recordAt(
      String conversation,
      int ordinal,
      String utterance,
      String answer,
      Outcome.Ending ending,
      Integer promptTokens,
      String agent,
      String systemBlock);

  /** Atomic allocation and recording for existing non-running history writers. */
  TurnRecord record(
      String conversation,
      String utterance,
      String answer,
      Outcome.Ending ending,
      Integer promptTokens,
      String agent,
      String systemBlock);

  String remember(String block);

  Optional<String> blockSentAt(String conversation, int turn);
}
