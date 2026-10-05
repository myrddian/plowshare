package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.ws.EventChannelHandler;
import io.aeyer.plowshare.server.ws.FileChannelHandler;
import io.aeyer.plowshare.server.ws.FrameRouter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * What a running job says about itself, and everything it must never say.
 *
 * <h2>The containment tests here are not tests to narrow</h2>
 *
 * <p>Three of them assert that a distinctive string reaching the runtime — in a tool call's
 * arguments, in an exception's message, and in the model's own prose — appears in <b>no event</b>.
 * If any fails, the code is wrong. They are written against the <em>serialised</em> event rather
 * than against the fields anybody thought of, so a field added later that carries the string is
 * caught by the assertion that already exists rather than by one somebody remembers to add.
 *
 * <p>All three are measured against a mutant rather than trusted. Each mutant is one line,
 * compiles, and is the change somebody would genuinely make:
 *
 * <ul>
 *   <li><b>"show what it is doing":</b> {@code JobRuntime} publishing {@code tool.schema().name() +
 *       " " + wanted.arguments()}. {@code a_tool_call_s_arguments_never_reach_an_event} fails at
 *       {@code assertNoEventHolds}, reporting the whole event — {@code "tool":"probe_write
 *       {\"path\":...,\"content\":\"<the marker>\"}"}. {@code a_tool_is_named_when_it_is_called}
 *       fails too, on the name;
 *   <li><b>"say why it stopped":</b> {@code JobRuntime} publishing {@code
 *       watch.toolCalled(definition.name(), describe(unreachable))} in the {@code catch
 *       (LlmException)} around the dispatcher call. {@code
 *       a_failure_s_message_never_reaches_an_event} fails at {@code assertNoEventHolds}, on an
 *       event whose tool reads {@code "LlmException: Malformed API token provided: <the marker>"}.
 *       {@code a_model_call_is_reported_as_spent_before_it_is_made} fails too, on the extra kind in
 *       the sequence;
 *   <li><b>"stream the answer":</b> {@code JobRuntime} publishing {@code
 *       watch.toolCalled(definition.name(), completion.content())} — and {@link
 *       JobWatch#toolCalled} is the mutant's natural home precisely because it is the <em>only</em>
 *       one of the four that takes a string a caller chooses, which is why it is documented as
 *       taking a name. Put in the {@code ANSWERED} branch it fails {@code
 *       a_model_s_prose_never_reaches_an_event} on the answer; put after the assistant message is
 *       appended it fails the same assertion on the working. Both were run.
 * </ul>
 *
 * <p>Each also asserts that the marker really did reach the run, which is what stops a green result
 * meaning the fixture never carried one — and in the second case that {@code Outcome.detail()}
 * <em>does</em> hold it, since a run whose failure text had been sanitised upstream would prove
 * nothing about this stream.
 *
 * <h2>What is faked and what is real</h2>
 *
 * <p>The registry, the handler, the store and the runtime are all the production classes. What is
 * faked is the two ends: an {@link LlmTransport} that says what a test needs it to say, and a
 * {@link WebSocketSession} — because the property that matters most about delivery is what happens
 * when a socket <em>does not complete a write</em>, and no real client can be made to stop reading
 * on demand. {@code EventChannelTest} is where a real socket over a real port is driven, and {@link
 * #a_job_s_events_reach_a_listener_as_json} keeps this file honest about the frame that arrives.
 */
class JobEventTest {

  /**
   * A string no part of this system would ever produce, so finding it anywhere is finding the thing
   * that carried it. Not an API key and not shaped like one: {@code InvariantsTest} fails a build
   * that holds the prefix an LM Studio key starts with, including one written into a test that is
   * trying to prove keys do not leak.
   */
  private static final String MARKER = "QQZX-do-not-repeat-this-9137";

  /**
   * Long enough that a loaded machine does not fail the suite, short enough that a wedged test
   * fails rather than hangs.
   */
  private static final long PATIENCE_MILLIS = 10_000;

  /**
   * How long the delivered count must hold still before it is believed. Long enough that a drain
   * thread between two takes is not read as finished, and short enough that a settled queue costs
   * one of these rather than PATIENCE_MILLIS.
   */
  private static final long SETTLE_MILLIS = 100;

  /**
   * The event channel now takes a {@link FrameRouter} as well as a registry, because a client may
   * send it a request frame and be answered. <b>Nothing in this file sends one</b> — every test
   * here drives the other direction, a job publishing to a listener — so the table is empty and
   * never consulted. Named rather than inlined so a reader meets that fact once instead of six
   * times.
   */
  private static final FrameRouter NO_FRAME_TYPES = new FrameRouter(Map.of());

  /**
   * The heartbeat interval the tests below run their stores at.
   *
   * <p>Fifty milliseconds and not the shipped twenty seconds, for the obvious reason and one less
   * obvious: every <em>other</em> test in this file builds a store on the default, so a run that
   * takes a few milliseconds to answer is nowhere near its first beat and the exact kind sequences
   * those tests assert on are undisturbed. That is a property of the default rather than luck —
   * {@code JobStore.HEARTBEAT} says so from the other side.
   */
  private static final Duration BEAT = Duration.ofMillis(50);

  private static final ObjectMapper JSON = new ObjectMapper();

  // --- the event set -----------------------------------------------------------

  @Test
  void a_job_that_answers_says_it_started_spent_a_call_and_ended() throws Exception {
    Recorder recorder = new Recorder();
    Scripted transport = new Scripted().thenAlways(() -> answer("the codename is Excalibur"));

    String job = runToCompletion(recorder, transport, agent("echo", List.of()), "sess-1");

    assertEquals(
        List.of(JobEvent.STARTED, JobEvent.MODEL_CALL, JobEvent.ENDED),
        recorder.kinds(),
        "the lifecycle of a job that answers on its first turn, in order");
    JobEvent ended = recorder.last();
    assertEquals(
        Ending.ANSWERED.name(),
        ended.ending(),
        "the ending travels as the constant's name, so a client built against a later"
            + " server binds a word it has never heard of rather than failing");
    assertEquals(1, ended.steps());
    assertEquals(1, ended.modelCalls());
    assertTrue(
        recorder.events().stream().allMatch(event -> job.equals(event.job())),
        "every event names the job it belongs to — a session may be running several");
    assertTrue(
        recorder.events().stream().allMatch(event -> "echo".equals(event.agent())),
        "and names the agent, so a listener that attached late still knows what it is"
            + " watching");
  }

  @Test
  void a_model_call_is_reported_as_spent_before_it_is_made() throws Exception {
    // The budget is claimed BEFORE the call, and Outcome.modelCalls counts a
    // call that failed. An event set that reported a call only once it
    // returned would disagree with the number the same run files, which is
    // the drift a second vocabulary produces. So: a run whose only model
    // call throws still reports one model call, in both places.
    Recorder recorder = new Recorder();
    Scripted transport =
        new Scripted()
            .thenAlways(
                () -> {
                  throw new LlmException("the endpoint is not there");
                });

    runToCompletion(recorder, transport, agent("echo", List.of()), "sess-2");

    assertEquals(
        List.of(JobEvent.STARTED, JobEvent.MODEL_CALL, JobEvent.ENDED),
        recorder.kinds(),
        "the call was announced although it never came back");
    JobEvent spent = recorder.ofKind(JobEvent.MODEL_CALL).get(0);
    assertEquals(1, spent.modelCalls());
    assertEquals(0, spent.steps(), "no turn had completed when the call was claimed");
    assertEquals(
        1,
        recorder.last().modelCalls(),
        "and the ending agrees with it, because both count what Outcome counts");
    assertEquals(
        0,
        recorder.last().steps(),
        "a call that threw completed no turn — JobRuntime's own definition");
  }

  @Test
  void a_tool_is_named_when_it_is_called() throws Exception {
    Recorder recorder = new Recorder();
    Probe probe = new Probe("probe_write");
    Scripted transport =
        new Scripted()
            .then(() -> asking("writing", new ToolCall("c1", "probe_write", "{\"n\":1}")))
            .thenAlways(() -> answer("done"));

    runToCompletion(recorder, transport, agent("writer", List.of("probe_write")), "sess-3", probe);

    assertEquals(
        List.of(
            JobEvent.STARTED,
            JobEvent.MODEL_CALL,
            JobEvent.TOOL_CALLED,
            JobEvent.MODEL_CALL,
            JobEvent.ENDED),
        recorder.kinds(),
        "a tool call ends a turn and not the job, and the stream says so");
    assertEquals(
        List.of("probe_write"),
        recorder.ofKind(JobEvent.TOOL_CALLED).stream().map(JobEvent::tool).toList());
    assertEquals(2, recorder.last().steps(), "two steps completed");
  }

  @Test
  void a_tool_name_the_model_invented_is_not_an_event() throws Exception {
    // The tool in an event is the name THIS SERVER registered, read off the
    // tool's own schema — so the string can only ever be one of a closed set
    // this server owns. A model that asks for something that is not there
    // gets a tool result naming what it may call, and the listener is told
    // nothing, because the only string available to tell it with would be
    // the model's invention.
    Recorder recorder = new Recorder();
    Scripted transport =
        new Scripted()
            .then(() -> asking("guessing", new ToolCall("c1", "probe_" + MARKER, "{}")))
            .thenAlways(() -> answer("fine, I will answer"));

    runToCompletion(recorder, transport, agent("echo", List.of()), "sess-4");

    assertEquals(
        List.of(), recorder.ofKind(JobEvent.TOOL_CALLED), "no tool ran, so no tool was named");
    assertNoEventHolds(
        recorder, MARKER, "a tool name a model invented is a string the model chose");
    assertEquals(
        Ending.ANSWERED.name(),
        recorder.last().ending(),
        "and the run carried on, because an unknown tool is a tool result");
  }

  // --- containment -------------------------------------------------------------

  @Test
  void a_tool_call_s_arguments_never_reach_an_event() throws Exception {
    // THE containment test. file_write is handed the content it is about to
    // write, so an event echoing arguments is a second, unaudited copy of
    // the file channel — one that bypasses every check FileAccess performs
    // and that a browser could read. Do not narrow this test.
    Recorder recorder = new Recorder();
    Probe probe = new Probe("probe_write");
    String arguments = "{\"path\":\"/home/operator/notes.md\",\"content\":\"" + MARKER + "\"}";
    Scripted transport =
        new Scripted()
            .then(() -> asking("writing", new ToolCall("c1", "probe_write", arguments)))
            .thenAlways(() -> answer("written"));

    runToCompletion(recorder, transport, agent("writer", List.of("probe_write")), "sess-5", probe);

    // The fixture really did carry it: without this the test could pass over
    // a run in which no tool was ever called with anything.
    assertEquals(
        List.of(arguments),
        probe.seen(),
        "the tool was handed the marker, so there was something to leak");
    // The containment assertions BEFORE the exact-name one, and the order is
    // load-bearing rather than tidy. Measured: with JobRuntime appending
    // " " + wanted.arguments() to the published name, the equality below
    // fails too — so with it first, the test that is cited as the
    // containment instrument would never reach the assertion it is cited
    // for, and the sentence naming the marker would be one no run had
    // produced. Trap five, inside the test written against it.
    assertNoEventHolds(
        recorder, MARKER, "an event names the tool and never replays what it was handed");
    assertNoEventHolds(
        recorder,
        "/home/operator/notes.md",
        "a path is as much the client's business as the content is");
    assertEquals(
        List.of("probe_write"),
        recorder.ofKind(JobEvent.TOOL_CALLED).stream().map(JobEvent::tool).toList(),
        "and the tool was still named");
  }

  @Test
  void a_failure_s_message_never_reaches_an_event() throws Exception {
    // The second route, and it is not hypothetical: OpenAiTransport carries
    // withheldIfItQuotesTheKey because the LM Studio endpoint really does
    // echo a submitted token back in its error body. Scribe settled the
    // mitigation — record the exception's TYPE and never its message,
    // because a transport-layer message can carry a URL, a header or a
    // request body — and an event goes further and carries neither.
    Recorder recorder = new Recorder();
    Scripted transport =
        new Scripted()
            .thenAlways(
                () -> {
                  throw new LlmException("Malformed API token provided: " + MARKER);
                });

    String job = runToCompletion(recorder, transport, agent("echo", List.of()), "sess-6");

    assertEquals(Ending.UNAVAILABLE.name(), recorder.last().ending());
    assertNoEventHolds(
        recorder, MARKER, "a transport's own words are not a thing a job says about itself");
    assertNoEventHolds(
        recorder,
        "LlmException",
        "not even the type: the ending already says which kind of stop this was");
    assertTrue(
        outcomes.get(job).detail().contains(MARKER),
        "and the marker really was in play — the contractual record behind"
            + " GET /v1/jobs/{id} does carry it, which is the whole reason the"
            + " stream must not");
  }

  @Test
  void a_model_s_prose_never_reaches_an_event() throws Exception {
    // The third route JobEvent's javadoc names — "the model's answer. Not
    // carried" — and the only one of the three that had no instrument until
    // slice 3e went looking for one. It is the guarantee token streaming
    // strains, so it is measured here rather than left standing on the four
    // signatures alone.
    //
    // Two markers, because the two halves of a turn's prose are disclosed
    // differently and the difference is the whole streaming argument:
    //
    //   the answer     JobRuntime's ANSWERED branch makes it Outcome.text,
    //                  so GET /v1/jobs/{id} returns it. Streaming it would
    //                  change WHEN it is disclosed;
    //   the working    every earlier call's content goes into history beside
    //                  its tool calls, and no endpoint returns it. There is
    //                  no "already discloses it" to point at, so streaming
    //                  it would be a NEW disclosure — of prose that has just
    //                  read a file and is saying what it found.
    //
    // "Within a turn" is doing work in that second row and is not padding. A
    // COMPACTION summary is also prose from a call that was not a turn's
    // last, and it IS published — GET /v1/conversations/{id}/compactions,
    // which this slice added on purpose, because a seam nobody can read is
    // not a mitigation. That is a disclosure somebody chose. A turn's working
    // is not, and this test is about a turn.
    //
    // A stream cannot tell them apart in advance: a call is the last one
    // because no tool calls came back with it, which is known only once it
    // has.
    Recorder recorder = new Recorder();
    Probe probe = new Probe("probe_read");
    String working = "QQZX-mid-turn-working-6620";
    String answer = "QQZX-the-final-answer-8085";
    Scripted transport =
        new Scripted()
            .then(() -> asking("let me look. " + working, new ToolCall("c1", "probe_read", "")))
            .thenAlways(() -> answer("the codename is " + answer));

    String job =
        runToCompletion(
            recorder, transport, agent("reader", List.of("probe_read")), "sess-7", probe);

    // The fixture really did make two calls with prose on both, so there was
    // something to leak from each half. Without this the test could pass
    // over a run that never reached its second call.
    assertEquals(1, probe.seen().size(), "the intermediate call really happened");
    assertEquals(2, outcomes.get(job).steps(), "so the run had a working half and an answer");

    assertNoEventHolds(
        recorder,
        working,
        "no endpoint returns a turn's working, so an event carrying it would"
            + " be a new disclosure and not an earlier one");
    assertNoEventHolds(
        recorder, answer, "and the answer is the job endpoint's to give, at the time it gives it");

    // The two disclosures, asserted in opposite directions. Together they are
    // the sentence the streaming decision had to be able to say truthfully
    // about every byte, and the measurement that it cannot be.
    assertTrue(
        outcomes.get(job).text().contains(answer),
        "the answer really was in play — GET /v1/jobs/{id} does carry it");
    assertFalse(
        outcomes.get(job).text().contains(working),
        "and the working is not on that endpoint either, so there is no disclosure for"
            + " a stream of it to be the earlier half of");
  }

  // --- who gets them -----------------------------------------------------------

  @Test
  void an_event_reaches_the_listener_on_the_job_s_own_session_and_no_other() throws Exception {
    SessionRegistry registry = new SessionRegistry();
    EventChannelHandler handler = new EventChannelHandler(registry, NO_FRAME_TYPES, new Watchers());
    Collecting mine = attachListener(handler, registry, "mine");
    Collecting theirs = attachListener(handler, registry, "theirs");

    handler.publish("mine", JobEvent.started("job_000001", "echo"));

    assertEquals(1, mine.await(1, PATIENCE_MILLIS).size());
    assertTrue(
        theirs.frames().isEmpty(),
        "a session's events are that session's; anything else is one client watching"
            + " another's work");
  }

  @Test
  void a_displaced_listener_stops_receiving_and_its_replacement_starts() throws Exception {
    // Displacement is settled one layer down and delivery is not, so the
    // intersection needs its own instrument: a queue and a thread now hang
    // off each connection, and events have to follow the socket a human is
    // actually looking at.
    //
    // WHAT THIS DOES NOT MEASURE, said rather than left to be assumed: that
    // the loser's drain thread stops. Nothing outside the handler can reach
    // a displaced connection's queue, and the thread is not enumerable
    // either — measured on JBR 21.0.8, a live virtual thread parked in
    // sleep does not appear in Thread.getAllStackTraces, which returns
    // platform threads. So stopDelivery on the displaced socket rests on
    // being the same call afterConnectionClosed makes, which every close
    // here takes.
    SessionRegistry registry = new SessionRegistry();
    EventChannelHandler handler = new EventChannelHandler(registry, NO_FRAME_TYPES, new Watchers());
    Collecting first = attachListener(handler, registry, "reconnecting");
    Collecting second = attachListener(handler, registry, "reconnecting");

    handler.publish("reconnecting", JobEvent.started("job_000001", "echo"));

    assertEquals(
        1,
        second.await(1, PATIENCE_MILLIS).size(),
        "the newer connection is the session's listener now");
    assertTrue(first.frames().isEmpty(), "and the displaced one receives nothing further");
    assertFalse(first.isOpen(), "having been closed by the connection that displaced it");
  }

  @Test
  void a_session_with_no_listener_drops_the_event_and_a_null_one_is_ordinary() throws Exception {
    SessionRegistry registry = new SessionRegistry();
    EventChannelHandler handler = new EventChannelHandler(registry, NO_FRAME_TYPES, new Watchers());
    registry.attach("watched", Role.FILE_PROVIDER, "a stand-in for a file channel");

    // Three shapes of "nobody is listening", and all three are ordinary: a
    // job with no session at all, an id nothing ever attached to, and a
    // session holding the other role. None of them is an error and none of
    // them stops anything.
    handler.publish(null, JobEvent.started("job_000001", "echo"));
    handler.publish("never-seen", JobEvent.started("job_000002", "echo"));
    handler.publish("watched", JobEvent.started("job_000003", "echo"));

    assertFalse(
        registry.find("never-seen").isPresent(),
        "and publishing did not invent a session to publish to");
  }

  @Test
  void a_job_with_no_session_still_runs_and_files_its_outcome() throws Exception {
    Recorder recorder = new Recorder();
    Scripted transport = new Scripted().thenAlways(() -> answer("done"));

    String job = runToCompletion(recorder, transport, agent("echo", List.of()), null);

    assertEquals(Ending.ANSWERED, outcomes.get(job).ending());
    assertTrue(
        recorder.sessions().stream().allMatch(session -> session == null),
        "the runtime carries the session it was given and interprets nothing: whether"
            + " a null one reaches anybody is the channel's question");
  }

  // --- the stream is droppable, and the job is not -----------------------------

  @Test
  void a_listener_that_never_finishes_a_write_does_not_hold_a_job_up() throws Exception {
    // A job whose progress depends on a socket an operator closed is a job
    // held hostage by a user interface. Tomcat's blocking send bounds itself
    // at twenty seconds, which is bounded and is still twenty seconds of a
    // turn — so publish must not be where the write happens.
    SessionRegistry registry = new SessionRegistry();
    EventChannelHandler handler = new EventChannelHandler(registry, NO_FRAME_TYPES, new Watchers());
    CountDownLatch wedged = new CountDownLatch(1);
    CountDownLatch releaseTheSocket = new CountDownLatch(1);
    Collecting stalled = new Collecting(wedged, releaseTheSocket);
    attach(handler, registry, "stalled", stalled);

    try (JobStore store =
        new JobStore(runtimeOver(new Scripted().thenAlways(() -> answer("done"))), handler)) {
      // The first write is in progress and will not complete.
      handler.publish("stalled", JobEvent.started("job_warmup", "echo"));
      assertTrue(
          wedged.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS),
          "the fake socket is inside a write that never returns");

      long before = System.nanoTime();
      String id =
          store.submit(agent("echo", List.of()), "which programme?", Home.global(), "stalled");
      Outcome outcome = await(store, id);
      long millis = Duration.ofNanos(System.nanoTime() - before).toMillis();

      assertEquals(
          Ending.ANSWERED,
          outcome.ending(),
          "the job ran to its answer with its listener's socket wedged");
      assertTrue(
          millis < 5_000,
          "and did not wait on it: "
              + millis
              + "ms, against a blocking send this"
              + " process bounds at twenty seconds");
    } finally {
      releaseTheSocket.countDown();
    }
  }

  @Test
  void a_burst_bigger_than_the_queue_is_dropped_rather_than_waited_on() throws Exception {
    SessionRegistry registry = new SessionRegistry();
    EventChannelHandler handler = new EventChannelHandler(registry, NO_FRAME_TYPES, new Watchers());
    CountDownLatch wedged = new CountDownLatch(1);
    CountDownLatch releaseTheSocket = new CountDownLatch(1);
    Collecting stalled = new Collecting(wedged, releaseTheSocket);
    attach(handler, registry, "flooded", stalled);
    handler.publish("flooded", JobEvent.started("job_warmup", "echo"));
    assertTrue(wedged.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS));

    long before = System.nanoTime();
    for (int nth = 0; nth < EventChannelHandler.PENDING * 4; nth++) {
      handler.publish("flooded", JobEvent.modelCall("job_000001", "echo", nth, nth));
    }
    long millis = Duration.ofNanos(System.nanoTime() - before).toMillis();

    try {
      assertTrue(
          millis < 2_000,
          "four queues' worth of events over a socket that is not draining took "
              + millis
              + "ms; publishing must never wait on a listener");
      // The count, and not only the clock. THE CLOCK ALONE MEASURES
      // NOTHING HERE: offer() never blocks, so an unbounded queue publishes
      // just as fast and the assertion above passes over a queue that grew
      // to four times its bound. What separates the two is how many
      // events survive to reach the socket, and asking that needs the
      // socket released and the drain finished.
      releaseTheSocket.countDown();
      int delivered = settled(stalled).size();
      assertTrue(
          delivered > 1,
          "nothing was delivered at all after the socket was released, so this"
              + " measures a broken fake rather than a bounded queue");
      assertTrue(
          delivered <= EventChannelHandler.PENDING + 1,
          delivered
              + " events reached a listener that was given "
              + (EventChannelHandler.PENDING * 4)
              + " while its socket was held"
              + " open, against a queue of "
              + EventChannelHandler.PENDING
              + " plus the one already in flight. The stream is droppable: a"
              + " listener further behind than the bound loses events rather"
              + " than growing the queue.");
    } finally {
      releaseTheSocket.countDown();
    }
  }

  /**
   * The frames a listener has, once it has stopped getting more.
   *
   * <p>Polls until the count holds still rather than waiting for a number, because the number is
   * what is under test: {@code Collecting.await} would need to be told how many to expect, and
   * being told is how a test stops measuring.
   */
  private static List<String> settled(Collecting listener) throws InterruptedException {
    int previous = -1;
    for (long waited = 0; waited < PATIENCE_MILLIS; waited += SETTLE_MILLIS) {
      int now = listener.frames().size();
      if (now == previous) {
        return listener.frames();
      }
      previous = now;
      Thread.sleep(SETTLE_MILLIS);
    }
    return listener.frames();
  }

  @Test
  void a_publisher_that_throws_does_not_end_a_run() throws Exception {
    // The stream is droppable and the run is not. A seam that throws is a
    // bug in the seam, and a run that died of one would be a job lost to a
    // user interface.
    AtomicInteger asked = new AtomicInteger();
    JobEvents broken =
        (session, event) -> {
          asked.incrementAndGet();
          throw new IllegalStateException("this publisher is broken");
        };
    try (JobStore store =
        new JobStore(runtimeOver(new Scripted().thenAlways(() -> answer("done"))), broken)) {
      String id =
          store.submit(agent("echo", List.of()), "which programme?", Home.global(), "sess-7");

      assertEquals(Ending.ANSWERED, await(store, id).ending());
      assertTrue(asked.get() >= 3, "and every event was still attempted: " + asked.get());
    }
  }

  // --- delivery ----------------------------------------------------------------

  @Test
  void a_job_s_events_reach_a_listener_as_json() throws Exception {
    SessionRegistry registry = new SessionRegistry();
    EventChannelHandler handler = new EventChannelHandler(registry, NO_FRAME_TYPES, new Watchers());
    Collecting listening = attachListener(handler, registry, "reading");

    try (JobStore store =
        new JobStore(runtimeOver(new Scripted().thenAlways(() -> answer("done"))), handler)) {
      String id =
          store.submit(agent("echo", List.of()), "which programme?", Home.global(), "reading");
      await(store, id);

      List<String> frames = listening.await(3, PATIENCE_MILLIS);
      List<JobEvent> read = new ArrayList<>();
      for (String frame : frames) {
        read.add(JSON.readValue(frame, JobEvent.class));
      }
      assertEquals(
          List.of(JobEvent.STARTED, JobEvent.MODEL_CALL, JobEvent.ENDED),
          read.stream().map(JobEvent::kind).toList(),
          "in the order the run produced them: one drain thread per listener, so a"
              + " burst cannot arrive out of order");
      assertEquals(id, read.get(0).job());
      assertEquals(Ending.ANSWERED.name(), read.get(2).ending());
    }
  }

  @Test
  void two_jobs_on_one_session_are_told_apart_by_their_ids() throws Exception {
    Recorder recorder = new Recorder();
    try (JobStore store =
        new JobStore(runtimeOver(new Scripted().thenAlways(() -> answer("done"))), recorder)) {
      String first = store.submit(agent("echo", List.of()), "one", Home.global(), "shared");
      await(store, first);
      String second = store.submit(agent("echo", List.of()), "two", Home.global(), "shared");
      await(store, second);

      assertNotEquals(first, second);
      assertEquals(3, recorder.forJob(first).size());
      assertEquals(3, recorder.forJob(second).size());
      assertEquals(
          recorder.events().size(),
          recorder.forJob(first).size() + recorder.forJob(second).size(),
          "every event belongs to one of the two, so a listener watching one is not"
              + " reading an interleaving of both");
    }
  }

  @Test
  void a_run_the_store_did_not_start_is_not_watched() throws Exception {
    // The unwatched overload, measured rather than asserted structurally.
    // The store's own two events are published because the store minted the
    // id and filed the outcome; the run inside makes a model call and says
    // nothing, because nothing handed it a watch. That is the same shape a
    // delegated child takes — AgentRunTool calls the same overload — and it
    // is why a child cannot publish an ENDED that a listener would read as
    // its parent's.
    Recorder recorder = new Recorder();
    JobRuntime runtime = runtimeOver(new Scripted().thenAlways(() -> answer("done")));

    try (JobStore store = new JobStore(runtime, recorder)) {
      String id =
          store.submit(
              "a curator pass",
              cancelled ->
                  runtime.run(agent("echo", List.of()), "one", Home.global(), Budget.of(10), null));
      Outcome outcome = await(store, id);

      assertEquals(Ending.ANSWERED, outcome.ending());
      assertEquals(1, outcome.modelCalls(), "the inner run really did call the model");
      assertEquals(
          List.of(JobEvent.STARTED, JobEvent.ENDED),
          recorder.kinds(),
          "and said nothing about it: an unwatched run publishes nothing, so the"
              + " only events are the two the store owns");
      assertEquals("a curator pass", recorder.last().agent());
      assertTrue(
          recorder.sessions().stream().allMatch(session -> session == null),
          "this door takes the work itself and has nowhere for a session to reach");
    }
  }

  // --- a job in flight says so -------------------------------------------------

  @Test
  void a_job_inside_a_model_call_keeps_saying_it_is_alive() throws Exception {
    // The measurement this whole slice comes from: a turn ran twelve minutes
    // and the socket carried STARTED, MODEL_CALL and then nothing until
    // ENDED. The model call is where a run spends its time, so it is where
    // the silence has to stop being indistinguishable from a dead server.
    Recorder recorder = new Recorder();
    CountDownLatch insideTheCall = new CountDownLatch(1);
    CountDownLatch releaseTheModel = new CountDownLatch(1);
    Scripted transport =
        new Scripted()
            .thenAlways(
                () -> {
                  insideTheCall.countDown();
                  held(releaseTheModel);
                  return answer("the codename is Excalibur");
                });

    try (JobStore store = new JobStore(runtimeOver(transport), recorder, null, null, BEAT)) {
      String id =
          store.submit(agent("echo", List.of()), "which programme?", Home.global(), "sess-alive");
      assertTrue(
          insideTheCall.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS),
          "the run never reached the model call this test holds it inside");

      List<JobEvent> beats = recorder.awaitOfKind(JobEvent.ALIVE, 3, PATIENCE_MILLIS);

      assertTrue(
          beats.stream().allMatch(beat -> id.equals(beat.job())),
          "a beat names its job, because a session may be running several");
      assertTrue(
          beats.stream().allMatch(beat -> "echo".equals(beat.agent())),
          "and the agent, so a listener that attached late knows what is alive");
      assertTrue(
          beats.stream().allMatch(beat -> beat.tool() == null),
          "a heartbeat is not a thing that happened, so it names no tool");
      assertTrue(beats.stream().allMatch(beat -> beat.ending() == null), "and it is not an ending");
      releaseTheModel.countDown();
      Outcome outcome = await(store, id);

      assertEquals(
          Ending.ANSWERED, outcome.ending(), "and the run that was saying so went on to answer");
      assertEquals(JobEvent.STARTED, recorder.kinds().get(0));
      assertEquals(
          JobEvent.MODEL_CALL,
          recorder.kinds().get(1),
          "the beats come after the call was claimed, not before it");
      assertEquals(JobEvent.ENDED, recorder.last().kind());
    }
  }

  @Test
  void a_heartbeat_carries_the_progress_the_run_has_reached() throws Exception {
    // A person watching a spinner learns nothing; a person watching two
    // numbers move learns that the run is getting somewhere. The counts are
    // the ones MODEL_CALL and ENDED already report -- claimed calls, and
    // steps completed -- rather than a third vocabulary for the same run.
    Recorder recorder = new Recorder();
    Probe probe = new Probe("probe_read");
    CountDownLatch insideTheSecondCall = new CountDownLatch(1);
    CountDownLatch releaseTheModel = new CountDownLatch(1);
    Scripted transport =
        new Scripted()
            .then(() -> asking("let me look", new ToolCall("c1", "probe_read", "{}")))
            .thenAlways(
                () -> {
                  insideTheSecondCall.countDown();
                  held(releaseTheModel);
                  return answer("done");
                });

    try (JobStore store = new JobStore(runtimeOver(transport, probe), recorder, null, null, BEAT)) {
      String id =
          store.submit(
              agent("reader", List.of("probe_read")),
              "which programme?",
              Home.global(),
              "sess-progress");
      assertTrue(
          insideTheSecondCall.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS),
          "the run never reached its second model call");

      List<JobEvent> beats = recorder.awaitOfKind(JobEvent.ALIVE, 2, PATIENCE_MILLIS);

      JobEvent beat = beats.get(beats.size() - 1);
      assertEquals(
          2, beat.modelCalls(), "two calls had been claimed by the time this beat was sent");
      assertEquals(
          1,
          beat.steps(),
          "and one step had completed: the call that asked for the tool, plus the"
              + " tool result it wanted");
      releaseTheModel.countDown();
      await(store, id);
      assertEquals(1, probe.seen().size(), "the fixture really did run its tool");
    }
  }

  @Test
  void a_finished_job_stops_beating_and_leaves_no_timer_behind() throws Exception {
    // THE test for the natural bug: a timer that outlives the job it was
    // started for and beats for ever. Silence alone would not catch it -- a
    // leaked timer whose publishing is merely suppressed is still a leaked
    // timer -- so this asserts both halves: nothing is published after the
    // ending, and the store has nothing left scheduled.
    //
    // The run is HELD FIRST so that beats are known to have started. Without
    // that, a green result here would also be what a heartbeat that never
    // beats at all looks like.
    //
    // Both halves were measured against a mutant rather than trusted, and
    // each catches one the other does not:
    //
    //   forget to stop it   JobStore.beat publishing unconditionally, with
    //                       the cancel in start's finally disabled -- the
    //                       bug somebody actually writes. The count fails:
    //                       "something was still publishing: [alive, alive,
    //                       ... ] expected: <5> but was: <14>";
    //   stop it quietly     the state check kept, both cancels disabled, so
    //                       the timer wakes every interval for the life of
    //                       the process and publishes nothing. Silence is
    //                       green; beating() fails, expected <0> but was
    //                       <1>.
    //
    // Disabling only the finally passes, and that is correct rather than a
    // hole: the task's own state check cancels it on the first wake after
    // the job finished, so no timer outlives its job. The test is written
    // against that property and not against either mechanism.
    Recorder recorder = new Recorder();
    CountDownLatch insideTheCall = new CountDownLatch(1);
    CountDownLatch releaseTheModel = new CountDownLatch(1);
    Scripted transport =
        new Scripted()
            .thenAlways(
                () -> {
                  insideTheCall.countDown();
                  held(releaseTheModel);
                  return answer("done");
                });

    try (JobStore store = new JobStore(runtimeOver(transport), recorder, null, null, BEAT)) {
      String id =
          store.submit(agent("echo", List.of()), "which programme?", Home.global(), "sess-stops");
      assertTrue(insideTheCall.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS));
      recorder.awaitOfKind(JobEvent.ALIVE, 2, PATIENCE_MILLIS);
      releaseTheModel.countDown();
      await(store, id);

      int whenItEnded = recorder.events().size();
      assertEquals(
          JobEvent.ENDED, recorder.last().kind(), "the ending is the last thing a job says");
      Thread.sleep(BEAT.toMillis() * 10);

      assertEquals(
          whenItEnded,
          recorder.events().size(),
          "ten intervals after the job finished, something was still publishing: "
              + recorder
                  .kinds()
                  .subList(
                      Math.min(whenItEnded, recorder.events().size()), recorder.events().size()));
      assertEquals(
          JobEvent.ENDED,
          recorder.last().kind(),
          "a finished job says nothing further, and ENDED stays the last event a"
              + " listener saw");
      assertEquals(
          0,
          store.beating(),
          "the job is done and its heartbeat is still scheduled, which is a timer"
              + " outliving its job whether or not anything is listening");
    }
  }

  @Test
  void an_interval_no_run_lives_long_enough_to_reach_produces_no_heartbeat() throws Exception {
    // The other direction, and the reason the interval is a number somebody
    // chose rather than "as often as possible": a beat is traffic on every
    // listener's socket, so nothing is sent until a run has been quiet for
    // one whole interval.
    Recorder recorder = new Recorder();
    CountDownLatch insideTheCall = new CountDownLatch(1);
    CountDownLatch releaseTheModel = new CountDownLatch(1);
    Scripted transport =
        new Scripted()
            .thenAlways(
                () -> {
                  insideTheCall.countDown();
                  held(releaseTheModel);
                  return answer("done");
                });

    try (JobStore store =
        new JobStore(runtimeOver(transport), recorder, null, null, Duration.ofSeconds(30))) {
      String id =
          store.submit(agent("echo", List.of()), "which programme?", Home.global(), "sess-quiet");
      assertTrue(insideTheCall.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS));
      Thread.sleep(BEAT.toMillis() * 6);

      assertEquals(
          List.of(),
          recorder.ofKind(JobEvent.ALIVE),
          "a run three hundred milliseconds into a thirty-second interval has not"
              + " been quiet long enough to be worth saying so");
      releaseTheModel.countDown();
      assertEquals(Ending.ANSWERED, await(store, id).ending());
    }
  }

  @Test
  void the_shipped_interval_is_under_the_minute_an_idle_proxy_cuts_at() {
    // The one number in this slice that cannot be measured from inside the
    // process, pinned so that a later change to it has to come past the
    // reason. Sixty seconds is where a proxy or a load balancer commonly
    // closes a connection with no traffic on it, and the client design's
    // premise is that this server is remote -- so an interval at or above
    // that ceiling is not a quieter heartbeat, it is no heartbeat at all on
    // exactly the deployments this exists for.
    assertTrue(
        JobStore.HEARTBEAT.compareTo(Duration.ofSeconds(60)) < 0,
        "the shipped interval is "
            + JobStore.HEARTBEAT
            + ", at or over the sixty"
            + " seconds an idle connection is commonly cut at");
    assertTrue(
        JobStore.HEARTBEAT.compareTo(Duration.ofSeconds(30)) <= 0,
        "and far enough under it that one dropped beat -- which the queue is entitled"
            + " to drop -- still leaves the socket quiet for less than a minute");
    assertTrue(
        JobStore.HEARTBEAT.compareTo(Duration.ofSeconds(5)) >= 0,
        "but not so low that a long run is a flood of identical frames on every"
            + " listener attached");
  }

  @Test
  void a_heartbeat_a_full_queue_cannot_take_is_dropped_and_the_run_carries_on() throws Exception {
    // A dropped heartbeat is correct in a way a dropped response is not: the
    // next one is along shortly. What must not happen is the other thing --
    // the run waiting on a listener that is not reading, which Tomcat would
    // bound at twenty seconds of a turn.
    SessionRegistry registry = new SessionRegistry();
    EventChannelHandler handler = new EventChannelHandler(registry, NO_FRAME_TYPES, new Watchers());
    CountDownLatch wedged = new CountDownLatch(1);
    CountDownLatch releaseTheSocket = new CountDownLatch(1);
    Collecting stalled = new Collecting(wedged, releaseTheSocket);
    attach(handler, registry, "flooded-beats", stalled);
    handler.publish("flooded-beats", JobEvent.started("job_warmup", "echo"));
    assertTrue(wedged.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS));
    for (int nth = 0; nth < EventChannelHandler.PENDING * 2; nth++) {
      handler.publish("flooded-beats", JobEvent.modelCall("job_000001", "echo", nth, nth));
    }

    CountDownLatch insideTheCall = new CountDownLatch(1);
    CountDownLatch releaseTheModel = new CountDownLatch(1);
    Scripted transport =
        new Scripted()
            .thenAlways(
                () -> {
                  insideTheCall.countDown();
                  held(releaseTheModel);
                  return answer("done");
                });
    try (JobStore store = new JobStore(runtimeOver(transport), handler, null, null, BEAT)) {
      long before = System.nanoTime();
      String id =
          store.submit(
              agent("echo", List.of()), "which programme?", Home.global(), "flooded-beats");
      assertTrue(insideTheCall.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS));
      // Several intervals' worth of beats, every one of them offered to a
      // queue that has been full since before the run started.
      Thread.sleep(BEAT.toMillis() * 6);
      releaseTheModel.countDown();
      Outcome outcome = await(store, id);
      long millis = Duration.ofNanos(System.nanoTime() - before).toMillis();

      assertEquals(
          Ending.ANSWERED,
          outcome.ending(),
          "the run answered with every heartbeat it sent going nowhere");
      assertTrue(
          millis < 5_000,
          "and did not wait on the listener: "
              + millis
              + "ms, against a blocking"
              + " send this process bounds at twenty seconds");
    } finally {
      releaseTheSocket.countDown();
    }
  }

  // --- fixtures ----------------------------------------------------------------

  private final Map<String, Outcome> outcomes = Collections.synchronizedMap(new HashMap<>());

  /**
   * Runs one job through the real {@link JobStore} and returns its id, with the outcome filed in
   * {@link #outcomes} for a test that needs to compare what the stream said against what the record
   * holds.
   */
  private String runToCompletion(
      Recorder recorder,
      Scripted transport,
      AgentDefinition definition,
      String session,
      AgentTool... tools)
      throws Exception {
    try (JobStore store = new JobStore(runtimeOver(transport, tools), recorder)) {
      String id = store.submit(definition, "which programme?", Home.global(), session);
      outcomes.put(id, await(store, id));
      return id;
    }
  }

  /**
   * Holds a scripted model call open until the test lets it go.
   *
   * <p>A {@link Supplier} cannot throw {@link InterruptedException}, so the wait is wrapped here
   * rather than at five call sites. A latch that is never released is a wedged suite, so this fails
   * rather than waits for ever.
   */
  private static void held(CountDownLatch release) {
    try {
      if (!release.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS)) {
        throw new AssertionError("a held model call was never released");
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted inside a held model call", stopped);
    }
  }

  private static Outcome await(JobStore store, String id) throws InterruptedException {
    for (long waited = 0; waited < PATIENCE_MILLIS; waited += 10) {
      Job job = store.get(id);
      if (job.state() == Job.State.DONE) {
        return job.outcome().orElseThrow();
      }
      Thread.sleep(10);
    }
    throw new AssertionError("job " + id + " never finished");
  }

  /**
   * The assertion the containment tests are made of.
   *
   * <p>Over the <b>serialised</b> event and not over the fields this file happens to know about, so
   * a field added later that carries the string fails an assertion that already exists. Trap ten is
   * an assertion shaped to catch exactly one spelling of a reversion; this is the shape that is
   * not.
   */
  private static void assertNoEventHolds(Recorder recorder, String needle, String why)
      throws Exception {
    for (JobEvent event : recorder.events()) {
      String frame = JSON.writeValueAsString(event);
      assertFalse(
          frame.contains(needle),
          why + " — but a '" + event.kind() + "' event carried '" + needle + "': " + frame);
    }
  }

  private Collecting attachListener(
      EventChannelHandler handler, SessionRegistry registry, String session) throws Exception {
    Collecting socket = new Collecting(null, null);
    attach(handler, registry, session, socket);
    return socket;
  }

  /**
   * Opens a connection the way the container would: the handler's own callback, so whatever it
   * hangs off the socket is hung off this one too.
   */
  private void attach(
      EventChannelHandler handler, SessionRegistry registry, String session, Collecting socket)
      throws Exception {
    socket.uri =
        URI.create(
            EventChannelHandler.PATH + "?" + FileChannelHandler.SESSION_PARAM + "=" + session);
    handler.afterConnectionEstablished(socket);
    assertTrue(
        registry.find(session).filter(live -> live.has(Role.LISTENER)).isPresent(),
        "the fake socket attached as a listener");
  }

  private static AgentDefinition agent(String name, List<String> tools) {
    return new AgentDefinition(
        name,
        "a fixture",
        "fast",
        tools,
        List.of(),
        List.of(),
        4,
        8,
        "You answer the question you are given.");
  }

  private static JobRuntime runtimeOver(LlmTransport transport, AgentTool... tools) {
    return new JobRuntime(
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "scripted",
                    List.of("model-fast"),
                    Map.of("fast", "model-fast"),
                    4,
                    1,
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger()),
        List.of(tools));
  }

  private static Completion answer(String content) {
    return new Completion(content, "stop", TokenUsage.UNKNOWN, List.of());
  }

  private static Completion asking(String content, ToolCall... wanted) {
    return new Completion(content, "tool_calls", TokenUsage.UNKNOWN, List.of(wanted));
  }

  /** Every event published, with the session it was published to. */
  private static final class Recorder implements JobEvents {

    private final List<JobEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sessions = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(String session, JobEvent event) {
      sessions.add(session);
      events.add(event);
    }

    List<JobEvent> events() {
      synchronized (events) {
        return List.copyOf(events);
      }
    }

    List<String> sessions() {
      synchronized (sessions) {
        return new ArrayList<>(sessions);
      }
    }

    List<String> kinds() {
      return events().stream().map(JobEvent::kind).toList();
    }

    List<JobEvent> ofKind(String kind) {
      return events().stream().filter(event -> kind.equals(event.kind())).toList();
    }

    /**
     * Waits for at least {@code count} events of one kind, then returns every one of that kind so
     * far. Fails rather than hangs.
     */
    List<JobEvent> awaitOfKind(String kind, int count, long millis) throws InterruptedException {
      for (long waited = 0; waited < millis; waited += 10) {
        List<JobEvent> found = ofKind(kind);
        if (found.size() >= count) {
          return found;
        }
        Thread.sleep(10);
      }
      throw new AssertionError(
          "only " + ofKind(kind).size() + " '" + kind + "' events arrived, wanted " + count);
    }

    List<JobEvent> forJob(String job) {
      return events().stream().filter(event -> job.equals(event.job())).toList();
    }

    JobEvent last() {
      List<JobEvent> all = events();
      return all.get(all.size() - 1);
    }
  }

  private static final class Scripted implements LlmTransport {

    private final List<Supplier<Completion>> steps = new ArrayList<>();
    private final AtomicInteger index = new AtomicInteger();
    private volatile Supplier<Completion> fallback = () -> answer("nothing left to say");

    Scripted then(Supplier<Completion> step) {
      steps.add(step);
      return this;
    }

    Scripted thenAlways(Supplier<Completion> step) {
      fallback = step;
      return this;
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      int at = index.getAndIncrement();
      return at < steps.size() ? steps.get(at).get() : fallback.get();
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      // The job runtime streams now. This double answers the same
      // thing either way, on purpose: reconciling two wire formats is the
      // transport's problem and OpenAiTransportTest is where it is
      // proved, so a fake that answered differently down this path would
      // only be testing itself. Delegating to complete(...) keeps every
      // assertion in this class — what a turn was offered, what it sent,
      // what came back — meaning exactly what it meant.
      Completion streamed = complete(wireModel, messages, sampling, tools);
      // Asked after the call, which is where a fake can honestly ask it:
      // the real transport asks once per chunk, and this one has exactly
      // one chunk. See LlmTransport.stream and CallerAbandonedException.
      if (abandoned.getAsBoolean()) {
        throw new CallerAbandonedException(poolName());
      }
      String content = streamed.content();
      if (content != null && !content.isEmpty()) {
        sink.answered(content);
      }
      return streamed;
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      throw new UnsupportedOperationException("the job runtime does not embed");
    }

    @Override
    public void close() {}
  }

  /**
   * A tool that records what it was handed, which is what makes the containment fixture something
   * rather than nothing.
   */
  private static final class Probe implements AgentTool {

    private final ToolSchema schema;
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    Probe(String name) {
      this.schema =
          ToolSchema.from(
              name, "a probe called " + name, Map.of("type", "object", "properties", Map.of()));
    }

    List<String> seen() {
      synchronized (seen) {
        return List.copyOf(seen);
      }
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      seen.add(argumentsJson);
      return "the probe ran";
    }
  }

  /**
   * A socket that records what was written to it, and can be made never to finish a write.
   *
   * <p>The one fake in this file that is not at the model end, and it earns its place: what has to
   * be measured is a listener that <em>does not complete a write</em>, and no real client can be
   * asked to stop reading. A blocked {@link #sendMessage} is what a peer with a full receive window
   * looks like from this side.
   */
  private static final class Collecting implements WebSocketSession {

    private final Map<String, Object> attributes = new HashMap<>();
    private final List<String> frames = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch wedged;
    private final CountDownLatch release;
    private volatile URI uri;
    private volatile boolean open = true;

    Collecting(CountDownLatch wedged, CountDownLatch release) {
      this.wedged = wedged;
      this.release = release;
    }

    List<String> frames() {
      synchronized (frames) {
        return List.copyOf(frames);
      }
    }

    /** Waits for at least {@code count} frames, then returns them. */
    List<String> await(int count, long millis) throws InterruptedException {
      for (long waited = 0; waited < millis; waited += 10) {
        if (frames().size() >= count) {
          return frames();
        }
        Thread.sleep(10);
      }
      throw new AssertionError("only " + frames().size() + " frames arrived, wanted " + count);
    }

    /**
     * Blocks the first write until released, and records every write.
     *
     * <p><b>The recording used to be in an else.</b> A {@code Collecting} built with a latch
     * returned from the wedged branch without adding anything, ever — so a test using one to hold a
     * queue open could never afterwards ask what had been delivered, and the only thing left to
     * assert was how long the publishing took. That made the drop test an instrument that could not
     * record what it was cited for: a full queue and an unbounded one both publish instantly,
     * because {@code offer} never blocks either way.
     *
     * <p>The block is still the first write only — the latch is counted down on entry and awaited
     * once — so what a caller sees is one write held open and everything behind it queued, which is
     * the state these tests need.
     */
    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
      if (wedged != null) {
        wedged.countDown();
        try {
          if (!release.await(60, TimeUnit.SECONDS)) {
            throw new IOException("the wedged socket was never released");
          }
        } catch (InterruptedException stopped) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted in the wedged socket", stopped);
        }
      }
      frames.add(String.valueOf(message.getPayload()));
    }

    @Override
    public String getId() {
      return "fake";
    }

    @Override
    public URI getUri() {
      return uri;
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
      return HttpHeaders.EMPTY;
    }

    @Override
    public Map<String, Object> getAttributes() {
      return attributes;
    }

    @Override
    public Principal getPrincipal() {
      return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return null;
    }

    @Override
    public String getAcceptedProtocol() {
      return null;
    }

    @Override
    public void setTextMessageSizeLimit(int limit) {}

    @Override
    public int getTextMessageSizeLimit() {
      return 0;
    }

    @Override
    public void setBinaryMessageSizeLimit(int limit) {}

    @Override
    public int getBinaryMessageSizeLimit() {
      return 0;
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
      return List.of();
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }

    @Override
    public void close(CloseStatus status) {
      open = false;
    }
  }
}
