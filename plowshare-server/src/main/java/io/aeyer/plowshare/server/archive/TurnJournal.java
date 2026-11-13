package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.agents.Outcome;
import java.util.Optional;

/** Persistent turn numbering and transcript evidence; callers retain conversation authority. */
public interface TurnJournal extends TurnHistoryRepository {
  /** Reserves a unique ordinal before execution; interrupted reservations are never reused. */
  int reserve(String conversation);

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
