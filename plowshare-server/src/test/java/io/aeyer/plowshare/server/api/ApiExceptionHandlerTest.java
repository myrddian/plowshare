package io.aeyer.plowshare.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The handler's mapping, asserted directly rather than through a controller.
 *
 * <p>A unit test and not a slice of the web layer, because what is being checked
 * is one lookup: that a fault the domain raises answers with the same status as
 * the one {@code api/} raises. Standing a context up to learn that would be a
 * slower way to ask the same question.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void answers_a_domain_caller_fault_with_the_same_status_as_the_api_one() {
        // The whole point of the new type: a caller who sent something wrong
        // gets the same answer whether the code that noticed sits on the HTTP
        // surface or under it.
        ResponseEntity<?> fromApi =
                handler.badRequest(new BadRequestException("a blank project"));
        ResponseEntity<?> fromDomain =
                handler.callerFault(new CallerFault("a blank project"));

        assertEquals(HttpStatus.BAD_REQUEST, fromApi.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, fromDomain.getStatusCode());
    }

    @Test
    void carries_the_message_through_rather_than_rewording_it() {
        // These messages are long, argued, and read by models. A handler that
        // substituted its own would throw away the part that tells a caller what
        // to do differently.
        String said = "conversation c1's last turn was answered by 'x'";

        Map<String, String> body = handler.callerFault(new CallerFault(said)).getBody();

        assertEquals(said, body.get("detail"));
    }

    @Test
    void answers_in_the_same_vocabulary_as_the_api_type() {
        // Same status is not enough: a caller switching on `error` would see two
        // different words for one condition if this arm invented its own.
        assertEquals(
                handler.badRequest(new BadRequestException("x")).getBody().get("error"),
                handler.callerFault(new CallerFault("x")).getBody().get("error"));
    }

    @Test
    void the_two_bodies_agree_on_every_field_and_not_only_the_ones_checked_above() {
        // error and detail are each pinned separately above, which passes for a
        // build where the two arms agree on those two fields and disagree on a
        // third one neither test above looks at. Comparing the whole map is
        // what catches a field added to one arm and not the other.
        assertEquals(
                handler.badRequest(new BadRequestException("x")).getBody(),
                handler.callerFault(new CallerFault("x")).getBody());
    }

    /**
     * The whole handler wiring, not just the method: {@link
     * ApiExceptionHandler#callerFault} is only ever reached because {@code
     * @ExceptionHandler(CallerFault.class)} tells Spring's dispatcher to route
     * here. Every test above calls the method directly and would stay green
     * with that annotation deleted — the whole method would simply go dead,
     * every migrated 400 would fall through to the {@code Throwable} catch-all,
     * and nothing in this file would notice. This test drives a real Spring
     * MVC dispatch, through a stub controller whose only job is to throw {@link
     * CallerFault}, so the annotation is load-bearing for it. Verified by
     * removing {@code @ExceptionHandler(CallerFault.class)} and watching this
     * fail with 500 where 400 was expected, then restoring it.
     */
    @Test
    void a_caller_fault_reaches_400_through_springs_own_dispatch() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StubCallerFaultController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/v1/stub-caller-fault"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.detail").value("a blank project"));
    }

    /**
     * A controller with no purpose beyond throwing {@link CallerFault}, so the
     * test above dispatches through Spring rather than calling the handler's
     * method directly.
     *
     * <p>Mapped under {@code /v1/}, on {@code AuthFilterTest.Probes}'s own
     * pattern: {@code
     * AuthFilterTest.every_route_this_application_publishes_is_gated_or_deliberately_open}
     * enumerates every {@code @RestController} on the test classpath, this one
     * included, and a path outside {@code /v1/} would fail that test as an
     * ungated route rather than exercising the one thing this class exists
     * for.
     */
    @RestController
    static class StubCallerFaultController {

        @GetMapping("/v1/stub-caller-fault")
        public String stub() {
            throw new CallerFault("a blank project");
        }
    }
}
