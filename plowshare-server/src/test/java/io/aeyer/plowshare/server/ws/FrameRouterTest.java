package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.Capabilities;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The four failures the dispatcher plan requires, plus a registered type actually reaching its
 * handler.
 *
 * <p>No socket, no Spring context — {@link FrameRouter} takes no reference to either, which is the
 * point being tested as much as any single assertion below: nothing here could hand a {@code
 * WebSocketSession} to a handler even if a test tried to.
 */
class FrameRouterTest {

  /**
   * What a channel routes a frame under — a session and no project, which is the whole of what a
   * socket knows. See {@link Asking}.
   */
  private static final Asking ASKING = FrameParity.ASKING;

  // -- unknown type: a NOT_FOUND-shaped outcome naming the type, not a 500 -

  @Test
  void an_unregistered_type_answers_not_found_naming_the_type() {
    FrameRouter router = new FrameRouter(Map.of());
    Envelope request = new Envelope("r1", "conversation.turns", Envelope.CURRENT_VERSION, Map.of());

    Outcome outcome = router.route(request, ASKING);

    assertEquals(Code.NOT_FOUND, outcome.code());
    assertTrue(
        outcome.said().contains("conversation.turns"),
        "the refusal names the type nothing claims: " + outcome.said());
  }

  // -- malformed JSON: a caller fault, and route() never throws ------------

  @Test
  void a_frame_that_will_not_parse_answers_a_caller_fault_rather_than_throwing() {
    FrameRouter router = new FrameRouter(Map.of());

    // Not an exception escaping route() -- an Outcome. That is the whole of
    // what "the session survives" means at this layer: FileChannelHandler's
    // own hazard was an exception reaching Spring's
    // ExceptionWebSocketHandlerDecorator and closing the session for every
    // other outstanding request on it. Nothing here can do that if nothing
    // throws.
    Outcome outcome = router.route("{this is not json", ASKING);

    assertEquals(Code.BAD_REQUEST, outcome.code());
  }

  @Test
  void a_frame_that_parses_to_the_json_literal_null_answers_a_caller_fault() {
    // The gap FileChannelHandler's own javadoc measured on Jackson 2.17:
    // readValue("null", X.class) returns a Java null with no exception at
    // all, so the catch around readValue cannot see this case -- it needs
    // its own check. Matched here rather than reinvented.
    FrameRouter router = new FrameRouter(Map.of());

    Outcome outcome = router.route("null", ASKING);

    assertEquals(Code.BAD_REQUEST, outcome.code());
  }

  @Test
  void a_frame_with_no_type_answers_a_caller_fault_rather_than_throwing() {
    // {} binds every Envelope component to null, and the compact
    // constructor's Objects.requireNonNull(type, ...) throws from inside
    // Jackson's own construction -- wrapped as ValueInstantiationException,
    // itself an IOException, so route(String, ...)'s single catch already
    // reaches it without a second clause.
    FrameRouter router = new FrameRouter(Map.of());

    Outcome outcome = router.route("{}", ASKING);

    assertEquals(Code.BAD_REQUEST, outcome.code());
  }

  // -- wrong protocol_version: refused, naming both versions ---------------

  @Test
  void a_frame_naming_the_wrong_protocol_version_is_refused_naming_both_versions() {
    FrameRouter router = new FrameRouter(Map.of());
    String frame =
        "{\"id\":\"r1\",\"type\":\"conversation.turns\","
            + "\"protocol_version\":\"plowshare-v2\",\"payload\":{}}";

    Outcome outcome = router.route(frame, ASKING);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertTrue(
        outcome.said().contains(Envelope.CURRENT_VERSION),
        "names the version this build speaks: " + outcome.said());
    assertTrue(
        outcome.said().contains("plowshare-v2"),
        "and the version the frame proposed instead: " + outcome.said());
  }

  // -- a handler that throws: whatever Faults says, never a leaked trace ---

  @Test
  void a_handler_that_throws_a_mapped_exception_answers_what_faults_says() {
    FrameRouter router =
        new FrameRouter(
            Map.of(
                "archive.read",
                (payload, asking) -> {
                  throw new ArchiveUnavailableException(
                      "the archive is down for maintenance", null);
                }));
    Envelope request = new Envelope("r1", "archive.read", Envelope.CURRENT_VERSION, Map.of());

    Outcome outcome = router.route(request, ASKING);

    assertEquals(Code.ARCHIVE_UNAVAILABLE, outcome.code());
    assertTrue(outcome.said().contains("the archive is down for maintenance"), outcome.said());
  }

