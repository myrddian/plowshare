package io.aeyer.plowshare.server.board;

/**
 * The board's own forum mutation capability. Callers establish account/project authority before
 * invoking it. Implementations own ordering, seats, wakes and atomic tree closure; transports and
 * bridges must not reproduce those mechanisms or infer read authority from a successful write.
 */
public interface BoardForum {
  Board.Opened open(Board.Open request);

  Board.Posted post(Board.Post request);

  Board.Closed close(Board.Close request);

  /**
   * Reuses an authorized snapshot; the implementation still locks and rechecks live topic state.
   */
  Board.Closed close(Board.Close request, BoardTopic selected);
}
