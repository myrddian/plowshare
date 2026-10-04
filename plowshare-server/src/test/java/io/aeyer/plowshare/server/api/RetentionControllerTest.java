package io.aeyer.plowshare.server.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.archive.Retention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The verb that runs a retention sweep, and the answer it gives back.
 *
 * <p>Pure MVC against a mocked {@link Retention}: what a sweep <em>does</em> is {@code
 * RetentionTest}'s subject against a real database, and this class's job is narrower — is there a
 * door, does it answer synchronously, and does the answer carry the account rather than a handle.
 *
 * <p><b>What this file cannot assert is the absence of a schedule</b>, which is the design decision
 * it exists beside. No test can prove a timer is not there; what stands in for it is that this is
 * the only caller of {@code Retention.sweep} in the tree, and {@code InvariantsTest}'s kind of
 * sweep is where a rule like that would have to live if it were ever worth pinning.
 */
class RetentionControllerTest {

  private Retention retention;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    retention = mock(Retention.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new RetentionController(retention))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  /**
   * 200 and a report, not 202 and a job.
   *
   * <p>Every other endpoint that starts work in this server answers 202 with a handle, because that
   * work calls a model and takes minutes. A sweep calls none — and an operation that deletes file
   * bodies and answers "accepted" gives an operator nothing to check.
   */
  @Test
  void a_sweep_answers_with_what_it_did_rather_than_with_a_job_to_poll() throws Exception {
    when(retention.sweep()).thenReturn(new Retention.SweepReport(2, 1, 7, 412_000L, 5));

    mvc.perform(post("/v1/retention/sweep"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.marked").value(2))
        .andExpect(jsonPath("$.conversations").value(1))
        .andExpect(jsonPath("$.payloads").value(7))
        .andExpect(jsonPath("$.characters").value(412_000L))
        // Its own field, and never added into the counts above: an
        // ejected payload leaves its row behind and a pruned job record
        // does not, so one number for both would tell an operator
        // nothing about what is still there.
        .andExpect(jsonPath("$.prunedJobs").value(5));

    verify(retention).sweep();
  }

  /**
   * The number an operator is actually trying to bring down is in the one line the report says of
   * itself.
   */
  @Test
  void the_report_says_in_one_line_what_was_marked_and_what_went() {
    String said = new Retention.SweepReport(2, 1, 7, 412_000L, 5).said();

    assertTrue(said.contains("marked 2 conversations"), said);
    assertTrue(said.contains("7 stored results"), said);
    assertTrue(said.contains("412000 characters"), said);
    assertTrue(said.contains("pruned 5 job records"), said);
  }

  /**
   * One of each, because "1 conversations" in an operator's log is the kind of thing that gets read
   * as a bug in the counting.
   */
  @Test
  void a_report_of_one_of_everything_reads_as_one_of_everything() {
    String said = new Retention.SweepReport(1, 1, 1, 100L, 1).said();

    assertTrue(said.contains("marked 1 conversation for"), said);
    assertTrue(said.contains("1 stored result holding"), said);
    assertTrue(said.contains("pruned 1 job record"), said);
  }
}
