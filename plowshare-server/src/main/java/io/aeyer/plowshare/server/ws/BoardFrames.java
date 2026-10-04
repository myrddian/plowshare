package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.BoardController;
import io.aeyer.plowshare.server.board.Board;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Operator top-ups share the HTTP service and absolute-ceiling semantics. */
@Component
public class BoardFrames implements FrameArea {
  private final Board board;

  public BoardFrames(Board board) {
    this.board = board;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.BOARD_TOPUP,
        (payload, asking) -> {
          String topic =
              Payloads.required(
                  payload,
                  "topic",
                  FrameTypes.BOARD_TOPUP,
                  "the topic whose root pot is to be raised");
          BoardController.Topup request =
              Payloads.as(payload, BoardController.Topup.class, FrameTypes.BOARD_TOPUP);
          return Outcome.ok(board.topup(topic, request.maxModelCalls()));
        });
  }
}
