package io.aeyer.plowshare.client.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.tools.SearchTools;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * The terminal's command surface, driven through the class a person runs.
 *
 * <h2>Through {@code Plowshare.run} and not through {@code Commands}</h2>
 *
 * <p>{@code ReplTest}'s reason, and it is stronger here: the claim these tests make is that a
 * person who types {@code plowshare memory recall …} gets an answer, and half of what has to be
 * true for that lives in the dispatch — the decision that the first word is a command at all, and
 * the escape for the agent whose name collides with one. A test that called {@code Commands.run}
 * directly would pass on a build where nothing routed to it.
 *
 * <h2>No session</h2>
 *
 * <p>None of these commands opens one, which is visible here as the absence of any {@code
 * /v1/files} or {@code /v1/events} upgrade in what the server saw. That is the design and not an
 * omission: a memory recall lends no files, and two sockets opened to read an index would be a
 * terminal that cannot answer a question while another one is answering it.
 */
class CommandsTest {

  private MockWebServer server;

  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();

  private static final String MEMORY =
      """
            {"id":"mem_000001","summary":"retries only fire on 5xx",
             "scope":"anything touching retries","state":"active","pinned":false,"uses":2,
             "lastUsed":null,"body":"Retry.java:41 catches IOException and rethrows.",
             "supersedes":null,"supersededBy":null,"invalidation":null,
             "formed":{"at":"2026-09-01T10:00:00Z","by":"enzo","where":"a terminal"},
             "home":{"project":null}}""";

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
                if (path.startsWith("/v1/files") || path.startsWith("/v1/events")) {
                  // Only the explicit `run` form reaches these; every command
                  // verb is asserted above not to.
                  return upgrade();
                }
                if (path.equals("/v1/memories/recall")) {
                  return json(
                      200,
                      """
                            {"question":"why","limit":10,"unsearchable":1,
                             "memories":[%s]}"""
                          .formatted(MEMORY));
                }
                if (path.equals("/v1/memories/index") || path.startsWith("/v1/memories/index?")) {
                  return json(
                      200,
                      """
                            [{"id":"mem_000001","summary":"retries only fire on 5xx",
                              "scope":"anything touching retries","unsearchable":false},
                             {"id":"mem_000002","summary":"the deploy key rotates monthly",
                              "scope":"deploying","unsearchable":true}]""");
                }
                if (path.startsWith("/v1/memories/mem_")) {
                  return json(200, MEMORY);
                }
                if (path.equals("/v1/memories")) {
                  return json(
                      200,
                      """
                            {"kind":"new","memoryId":"mem_000003","reason":"nothing like it",
                             "targetId":null,"demoted":[]}""");
                }
                if (path.equals("/v1/curate")) {
                  return json(
                      202,
                      """
                            {"id":"job_000009","agent":"curator"}""");
                }
                if (path.equals("/v1/search")) {
                  // clone(), not readUtf8(): `seen` holds this very request
                  // and a test below reads its body, which a consuming read
                  // here would have already emptied.
                  boolean more = request.getBody().clone().readUtf8().contains("apostrophe");
                  return json(
                      200,
                      """
                            {"hits":[{"url":"https://a.example/retries",
                                      "title":"Retry budgets, explained",
                                      "snippet":"a budget caps attempts per run"}],
                             "page":1,"pageSize":10,"total":%d,"hasMore":%s,"refusal":null}"""
                          .formatted(more ? 40 : 1, more));
                }
                if (path.endsWith("/resolve")) {
                  return json(
                      200,
                      """
                            {"proposal":{"id":"prp_000001","memoryId":"mem_000001",
                                         "project":"payments","action":"promote",
                                         "reason":"it holds everywhere","state":"accepted",
                                         "createdAt":"2026-08-29T12:00:00Z"},
                             "promotedId":"mem_000009","demoted":[]}""");
                }
                if (path.startsWith("/v1/proposals")) {
                  return json(
                      200,
                      """
                            [{"id":"prp_000001","memoryId":"mem_000001","project":"payments",
                              "action":"promote","reason":"it holds everywhere",
                              "state":"pending","createdAt":"2026-08-29T12:00:00Z",
                              "proposedBy":"curator"}]""");
                }
                if (path.startsWith("/v1/entries/search")) {
                  // Two hits of four, so the answer has to say both numbers and
                  // work out the next offset; a reach with something in every
                  // one of its three counts, so the prose that stands in for
                  // the rows a search cannot have is exercised.
                  return json(
                      200,
                      """
                            {"total":4,"offset":0,"limit":2,
                             "reach":{"searched":61,"ejected":4,"recordedOnly":2},
                             "hits":[
                              {"conversationId":"cnv_000007","ordinal":12,"turnOrdinal":3,
                               "kind":"tool_result","rank":0.61,
                               "snippet":"rotated the [deploy key] on the ninth",
                               "length":96000,"supersededBy":40,
                               "handle":"res_000004",
                               "recordedAt":"2026-09-02T11:00:00Z"},
                              {"conversationId":"cnv_000001","ordinal":3,"turnOrdinal":1,
                               "kind":"utterance","rank":0.4,
                               "snippet":"who has the [deploy key]?","length":25,
                               "supersededBy":null,"handle":null,
                               "recordedAt":"2026-09-04T09:00:00Z"}]}""");
                }
                if (path.contains("/chat") || path.contains("/trajectory")) {
                  return json(
                      200,
                      """
                            {"total":2,"offset":0,"limit":20,"entries":[
                              {"ordinal":1,"turnOrdinal":1,"kind":"utterance",
                               "excerpt":"what broke the deploy?","length":22,"cut":false,
                               "supersededBy":null,"toolCallId":null,"toolCalls":[],
                               "handle":null,"recordedAt":"2026-09-04T09:00:00Z",
                               "tookMillis":null}]}""");
                }
                if (path.contains("/context")) {
                  return json(
                      200,
                      """
                            {"sent":18543,"sentAtTurn":12,"turns":14,"turnsMeasured":12,
                             "systemPromptTokens":null,"toolTokens":null,"messageTokens":null,
                             "cacheHitRate":null,
                             "unavailable":[{"component":"cacheHitRate",
                                             "reason":"usage carries no cached_tokens"}],
                             "prefix":null}""");
                }
                if (path.startsWith("/v1/conversations")) {
                  return json(
                      200,
                      """
                            [{"id":"cnv_000001","project":"payments","maxModelCalls":40}]""");
                }
                if (path.endsWith("/ask")) {
                  return json(
                      202,
                      """
                            {"id":"job_000042","agent":"ask"}""");
                }
                if (path.equals("/v1/documents/search")) {
                  return json(
                      200,
                      """
                            {"query":"retry budget","limit":10,"searchable":412,"unsearchable":3,
                             "hits":[{"chunkId":"11111111-1111-1111-1111-111111111111",
                                      "text":"Retries are budgeted.","similarity":0.81,
                                      "paragraphId":"22222222-2222-2222-2222-222222222222",
                                      "paragraphText":"Retries are budgeted per run.",
                                      "paragraphOrdinal":4,
                                      "documentId":"33333333-3333-3333-3333-333333333333",
                                      "sourceName":"retries.md","title":"The Retry Budget"}]}""");
                }
                // The corpus reads, answered after `/search` and after `/ask` so
                // that the catch-all detail branch below cannot swallow either.
                // The synthetic chapter and the section-less passage are in the
                // fixtures deliberately: those are the two shapes a renderer
                // gets wrong, and the state most of a real corpus is in.
                if (path.equals("/v1/documents/rank")) {
                  return json(
                      200,
                      """
                            {"query":"graph counterexamples","limit":20,
                             "rankable":2,"unranked":7,"documents":[
                               {"documentId":"33333333-3333-3333-3333-333333333333",
                                "sourceName":"graphs.md","title":"The Counterexample",
                                "summary":"refutes the conjecture",
                                "ingestedAt":"2026-09-05T09:00:00Z","score":0.74}]}""");
                }
                if (path.endsWith("/stance")) {
                  return json(
                      200,
                      """
                            {"documentId":"33333333-3333-3333-3333-333333333333",
                             "claim":"the conjecture holds","topical":0.62,"stance":-0.18,
                             "basis":"vector_only"}""");
                }
                if (path.equals("/v1/documents/retrieve")) {
                  return json(
                      200,
                      """
                            {"query":"eleven vertices","document":null,"limit":5,"hits":[
                              {"score":0.81,"chunk":{
                                 "chunkId":"11111111-1111-1111-1111-111111111111",
                                 "text":"The counterexample has eleven vertices.",
                                 "paragraphId":"22222222-2222-2222-2222-222222222222",
                                 "paragraphOrdinal":3,
                                 "paragraphSummary":"eleven vertices suffice",
                                 "section":{"id":"44444444-4444-4444-4444-444444444444",
                                            "title":null,"synthetic":true,
                                            "summary":"reports the search"},
                                 "chapter":{"id":"55555555-5555-5555-5555-555555555555",
                                            "title":null,"synthetic":true,"summary":null},
                                 "documentId":"33333333-3333-3333-3333-333333333333",
                                 "sourceName":"graphs.md","title":"The Counterexample",
                                 "documentSummary":"refutes the conjecture"}},
                              {"score":0.42,"chunk":{
                                 "chunkId":"66666666-6666-6666-6666-666666666666",
                                 "text":"An aside nothing placed.",
                                 "paragraphId":"77777777-7777-7777-7777-777777777777",
                                 "paragraphOrdinal":9,"paragraphSummary":null,
                                 "section":null,"chapter":null,
                                 "documentId":"33333333-3333-3333-3333-333333333333",
                                 "sourceName":"graphs.md","title":"The Counterexample",
                                 "documentSummary":"refutes the conjecture"}}]}""");
                }
                if (path.equals("/v1/documents") || path.startsWith("/v1/documents?")) {
                  return json(
                      200,
                      """
                            {"total":1,"limit":50,"offset":0,"naming":null,"documents":[
                              {"documentId":"33333333-3333-3333-3333-333333333333",
                               "sourceName":"graphs.md","title":"The Counterexample",
                               "summary":"refutes the conjecture","vocabulary":"SECTION",
                               "ingestedAt":"2026-09-05T09:00:00Z","ingestedBy":"operator",
                               "byteSize":4096,"chapters":1,"sections":3,"paragraphs":12,
                               "chunks":30}]}""");
                }
                if (path.startsWith("/v1/documents/")) {
                  return json(
                      200,
                      """
                            {"documentId":"33333333-3333-3333-3333-333333333333",
                             "sourceName":"graphs.md","title":"The Counterexample",
                             "summary":"refutes the conjecture","vocabulary":"SECTION",
                             "ingestedAt":"2026-09-05T09:00:00Z","ingestedBy":"operator",
                             "byteSize":4096,"chapters":[
                               {"id":"55555555-5555-5555-5555-555555555555","title":null,
                                "synthetic":true,"summary":null,"sections":[
                                  {"id":"44444444-4444-4444-4444-444444444444",
                                   "title":"1. Introduction","synthetic":false,
                                   "summary":"states the conjecture"},
                                  {"id":"88888888-8888-8888-8888-888888888888",
                                   "title":null,"synthetic":true,"summary":null}]}]}""");
                }
                if (path.endsWith("/move")) {
                  return new MockResponse().setResponseCode(204);
                }
                if (path.startsWith("/v1/projects")) {
                  if ("DELETE".equals(request.getMethod())) {
                    return new MockResponse().setResponseCode(204);
                  }
                  return json(
                      200,
                      """
                            {"name":"payments","workspace":"/srv/pay",
                             "lent":["/srv/pay/.github"],
                             "exclusions":["/srv/pay/.env","/srv/plowshare"]}""");
                }
                if (path.endsWith("/runs")) {
                  return json(
                      202,
                      """
                            {"id":"job_000001","agent":"scribe"}""");
                }
                if (path.startsWith("/v1/jobs/job_000002")) {
                  return json(
                      200,
                      """
                            {"id":"job_000002","agent":"scribe","state":"FINISHED",
                             "cancelRequested":false,
                             "outcome":{"ending":"TURN_CAP","answered":false,
                                        "text":"I THINK THE ANSWER MIGHT BE","steps":6,
                                        "modelCalls":6,"detail":"stopped after 6 steps"}}""");
                }
                if (path.startsWith("/v1/jobs/job_000003")) {
                  return json(
                      200,
                      """
                            {"id":"job_000003","agent":"scribe","state":"RUNNING",
                             "cancelRequested":true,"outcome":null}""");
                }
                if (path.startsWith("/v1/jobs/")) {
                  return json(
                      200,
                      """
                            {"id":"job_000001","agent":"scribe","state":"FINISHED",
                             "cancelRequested":false,
                             "outcome":{"ending":"ANSWERED","answered":true,"text":"it was DNS",
                                        "steps":2,"modelCalls":2,"detail":null}}""");
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

  /**
   * The capability the parity design names first: a person at a terminal can ask the archive a
   * question.
   *
   * <p>The memory comes back <b>in full</b> — an index line would be a different capability, and
   * one this surface also has under another verb. The unsearchable count is asserted for the reason
   * it exists at all: an empty or short answer that does not say part of the archive was
   * unreachable is a conclusion a person acts on.
   */
  @Test
  void a_question_reaches_the_archive_and_the_memories_come_back_in_full() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        plowshare(
            printed, complaints, "memory", "recall", "why do retries fire?", "--server", where());

    assertEquals(0, status, printed.text() + complaints.text());
    assertEquals("/v1/memories/recall", seen.get(0).getPath());
    assertTrue(
        seen.get(0).getBody().readUtf8().contains("why do retries fire?"),
        "the question did not reach the server");
    assertTrue(printed.text().contains("retries only fire on 5xx"), printed.text());
    assertTrue(
        printed.text().contains("Retry.java:41"),
        "the body was not printed, so this was an index and not a recall: " + printed.text());
    assertTrue(
        printed.text().contains("1 memory"),
        "the memory recall could not search was not reported: " + printed.text());
  }

