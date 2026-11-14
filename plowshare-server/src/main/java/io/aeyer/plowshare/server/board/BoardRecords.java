package io.aeyer.plowshare.server.board;

import java.util.Optional;

/**
 * Typed forum snapshots for authorized operations and receipt recovery; no SQL crosses this API.
 */
public interface BoardRecords {
  Optional<BoardTopic> topic(String id);

  Optional<BoardMessage> message(String id);
}
