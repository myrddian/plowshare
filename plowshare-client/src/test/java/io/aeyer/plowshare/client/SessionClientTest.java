package io.aeyer.plowshare.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.cli.Plowshare;
import io.aeyer.plowshare.client.files.Workspace;
import io.aeyer.plowshare.protocol.JobEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The terminal end of a session, against a server that is a socket rather than a mock of one.
 *
 * <h2>What only this can measure</h2>
 *
 * <p>Everything before task 6 in this slice is server-side and provable through a harness that
 * already holds both halves. {@code RemoteWiringTest} and {@code FileChannelTest} drive a real
 * {@code ChannelClient}, but they drive it from inside the server module, where the registry can be
 * asked what attached. A client on somebody's laptop has no registry to ask, and the three things
 * this class is here for all live in that gap:
 *
 * <ul>
 *   <li><b>a run submitted before the provider role is up gets the local provider alone, and
 *       nothing on the server calls that an error</b> — {@code AgentController} says so in as many
 *       words. The only place that can be made loud is here;
 *   <li><b>the id is minted here or it is not minted at all.</b> The registry requires only that an
 *       id be non-blank and the file channel's own tests attach under the words {@code closing},
 *       {@code wedged} and {@code crowd}, so "a session id is a bearer capability that is not worth
 *       guessing at" is a property of whatever opens the session and of nothing else;
 *   <li><b>losing the event socket is not losing the run</b>, and a client that reported one as the
 *       other would be lying about a job that is still going.
 * </ul>
 *
 * <h2>MockWebServer, upgrades and all</h2>
 *
 * <p>Two WebSocket upgrades and two HTTP calls against one {@link MockWebServer} on an
 * <b>ephemeral</b> port, through a {@link Dispatcher} rather than an enqueued sequence: three
 * connections open concurrently, so a queue would be asserting on the order the operating system
 * happened to accept them in.
 *
 * <p>MockWebServer and not a real server because the interesting answers are ones a working server
 * never gives — an upgrade refused with a 404, a socket that closes in the middle of a run — and
 * because a client-module test may not depend on the server module at all.
 */
class SessionClientTest {

  /**
   * Long enough that a loopback socket on a loaded machine is not the thing being measured, and far
   * inside the 120-second suite bound so a failure is this assertion rather than a timeout with no
   * message.
   */
  private static final Duration PATIENCE = Duration.ofSeconds(5);

  /**
   * A version-four UUID as {@code toString} writes one. Used to assert one is NOT there, so it is
   * spelled out rather than derived from an id this test holds — the terminal mints its own and
   * never hands it over.
   */
  private static final Pattern WHOLE_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private static final String STARTED_JOB =
      """
            {"id":"job_000001","agent":"promotion_judge"}""";

  private static final String ANSWERED_JOB =
      """
            {"id":"job_000001","agent":"promotion_judge","state":"FINISHED",
             "cancelRequested":false,
             "outcome":{"ending":"ANSWERED","answered":true,"text":"it is fine",
                        "steps":2,"modelCalls":2,"detail":null}}""";

  /**
   * The other half of the pair the exit status turns on: a run that finished and did not answer.
   * Its {@code text} is deliberately non-empty, so a terminal that printed the text without reading
   * {@code answered} would print something and pass a containment check.
   */
  private static final String TRUNCATED_JOB =
      """
            {"id":"job_000001","agent":"promotion_judge","state":"FINISHED",
             "cancelRequested":false,
             "outcome":{"ending":"TURN_LIMIT","answered":false,
                        "text":"I was still thinking about it",
                        "steps":6,"modelCalls":6,
                        "detail":"stopped after 6 steps"}}""";

  private MockWebServer server;
  private ServerClient http;
  private SessionClient session;

  /**
   * Every request the server saw, in the order it dispatched them. Recorded here rather than
   * through {@code takeRequest} because three connections are live at once and this test asks "did
   * both roles dial?", which is a question about the set.
   */
  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();