  @Test
  void a_handler_that_throws_an_unclassified_exception_answers_internal_error() {
    FrameRouter router =
        new FrameRouter(
            Map.of(
                "boom.now",
                (payload, asking) -> {
                  throw new IllegalStateException("nobody classified this one");
                }));
    Envelope request = new Envelope("r1", "boom.now", Envelope.CURRENT_VERSION, Map.of());

    Outcome outcome = router.route(request, ASKING);

    assertEquals(Code.INTERNAL_ERROR, outcome.code());
    assertFalse(
        outcome.said().contains("\tat "),
        "no stack trace frame leaked into the sentence a caller sees: " + outcome.said());
    assertFalse(
        outcome
            .said()
            .contains("io.aeyer.plowshare.server.ws.FrameRouterTest.a_handler_that_throws"),
        "no stack trace leaked this test's own method name either: " + outcome.said());
  }

  // -- a registered type reaches its handler --------------------------------

  @Test
  void a_registered_type_reaches_its_handler_with_its_payload_and_caller() {
    FrameRouter router =
        new FrameRouter(
            Map.of(
                "test.echo",
                (payload, asking) ->
                    Outcome.ok(
                        Map.of(
                            "sawGreeting", payload.get("greeting"),
                            "sawSession", asking.sessionId()))));
    String frame =
        "{\"id\":\"req-42\",\"type\":\"test.echo\","
            + "\"protocol_version\":\""
            + Envelope.CURRENT_VERSION
            + "\","
            + "\"payload\":{\"greeting\":\"hi\"}}";

    Outcome outcome = router.route(frame, ASKING);

    assertEquals(Code.OK, outcome.code());
    assertEquals(Map.of("sawGreeting", "hi", "sawSession", "session-1"), outcome.payload());
    // route(Envelope/String, ...) returns a bare Outcome, matching Task 1's
    // Outcome -- which carries no id field by its own design (see Outcome's
    // class javadoc). Echoing "req-42" onto the wire response's own id is
    // the channel handler's job in a later task: it already holds both the
    // parsed request (and therefore request.id()) and this Outcome without
    // this router needing to pair them itself. What this test proves is
    // narrower and fully in this router's scope: dispatch by type reaches
    // the correct handler, and the payload and caller it receives are
    // exactly what the envelope named -- nothing scrambled or dropped on
    // the way through.
  }

  // -- registration itself is checked, not merely used ----------------------

  @Test
  void registering_a_type_that_is_not_a_dotted_discriminator_fails_the_boot() {
    FrameHandler harmless = (payload, asking) -> Outcome.ok();

    assertThrows(
        IllegalArgumentException.class,
        () -> new FrameRouter(Map.of("conversationTurns", harmless)));
  }

  // -- the surface is declared: spec §3.7, held where both halves are visible -

  /**
   * Every frame type this server knows is declared in {@code client.Capabilities}, which is spec
   * §3.7's whole claim: "a frame type with no declaration fails the build, and an endpoint with no
   * frame equivalent shows as a declared gap".
   *
   * <p><b>"The build" and not "the boot", and this method is the reason.</b> §3.7 was amended after
   * the dispatcher landed, for the argument the next heading gives; the other half of the sentence
   * does still fail the boot, in {@code Capability}'s compact constructor. Quoting the original
   * here would make this test the evidence for a claim it disproves.
   *
   * <h2>Why this is a test and not a line in {@link FrameRoutingConfig}</h2>
   *
   * <p>{@code toolsAreDeclared} is called from {@code PlowshareClient.tools} and {@code
   * commandsAreDeclared} from {@code cli.Commands}' static initialiser — <b>both in {@code
   * plowshare-client}, the module that owns the register</b>. This surface is assembled in {@code
   * plowshare-server}, and that module's {@code src/main} cannot see the client at all: {@code
   * plowshare-server/build.gradle.kts} takes the client as a {@code testImplementation} only,
   * deliberately, "so the compiler still enforces that the server knows nothing about MCP". A call
   * to {@code Capabilities.framesAreDeclared} in {@code FrameRoutingConfig} would not compile, and
   * buying one would put the MCP SDK on this server's runtime class path to do it.
   *
   * <p>So this check lands exactly where {@code screens}' did, and for the same reason {@code
   * Capabilities.screens()}' javadoc gives: the enforcement lives on the side that can see the
   * surface. For the console that was {@code parity.test.ts}; for the socket it is here, the one
   * source set in this repository where the real routing table and the real register can be in one
   * JVM.
   *
   * <p><b>Read from {@link FrameTypes} and not from the routing map</b>, which is the stronger of
   * the two directions available: a constant added there and left unrouted is still a type this
   * server has named, and is exactly what the breadth plan will be adding fifty of.
   */
  @Test
  void every_frame_type_this_server_knows_is_declared_in_capabilities() throws Exception {
    List<String> known = new ArrayList<>();
    for (Field field : FrameTypes.class.getDeclaredFields()) {
      int how = field.getModifiers();
      boolean constant = Modifier.isPublic(how) && Modifier.isStatic(how);
      if (constant && field.getType() == String.class) {
        known.add((String) field.get(null));
      }
    }

    assertTrue(
        known.contains(FrameTypes.REFUSED),
        "the response-only type is in the set this check is handed, which is why"
            + " framesAreDeclared has to exclude it: "
            + known);
    Capabilities.framesAreDeclared(known);
  }

