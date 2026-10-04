package io.aeyer.plowshare.server.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.RecordKind;
import io.aeyer.plowshare.server.orchestrations.RecordPage;
import io.aeyer.plowshare.server.orchestrations.RecordReads;
import io.aeyer.plowshare.server.orchestrations.RecordRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrchestrationRecordControllerTest {

  private RecordReads reads;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    reads = mock(RecordReads.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new OrchestrationRecordController(reads))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void answers_the_same_page_the_frame_does() throws Exception {
    RecordPage page =
        new RecordPage(
            List.of(
                new RecordRow(
                    3,
                    Instant.parse("2026-09-28T09:00:00Z"),
                    "orc_1",
                    "conductor",
                    RecordKind.STAGE_MOVED,
                    "code: in_progress → done",
                    "built it",
                    null)),
            3,
            3,
            false);
    when(reads.read("enzo", "orc_1", null, null, true, null, List.of("stage_moved")))
        .thenReturn(new RecordReads.Read("orc_1", page, 100));

    mvc.perform(
            get("/v1/orchestrations/orc_1/record")
                .param("tail", "true")
                .param("kinds", "stage_moved")
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "enzo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.root").value("orc_1"))
        .andExpect(jsonPath("$.through").value(3))
        .andExpect(jsonPath("$.more").value(false))
        .andExpect(jsonPath("$.rows[0].kind").value("stage_moved"))
        .andExpect(jsonPath("$.rows[0].text").value("code: in_progress → done"))
        .andExpect(jsonPath("$.rows[0].detail").value("built it"));
  }

  /**
   * V64: a row's whole text is sent as {@code body} when it has one, and left out when not — not
   * sent as null, so a reader built before it reads the page it always did.
   */
  @Test
  void a_body_is_sent_when_a_row_has_one_and_left_out_when_not() throws Exception {
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

    mvc.perform(
            get("/v1/orchestrations/orc_1/record")
                .param("tail", "true")
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "enzo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].text").value("asked: Which database?"))
        .andExpect(jsonPath("$.rows[0].body").value("Which database?\nPostgres or SQLite"))
        .andExpect(jsonPath("$.rows[1].body").doesNotExist())
        .andExpect(jsonPath("$.rows[1].detail").hasJsonPath());
  }

  @Test
  void a_refusal_from_the_read_is_a_400() throws Exception {
    when(reads.read(null, "orc_1", null, null, true, null, null))
        .thenThrow(new CallerFault("the record is read as an account"));

    mvc.perform(get("/v1/orchestrations/orc_1/record").param("tail", "true"))
        .andExpect(status().isBadRequest());
  }
}
