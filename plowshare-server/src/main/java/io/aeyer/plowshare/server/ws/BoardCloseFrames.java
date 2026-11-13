package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.board.BoardClosing;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Strict external conversion before invoking the account-authorized closing capability. */
@Component
public final class BoardCloseFrames implements FrameArea {
  private final BoardClosing closing;

  public BoardCloseFrames(BoardClosing closing) {
    this.closing = closing;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.BOARD_CLOSE, this::close);
  }

  Outcome close(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.BOARD_CLOSE);
    BoardClosing.Command command;
    try {
      if (!Set.of("project", "topic", "body", "cites", "requestId").containsAll(payload.keySet()))
        throw new IllegalArgumentException("Unknown close field");
      String project =
          Payloads.required(payload, "project", FrameTypes.BOARD_CLOSE, "the selected project");
      String topic = Payloads.required(payload, "topic", FrameTypes.BOARD_CLOSE, "the topic ID");
      String body = Payloads.required(payload, "body", FrameTypes.BOARD_CLOSE, "the resolution");
      String id =
          Payloads.required(payload, "requestId", FrameTypes.BOARD_CLOSE, "a stable request UUID");
      UUID request = UUID.fromString(id);
      if (!request.toString().equalsIgnoreCase(id))
        throw new IllegalArgumentException("Noncanonical request UUID");
      var cites = new ArrayList<String>();
      if (payload.containsKey("cites")) {
        if (!(payload.get("cites") instanceof List<?> values) || values.size() > 200)
          throw new IllegalArgumentException("Citations require a bounded list");
        for (Object value : values) {
          if (!(value instanceof String text))
            throw new IllegalArgumentException("Citation requires text");
          cites.add(text);
        }
      }
      command = new BoardClosing.Command(project, topic, body, cites, request);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid board.close fields, identity or resolution bounds");
    }
    return Outcome.ok(closing.close(account, command));
  }
}