  /**
   * The routing table itself, which is a subset of the above but is the set a client can actually
   * reach. Built through {@link FrameRoutingConfig} so that it is the real table and not a second
   * list.
   */
  @Test
  void every_routed_frame_type_is_declared_in_capabilities() {
    FrameRouter routing = FrameAreas.router();

    Capabilities.framesAreDeclared(routing.types());
    // The one way this check could pass while hiding a real gap:
    // framesAreDeclared filters the response-only type, so an area that
    // registered a handler for it would be routable and undeclared at once,
    // with nothing above saying so.
    assertFalse(
        routing.types().contains(FrameTypes.REFUSED),
        FrameTypes.REFUSED
            + " travels server-to-client only -- it is what a frame this"
            + " server could not read is answered under, and framesAreDeclared"
            + " excludes it, so a handler registered for it would be reachable and"
            + " undeclared: "
            + routing.types());
  }

  /**
   * The routed set and the declared set are the same set, in both directions.
   *
   * <p>{@link Capabilities#framesAreDeclared} is one-directional by construction: it reports a
   * routed type nobody declared and is silent about a declared type nobody routes. That second
   * direction is a real way to drift — a typo inside one of {@code ALL}'s {@code List.of(…)}
   * literals, or a type dropped from an area while its entry keeps naming it — and it fails nothing
   * anywhere else. The register would go on promising a frame no client can send, and the promise
   * is what a front end is written against.
   *
   * <p>Cheap to assert here for the reason the method above gives at length: this is the one source
   * set where the real routing table and the real register are in the same JVM. The two sets are
   * equal today, so this pins that rather than establishing it.
   *
   * <p>The message says which direction broke, because the two are fixed in opposite places — an
   * undeclared type needs a row in {@code Capabilities}, an unrouted one needs either a handler or
   * its declaration removed.
   */
  @Test
  void nothing_is_declared_that_no_area_routes() {
    Set<String> routed = new TreeSet<>(FrameAreas.router().types());
    Set<String> declared = new TreeSet<>(Capabilities.frames());

    Set<String> unrouted = new TreeSet<>(declared);
    unrouted.removeAll(routed);
    Set<String> undeclared = new TreeSet<>(routed);
    undeclared.removeAll(declared);

    assertEquals(
        declared,
        routed,
        "the register and the routing table have drifted apart."
            + " Declared and routed by nothing: "
            + unrouted
            + " -- each needs a handler registered in its area, or its spelling"
            + " corrected in Capabilities.ALL, or its declaration dropped."
            + " Routed and declared by nothing: "
            + undeclared
            + " -- each needs a row in Capabilities.ALL, which is the direction"
            + " framesAreDeclared already reports.");
  }

  /**
   * The one literal this arrangement has to duplicate across the module boundary. {@code
   * Capabilities} cannot import {@link FrameTypes} — the dependency runs the other way — so it
   * spells the response-only type out itself, and this is what keeps the two spellings in step.
   */
  @Test
  void the_register_spells_the_response_only_type_the_same_way_this_server_does() {
    assertEquals(FrameTypes.REFUSED, Capabilities.RESPONSE_ONLY_REFUSED);
  }
}
