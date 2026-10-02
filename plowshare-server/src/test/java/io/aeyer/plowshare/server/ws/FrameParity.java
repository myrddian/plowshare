package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * What a parity test needs, once, so that fifty of them are fifty imports
 * rather than fifty copies.
 *
 * <h2>Why this exists at n = 2</h2>
 *
 * <p>{@code assertSameRefusal} was duplicated byte for byte across the two
 * pilot tests before this class existed, and the breadth plan writes one parity
 * test per endpoint across seven parallel tasks — so the next copy is the
 * fifty-second, and the copies would diverge silently: a task that weakened its
 * own copy to get green (comparing statuses but not sentences, say) would look
 * exactly like a task that did the work. The assertion a breadth task depends on
 * is written here once and cannot be locally weakened.
 *
 * <p><b>Every method below compares two surfaces.</b> None of them assert
 * anything about a frame alone — a frame test that passes while the endpoint
 * says something else is the one failure this slice exists to prevent.
 */
final class FrameParity {

    /**
     * What the event channel routes a frame under: a session, and no project.
     * A handler that needs a project takes it out of the payload — see {@link
     * Asking} — so this constant is the whole of what the surface supplies and
     * a parity test cannot accidentally hand a handler more.
     */
    static final Asking ASKING = new Asking("session-1");

    /**
     * The mapper the channel really writes a frame with — {@link
     * FrameJson#answering()} and not a bare one, because a comparison made with
     * a bare mapper would have agreed with a frame surface that could not
     * serialise its own answer. See {@link FrameJson} for the case that found
     * it.
     */
    private static final ObjectMapper JSON = FrameJson.answering();

    private static final TypeReference<Map<String, String>> AS_REFUSAL = new TypeReference<>() {};

    private static final TypeReference<Map<String, Object>> AS_PAYLOAD = new TypeReference<>() {};

    private FrameParity() {
    }