  /**
   * {@code search} is a <b>solo</b> command — {@code plowshare search <query>}, with no second word
   * choosing among verbs the way {@code memory recall} does — and this is the case {@link
   * Commands#run}'s solo dispatch exists for: the group word alone has to resolve to a command,
   * with everything after it treated as that command's own arguments and the documented defaults
   * sent for the three numbers nobody typed.
   *
   * <p>Asserted against {@link SearchTools}' own constants rather than literals: {@code
   * Commands.search} reads {@code SearchTools.DEFAULT_PAGE_SIZE}/{@code DEFAULT_MAX}/{@code
   * DEFAULT_PAGE} directly rather than a second declaration of the same numbers, and a test that
   * hardcoded {@code 10}/{@code 30}/{@code 1} here would keep passing even if that single
   * declaration changed — pinning a copy of the invariant instead of the invariant itself.
   */
  @Test
  void a_bare_search_reaches_the_server_with_the_documented_defaults() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status = plowshare(printed, complaints, "search", "retry budgets", "--server", where());

    assertEquals(0, status, printed.text() + complaints.text());
    assertEquals("/v1/search", seen.get(0).getPath());
    String body = seen.get(0).getBody().readUtf8();
    assertTrue(body.contains("\"query\":\"retry budgets\""), body);
    assertTrue(body.contains("\"pageSize\":" + SearchTools.DEFAULT_PAGE_SIZE), body);
    assertTrue(body.contains("\"max\":" + SearchTools.DEFAULT_MAX), body);
    assertTrue(body.contains("\"page\":" + SearchTools.DEFAULT_PAGE), body);
    assertTrue(printed.text().contains("https://a.example/retries"), printed.text());
    assertTrue(printed.text().contains("a budget caps attempts per run"), printed.text());
  }

  /**
   * <b>A continuation line is a command, and it has to survive an apostrophe.</b>
   *
   * <p>{@code plowshare search 'what's new'} is not a runnable line: the shell closes the quote at
   * {@code what} and leaves the person who copied it at a continuation prompt, which is precisely
   * the reader this line exists for. The words people search with are where apostrophes live, so
   * this is the ordinary case and not an exotic one.
   *
   * <p>Asserts the exact POSIX form — {@code 'what'\''s new'} — rather than merely that a backslash
   * appears somewhere: the failure being guarded against is a nearly-right escaping, and "contains
   * a backslash" passes for every one of them. The query itself carries a real apostrophe, so a
   * quoting change that dropped the escaping entirely cannot pass either.
   */
  @Test
  void the_continuation_line_survives_an_apostrophe_in_the_query() {
    Transcript printed = new Transcript();

    int status =
        plowshare(printed, new Transcript(), "search", "what's an apostrophe", "--server", where());

    assertEquals(0, status, printed.text());
    assertTrue(
        printed.text().contains("more: plowshare search 'what'\\''s an apostrophe' --max "),
        printed.text());
  }

  /**
   * A search with no query is refused as a usage error before anything is sent — {@code Line.of}'s
   * ordinary arity check, reached here through the solo-dispatch path rather than the two-word one
   * every other command uses, which is exactly what this test is pinning: the new path still goes
   * through the same argument validation as the old one.
   */
  @Test
  void a_search_with_no_query_is_refused_and_nothing_is_sent() {
    Transcript complaints = new Transcript();

    int status = plowshare(new Transcript(), complaints, "search", "--server", where());

    assertEquals(2, status);
    assertTrue(seen.isEmpty(), "a usage error reached the server: " + paths());
  }

  /**
   * No session is opened for a command that lends nothing.
   *
   * <p>Cheap to assert and worth pinning: the one-shot form of this terminal opens two WebSockets
   * before it does anything, and a command surface that reached for the same setup would make every
   * read wait on a file channel it has no use for.
   */
  @Test
  void a_command_dials_no_file_channel_and_no_event_stream() {
    plowshare(
        new Transcript(), new Transcript(), "memory", "recall", "anything", "--server", where());

    assertTrue(
        seen.stream().noneMatch(request -> request.getPath().startsWith("/v1/files")),
        "a read opened a file channel: " + paths());
    assertTrue(
        seen.stream().noneMatch(request -> request.getPath().startsWith("/v1/events")),
        "a read opened an event stream: " + paths());
  }

  /**
   * A group word with no verb after it says what the verbs are, and says how to run an agent that
   * happens to be called that.
   *
   * <p><b>The escape is the load-bearing half.</b> Reserving five words at the front of the command
   * line takes them away from {@code plowshare &lt;agent&gt; &lt;task&gt;}, and an agent named
   * {@code memory} is a thing a deployment may have. The refusal is loud and names {@code run},
   * which is the form that takes any agent name at all — rather than the alternative, which is
   * guessing from the shape of the rest of the line and being silently wrong.
   */
  @Test
  void a_group_with_no_verb_lists_the_verbs_and_names_the_escape() {
    Transcript complaints = new Transcript();

    int status = plowshare(new Transcript(), complaints, "memory", "--server", where());

    assertEquals(2, status);
    assertTrue(complaints.text().contains("recall"), complaints.text());
    assertTrue(
        complaints.text().contains("plowshare run memory"),
        "the refusal did not say how to run an agent called 'memory': " + complaints.text());
    assertTrue(seen.isEmpty(), "a usage error reached the server: " + paths());
  }

  /** A verb nothing offers is refused the same way, and nothing is sent. */
  @Test
  void an_unknown_verb_in_a_known_group_is_refused_rather_than_guessed() {
    Transcript complaints = new Transcript();

    int status =
        plowshare(new Transcript(), complaints, "memory", "forget", "mem_1", "--server", where());

    assertEquals(2, status);
    assertTrue(complaints.text().contains("memory forget"), complaints.text());
    assertTrue(seen.isEmpty(), "a usage error reached the server: " + paths());
  }

  /**
   * {@code plowshare run &lt;agent&gt; &lt;task&gt;} is the bare form said out loud, and it is what
   * makes every agent name reachable.
   *
   * <p>Including the reserved ones: the first word after {@code run} is an agent, whatever it
   * spells, so an agent called {@code memory} — or called {@code run} — is still runnable from this
   * terminal.
   */
  @Test
  void run_names_the_agent_explicitly_and_takes_a_name_that_collides() {
    int status =
        plowshare(
            new Transcript(),
            new Transcript(),
            "run",
            "memory",
            "what is in this directory?",
            "--server",
            where());

    assertEquals(0, status, "the explicit run form did not start a run: " + paths());
    assertTrue(
        seen.stream().anyMatch(request -> "/v1/agents/memory/runs".equals(request.getPath())),
        "the agent after 'run' was not the agent that ran: " + paths());
  }

  /**
   * A server that is not there is never rendered as an archive holding nothing.
   *
   * <p>The rule this whole client is organised around, and the command surface is a new place for
   * it to be got wrong: "nothing is remembered" and "I could not ask" are indistinguishable once
   * they render the same way, and the first is a conclusion somebody acts on.
   */
  @Test
  void a_server_that_is_not_listening_is_never_an_empty_archive() throws IOException {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        plowshare(
            printed, complaints, "memory", "index", "--server", "http://127.0.0.1:" + closedPort());

    assertEquals(2, status);
    assertTrue(complaints.text().contains("never asked"), complaints.text());
    assertFalse(
        printed.text().contains("holds nothing"),
        "an unreachable server was reported as an empty archive: " + printed.text());
  }

  // --- the archive ------------------------------------------------------------------

  /**
   * The index is a survey, and it marks the memories no question can reach.
   *
   * <p>The marker is the half worth pinning. A memory written while the embedding endpoint was down
   * is active, listed, readable by id and invisible to every recall — so a list that showed it like
   * any other would leave a person concluding, from an empty recall, that the archive does not hold
   * the answer it is holding.
   */
  @Test
  void the_index_marks_the_memories_no_question_can_reach() {
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed,
            new Transcript(),
            "memory",
            "index",
            "--project",
            "payments",
            "--server",
            where());

    assertEquals(0, status, printed.text());
    assertEquals("/v1/memories/index?project=payments", seen.get(0).getPath());
    assertTrue(printed.text().contains("mem_000002"), printed.text());
    assertTrue(
        printed.text().contains("not searchable"),
        "a memory no recall can reach was listed like any other: " + printed.text());
  }

  /** A memory is read by id, in full, with its provenance. */
  @Test
  void a_memory_is_read_by_id_in_full() {
    Transcript printed = new Transcript();

    int status =
        plowshare(printed, new Transcript(), "memory", "read", "mem_000001", "--server", where());

    assertEquals(0, status, printed.text());
    assertEquals("/v1/memories/mem_000001", seen.get(0).getPath());
    assertTrue(printed.text().contains("Retry.java:41"), printed.text());
    assertTrue(
        printed.text().contains("enzo"),
        "a memory was rendered without who formed it: " + printed.text());
  }

  /**
   * A memory written from a terminal is signed by whoever is at it.
   *
   * <p>{@code formedBy} is required by the archive and the MCP tool asks the model for it. There is
   * nobody to ask here and there is an answer already on the machine, so the account name is the
   * default and {@code --by} overrides it. The alternative was the tool surface's {@code
   * "unknown"}, which is what a memory's provenance says when it was never recorded — writing that
   * for somebody sitting at their own login would be recording an absence that is not there.
   */
  @Test
  void a_memory_written_from_a_terminal_is_signed_by_whoever_is_at_it() {
    int status =
        plowshare(
            new Transcript(),
            new Transcript(),
            "memory",
            "write",
            "--summary",
            "retries only fire on 5xx",
            "--scope",
            "anything touching retries",
            "--body",
            "Retry.java:41 catches IOException and rethrows.",
            "--server",
            where());

    assertEquals(0, status);
    String body = seen.get(0).getBody().readUtf8();
    assertEquals("/v1/memories", seen.get(0).getPath());
    assertTrue(
        body.contains("\"formedBy\":\"" + System.getProperty("user.name") + "\""),
        "the write was not signed by the account that made it: " + body);
    assertTrue(body.contains("Retry.java:41"), body);
  }

  /**
   * A curator pass is started, and what it started is named so it can be read back — this terminal
   * does not wait for it.
   */
  @Test
  void a_curator_pass_names_the_job_it_started() {
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed,
            new Transcript(),
            "memory",
            "curate",
            "payments",
            "--max-model-calls",
            "40",
            "--server",
            where());

    assertEquals(0, status, printed.text());
    assertEquals("/v1/curate", seen.get(0).getPath());
    assertTrue(
        seen.get(0).getBody().readUtf8().contains("\"maxModelCalls\":40"),
        seen.get(0).getBody().readUtf8());
    assertTrue(printed.text().contains("job_000009"), printed.text());
    assertTrue(
        printed.text().contains("plowshare job status job_000009"),
        "nothing said how to read the pass back: " + printed.text());
  }

  /**
   * The promotion queue can be read and settled without leaving the terminal.
   *
   * <p>Both halves in one test because a queue that can be listed and not answered is not a
   * capability anybody has: the whole reason the rows are filed is that a person decides them.
   */
  @Test
  void the_promotion_queue_is_listed_and_settled_from_a_terminal() {
    Transcript printed = new Transcript();

    int listed =
        plowshare(
            printed,
            new Transcript(),
            "memory",
            "proposals",
            "--project",
            "payments",
            "--server",
            where());
    assertEquals(0, listed, printed.text());
    assertTrue(printed.text().contains("prp_000001"), printed.text());
    assertTrue(printed.text().contains("it holds everywhere"), printed.text());

    Transcript settled = new Transcript();
    int status =
        plowshare(
            settled,
            new Transcript(),
            "memory",
            "resolve",
            "prp_000001",
            "accept",
            "--reason",
            "it is true of every project",
            "--server",
            where());

    assertEquals(0, status, settled.text());
    RecordedRequest decision = seen.get(seen.size() - 1);
    assertEquals("/v1/proposals/prp_000001/resolve", decision.getPath());
    String body = decision.getBody().readUtf8();
    assertTrue(body.contains("\"accept\":true"), body);
    assertTrue(
        body.contains("\"by\":\"" + System.getProperty("user.name") + "\""),
        "the decision was not signed by the account that made it: " + body);
    assertTrue(
        settled.text().contains("mem_000009"),
        "the promoted memory was not named: " + settled.text());
  }

  // --- conversations, the corpus and projects ----------------------------------------

  /**
   * A conversation can be found, read both ways, and priced.
   *
   * <p>Four verbs in one test because the claim is about the set: a chat without a trajectory is
   * the projection with no way to see what it hides, and either without the listing is a reading of
   * a conversation whose id you could only have if you held it yourself.
   */
  @Test
  void a_conversation_is_found_read_both_ways_and_priced() {
    Transcript listed = new Transcript();
    assertEquals(
        0,
        plowshare(
            listed,
            new Transcript(),
            "conversation",
            "list",
            "--project",
            "payments",
            "--server",
            where()),
        listed.text());
    assertTrue(listed.text().contains("cnv_000001"), listed.text());

    Transcript chat = new Transcript();
    assertEquals(
        0,
        plowshare(
            chat, new Transcript(), "conversation", "chat", "cnv_000001", "--server", where()),
        chat.text());
    assertTrue(chat.text().contains("what broke the deploy?"), chat.text());

    Transcript trajectory = new Transcript();
    assertEquals(
        0,
        plowshare(
            trajectory,
            new Transcript(),
            "conversation",
            "trajectory",
            "cnv_000001",
            "--server",
            where()),
        trajectory.text());

    Transcript context = new Transcript();
    assertEquals(
        0,
        plowshare(
            context,
            new Transcript(),
            "conversation",
            "context",
            "cnv_000001",
            "--server",
            where()),
        context.text());
    assertTrue(context.text().contains("18543"), context.text());
    assertTrue(
        context.text().contains("cached_tokens"),
        "a number the server declines to give was rendered as a blank rather than as"
            + " the reason it gave: "
            + context.text());

    List<String> asked = paths();
    assertTrue(asked.contains("/v1/conversations/cnv_000001/chat"), asked.toString());
    assertTrue(asked.contains("/v1/conversations/cnv_000001/trajectory"), asked.toString());
  }

  /**
   * A phrase finds where it was said, and the answer says what it could not look at.
   *
   * <p>The one conversation verb that starts from words rather than from an id, and the only route
   * to the other four for a person who has a phrase and no conversation. Four things are asserted
   * about the rendering and each is a way the answer could mislead:
   *
   * <ul>
   *   <li><b>Both numbers.</b> Two hits of four; a page that printed only the two would read as the
   *       whole of what matched.
   *   <li><b>The entry's real length, not the snippet's.</b> A snippet comes from the middle of an
   *       entry, so the ninety-six thousand characters around it are the reason to go and read it.
   *   <li><b>The ejected payloads as a sentence.</b> A search has nowhere to put a row it cannot
   *       match, so they are a count and a reason — and a short answer that did not mention them is
   *       one somebody acts on.
   *   <li><b>The next command, whole.</b> With the offset worked out and the tier kept: a
   *       continuation that dropped {@code --project} would silently page into the global tier.
   * </ul>
   *
   * <p>And the negative one: no tool name anywhere in it. The MCP rendering of this same answer
   * ends by naming {@code conversation_trajectory}, which is correct for a model holding that menu
   * and is a dead end at a terminal.
   */
  @Test
  void a_phrase_finds_where_it_was_said_and_says_what_it_could_not_look_at() {
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed,
            new Transcript(),
            "conversation",
            "search",
            "deploy key",
            "--project",
            "payments",
            "--server",
            where());

    assertEquals(0, status, printed.text());
    String asked = seen.get(0).getPath();
    assertTrue(asked.startsWith("/v1/entries/search"), asked);
    assertTrue(asked.contains("q=deploy") && asked.contains("project=payments"), asked);

    String text = printed.text();
    assertTrue(
        text.contains("cnv_000007") && text.contains("cnv_000001"),
        "a hit with no conversation to read it in is a paragraph from nowhere: " + text);
    assertTrue(text.contains("rotated the [deploy key] on the ninth"), text);
    assertTrue(text.contains("4"), "the total was not shown: " + text);
    assertTrue(
        text.contains("96000"),
        "the entry's real length did not travel beside its snippet: " + text);
    assertTrue(
        text.contains("ejected"),
        "four entries the search could not look at were left silently missing: " + text);
    assertTrue(
        text.contains("plowshare conversation search")
            && text.contains("--offset 2")
            && text.contains("--project payments"),
        "the next page was not spelled out with the offset worked out: " + text);
    assertFalse(
        text.contains("conversation_trajectory") || text.contains("_search"),
        "the terminal named a tool a person at it does not have: " + text);
  }

  /**
   * A question nothing matched says so, and says it of a tier it names — never as an empty page a
   * reader would read as an empty archive.
   */
  @Test
  void a_question_that_matched_nothing_still_says_what_was_searched() {
    server.setDispatcher(
        io.aeyer.plowshare.client.fixtures.WsFixture.wrap(
            new Dispatcher() {
              @Override
              public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                return json(
                    200,
                    """
                        {"total":0,"offset":0,"limit":20,"hits":[],
                         "reach":{"searched":0,"ejected":0,"recordedOnly":0}}""");
              }
            }));
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed,
            new Transcript(),
            "conversation",
            "search",
            "nothing at all",
            "--server",
            where());

    assertEquals(0, status, printed.text());
    assertTrue(
        printed.text().contains("global"),
        "an answer of nothing that does not say where it looked cannot be acted on: "
            + printed.text());
    assertTrue(
        printed.text().contains("nothing has been said in this tier at all"),
        "an empty tier reads as a question that found nothing: " + printed.text());
  }

  /**
   * The corpus answers a question typed at a terminal, and cites the paragraph rather than the
   * chunk that matched.
   */
  @Test
  void the_corpus_answers_a_question_and_cites_a_paragraph() {
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed, new Transcript(), "document", "search", "retry budget", "--server", where());

    assertEquals(0, status, printed.text());
    assertEquals("/v1/documents/search", seen.get(0).getPath());
    assertTrue(printed.text().contains("Retries are budgeted per run."), printed.text());
    assertTrue(
        printed.text().contains("22222222-2222-2222-2222-222222222222"),
        "the citation was not shown: " + printed.text());
    assertTrue(
        printed.text().contains("3"),
        "the passages the search could not reach were not reported: " + printed.text());
  }

  /**
   * <b>A search names the document, and the id is the one thing it names that the next verb
   * needs.</b>
   *
   * <p>{@code plowshare document ask} takes a document id as its first positional argument and
   * there is no other verb on this surface that produces one. {@code DocumentSearchResponse} has
   * carried {@code documentId} on every hit since the endpoint shipped, and this renderer dropped
   * it — so the two verbs sat one line apart in the usage block with no way to get from the first
   * to the second, and the ask was reachable from {@code curl} and nothing else.
   *
   * <p>Anchor's shell does not have this gap and it is the direct comparison: its {@code search}
   * prints {@code score documentId title}, and its {@code use <uuid-or-title>} is what the id is
   * for. A person here reads the same two lines and types the second.
   *
   * <p><b>Not a bound-document mode, and that is a decision rather than a shortfall.</b> Anchor
   * binds into a {@code volatile} field on a Spring Shell session and gates five commands behind
   * it; {@code plowshare document ask} is one process that exits, so a {@code use} verb here would
   * have nowhere to put the binding but a file under the user's config — state this branch may not
   * write, and a mode a person could be left in without knowing. The conversation is where this
   * system keeps that kind of state, and {@code close_reader} is the surface that uses it.
   */
  @Test
  void a_search_names_the_document_id_that_the_ask_verb_demands() {
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed, new Transcript(), "document", "search", "retry budget", "--server", where());

    assertEquals(0, status, printed.text());
    assertTrue(
        printed.text().contains("33333333-3333-3333-3333-333333333333"),
        "the document id was not shown, so nothing a person read here can be typed"
            + " into 'document ask': "
            + printed.text());
  }

  /**
   * Which papers are about something, and how much of the corpus could be looked at.
   *
   * <p>Both halves matter. The second is not a footnote: a document is summarised before anything
   * embeds the summary, so a corpus ingested minutes ago and never restarted is entirely unrankable
   * while every word of it is searchable, and a top result out of two rankable documents in a
   * corpus of nine is a different fact from a top result out of nine.
   */
  @Test
  void a_ranking_names_papers_and_says_how_much_of_the_corpus_it_could_see() {
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed,
            new Transcript(),
            "document",
            "rank",
            "graph counterexamples",
            "--server",
            where());

    assertEquals(0, status, printed.text());
    assertEquals("/v1/documents/rank", seen.get(0).getPath());
    String text = printed.text();
    assertTrue(text.contains("graphs.md"), text);
    assertTrue(text.contains("id: 33333333-3333-3333-3333-333333333333"), text);
    assertTrue(text.contains("2 documents in the corpus could be ranked"), text);
    assertTrue(text.contains("7 have a summary nothing has embedded"), text);
    // Said once and at the bottom: a reader who takes the number for
    // agreement reads a refutation as a confirmation.
    assertTrue(text.contains("ranks by what a paper is ABOUT"), text);
  }

  /**
   * The stance verb prints what the number was made of, beside the number.
   *
   * <p>Two floats look like a measurement. What this is: a claim and the string "not " plus the
   * claim, both compared against one sentence a model wrote about a paper nothing has read.
   * Anchor's shell prints {@code vector_only} under the label "Mode" at the bottom, which is true
   * and is not the same as saying it.
   */
  @Test
  void a_stance_prints_the_caveat_beside_the_numbers_and_not_under_them() {
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed,
            new Transcript(),
            "document",
            "stance",
            "33333333-3333-3333-3333-333333333333",
            "the conjecture holds",
            "--server",
            where());

    assertEquals(0, status, printed.text());
    assertEquals(
        "/v1/documents/33333333-3333-3333-3333-333333333333/stance", seen.get(0).getPath());
    String text = printed.text();
    assertTrue(text.contains("+0.620"), text);
    assertTrue(text.contains("-0.180"), text);
    // The topical number is printed above the lean, because a lean near zero
    // means opposite things depending on it.
    assertTrue(text.indexOf("about it") < text.indexOf("leans"), text);
    assertTrue(text.contains("NOTHING HAS READ THE PAPER"), text);
    assertTrue(text.contains("vector_only"), text);
    assertTrue(text.contains("document ask"), text);
  }

  /**
   * A passage arrives under the argument it sits inside, and a unit the document never named is
   * words rather than a null.
   *
   * <p><b>The second half is the assertion.</b> Anchor reaches the identical JSON — a null title
   * beside {@code synthetic: true} — and its shell prints {@code [null]} beside the passage and the
   * four letters "null" as a heading. The sentinel's whole design is that a bypass is loud; a JSON
   * null is what makes it quiet again, and {@code Structural} is the one place on this side that
   * turns it back into a sentence.
   */
  @Test
  void a_retrieve_shows_the_stack_above_a_passage_and_never_the_word_null() {
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed,
            new Transcript(),
            "document",
            "retrieve",
            "eleven vertices",
            "--server",
            where());

    assertEquals(0, status, printed.text());
    assertEquals("/v1/documents/retrieve", seen.get(0).getPath());
    String text = printed.text();
    assertTrue(text.contains("The counterexample has eleven vertices."), text);
    assertTrue(text.contains("cite: 22222222-2222-2222-2222-222222222222"), text);
    assertTrue(text.contains("document: refutes the conjecture"), text);
    assertTrue(text.contains("(unnamed segment) — reports the search"), text);
    // The passage with no section at all, which is the third state and not
    // the second: the document named nothing is a different fact from there
    // being nothing to name.
    assertTrue(text.contains("(in no section)"), text);
    assertFalse(text.contains("null"), "a null title reached a person: " + text);
  }

  /**
   * The verb that answers where an id comes from. Nothing on either main could enumerate the corpus
   * before it.
   */
  @Test
  void the_corpus_can_be_listed_and_every_row_carries_the_id_the_other_verbs_need() {
    Transcript printed = new Transcript();

    int status = plowshare(printed, new Transcript(), "document", "list", "--server", where());

    assertEquals(0, status, printed.text());
    assertEquals("/v1/documents", seen.get(0).getPath());
    String text = printed.text();
    assertTrue(text.contains("graphs.md"), text);
    assertTrue(text.contains("id: 33333333-3333-3333-3333-333333333333"), text);
    assertTrue(text.contains("12 paragraphs"), text);
  }

  /** A naming narrows the listing, and it is a query parameter rather than a second route. */
  @Test
  void a_listing_can_be_narrowed_by_a_name_a_person_half_remembers() {
    assertEquals(
        0,
        plowshare(
            new Transcript(),
            new Transcript(),
            "document",
            "list",
            "--naming",
            "counter",
            "--server",
            where()));

    assertEquals("/v1/documents?q=counter", seen.get(0).getPath());
  }

  /**
   * One document's outline, in the document's own word for its parts.
   *
   * <p>V26's {@code top_level_label} gets a second reader here: a paper that calls its top-level
   * parts sections is not shown a heading saying "chapters", because the person reading the outline
   * is about to go looking for that word in the paper.
   */
  @Test
  void a_documents_outline_is_rendered_in_the_documents_own_vocabulary() {
    Transcript printed = new Transcript();

    int status =
        plowshare(
            printed,
            new Transcript(),
            "document",
            "show",
            "33333333-3333-3333-3333-333333333333",
            "--server",
            where());

    assertEquals(0, status, printed.text());
    assertEquals("/v1/documents/33333333-3333-3333-3333-333333333333", seen.get(0).getPath());
    String text = printed.text();
    assertTrue(text.contains("1 section:"), text);
    assertTrue(text.contains("1. Introduction"), text);
    assertTrue(text.contains("(unnamed segment)"), text);
    assertFalse(text.contains("null"), "a null title reached a person: " + text);
  }

  /**
   * All six project verbs, and the one that is a rename says so.
   *
   * <p>{@code project move} on the tool side changes a project's <em>name</em> and never its
   * directory. A person reading a usage block reads "move" as the files moving, so the terminal
   * calls it {@code rename} — the same HTTP call, under the word that means what it does.
   *
   * <p><b>The lent roots are asserted in the printed leash and not only in the route.</b> A verb
   * that reached the right endpoint and printed only the workspace would show a person a
   * <em>shorter</em> leash than the one being enforced, which is the failure the exclusion
   * assertion above it exists to prevent in the other direction.
   */
  @Test
  void the_six_project_verbs_reach_their_six_endpoints() {
    Transcript defined = new Transcript();
    assertEquals(
        0,
        plowshare(
            defined,
            new Transcript(),
            "project",
            "define",
            "payments",
            "/srv/pay",
            "--exclude",
            ".env,secrets",
            "--server",
            where()),
        defined.text());
    assertTrue(
        defined.text().contains("/srv/plowshare"),
        "the exclusions the server adds of its own were not shown, so a person cannot"
            + " see the leash is shorter than the directory they named: "
            + defined.text());

    assertEquals(
        0,
        plowshare(
            new Transcript(),
            new Transcript(),
            "project",
            "workspace",
            "payments",
            "/srv/pay2",
            "--server",
            where()));

    Transcript lent = new Transcript();
    assertEquals(
        0,
        plowshare(
            lent,
            new Transcript(),
            "project",
            "lend",
            "payments",
            "/srv/pay/.github",
            "/srv/shared",
            "--server",
            where()),
        lent.text());
    assertTrue(
        lent.text().contains("/srv/pay/.github"),
        "a lend that printed only the workspace would show a shorter leash than the"
            + " one now enforced: "
            + lent.text());

    assertEquals(
        0,
        plowshare(
            new Transcript(),
            new Transcript(),
            "project",
            "unlend",
            "payments",
            "/srv/shared",
            "--server",
            where()));
    assertEquals(
        0,
        plowshare(
            new Transcript(),
            new Transcript(),
            "project",
            "rename",
            "payments",
            "billing",
            "--server",
            where()));
    assertEquals(
        0,
        plowshare(
            new Transcript(),
            new Transcript(),
            "project",
            "forget",
            "payments",
            "--server",
            where()));

    List<String> asked = paths();
    assertTrue(asked.contains("/v1/projects"), asked.toString());
    assertTrue(asked.contains("/v1/projects/payments/workspace"), asked.toString());
    assertTrue(asked.contains("/v1/projects/payments/lend"), asked.toString());
    assertTrue(asked.contains("/v1/projects/payments/unlend"), asked.toString());
    assertTrue(asked.contains("/v1/projects/payments/move"), asked.toString());
    assertEquals("DELETE", seen.get(seen.size() - 1).getMethod(), asked.toString());
  }

  // --- a run, after it has been started ----------------------------------------------

  /**
   * A job is read by id, and a run that stopped is not dressed as an answer.
   *
   * <p>The exit status is the assertion. {@code 0} only when the run answered, which is the rule
   * the one-shot form of this terminal already keeps — so a script that reads a job back gets the
   * same answer it would have got by watching the run.
   */
  @Test
  void a_job_read_by_id_answers_with_the_same_status_as_watching_it_would() {
    Transcript answered = new Transcript();
    assertEquals(
        0,
        plowshare(answered, new Transcript(), "job", "status", "job_000001", "--server", where()),
        answered.text());
    assertTrue(answered.text().contains("it was DNS"), answered.text());

    Transcript stopped = new Transcript();
    Transcript complaints = new Transcript();
    assertEquals(
        1,
        plowshare(stopped, complaints, "job", "status", "job_000002", "--server", where()),
        "a run that stopped without answering exited as though it had answered");
    assertFalse(
        stopped.text().contains("I THINK THE ANSWER MIGHT BE"),
        "a stopped run's half-finished prose was printed as an answer: " + stopped.text());
    assertTrue(complaints.text().contains("stopped after 6 steps"), complaints.text());
  }

  /**
   * A run that has not finished says so rather than pretending to a result, and says that somebody
   * has already asked it to stop.
   */
  @Test
  void a_run_still_going_is_reported_as_running() {
    Transcript printed = new Transcript();

    int status =
        plowshare(printed, new Transcript(), "job", "status", "job_000003", "--server", where());

    assertEquals(0, status, printed.text());
    assertTrue(printed.text().contains("running"), printed.text());
    assertTrue(
        printed.text().contains("stop"),
        "a run somebody had already asked to stop did not say so: " + printed.text());
  }

  /**
   * A run is stopped by id, from a second terminal or after the first one lost its socket — which
   * is exactly what {@code stillRunning} tells a person to do, and could not be done from here
   * until now.
   */
  @Test
  void a_run_is_stopped_by_id() {
    Transcript printed = new Transcript();

    int status =
        plowshare(printed, new Transcript(), "job", "cancel", "job_000003", "--server", where());

    assertEquals(0, status, printed.text());
    assertEquals("/v1/jobs/job_000003/cancel", seen.get(0).getPath());
    assertEquals("POST", seen.get(0).getMethod());
    assertTrue(
        printed.text().contains("next turn boundary"),
        "a cancel was reported as though it were immediate: " + printed.text());
  }

  // --- the per-document ask ---------------------------------------------------------

  /**
   * <b>The document is the route and the question is the body, and the verb reports a handle rather
   * than an answer.</b>
   *
   * <p>Two things only a real request checks. The document id travels as a path segment, so a
   * client that put it in the body would compile, bind and POST to a route that does not exist; and
   * a deliberation is three model calls in series, so a verb that printed anything resembling an
   * answer would be describing a job that has not run.
   */
  @Test
  void an_ask_names_the_document_in_the_route_and_reports_a_handle() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    int status =
        plowshare(
            printed,
            complaints,
            "document",
            "ask",
            "11111111-2222-3333-4444-555555555555",
            "is the bound tight",
            "--server",
            where());

    assertEquals(0, status, printed.text() + complaints.text());
    assertEquals("/v1/documents/11111111-2222-3333-4444-555555555555/ask", seen.get(0).getPath());
    assertTrue(
        seen.get(0).getBody().readUtf8().contains("is the bound tight"),
        "the question did not reach the server");
    assertTrue(printed.text().contains("job_000042"), printed.text());
    assertTrue(printed.text().contains("did not wait"), printed.text());
    assertTrue(
        printed.text().contains("job status"),
        "the verb that reads the answer was not named: " + printed.text());
  }

  /**
   * And it says what the answer will carry, because a quotation that is not in the paragraph it
   * names is the one signal the ask exists to produce.
   */
  @Test
  void an_ask_says_that_every_quotation_is_checked_against_its_paragraph() {
    Transcript printed = new Transcript();
    Transcript complaints = new Transcript();

    plowshare(
        printed,
        complaints,
        "document",
        "ask",
        "11111111-2222-3333-4444-555555555555",
        "anything",
        "--server",
        where());

    assertTrue(printed.text().contains("failed attribution"), printed.text());
  }

  // --- fixtures ---------------------------------------------------------------------

  private int plowshare(Transcript printed, Transcript complaints, String... args) {
    return Plowshare.run(args, empty(), printed.stream(), complaints.stream());
  }

  private static InputStream empty() {
    return new ByteArrayInputStream(new byte[0]);
  }

  private String where() {
    return server.url("/").toString();
  }

  private List<String> paths() {
    return seen.stream().map(RecordedRequest::getPath).toList();
  }

  /** A port nothing is listening on: bound to claim it, then released. */
  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /**
   * The server's end of one socket.
   *
   * <p>{@code onClosing} answers the close, for the reason {@code ReplTest} records: without it the
   * handshake never completes and {@link MockWebServer#shutdown()} fails every test in the class
   * after the assertions have passed.
   */
  private static MockResponse upgrade() {
    return new MockResponse()
        .withWebSocketUpgrade(
            new WebSocketListener() {
              @Override
              public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, null);
              }
            });
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
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
}
