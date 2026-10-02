package io.aeyer.plowshare.server.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.buffers.Buffers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The verb that runs a buffer purge, and the answer it gives back.
 *
 * <p>Pure MVC against a mocked {@link Buffers}, on {@code
 * RetentionControllerTest}'s own shape: what a purge <em>does</em> is a store
 * test's subject against a real database, and this class's job is narrower —
 * is there a door, does it answer synchronously, and does the answer carry
 * the account rather than a handle.
 *
 * <p><b>The liveness guarantee this slice exists to provide lives elsewhere,
 * against a real database, and this class does not re-prove it.</b> A page
 * inside its live window surviving a purge, however old its fetch is, is
 * {@code FetchedPageStoreTest.a_page_being_read_is_not_purged_however_old_its_fetch_is};
 * a page past both thresholds actually being deleted is {@code
 * FetchedPageStoreTest.a_page_past_its_ttl_is_purged}; and a page whose read
 * has since gone cold being reachable again is {@code
 * FetchedPageStoreTest.a_page_whose_read_has_gone_cold_is_purged_again}.
 * Asserting any of those three here, against a mock, would only prove a stub
 * returns its stub — {@link Buffers} is mocked precisely so this class is not
 * tempted to restate what those tests already pin against Postgres.
 */
class BufferPurgeControllerTest {

    private Buffers buffers;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        buffers = mock(Buffers.class);
        mvc = MockMvcBuilders.standaloneSetup(new BufferPurgeController(buffers))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * 200 and a report, not 202 and a job — {@code
     * RetentionControllerTest}'s own reasoning: this call does a handful of
     * bounded deletes rather than calling a model, so an operator who asked
     * for space back is told what came back rather than handed a job to poll.
     */
    @Test
    void a_purge_answers_with_what_it_did_rather_than_with_a_job_to_poll() throws Exception {
        when(buffers.purge()).thenReturn(new Buffers.BufferPurgeReport(1, 1));

        mvc.perform(post("/v1/buffers/purge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetchedPages").value(1))
                .andExpect(jsonPath("$.resultSets").value(1));

        verify(buffers).purge();
    }

    /**
     * The door has no memory of its own: it calls {@link Buffers#purge()}
     * again on a second request rather than caching or short-circuiting on
     * the first answer, and forwards whatever the second call reports —
     * which, against the real stores, is the "safe to call twice" that
     * {@link Buffers}' own javadoc argues for. A mock cannot prove the second
     * call finds nothing left to purge; it can only prove this controller
     * never stands between a caller and a fresh answer.
     */
    @Test
    void purging_twice_calls_the_collaborator_each_time_rather_than_caching_a_report()
            throws Exception {
        when(buffers.purge())
                .thenReturn(new Buffers.BufferPurgeReport(1, 1))
                .thenReturn(new Buffers.BufferPurgeReport(0, 0));

        mvc.perform(post("/v1/buffers/purge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetchedPages").value(1))
                .andExpect(jsonPath("$.resultSets").value(1));

        mvc.perform(post("/v1/buffers/purge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetchedPages").value(0))
                .andExpect(jsonPath("$.resultSets").value(0));

        verify(buffers, times(2)).purge();
    }
}
