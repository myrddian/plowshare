package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.RecordPageView;
import io.aeyer.plowshare.server.orchestrations.RecordKind;
import io.aeyer.plowshare.server.orchestrations.RecordPage;
import io.aeyer.plowshare.server.orchestrations.RecordReads;
import io.aeyer.plowshare.server.orchestrations.RecordRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordFramesTest {

  private RecordReads reads;
  private FrameRouter router;

  @BeforeEach
  void setUp() {
    reads = mock(RecordReads.class);
    router = new FrameRoutingConfig().frameRouter(List.of(new RecordFrames(reads)));
  }

  private Outcome route(String json, String handle) {
    return router.route(
        FrameParity.frame(FrameTypes.ORCHESTRATION_RECORD, json), new Asking("tab-1", handle));
  }

  @Test
  void reads_the_window_it_was_sent_as_the_account_that_sent_it() {
    RecordPage page =
        new RecordPage(
            List.of(
                new RecordRow(
                    7,
                    Instant.parse("2026-09-28T09:00:00Z"),
                    "orc_child",
                    "coder",
                    RecordKind.TOOL_CALL,
                    "coder · run ./gradlew test",
                    "exit 1",
                    null)),
            9,
            12,
            true);
    when(reads.read("enzo", "orc_child", null, null, true, 40, List.of("tool_call")))
        .thenReturn(new RecordReads.Read("orc_root", page, 40));

    Outcome outcome =
        route(
            "{\"root\":\"orc_child\",\"tail\":true,\"limit\":40," + "\"kinds\":[\"tool_call\"]}",
            "enzo");

    assertEquals(Code.OK, outcome.code());
    RecordPageView view = (RecordPageView) outcome.payload();
    assertEquals("orc_root", view.root());
    assertEquals(9, view.total());
    assertEquals(40, view.limit());
    assertEquals(12, view.through());
    assertEquals(7, view.oldest());
    assertEquals(Boolean.TRUE, view.more());
    assertEquals("tool_call", view.rows().get(0).kind());
    assertEquals("exit 1", view.rows().get(0).detail());
  }

  /**
   * V64: the frame writes a row's {@code body} as the endpoint does — there when the row has one,
   * left out when not.
   */
  @Test
  void a_body_is_written_when_a_row_has_one_and_left_out_when_not() throws Exception {
    RecordPage page =
        new RecordPage(
            List.of(
                new RecordRow(
                    4,
                    Instant.parse("2026-09-28T09:00:00Z"),
                    "orc_1",
                    "conductor",
                    RecordKind.QUESTION_ASKED,
                    "asked: Which database?",
                    null,
                    "Which database?\nPostgres or SQLite"),
                new RecordRow(
                    5,
                    Instant.parse("2026-09-28T09:01:00Z"),
                    "orc_1",
                    "conductor",
                    RecordKind.QUESTION_ANSWERED,
                    "answered by enzo: PostgreSQL",
                    null,
                    null)),
            5,
            5,
            false);
    when(reads.read("enzo", "orc_1", null, null, true, null, null))
        .thenReturn(new RecordReads.Read("orc_1", page, 100));

    Outcome outcome = route("{\"root\":\"orc_1\",\"tail\":true}", "enzo");

    JsonNode rows = FrameJson.answering().valueToTree(outcome.payload()).get("rows");
    assertEquals("Which database?\nPostgres or SQLite", rows.get(0).get("body").asText());
    assertFalse(rows.get(1).has("body"));
    assertTrue(rows.get(1).has("detail"), "detail is still written as null");
  }

  @Test
  void a_socket_with_no_account_is_refused_and_nothing_is_read() {
    Outcome outcome = route("{\"root\":\"orc_1\",\"tail\":true}", null);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    verify(reads, never()).read(any(), any(), any(), any(), any(), any(), any());
  }
}
