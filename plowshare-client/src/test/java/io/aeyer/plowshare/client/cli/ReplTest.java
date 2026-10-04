package io.aeyer.plowshare.client.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
 * A conversation held in a terminal, driven through the class a person runs and read back out of
 * what it printed.
 *
 * <h2>Why this drives {@code Plowshare.run} and not {@code Repl.hold}</h2>
 *
 * <p>Every claim here is about what somebody sees and what reaches the wire, and both of those are
 * properties of the whole terminal: the argument parsing that decides there is a conversation at
 * all, the session that has to be attached before a turn can be submitted, and the loop. A test
 * that built a {@code Repl} over a stub would be measuring the loop against a fixture of its own
 * assumptions — the shape of the mistake this project keeps naming, a test helper blind to what it
 * is helping test.
 *
 * <h2>Against a socket, and never a real one</h2>
 *
 * <p>{@link MockWebServer} on an ephemeral port, through a {@link Dispatcher} rather than an
 * enqueued sequence: three connections are open at once — two WebSocket upgrades and the HTTP calls
 * — so a queue would be asserting on the order the operating system happened to accept them in.
 * Nothing here reaches a model endpoint or any other machine.
 *
 * <h2>Why these tests take seconds</h2>
 *
 * <p>{@code Plowshare.POLL} is two seconds, and the watch loop reads the job endpoint only after a
 * poll of the event stream came back with nothing. So a turn against a server that sends no
 * lifecycle events costs one poll, and a three-turn test costs three. That is the shipped timing
 * rather than this class's, and shortening it would mean making a constant with a written reason
 * into a parameter for a test's convenience.
 */
class ReplTest {

  /** The conversation every test in this class speaks into. */
  private static final String CONVERSATION = "cnv_000001";

  /** What {@code POST /v1/conversations} answers. */
  private static final String OPENED =
      """
            {"id":"%s","project":null,"maxModelCalls":12}"""
          .formatted(CONVERSATION);

  /**
   * A turn that stopped at its cap, carrying text that a working server would never put there.
   *
   * <p><b>The text is the fixture's whole point.</b> {@code JobRuntime.stopped} has no parameter a
   * model's prose could reach a stopping outcome through, so a real {@code TURN_CAP} says what the
   * server wrote about the ending. This one says what the model was in the middle of saying, which
   * is what a terminal that printed {@code text} without reading {@code answered} would put in
   * front of a person as an answer.
   */
  private static final String CAPPED =
      """
            {"id":"%s","agent":"interlocutor","state":"FINISHED","cancelRequested":false,
             "outcome":{"ending":"TURN_CAP","answered":false,
                        "text":"I THINK THE ANSWER MIGHT BE","steps":6,"modelCalls":6,
                        "detail":"stopped after 6 turns"}}""";

  /**
   * A turn somebody stopped.
   *
   * <p>Its {@code text} is a model's half-finished sentence and its {@code detail} is empty, which
   * is what {@code JobRuntime} produces for this ending — {@code stopped(...)} is called with
   * {@code ""}. Both halves are load bearing: the first is what must never be shown as an answer,
   * and the second is what used to make this terminal print a sentence ending in a colon and
   * nothing after it.
   */
  private static final String CANCELLED =
      """
            {"id":"%s","agent":"interlocutor","state":"FINISHED","cancelRequested":true,
             "outcome":{"ending":"CANCELLED","answered":false,
                        "text":"SO THE RETRY LOOP PROBABLY","steps":2,"modelCalls":2,
                        "detail":""}}""";

  /** A job the server has not finished, which is what a cancel is raced against. */
  private static final String RUNNING =
      """
            {"id":"%s","agent":"interlocutor","state":"RUNNING","cancelRequested":false,
             "outcome":null}""";

  private MockWebServer server;

  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();

  /** Job ids, minted the way the server mints them so a transcript reads like a real one. */
  private final AtomicInteger jobs = new AtomicInteger();

