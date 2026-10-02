package io.aeyer.plowshare.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.digests.Digester;
import io.aeyer.plowshare.server.agents.digests.Digests;
import io.aeyer.plowshare.server.agents.digests.MemoryProperties;
import io.aeyer.plowshare.server.agents.digests.Navigator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pure MVC test against mocked collaborators, matching the shape of {@code
 * MemoryControllerTest}. {@link Navigator} and {@link Digester} are never
 * exercised for real here; the two tests below both take {@code navigate},
 * which resolves its {@code Home} as an argument before either collaborator is
 * touched.
 */
class DigestControllerTest {

    private Navigator navigator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        navigator = mock(Navigator.class);
        Digester digester = mock(Digester.class);
        MemoryProperties properties = new MemoryProperties();
        JobStore jobs = mock(JobStore.class);
        // A real Digests over the same four mocks the controller used to hold
        // directly. The decisions moved there in the breadth plan's Task 6 --
        // DigestsTest measures them as decisions -- and what this file goes on
        // measuring is the half that did not move: the status and the body a
        // caller of each route sees.
        mvc = MockMvcBuilders.standaloneSetup(new DigestController(
                        new Digests(navigator, digester, properties, jobs)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void refuses_a_blank_project_as_the_callers_fault_and_not_as_a_server_fault() throws Exception {
        // It answered 500 "This is a fault in the server, not in the request"
        // until this test, because this controller's own copy of the resolver was
        // the one that never wrapped IllegalArgumentException. A caller who sent a
        // blank field can fix it; the catch-all told them they could not.
        mvc.perform(post("/v1/memories/navigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project\":\"   \",\"question\":\"anything\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void still_treats_an_absent_project_as_the_global_home() throws Exception {
        // The other half of the resolver's contract, and the half that must not
        // change: null is global, and only blank is a refusal.
        when(navigator.navigate(any(), any(), any(), any()))
                .thenReturn(new Navigator.Result("root", List.of(), "text", true, 0));
        mvc.perform(post("/v1/memories/navigate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"anything\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void digest_refuses_a_blank_project_too_because_resolution_still_happens_before_the_job_is_submitted()
            throws Exception {
        // This test pins an ordering, not a status code. `digest` resolves
        // RequestedHome.in(request.project()) on the request thread, before
        // jobs.submit ever sees the lambda that closes over it — so a blank
        // project fails fast, here, as a 400. Move that resolution inside the
        // submitted lambda and this becomes a 202 plus a job that fails quietly
        // on a worker thread, with no test here to catch the regression.
        mvc.perform(post("/v1/memories/digest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project\":\"   \",\"question\":\"anything\"}"))
                .andExpect(status().isBadRequest());
    }
}
