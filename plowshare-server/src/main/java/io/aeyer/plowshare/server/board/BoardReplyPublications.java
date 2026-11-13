package io.aeyer.plowshare.server.board;

/** Crash-recoverable publication of already owed forum answers; never executes a board mutation. */
public interface BoardReplyPublications {
  /** Publishes and removes one oldest pending answer atomically; false means no unlocked work. */
  boolean publishNext();
}