  /**
   * What {@code GET /v1/jobs/&#123;id&#125;} answers for one job, when a test wants something other
   * than an answer.
   */
  private final Map<String, String> outcomes = new ConcurrentHashMap<>();

  /**
   * How many times the terminal has asked what has been folded, so a test can make a seam appear
   * between two turns rather than existing from the start.
   */
  private final AtomicInteger askedAboutFolds = new AtomicInteger();

  /** What that endpoint answers, by the number of the ask. */
  private volatile List<String> folds = List.of();

  /**
   * Set to make every compactions read fail, which is what a server built before this endpoint
   * does.
   */
  private volatile int refuseFoldsWith;

  /** Set to make opening a conversation fail, with this body. */
  private volatile String refuseTurnWith;

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.setDispatcher(
        io.aeyer.plowshare.client.fixtures.WsFixture.wrap(
            new Dispatcher() {
              @Override
              public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/v1/files")) {
                  return upgrade(socket -> {});
                }
                if (path.startsWith("/v1/events")) {
                  return upgrade(socket -> {});
                }
                if (path.equals("/v1/conversations")) {
                  return json(200, OPENED);
                }
                if (path.endsWith("/compactions")) {
                  if (refuseFoldsWith != 0) {
                    return json(
                        refuseFoldsWith,
                        """
                                {"error":"not found","detail":"no such thing here"}""");
                  }
                  int nth = askedAboutFolds.incrementAndGet();
                  return json(200, nth <= folds.size() ? folds.get(nth - 1) : lastFold());
                }
                if (path.endsWith("/runs")) {
                  if (refuseTurnWith != null) {
                    return json(409, refuseTurnWith);
                  }
                  String id = "job_%06d".formatted(jobs.incrementAndGet());
                  return json(
                      202,
                      """
                            {"id":"%s","agent":"interlocutor"}"""
                          .formatted(id));
                }
                if (path.endsWith("/cancel")) {
                  String id = between(path, "/v1/jobs/", "/cancel");
                  outcomes.put(id, CANCELLED.formatted(id));
                  return json(200, RUNNING.formatted(id));
                }
                if (path.startsWith("/v1/jobs/")) {
                  String id = path.substring("/v1/jobs/".length());
                  return json(200, outcomes.getOrDefault(id, answered(id)));
                }
                return new MockResponse().setResponseCode(404);
              }
            }));
    server.start();
  }

  @AfterEach
  void stop() throws IOException {
    server.shutdown();
  }

  // --- several turns in one conversation ---------------------------------------

  /**
   * The acceptance path: three sentences, three turns, one conversation, and every answer in front
   * of the person who asked for it.
   *
   * <p>The conversation is opened <b>once</b> and each turn carries its id, and both halves are
   * asserted. A terminal that opened a conversation per utterance would print the same three
   * answers and would have held three conversations of one turn each — no shared budget, no
   * history, and nothing in the transcript saying so.
   */
  @Test
  void three_sentences_are_three_turns_in_one_conversation() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        talk("what does the retry code do?\nand on a timeout?\nthank you\n", printed, complaints);

    assertEquals(
        0,
        status,
        "the conversation did not end cleanly. It said: " + printed.text() + complaints.text());
    assertEquals(
        1,
        count("/v1/conversations"),
        "a conversation was opened per utterance rather than once; the server saw " + paths());
    assertEquals(
        3,
        count("/v1/agents/interlocutor/runs"),
        "three sentences were not three turns; the server saw " + paths());
    for (RecordedRequest turn : requestsFor("/v1/agents/interlocutor/runs")) {
      String body = turn.getBody().readUtf8();
      assertTrue(
          body.contains("\"conversation\":\"" + CONVERSATION + "\""),
          "an utterance went out as a plain run rather than as a turn, so it would"
              + " have spent a fresh budget and seen no history: "
              + body);
    }
    assertTrue(printed.text().contains("answer to job_000001"), printed.text());
    assertTrue(printed.text().contains("answer to job_000002"), printed.text());
    assertTrue(printed.text().contains("answer to job_000003"), printed.text());
  }

  /**
   * The utterances are the lines, in order, even when they all arrive at once.
   *
   * <p>Piped input is every line available from the moment the first turn starts, which is exactly
   * the state a person typing ahead puts this loop in. The turn's own interrupt check reads what is
   * waiting, and if it kept anything that was not {@code :stop} the second line would be eaten and
   * this would be a two-line script that spoke once.
   */
  @Test
  void a_line_typed_while_a_turn_is_running_is_the_next_utterance_and_not_lost() {
    Transcript printed = new Transcript();

    talk("first thing\nsecond thing\n", printed, new Transcript());

    List<String> spoken =
        requestsFor("/v1/agents/interlocutor/runs").stream()
            .map(request -> request.getBody().readUtf8())
            .toList();
    assertEquals(2, spoken.size(), "a line that arrived early was swallowed: " + spoken);
    assertTrue(spoken.get(0).contains("first thing"), spoken.toString());
    assertTrue(spoken.get(1).contains("second thing"), spoken.toString());
  }

  // --- a turn that did not answer ------------------------------------------------

  /**
   * A turn that stopped is a fact about that turn and not about the conversation.
   *
   * <p>The spec: "a REPL that silently retried, or that died on the first capped turn, would both
   * be lying about what happened." So the ending is printed, the prompt comes back, the next
   * utterance is a second turn, and the whole conversation still exits 0 — the person left it,
   * nothing stopped it.
   *
   * <p><b>And the capped turn's {@code text} is not printed.</b> The fixture carries model prose
   * there, which a real server never would, so a terminal that showed {@code text} without reading
   * {@code answered} would put half a thought in front of a person as an answer and pass every
   * other assertion here.
   */
  @Test
  void a_turn_that_did_not_answer_leaves_the_conversation_open() {
    outcomes.put("job_000001", CAPPED.formatted("job_000001"));
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        talk(
            "what broke the deploy?\nnever mind, what is in this directory?\n",
            printed,
            complaints);

    assertEquals(
        0, status, "one turn that stopped ended the whole conversation: " + complaints.text());
    assertTrue(printed.text().contains("TURN_CAP after 6 steps, 6 model calls"), printed.text());
    assertTrue(complaints.text().contains("stopped without answering"), complaints.text());
    assertFalse(
        printed.text().contains("I THINK THE ANSWER MIGHT BE"),
        "a turn that stopped had its half-finished prose printed as an answer: " + printed.text());
    assertEquals(
        2,
        count("/v1/agents/interlocutor/runs"),
        "the conversation did not take a second turn; the server saw " + paths());
    assertTrue(
        printed.text().contains("answer to job_000002"),
        "the turn after the one that stopped never got its answer printed: " + printed.text());
  }

  // --- interruption ------------------------------------------------------------

  /**
   * {@code :stop} typed while a turn is running is the cancel endpoint, and the conversation goes
   * on.
   *
   * <p>The server answers {@code RUNNING} with no outcome until it sees the cancel, so the turn
   * cannot end by itself: if the terminal never sent one, this loop would still be polling when the
   * test timed out. That is what makes the assertion about the POST a measurement rather than a
   * coincidence.
   */
  @Test
  void stop_typed_during_a_turn_asks_the_server_to_cancel_it_and_the_conversation_goes_on() {
    outcomes.put("job_000001", RUNNING.formatted("job_000001"));
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        talk("read every file in the tree\n:stop\nsomething smaller then\n", printed, complaints);

    assertEquals(0, status, printed.text() + complaints.text());
    assertEquals(
        1,
        count("/v1/jobs/job_000001/cancel"),
        "the turn was never cancelled; the server saw " + paths());
    assertTrue(printed.text().contains("CANCELLED after 2 steps, 2 model calls"), printed.text());
    assertEquals(
        2,
        count("/v1/agents/interlocutor/runs"),
        "an interrupted turn ended the conversation; the server saw " + paths());
  }

  /**
   * <b>The decision this task took about a cancelled turn, from the side it could have broken.</b>
   *
   * <p>What a cancelled turn contributes to a conversation is the utterance and the fact of the
   * interruption, never a word the model had got as far as producing. Server-side that is already
   * true by construction and pinned by {@code
   * JobRuntimeTest.no_ending_but_answered_carries_the_model_s_last_prose} over this very ending.
   * This is the person's half of the same rule: what reaches them comes from {@code ending}, {@code
   * turns} and {@code modelCalls}, which cannot carry prose, and the {@code text} the fixture puts
   * a half-sentence in is not shown at all.
   *
   * <p>The empty {@code detail} is asserted too. Every stopping ending carries one and four of the
   * seven set it to {@code ""}, so a terminal branching on {@code null} alone printed "the run
   * stopped without answering: " with nothing after the colon — a sentence that reads as one that
   * was cut off, in front of a person whose turn really was cut off.
   */
  @Test
  void an_interrupted_turn_shows_the_interruption_and_never_the_words_it_stopped_mid() {
    outcomes.put("job_000001", CANCELLED.formatted("job_000001"));
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    talk("what does this do?\n:quit\n", printed, complaints);

    String whole = printed.text() + complaints.text();
    assertTrue(
        printed.text().contains("CANCELLED after 2 steps, 2 model calls"),
        "the person was not told the turn was cancelled: " + whole);
    assertFalse(
        whole.contains("SO THE RETRY LOOP PROBABLY"),
        "a cancelled turn's partial output was shown, which is the one thing the spec"
            + " says not to do with it: "
            + whole);
    assertTrue(complaints.text().contains("stopped without answering"), whole);
    assertFalse(
        complaints.text().contains("answering: "),
        "an empty detail was rendered as a sentence that trails off after a colon: "
            + complaints.text());
  }

  // --- the seam ------------------------------------------------------------------

  /**
   * A compaction is the one thing the speaker cannot see for itself, so it is printed — once.
   *
   * <p>The seam appears between the first turn and the second and stands from then on, which is
   * what a standing compaction does: {@code CompactionStore.forConversation} answers every fold a
   * conversation has had, every time it is asked. A terminal that printed what it was told rather
   * than what was new would repeat the whole summary after every remaining turn, which is why the
   * third turn is here.
   */
  @Test
  void a_fold_is_printed_once_and_says_how_far_back_it_reaches() {
    folds =
        List.of(
            "[]",
            """
                [{"throughOrdinal":2,"summary":"they settled on three retries"}]""");
    Transcript printed = new Transcript();

    talk("one\ntwo\nthree\n", printed, new Transcript());

    String whole = printed.text();
    assertTrue(
        whole.contains("turns 1 to 2 were summarised"),
        "a compaction happened and the person was never told: " + whole);
    assertTrue(
        whole.contains("they settled on three retries"),
        "the seam was announced without what stands behind it, which is the half a"
            + " person needs to tell a good summary from a bad one: "
            + whole);
    assertEquals(
        1,
        occurrences(whole, "they settled on three retries"),
        "the standing summary was reprinted after every later turn: " + whole);
  }

  /**
   * A second seam takes up where the first left off, and does not claim the first one's ground.
   *
   * <p><b>This line said "turns 1 to" for every row, and folds are incremental now.</b> A second
   * compaction covers the turns since the first and leaves that first summary standing, so a
   * terminal opening both sentences at turn one tells a person the second summary covers ground the
   * first one is already holding — the same confident account of a span it does not stand for that
   * the seam the model reads was fixed to stop making.
   *
   * <p><b>The lower bound is derived from the row before and not from the server</b>, which is what
   * makes this a terminal change and not an API one: {@code GET
   * /v1/conversations/&#123;id&#125;/compactions} carries only {@code through_ordinal}, the rows
   * come back in reach order, and a fold starts one turn after the fold before it ends. The first
   * row has no row before it and starts at one.
   */
  @Test
  void a_second_fold_is_printed_as_the_span_since_the_first_and_not_from_turn_one() {
    folds =
        List.of(
            "[]",
            """
                [{"throughOrdinal":2,"summary":"they settled on three retries"}]""",
            """
                [{"throughOrdinal":2,"summary":"they settled on three retries"},
                 {"throughOrdinal":4,"summary":"then they rolled the deploy back"}]""");
    Transcript printed = new Transcript();

    talk("one\ntwo\nthree\nfour\n", printed, new Transcript());

    String whole = printed.text();
    assertTrue(
        whole.contains("turns 1 to 2 were summarised"),
        "the first seam covers turns 1 to 2: " + whole);
    assertTrue(
        whole.contains("turns 3 to 4 were summarised"),
        "the second seam covers the turns since the first and has to say so: " + whole);
    assertEquals(
        0,
        occurrences(whole, "turns 1 to 4"),
        "the second seam claimed the ground the first one already holds: " + whole);
  }

  /**
   * A server that cannot answer for folds costs one sentence and not one per turn.
   *
   * <p>A deployment built before this endpoint answers 404 to every ask, and a terminal repeating
   * that in the middle of somebody's conversation would be reporting the same fact about itself
   * over and over. It also must not stop the conversation: what is lost is the view of the seam and
   * not the turn.
   */
  @Test
  void a_server_that_cannot_say_what_was_folded_says_so_once_and_the_conversation_goes_on() {
    refuseFoldsWith = 404;
    Transcript printed = new Transcript();

    int status = talk("one\ntwo\n", printed, new Transcript());

    assertEquals(0, status, printed.text());
    assertEquals(
        1,
        occurrences(printed.text(), "did not answer for " + CONVERSATION),
        "a server with no compactions endpoint was reported once per turn: " + printed.text());
    assertEquals(
        2,
        count("/v1/agents/interlocutor/runs"),
        "a failed seam read ended the conversation; the server saw " + paths());
    assertTrue(
        printed.text().contains("answer to job_000002"),
        "the turn after the failed read lost its answer: " + printed.text());
  }

  // --- the ways a conversation ends -----------------------------------------------

  /**
   * A conversation with nothing left is the server's refusal, in the server's words, and the end of
   * the loop.
   *
   * <p>409 rather than a transport failure, so the exit status is 1: the conversation ended and the
   * person did not end it. A second sentence from this side is deliberately absent — the server's
   * already says which of the two 409s it is, and one that had to cover both would be vague about
   * the only thing worth knowing.
   */
  @Test
  void a_conversation_the_server_will_not_take_another_turn_in_ends_in_its_own_words() {
    refuseTurnWith =
        """
                {"error":"conflict","detail":"conversation cnv_000001 has spent all 12 model\
                 calls of the budget it was opened with"}""";
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status = talk("one more thing\n", printed, complaints);

    assertEquals(1, status, "a conversation that ran out exited " + status);
    assertTrue(
        complaints.text().contains("has spent all 12 model calls"),
        "the server's own reason never reached the person: " + complaints.text());
  }

  /** End of input leaves, and leaving is not a failure. */
  @Test
  void the_end_of_the_input_leaves_the_conversation() {
    Transcript printed = new Transcript();

    int status = talk("", printed, new Transcript());

    assertEquals(0, status, printed.text());
    assertTrue(printed.text().contains("left conversation " + CONVERSATION), printed.text());
    assertEquals(
        0,
        count("/v1/agents/interlocutor/runs"),
        "a terminal nobody said anything to submitted a turn; the server saw " + paths());
  }

  /** So does {@code :quit}, and it is not sent anywhere as an utterance. */
  @Test
  void quit_leaves_and_is_never_spoken_into_the_conversation() {
    Transcript printed = new Transcript();

    int status = talk(":quit\n", printed, new Transcript());

    assertEquals(0, status, printed.text());
    assertEquals(
        0,
        count("/v1/agents/interlocutor/runs"),
        "':quit' was submitted as something to answer; the server saw " + paths());
  }

  // --- which agent ------------------------------------------------------------------

  /**
   * A person who names no agent gets the one this server ships for being talked to, and a person
   * who names one gets that.
   *
   * <p>Both halves in one test because either alone is half the decision: a default with no way
   * past it would make this terminal the only caller that cannot put another agent's turn in a
   * conversation, and a flag with no default would make the first thing a person types a lookup.
   */
  @Test
  void the_default_agent_is_the_interlocutor_and_a_named_one_takes_its_place() {
    talk("hello\n", new Transcript(), new Transcript());
    assertEquals(
        1,
        count("/v1/agents/interlocutor/runs"),
        "a conversation with no agent named went somewhere else; the server saw " + paths());

    seen.clear();
    Plowshare.run(
        new String[] {"--talk", "code_reviewer", "--max-model-calls", "12", "--server", where()},
        lines("hello\n"),
        new Transcript().stream(),
        new Transcript().stream());

    assertEquals(
        1,
        count("/v1/agents/code_reviewer/runs"),
        "a named agent did not take the default's place; the server saw " + paths());
  }

  // --- the command line ----------------------------------------------------------

  /**
   * A conversation with no allowance named is opened anyway, and the number the server chose is
   * what the person is told.
   *
   * <p><b>This terminal still invents no default</b>, which is the distinction the whole change
   * turns on: the number goes out as a null rather than being filled in here, so it comes from
   * {@code plowshare.conversations} on the box that holds the conversation — the server reads an
   * absent key and a null one the same way, and neither is a figure this terminal made up. What
   * changed is that the flag is no longer <em>required</em> — the refusal predated that setting,
   * and the owner's judgement that took the same field off the console named REPL mode
   * specifically. The banner reports {@code Conversation.maxModelCalls} as it came back, so a
   * person who named nothing still knows what they got.
   */
  @Test
  void a_conversation_with_no_allowance_takes_the_server_s_own_and_says_what_it_got() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(
            new String[] {"--talk", "--server", where()},
            lines(":quit\n"),
            printed.stream(),
            complaints.stream());

    assertEquals(0, status, printed.text() + complaints.text());
    assertEquals(
        1, count("/v1/conversations"), "no conversation was opened; the server saw " + paths());
    String body = requestsFor("/v1/conversations").get(0).getBody().readUtf8();
    assertTrue(
        body.contains("\"maxModelCalls\":null"),
        "this terminal filled in an allowance of its own rather than leaving the"
            + " number to the server: "
            + body);
    assertTrue(
        printed.text().contains("12 model calls"),
        "the allowance the conversation was actually opened with was not reported: "
            + printed.text());
  }

  /** A named allowance is still sent, and is the one the conversation gets. */
  @Test
  void an_allowance_that_was_named_is_still_what_goes_out() {
    Plowshare.run(
        new String[] {"--talk", "--max-model-calls", "40", "--server", where()},
        lines(":quit\n"),
        new Transcript().stream(),
        new Transcript().stream());

    String body = requestsFor("/v1/conversations").get(0).getBody().readUtf8();
    assertTrue(body.contains("\"maxModelCalls\":40"), body);
  }

  /**
   * The same flag on a one-shot run is refused rather than ignored.
   *
   * <p>A run spends a budget built from its agent's own {@code max-model-calls} and nothing on that
   * path reads this, so accepting it would let a person cap a run at four and watch it make twelve
   * calls — a silent difference between what was asked for and what happened, which is the failure
   * this project keeps naming.
   */
  @Test
  void an_allowance_on_a_run_that_is_not_a_conversation_is_refused_rather_than_ignored() {
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(
            new String[] {"scribe", "go", "--max-model-calls", "4", "--server", where()},
            lines(""),
            new Transcript().stream(),
            complaints.stream());

    assertEquals(2, status);
    assertTrue(complaints.text().contains("there is no conversation here"), complaints.text());
    assertTrue(seen.isEmpty(), "a refused command line reached the server: " + paths());
  }

  /** A sentence on the command line is not a conversation's first utterance. */
  @Test
  void a_task_given_to_talk_is_refused_rather_than_spoken() {
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(
            new String[] {
              "--talk",
              "interlocutor",
              "fix the retries",
              "--max-model-calls",
              "12",
              "--server",
              where()
            },
            lines(""),
            new Transcript().stream(),
            complaints.stream());

    assertEquals(2, status);
    assertTrue(complaints.text().contains("one line at a time"), complaints.text());
    assertTrue(seen.isEmpty(), "a refused command line reached the server: " + paths());
  }

  /** An allowance that is not a number says what it got. */
  @Test
  void an_allowance_that_is_not_a_number_says_what_it_was_given() {
    Transcript complaints = new Transcript();

    int status =
        Plowshare.run(
            new String[] {"--talk", "--max-model-calls", "lots", "--server", where()},
            lines(""),
            new Transcript().stream(),
            complaints.stream());

    assertEquals(2, status);
    assertTrue(complaints.text().contains("'lots'"), complaints.text());
  }

  // --- fixtures ---------------------------------------------------------------------

  /** The whole terminal, in conversation, over the mock server. */
  private int talk(String typed, Transcript printed, Transcript complaints) {
    return Plowshare.run(
        new String[] {"--talk", "--max-model-calls", "12", "--server", where()},
        lines(typed),
        printed.stream(),
        complaints.stream());
  }

  private static InputStream lines(String typed) {
    return new ByteArrayInputStream(typed.getBytes(UTF_8));
  }

  private static String answered(String job) {
    return """
                {"id":"%s","agent":"interlocutor","state":"FINISHED","cancelRequested":false,
                 "outcome":{"ending":"ANSWERED","answered":true,"text":"answer to %s",
                            "steps":2,"modelCalls":2,"detail":null}}"""
        .formatted(job, job);
  }

  /**
   * What the compactions endpoint keeps answering once the scripted asks run out: a fold that has
   * happened does not un-happen.
   */
  private String lastFold() {
    return folds.isEmpty() ? "[]" : folds.get(folds.size() - 1);
  }

  private static String between(String path, String after, String before) {
    return path.substring(after.length(), path.length() - before.length());
  }

  private long count(String path) {
    return seen.stream().filter(request -> path.equals(request.getPath())).count();
  }

  private List<RecordedRequest> requestsFor(String path) {
    return seen.stream().filter(request -> path.equals(request.getPath())).toList();
  }

  private List<String> paths() {
    return seen.stream().map(RecordedRequest::getPath).toList();
  }

  private static int occurrences(String haystack, String needle) {
    int found = 0;
    for (int at = haystack.indexOf(needle);
        at >= 0;
        at = haystack.indexOf(needle, at + needle.length())) {
      found++;
    }
    return found;
  }

  private String where() {
    return server.url("/").toString();
  }

  /**
   * A PrintStream and the bytes it collected, so an assertion can quote the whole transcript when
   * it fails rather than only the line it looked at.
   */
  private static final class Transcript {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream stream = new PrintStream(bytes, true, UTF_8);

    PrintStream stream() {
      return stream;
    }

    String text() {
      stream.flush();
      return bytes.toString(UTF_8);
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
   * <p>{@code onClosing} answers the close, and it is not decoration: {@code SessionClientTest}
   * measured that without it the handshake never completes, the connection stays open, and {@link
   * MockWebServer#shutdown()} fails every test in the class <b>after the assertions have
   * passed</b>.
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

  @FunctionalInterface
  private interface Held {
    void accept(WebSocket socket);
  }
}