    /**
     * The HTTP surface, as a running application really renders it.
     *
     * <h2>Why a plain {@code standaloneSetup} is not that, and how it shows</h2>
     *
     * <p>{@code MockMvcBuilders.standaloneSetup} installs Spring's default
     * converters, which are <b>not</b> the ones Spring Boot's
     * {@code JacksonAutoConfiguration} builds: Boot disables {@code
     * WRITE_DATES_AS_TIMESTAMPS}, and the bare default does not. So a standalone
     * harness renders an {@link java.time.Instant} as {@code 1789084800.000000000}
     * where the deployed server sends {@code "2026-09-11T00:00:00Z"} — which the
     * console's own wire types confirm, since {@code recordedAt} is typed there
     * as a string.
     *
     * <p><b>That makes the bare harness the wrong thing to compare a frame
     * against.</b> A parity test using it would see a difference that does not
     * exist in production and, worse, could be "fixed" by teaching the frame
     * surface to write timestamps as numbers — shipping the drift the test was
     * written to prevent. Anything whose answer carries a date has to be driven
     * through this method.
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is disabled for the same reason:
     * Boot disables it, so a body carrying a field this build does not know is
     * bound in production and would be a 400 under the bare default. Both
     * halves of the mapper are therefore "what the deployed server does", and
     * the serialising half is literally {@link FrameJson#answering()} — the one
     * the channel writes with.
     *
     * @param controller the controller under test, built over the same mocks
     *     the frame area is
     */
    static MockMvc endpointsOf(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        FrameJson.answering()
                                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * The request as a frame — {@code payload} is dropped in unchanged, so the
     * two surfaces are driven by one string and not by two spellings of one
     * intent.
     *
     * @param type the dotted discriminator, from {@link FrameTypes}
     * @param payload the payload's raw JSON text, exactly what the endpoint's
     *     body would be
     */
    static String frame(String type, String payload) {
        return "{\"id\":\"req-1\",\"type\":\"" + type
                + "\",\"protocol_version\":\"" + Envelope.CURRENT_VERSION
                + "\",\"payload\":" + payload + "}";
    }

    /**
     * The endpoint's status and body and the outcome's code and payload are the
     * same answer said twice.
     */
    static void assertSameAnswer(MockHttpServletResponse http, Outcome outcome) throws Exception {
        assertEquals(outcome.code().httpStatus(), http.getStatus(),
                "the frame's code and the endpoint's status are the same row of one table");
        assertEquals(body(http), JSON.writeValueAsString(outcome.payload()),
                "and the frame's payload is that same answer, field for field");
    }

    /**
     * The endpoint answered with no body and the outcome carries no payload —
     * one answer said twice, for the two verbs on this surface that answer
     * {@link io.aeyer.plowshare.protocol.frames.Code#NO_CONTENT}.
     *
     * <p><b>A separate method rather than a branch in {@link
     * #assertSameAnswer}</b>, because that one would pass this case for the
     * wrong reason and then stop: an empty HTTP body is {@code ""} and a null
     * payload serialises as {@code "null"}, so comparing them would fail a
     * perfectly correct 204 — and the obvious repair, skipping the body
     * comparison when the payload is null, would silently excuse a handler that
     * lost a payload the endpoint still sends. Here both halves are asserted
     * positively: the frame carries nothing <em>and</em> the endpoint sent
     * nothing.
     *
     * <p>The status comparison is the one that matters for this shape. {@code
     * Outcome.ok()} is a well-formed answer carrying no payload either, so a
     * handler that reached for it instead of {@code NO_CONTENT} would differ
     * from its endpoint in the one field a body comparison cannot see.
     */
    static void assertSameEmptyAnswer(MockHttpServletResponse http, Outcome outcome)
            throws Exception {
        assertEquals(outcome.code().httpStatus(), http.getStatus(),
                "the frame's code and the endpoint's status are the same row of one table");
        assertNull(outcome.payload(),
                "a verb whose endpoint answers with no body answers with no payload here");
        assertEquals("", body(http),
                "and the endpoint really did send none, so there is nothing to compare it to");
    }

    /**
     * The endpoint's body, decoded as UTF-8.
     *
     * <p><b>Named rather than {@code http.getContentAsString()}, because that
     * method is wrong here and silently.</b> {@code application/json} carries
     * no charset parameter, so {@link MockHttpServletResponse} falls back to
     * ISO-8859-1 and every multi-byte character in a body comes back as
     * mojibake — which turns any endpoint whose answer contains one of this
     * codebase's em dashes into a parity failure that looks like real drift and
     * is not. The first breadth task met it on {@code conversation.context},
     * whose prose about how a token count was estimated is full of them.
     */
    private static String body(MockHttpServletResponse http) throws Exception {
        return http.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * The endpoint's {@code error}/{@code detail} body and the outcome's code
     * and sentence are the same refusal said twice.
     *
     * <p><b>The sentence comparison is the assertion that matters</b>, and the
     * reason this helper may not be softened into a status check: the client
     * design's §5.2 records that a client's own hand-written sentences are
     * correct only when {@code said} is null, so a client really does read this
     * string. Two surfaces answering 404 in different words is drift that a
     * status-only parity test passes straight through — measured on {@code
     * conversation.turns}, whose blocked pilot was blocked on exactly that.
     */
    static void assertSameRefusal(MockHttpServletResponse http, Outcome outcome) throws Exception {
        Map<String, String> body = JSON.readValue(body(http), AS_REFUSAL);
        assertEquals(outcome.code().httpStatus(), http.getStatus(), "the same status");
        assertEquals(outcome.code().slug(), body.get("error"), "under the same slug");
        assertEquals(body.get("detail"), outcome.said(),
                "and in the same words, which is the assertion that matters: a caller"
                        + " reading the frame's sentence is reading what the endpoint says");
    }

    /**
     * A payload carrying a field this build has never heard of is answered
     * exactly as the same payload without it — spec §3.2's "the payload is
     * tolerant", asserted per frame type.
     *
     * <p><b>This is the test that fails when a handler decodes strictly.</b> A
     * handler that built its own {@code ObjectMapper} and forgot to disable
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} turns a newer client's extra field
     * into a 400 for its type and no other, which no other assertion in a
     * parity suite can see: the endpoint is never sent the extra field, so
     * there is nothing for a two-surface comparison to disagree with. Calling
     * this is one line, and {@code FrameShapeTest} covers the case where a
     * breadth task forgets to call it at all.
     *
     * <p>The two routes must answer identically, not merely both succeed — a
     * refusal is an answer too, so this works for a payload the handler will
     * reject for some other reason, and compares what it rejected it with.
     *
     * @param router a router claiming {@code type}
     * @param type the frame type under test
     * @param payload the payload's raw JSON text, an object
     */
    static void assertUnknownFieldsAreIgnored(FrameRouter router, String type, String payload)
            throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>(JSON.readValue(payload, AS_PAYLOAD));
        fields.put("aFieldOnlyANewerClientKnows", "42");

        Outcome asWritten = router.route(frame(type, payload), ASKING);
        Outcome withTheField =
                router.route(frame(type, JSON.writeValueAsString(fields)), ASKING);

        assertEquals(asWritten.code(), withTheField.code(),
                "spec 3.2: the envelope is exact and the payload is tolerant, so a field this"
                        + " build has never heard of is ignored rather than refused");
        assertEquals(asWritten.said(), withTheField.said(),
                "and it is not merely the same code -- the same answer, word for word");
        assertEquals(JSON.writeValueAsString(asWritten.payload()),
                JSON.writeValueAsString(withTheField.payload()),
                "and the same body");
    }
}
