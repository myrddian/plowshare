package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.aeyer.plowshare.server.board.BoardClosing;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoardCloseFramesTest {
  private final BoardClosing closing = mock(BoardClosing.class);
  private final BoardCloseFrames frames = new BoardCloseFrames(closing);
  private final UUID request = UUID.randomUUID();

  private Map<String, Object> payload() {
    return new HashMap<>(
        Map.of(
            "project",
            "payments",
            "topic",
            "bdt_Topic",
            "body",
            "Resolved",
            "requestId",
            request.toString(),
            "cites",
            List.of("bdm_Post")));
  }

  @Test
  void authenticated_identity_and_validated_command_reach_the_capability() {
    frames.close(payload(), new Asking("session", "contributor"));
    verify(closing)
        .close(
            "contributor",
            new BoardClosing.Command(
                "payments", "bdt_Topic", "Resolved", List.of("bdm_Post"), request));
    assertEquals(List.of(FrameTypes.BOARD_CLOSE), List.copyOf(frames.frames().keySet()));
  }

  @Test
  void missing_login_and_invalid_fields_never_run_business_logic() {
    assertThrows(CallerFault.class, () -> frames.close(payload(), new Asking("session")));
    for (var invalid :
        List.<Map.Entry<String, Object>>of(
            Map.entry("account", "forged"),
            Map.entry("topic", "cnv_Wrong"),
            Map.entry("body", " "),
            Map.entry("body", "x".repeat(16001)),
            Map.entry("body", "bad\u0000text"),
            Map.entry("cites", "bdm_Post"),
            Map.entry("cites", List.of(5)),
            Map.entry("cites", List.of("bdt_Wrong")),
            Map.entry("cites", Collections.nCopies(201, "bdm_Post")),
            Map.entry("requestId", "1-1-1-1-1"),
            Map.entry("project", "bad\nproject"))) {
      var body = payload();
      body.put(invalid.getKey(), invalid.getValue());
      assertThrows(
          CallerFault.class,
          () -> frames.close(body, new Asking("session", "contributor")),
          invalid.getKey());
    }
    var body = payload();
    body.put("cites", null);
    assertThrows(CallerFault.class, () -> frames.close(body, new Asking("session", "contributor")));
    verifyNoInteractions(closing);
  }
}