  /**
   * The server's end of each socket, so a test can send a frame down one or take one away mid-run.
   */
  private volatile WebSocket serverFiles;

  private volatile WebSocket serverEvents;

  /**
   * Set by a test that wants the event channel refused rather than upgraded — which is what a
   * server built before this slice does with {@code /v1/events}.
   */
  private volatile int refuseEventsWith;

  /**
   * What {@code GET /v1/jobs/*} answers. Switched by the two tests that drive the whole terminal,
   * because the exit status is a claim about this.
   */
  private volatile String jobBody = ANSWERED_JOB;

  @BeforeEach
  void start() throws IOException {
    refuseEventsWith = 0;
    jobBody = ANSWERED_JOB;
    server = new MockWebServer();
    server.setDispatcher(
        io.aeyer.plowshare.client.fixtures.WsFixture.wrap(
            new Dispatcher() {
              @Override
              public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/v1/files")) {
                  return upgrade(socket -> serverFiles = socket);
                }
                if (path.startsWith("/v1/events")) {
                  if (refuseEventsWith != 0) {
                    return new MockResponse().setResponseCode(refuseEventsWith);
                  }
                  return upgrade(socket -> serverEvents = socket);
                }
                if (path.endsWith("/runs")) {
                  return json(202, STARTED_JOB);
                }
                if (path.startsWith("/v1/jobs/")) {
                  return json(200, jobBody);
                }
                return new MockResponse().setResponseCode(404);
              }
            }));
    server.start();
    http = new HttpServerClient(server.url("/").toString());
    session = new SessionClient(http, new Workspace());
  }

  @AfterEach
  void stop() throws IOException {
    session.close();
    server.shutdown();
  }

  // --- the id ---------------------------------------------------------------

  /**
   * The spec's "a session id is a bearer capability, and guessing is not the threat model" bullet,
   * made true for anything this client opens.
   *
   * <p>Version four and not merely "parses as a UUID": {@link UUID#fromString} is happy with a nil
   * UUID and with anything shaped like one, so a constant compiled into this class would pass the
   * parse. Two clients differing is the other half — a single id read from configuration would
   * parse and would have a version too.
   */
  @Test
  void the_id_is_a_random_uuid_and_not_a_word_somebody_chose() {
    SessionClient other = new SessionClient(http, new Workspace());

    assertEquals(
        4,
        UUID.fromString(session.id()).version(),
        "session ids are minted here or nowhere: the registry asks only that an id be"
            + " non-blank, so this is what makes the design spec's bearer-capability"
            + " bullet a property rather than an aspiration");
    assertNotEquals(
        session.id(),
        other.id(),
        "two sessions opened by this client share an id, so it is a constant rather"
            + " than a mint");
    other.close();
  }

  // --- attaching ------------------------------------------------------------

  @Test
  void attaching_dials_both_roles_under_the_one_id() throws Exception {
    session.attach(PATIENCE);

    assertTrue(
        dialed("/v1/files?session=" + session.id() + "&source=1"),
        "the file-provider role did not dial; the server saw " + paths());
    assertTrue(
        dialed("/v1/events?session=" + session.id()),
        "the listener role did not dial; the server saw " + paths());
    assertTrue(session.providing(), "the file-provider role is not attached after attach()");
    assertTrue(session.listening(), "the listener role is not attached after attach()");
  }

  /**
   * A server that predates this slice answers {@code /v1/events} with a 404, because there is no
   * handler on that path. That is the loudest signal available and the client must not swallow it:
   * the alternative is a CLI that silently never renders an event and looks exactly like a run that
   * is stuck.
   */
  @Test
  void a_server_with_no_event_channel_fails_the_attach_rather_than_watching_nothing() {
    refuseEventsWith = 404;

    IOException refused = assertThrows(IOException.class, () -> session.attach(PATIENCE));

    assertTrue(
        refused.getMessage().contains("v1/events"),
        "the attach failed without naming the path that was refused: " + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("404"),
        "the attach failed without naming the status the server answered, which is the"
            + " one thing that separates 'this server has no event channel' from"
            + " 'this server is not there': "
            + refused.getMessage());
  }

  /**
   * A failed attach leaves nothing attached, which is the same rule as the submission guard one
   * method down.
   *
   * <p>A client holding a live file provider and no listener is a client that would submit runs it
   * cannot watch, and it would be in that state through no decision anybody made. The failure
   * closes what it opened.
   */
  @Test
  void an_attach_that_fails_halfway_leaves_no_half_open_session() {
    refuseEventsWith = 404;

    assertThrows(IOException.class, () -> session.attach(PATIENCE));

    // Both halves, because the message names both. Without the first,
    // deleting files.open() from attach() leaves this green: a provider that
    // never dialled is also a provider that is not attached, and the
    // sentence below would then be describing a session that was never half
    // open in the first place.
    assertTrue(
        dialed("/v1/files?session=" + session.id() + "&source=1"),
        "the file-provider role never dialled at all, so this test is not watching the"
            + " half-open session it claims to be — the server saw "
            + paths());
    assertFalse(
        session.providing(),
        "the file-provider socket opened, the listener was refused, and the provider was"
            + " left attached — a half session nobody asked for");
    assertFalse(session.listening());
  }

  // --- submitting -----------------------------------------------------------

  @Test
  void the_run_carries_the_session_id_it_attached_under() throws Exception {
    session.attach(PATIENCE);

    assertEquals(
        "job_000001", session.submit("promotion_judge", "rule on mem_000001", "pay", null));

    RecordedRequest run = requestFor("/v1/agents/promotion_judge/runs");
    String body = run.getBody().readUtf8();
    assertTrue(
        body.contains("\"session\":\"" + session.id() + "\""),
        "the run went out without the session that opened it, so it would reach only"
            + " the filesystems the server itself can see: "
            + body);
  }

  /**
   * The refusal this whole class exists for.
   *
   * <p>{@code AgentController} deliberately does not validate the session against the registry — a
   * body naming a session nothing has attached to submits and runs. So a run submitted before the
   * provider socket is up is accepted, executes against the server's own filesystems, and reports
   * nothing amiss. That is the smaller-capability-by-accident the slice's nullable session exists
   * to prevent, and the client is the only party that can see it happening.
   */
  @Test
  void a_run_submitted_before_the_provider_attaches_is_refused_and_never_reaches_the_wire() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> session.submit("promotion_judge", "rule on mem_000001", null, null));

    assertTrue(
        refused.getMessage().contains("never been attached"),
        "the guard fired on a session that never attached and did not say so: "
            + refused.getMessage());
    assertTrue(
        seen.isEmpty(),
        "the run was refused after it had already gone out; the server saw " + paths());
  }

  /**
   * The same refusal, about a different situation, saying which.
   *
   * <p>Paired with the test above deliberately: one message for both would be a guard whose words
   * describe a state it did not fire on. "Never attached" is a client that has not called {@code
   * attach}; this one is a laptop whose socket went away, and the two send a reader to different
   * places.
   */
  @Test
  void a_run_submitted_after_the_provider_drops_says_it_dropped_and_not_that_it_never_came()
      throws Exception {
    session.attach(PATIENCE);
    await(() -> serverFiles != null, "the mock server never opened its file channel");
    serverFiles.close(1000, "the operator's machine went away");
    await(() -> !session.providing(), "the client never noticed its file channel close");

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> session.submit("promotion_judge", "rule on mem_000001", null, null));

    assertTrue(
        refused.getMessage().contains("has since closed"),
        "a socket that opened and went away was reported as one that never opened: "
            + refused.getMessage());
    assertFalse(
        refused.getMessage().contains("never been attached"),
        "the guard fired on a dropped socket and described an unattached one: "
            + refused.getMessage());
  }

  // --- watching -------------------------------------------------------------

  @Test
  void the_lifecycle_events_arrive_in_the_order_the_server_sent_them() throws Exception {
    session.attach(PATIENCE);

    sendEvent(
        """
                {"job":"job_000001","kind":"started","agent":"promotion_judge",
                 "tool":null,"ending":null,"steps":0,"modelCalls":0}""");
    sendEvent(
        """
                {"job":"job_000001","kind":"model_call","agent":"promotion_judge",
                 "tool":null,"ending":null,"steps":0,"modelCalls":1}""");
    sendEvent(
        """
                {"job":"job_000001","kind":"tool_called","agent":"promotion_judge",
                 "tool":"file_read","ending":null,"steps":1,"modelCalls":1}""");
    sendEvent(
        """
                {"job":"job_000001","kind":"ended","agent":"promotion_judge",
                 "tool":null,"ending":"ANSWERED","steps":2,"modelCalls":2}""");

    assertEquals(JobEvent.STARTED, next().kind());
    assertEquals(JobEvent.MODEL_CALL, next().kind());
    JobEvent tool = next();
    assertEquals(JobEvent.TOOL_CALLED, tool.kind());
    assertEquals("file_read", tool.tool());
    assertEquals(JobEvent.ENDED, next().kind());
  }

  /**
   * A frame this client cannot read is dropped and the watch goes on.
   *
   * <p>{@code ChannelClient} settled this shape for the other socket: there is nothing to answer
   * and nobody to answer it to, and the run's record is elsewhere. A watch that ended on one
   * unreadable frame would report a healthy run as a lost one.
   *
   * <p>The fixture has something for the drop to filter out and something to survive it, in that
   * order, so a client that stopped reading on the bad frame fails here rather than passing on an
   * empty queue.
   */
  @Test
  void a_frame_that_is_not_an_event_is_dropped_and_the_next_one_still_arrives() throws Exception {
    session.attach(PATIENCE);

    sendEvent("{this is not an event}");
    sendEvent(
        """
                {"job":"job_000001","kind":"started","agent":"promotion_judge",
                 "tool":null,"ending":null,"steps":0,"modelCalls":0}""");

    assertEquals(JobEvent.STARTED, next().kind());
  }

  /**
   * The stream is droppable by design; the run is not.
   *
   * <p>The outcome lives behind {@code GET /v1/jobs/&#123;id&#125;} and that is the only
   * contractual record of a run. A client that lost its listener and then said the run had failed
   * would be lying about a job that is still going — and the file-provider role, which is the one
   * that actually changes what a run can do, is untouched by a listener going away.
   */
  @Test
  void losing_the_event_socket_mid_run_loses_the_view_and_not_the_run() throws Exception {
    session.attach(PATIENCE);
    session.submit("promotion_judge", "rule on mem_000001", null, null);

    serverEvents.close(1000, "the terminal went away");
    await(() -> !session.listening(), "the client never noticed its event channel close");

    assertTrue(
        session.providing(),
        "a listener closing took the file-provider role with it; SESSION_GONE is"
            + " produced by a file request that cannot be served, never by a socket"
            + " closing");
    ServerClient.JobStatus finished = session.job("job_000001");
    assertEquals(
        "ANSWERED",
        finished.outcome().ending(),
        "the outcome was still there to be read and the client could not read it");
  }

  // --- what a person sees ---------------------------------------------------

  /**
   * Two columns before every line: how long the run has been going, and how long since anything
   * last happened.
   *
   * <p>The gap column is the whole of what separates working from stuck in a slice with no token
   * stream. Asserted as whole strings because the claim is about what a person reads, and a
   * containment check would pass on a line with the columns missing.
   */
  @Test
  void an_event_renders_with_the_elapsed_time_and_the_gap_before_it() {
    assertEquals(
        "   0.0s  +0.0s  started      promotion_judge",
        Plowshare.line(
            JobEvent.started("job_000001", "promotion_judge"), Duration.ZERO, Duration.ZERO));
    assertEquals(
        "   3.2s  +2.8s  tool called  file_read",
        Plowshare.line(
            JobEvent.toolCalled("job_000001", "promotion_judge", "file_read"),
            Duration.ofMillis(3200),
            Duration.ofMillis(2800)));
    assertEquals(
        "  33.0s +29.8s  ended        ANSWERED after 2 steps, 2 model calls",
        Plowshare.line(
            JobEvent.ended("job_000001", "promotion_judge", "ANSWERED", 2, 2),
            Duration.ofMillis(33_000),
            Duration.ofMillis(29_800)));
  }

  /**
   * Silence is rendered rather than left to be inferred from a still terminal.
   *
   * <p>A person watching a run with no token stream has exactly one question — is it working or is
   * it stuck — and a terminal that has printed nothing for thirty seconds answers neither. This
   * line says how long the quiet has lasted and what the last thing to happen was, which is the
   * difference between "the model is thinking" and "the tool never came back".
   */
  @Test
  void silence_is_reported_with_its_age_and_the_last_thing_that_happened() {
    String waiting =
        Plowshare.waiting(
            Duration.ofMillis(43_100),
            Duration.ofMillis(31_400),
            JobEvent.toolCalled("job_000001", "promotion_judge", "file_read"),
            true);

    assertEquals("  43.1s +31.4s  waiting      still nothing since tool called file_read", waiting);
  }

  /**
   * The same quiet means a different thing once the socket has gone, and says so.
   *
   * <p>Paired with the test above deliberately. With the stream up, nothing arriving is the run
   * thinking; with it gone, nothing is going to arrive whatever the run does, and repeating "still
   * nothing since tool called file_read" would invite a reader to conclude something about the run
   * from a fact about their terminal.
   */
  @Test
  void the_quiet_after_the_stream_is_gone_is_reported_as_this_terminals_and_not_the_runs() {
    String waiting =
        Plowshare.waiting(
            Duration.ofMillis(43_100),
            Duration.ofMillis(31_400),
            JobEvent.toolCalled("job_000001", "promotion_judge", "file_read"),
            false);

    assertEquals(
        "  43.1s +31.4s  waiting      no event stream; still asking the job endpoint", waiting);
  }

  /**
   * The terminal goes on reporting after it loses its socket, which is the one case it must.
   *
   * <p><b>This is the regression.</b> The loop said the stream was gone once, and from then on the
   * first branch was spent and the second was guarded on {@code session.listening()} — false
   * forever — so a person watching a ten-minute run saw one line and then a still terminal until it
   * ended. That is the exact thing {@code Plowshare}'s own javadoc says the design exists to
   * prevent, arriving in the case where the heartbeat is the only signal left.
   *
   * <p>Driven through {@code Heartbeat} with synthetic clock readings rather than through the loop,
   * because the bug lives ten seconds of quiet away and a test that waited for it would be a test
   * nobody runs.
   */
  @Test
  void a_terminal_that_has_lost_its_stream_keeps_saying_the_run_is_alive() {
    Plowshare.Heartbeat beat = new Plowshare.Heartbeat(0L);
    beat.onEvent(JobEvent.toolCalled("job_000001", "promotion_judge", "file_read"), seconds(3));

    assertTrue(
        beat.onQuiet(false, seconds(5)).contains("the event stream is gone"),
        "losing the socket was not reported at all");
    assertNull(
        beat.onQuiet(false, seconds(6)),
        "it repeated itself before the quiet was long enough to be news");

    String later = beat.onQuiet(false, seconds(16));
    assertNotNull(
        later,
        "the terminal fell silent after losing its stream, in the one case the"
            + " heartbeat exists for");
    assertEquals(
        "  16.0s +13.0s  waiting      no event stream; still asking the job endpoint",
        later,
        "and the gap column must still measure the last EVENT rather than the last line"
            + " printed, or the growing number a person watches for restarts");
  }

  /**
   * The same clock, with the socket up: the quiet is the run's and is named as such. Written as the
   * pair so a fix that made every quiet line say "no event stream" would fail here.
   */
  @Test
  void a_terminal_that_still_has_its_stream_names_the_last_thing_the_run_did() {
    Plowshare.Heartbeat beat = new Plowshare.Heartbeat(0L);
    beat.onEvent(JobEvent.toolCalled("job_000001", "promotion_judge", "file_read"), seconds(3));

    assertNull(beat.onQuiet(true, seconds(9)));
    assertEquals(
        "  14.0s +11.0s  waiting      still nothing since tool called file_read",
        beat.onQuiet(true, seconds(14)));
  }

  /**
   * A kind this build has never heard of renders rather than throwing.
   *
   * <p>{@code JobEvent.kind} is a string and not an enum, and its javadoc says why: the two halves
   * of this wire ship separately, so a client built against a later server must not fail over a
   * constant it has never seen. That promise is only kept if something on this side reads an
   * unknown kind without deciding it is a bug — and the natural implementation, a switch over four
   * literals, throws.
   */
  @Test
  void a_kind_from_a_later_server_renders_instead_of_ending_the_watch() {
    String rendered =
        Plowshare.line(
            new JobEvent("job_000001", "turn_finished", "promotion_judge", null, null, 3, 4),
            Duration.ofMillis(1000),
            Duration.ofMillis(1000));

    assertTrue(
        rendered.contains("turn_finished"),
        "an unknown kind was rendered as something other than itself: " + rendered);
    assertTrue(rendered.contains("promotion_judge"), rendered);
  }

  /**
   * The client's own queue drops rather than growing, and counts what it dropped.
   *
   * <p>{@code SessionClient.PENDING} argues at length that it must equal {@code
   * EventChannelHandler.PENDING} — smaller and this client becomes the drop point for a stream the
   * server was willing to carry; larger and it buffers past where the server gave up. Nothing
   * measured either half. The server's side deliberately overruns its own constant to prove the
   * drop; this is the same instrument on this side, and the asymmetry was the gap.
   *
   * <p>Nothing drains while the burst arrives, which is the fixture: a test that called {@code
   * nextEvent} in the loop would keep the queue empty and pass against an unbounded one. Four
   * queues' worth, so the bound is exceeded by a margin no scheduling accident closes.
   */
  @Test
  void a_burst_larger_than_the_queue_is_dropped_and_counted_rather_than_grown() throws Exception {
    session.attach(PATIENCE);

    for (int nth = 0; nth < SessionClient.PENDING * 4; nth++) {
      sendEvent(
          """
                    {"job":"job_000001","kind":"model_call","agent":"promotion_judge",
                     "tool":null,"ending":null,"steps":%d,"modelCalls":%d}"""
              .formatted(nth, nth));
    }
    await(
        () -> session.dropped() > 0,
        "four queues' worth of events arrived and this client dropped none of them,"
            + " so its queue is not bounded at "
            + SessionClient.PENDING);

    assertTrue(session.dropped() > 0);
    // And what it kept is still readable: a full queue is a view with holes
    // in it, not a watch that has stopped.
    assertEquals(
        JobEvent.MODEL_CALL,
        next().kind(),
        "the queue filled and the events already in it became unreadable");
  }

  // --- the whole terminal ----------------------------------------------------

  /**
   * The acceptance path, end to end through the class a person runs.
   *
   * <p>Everything above measures a piece. This drives {@link Plowshare#run} against the same socket
   * and reads what it printed, which is the only thing that can fail when the pieces are each fine
   * and the terminal prints nothing.
   *
   * <p>The "no workspace" line is asserted rather than tolerated: a session that lends nothing is a
   * supported shape and a bad one to arrive at by forgetting a flag, so the terminal says so out
   * loud.
   */
  @Test
  void the_terminal_prints_the_session_the_job_and_the_answer_and_exits_zero() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(
            new String[] {"promotion_judge", "rule on mem_000001", "--server", where()},
            printed.stream(),
            complaints.stream());

    assertEquals(
        0,
        status,
        "the run answered and the terminal did not exit 0. It said: "
            + printed.text()
            + complaints.text());
    assertTrue(
        printed.text().contains("no workspace: this session lends no files"),
        "a session lending nothing said nothing about it: " + printed.text());
    assertTrue(printed.text().contains("job job_000001 — promotion_judge"), printed.text());
    assertTrue(printed.text().contains("ANSWERED after 2 steps, 2 model calls"), printed.text());
    assertTrue(
        printed.text().contains("it is fine"),
        "the answer itself was never printed: " + printed.text());

    // Containment, with its positive control beside it. SessionClient's own
    // javadoc says an id that has been used is as good as a password to
    // whoever sees it, and stdout is a CI log, a paste and a screen share;
    // ChannelClient redacts its URL for the same reason. What reaches the
    // terminal is a handle, and the control is what stops this passing on a
    // transcript that simply says nothing about the session.
    assertTrue(
        printed.text().contains("session ") && printed.text().contains("…"),
        "nothing identified the session at all, so the assertion below is vacuous: "
            + printed.text());
    assertFalse(
        WHOLE_UUID.matcher(printed.text()).find(),
        "a whole session id reached stdout, and it is the capability itself: " + printed.text());
  }

  /**
   * A run that stopped is never dressed as one that answered.
   *
   * <p>The pair with the test above is the claim, and the fixture is written so that the wrong
   * implementation passes neither: {@code TRUNCATED_JOB} carries real {@code text}, so a terminal
   * that printed the text and exited 0 without reading {@code answered} would look right here until
   * the status is checked. That bit is separate from the ending for exactly this reason.
   */
  @Test
  void a_run_that_stopped_without_answering_exits_one_and_does_not_print_its_text() {
    jobBody = TRUNCATED_JOB;
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(
            new String[] {"promotion_judge", "rule on mem_000001", "--server", where()},
            printed.stream(),
            complaints.stream());

    assertEquals(1, status, "a run that did not answer exited " + status);
    assertTrue(complaints.text().contains("stopped without answering"), complaints.text());
    assertFalse(
        printed.text().contains("I was still thinking about it"),
        "the terminal printed a truncated run's text as though it were an answer: "
            + printed.text());
  }

  /**
   * A mistyped {@code --server} exits 2 rather than throwing out of main.
   *
   * <p>{@code HttpServerClient} refuses an unusable base URL at construction, and that construction
   * used to sit outside every catch clause — so the IllegalArgumentException reached the JVM: a
   * stack trace and exit 1, against the "2 for anything that stopped this from watching a run" the
   * class javadoc promises. The status is the assertion; a person scripting this reads it and a
   * person reading a stack trace does not.
   */
  @Test
  void a_server_url_that_is_not_a_url_exits_two_and_says_so() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(
            new String[] {"promotion_judge", "go", "--server", "not a url"},
            printed.stream(),
            complaints.stream());

    assertEquals(2, status, "it said: " + complaints.text());
    assertTrue(complaints.text().contains("not a usable server URL"), complaints.text());
    assertTrue(seen.isEmpty(), "a mistyped server URL reached a server: " + paths());
  }

  /**
   * A workspace that is not there is refused rather than announced.
   *
   * <p>{@code Workspace.set} validates nothing — deliberately, it keeps what was typed and lets
   * {@code FileAccess} resolve where the comparison happens — so {@code --workspace /typo} printed
   * "lending [/typo]" and then ran a session that lent nothing. That is the silent smaller
   * capability the "no workspace" line exists to prevent, arriving through the branch meant to
   * prevent it.
   */
  @Test
  void a_workspace_that_is_not_a_directory_is_refused_before_anything_is_lent() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(
            new String[] {
              "promotion_judge",
              "go",
              "--server",
              where(),
              "--workspace",
              "/no/such/directory/on/this/machine"
            },
            printed.stream(),
            complaints.stream());

    assertEquals(2, status);
    assertTrue(
        complaints.text().contains("which is not a directory on this machine"), complaints.text());
    assertFalse(
        printed.text().contains("lending"),
        "it announced a workspace it had not checked: " + printed.text());
  }

  /**
   * An option where a value should be is a missing argument, and reading it as a value lends a
   * directory named "--project" while silently dropping the project.
   */
  @Test
  void an_option_given_where_a_value_belongs_is_a_missing_argument() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(
            new String[] {"promotion_judge", "go", "--workspace", "--project", "pay"},
            printed.stream(),
            complaints.stream());

    assertEquals(2, status);
    assertTrue(complaints.text().contains("missing argument"), complaints.text());
  }

  /** A usage error is a usage error and never a run: nothing is dialled. */
  @Test
  void a_command_line_with_no_task_prints_the_usage_and_dials_nothing() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(new String[] {"promotion_judge"}, printed.stream(), complaints.stream());

    assertEquals(2, status);
    assertTrue(complaints.text().contains("usage: plowshare"), complaints.text());
    assertTrue(seen.isEmpty(), "a malformed command line reached the server: " + paths());
  }

  // --- fixtures --------------------------------------------------------------

  /** A nanosecond reading {@code n} seconds after a Heartbeat's origin of 0. */
  private static long seconds(long n) {
    return Duration.ofSeconds(n).toNanos();
  }

  private JobEvent next() throws InterruptedException {
    JobEvent event = session.nextEvent(PATIENCE);
    assertNotNull(event, "no event arrived within " + PATIENCE);
    return event;
  }

  /**
   * Sent and flushed before the test goes on, so ordering assertions are about the client and not
   * about okhttp's writer.
   */
  private void sendEvent(String frame) throws InterruptedException {
    // The client can observe the upgrade before MockWebServer's onOpen
    // callback has published its end of the socket.
    await(() -> serverEvents != null, "the server event socket never opened");
    assertTrue(serverEvents.send(frame), "the server could not send a frame: " + frame);
  }

  private boolean dialed(String path) {
    return seen.stream().anyMatch(request -> path.equals(request.getPath()));
  }

  private RecordedRequest requestFor(String path) {
    return seen.stream()
        .filter(request -> path.equals(request.getPath()))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("the server never saw " + path + "; it saw " + paths()));
  }

  private List<String> paths() {
    return seen.stream().map(RecordedRequest::getPath).toList();
  }

  private static void await(BooleanSupplier condition, String complaint)
      throws InterruptedException {
    for (long waited = 0; waited < PATIENCE.toMillis(); waited += 10) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(complaint + " within " + PATIENCE);
  }

  /** Where the mock server is, as the terminal's --server argument. */
  private String where() {
    return server.url("/").toString();
  }

  /**
   * A PrintStream and the bytes it collected, so an assertion can quote the whole transcript when
   * it fails rather than only the line it looked at.
   */
  private static final class Transcript {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    PrintStream stream() {
      return stream;
    }

    String text() {
      stream.flush();
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  /**
   * The server's end of one socket.
   *
   * <p>{@code onClosing} answers the close, and it is not decoration: without it the handshake
   * never completes, the connection stays open, and {@link MockWebServer#shutdown()} fails every
   * test in this class with "Gave up waiting for queue to shut down" <b>after the assertions have
   * passed</b>. Measured here, and it is the same okhttp behaviour {@code ChannelClient} records
   * from the other side — a listener that only observes a close leaves a half-closed socket behind.
   */
  private static MockResponse upgrade(Held held) {
    return new MockResponse()
        .withWebSocketUpgrade(
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket socket, Response response) {
                held.accept(socket);
              }

              @Override
              public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, null);
              }
            });
  }

  /**
   * Named rather than a {@code Consumer} so the assignment it performs reads as "this test now
   * holds the server's end of that socket".
   */
  @FunctionalInterface
  private interface Held {
    void accept(WebSocket socket);
  }
}
