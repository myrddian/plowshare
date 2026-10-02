package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.server.api.DefineProjectRequest;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.Faults;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one decoder every frame handler reads its payload through, measured
 * against spec §3.2's second half and against the payload-key convention.
 */
class PayloadsTest {

    // -- 3.2: the payload is tolerant -----------------------------------------

    /**
     * A field this build has never heard of is ignored rather than refused.
     *
     * <p>The assertion the whole class exists for: §3.2 makes the envelope
     * exact and the payload tolerant, because the client and the server ship
     * separately and a newer client sending a field an older server has no use
     * for is a normal condition, not a malformed request.
     */
    @Test
    void a_field_this_build_has_never_heard_of_is_ignored() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "payments");
        payload.put("workspace", "/srv/repo");
        payload.put("retentionPolicyFromV2", "forever");

        DefineProjectRequest asked =
                Payloads.as(payload, DefineProjectRequest.class, FrameTypes.PROJECT_DEFINE);

        assertEquals("payments", asked.name());
        assertEquals("/srv/repo", asked.workspace());
    }

    /** And the same for a key read directly: unknown siblings change nothing. */
    @Test
    void an_unknown_sibling_does_not_hide_the_key_that_was_asked_for() {
        Map<String, Object> payload =
                Map.of("conversation", "cnv_1", "somethingNewer", List.of(1, 2));

        assertEquals("cnv_1", Payloads.required(payload, "conversation",
                FrameTypes.CONVERSATION_TURNS, "an id."));
    }

    /** No payload at all is the same request as an empty one — one that named
     *  no fields, refused later by whatever validates the fields. */
    @Test
    void an_absent_payload_reads_as_an_empty_one() {
        DefineProjectRequest asked =
                Payloads.as(null, DefineProjectRequest.class, FrameTypes.PROJECT_DEFINE);

        assertEquals(null, asked.name());
        assertEquals(null, asked.workspace());
    }

    // -- the wrong shape is 400 and not 500 -----------------------------------

    /**
     * A field bound to the wrong JSON type is a caller fault naming the type
     * and the fields it takes — and a 400 through {@code Faults}, not the 500
     * an uncaught {@link IllegalArgumentException} would have been.
     */
    @Test
    void a_field_of_the_wrong_json_type_is_a_caller_fault_that_names_the_shape() {
        Map<String, Object> payload = Map.of("name", "payments", "lent", "/srv/shared");

        CallerFault refused = assertThrows(CallerFault.class, () -> Payloads.as(
                payload, DefineProjectRequest.class, FrameTypes.PROJECT_DEFINE));

        assertTrue(refused.getMessage().contains(FrameTypes.PROJECT_DEFINE),
                "names the frame type that was got wrong: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("workspace"),
                "and enumerates the record's own fields rather than a list written out by"
                        + " hand, which cannot fall behind the record: " + refused.getMessage());
        assertEquals(Code.BAD_REQUEST, Faults.of(refused).code(),
                "and Faults is still the one thing deciding that is a 400");
    }

    /** A missing value is the caller's mistake and says which field. */
    @Test
    void a_missing_key_is_a_caller_fault_naming_the_field_and_the_type() {
        CallerFault refused = assertThrows(CallerFault.class, () -> Payloads.required(
                Map.of(), "conversation", FrameTypes.CONVERSATION_TURNS,
                "the id POST /v1/conversations answered with."));

        assertTrue(refused.getMessage().contains("conversation"), refused.getMessage());
        assertTrue(refused.getMessage().contains(FrameTypes.CONVERSATION_TURNS),
                refused.getMessage());
        assertEquals(Code.BAD_REQUEST, Faults.of(refused).code());
    }

    /** A blank string names nothing, exactly as an absent field names nothing. */
    @Test
    void a_blank_value_names_nothing() {
        assertThrows(CallerFault.class, () -> Payloads.required(
                Map.of("conversation", "   "), "conversation",
                FrameTypes.CONVERSATION_TURNS, "an id."));
    }

    // -- the payload-key convention, enforced rather than written down --------

    /**
     * A handler asking for a payload field called {@code id} is refused as a
     * programming error, not as a caller fault.
     *
     * <p><b>This is the convention made structural.</b> {@code id} at the
     * envelope level is the client's correlation, so a payload naming its own
     * {@code id} means two things by one name at two nesting levels — and fifty
     * breadth handlers each inventing a spelling for "which one" is fifty facts
     * a client learns one at a time. The refusal is an {@link
     * IllegalArgumentException} rather than a {@link CallerFault} deliberately:
     * the mistake is the handler author's and not the client's, so it belongs
     * to the build and not to a 400.
     */
    @Test
    void a_payload_key_of_id_is_refused_as_a_programming_error() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Payloads.required(Map.of("id", "cnv_1"), "id",
                        FrameTypes.CONVERSATION_TURNS, "an id."));

        assertTrue(refused.getMessage().contains("noun"),
                "and says what to name it instead: " + refused.getMessage());
    }
}
