package io.aeyer.plowshare.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.MemoryProposal;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real HTTP client against a real socket, for the one thing a stubbed {@code ServerClient} can
 * never measure: what this class does with an answer it did not expect.
 *
 * <p>{@code MemoryToolsTest} stubs the interface, so it exercises how a tool renders a {@code
 * ServerError} but never how one comes to exist. {@code EndToEndTest} drives the real client
 * against a real server, but a working one — every response there is a 2xx. Between them, nothing
 * made the server answer non-2xx to {@link HttpServerClient}, and deleting the {@code if
 * (!response.isSuccessful())} guard in {@code send} left all 176 tests green.
 *
 * <p>What that guard prevents is named in {@link ServerClient}'s own javadoc as the thing that must
 * never happen. Without it a 404's error document is bound straight onto a {@link
 * io.aeyer.plowshare.protocol.Memory}, and the binding failure surfaces as {@code
 * ServerUnreachableException: could not reach the Plowshare server at … — Cannot construct instance
 * of Memory}. The server is fine; it answered, promptly and correctly, that the id does not exist.
 * A caller told the server is unreachable goes off restarting it.
 *
 * <p>MockWebServer rather than a live server, because the whole point is to produce statuses and
 * bodies a working server would not: a 404 with a real error document, an HTML 502 from something
 * in front of the server, a 200 with nothing in it.
 */
class HttpServerClientTest {

  private MockWebServer server;
  private HttpServerClient client;

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.start();
    client = new HttpServerClient(server.url("/").toString());
  }

  @AfterEach
  void stop() throws IOException {
    server.shutdown();
  }

  // --- reading a conversation back, on the wire -----------------------------

  /**
   * The two readings are two paths, and neither is the other.
   *
   * <p>The one thing only a real request can check about this pair. Both answer the same shape, so
   * a client that sent a chat where a trajectory was asked for would bind cleanly and render a
   * conversation with the folded turns missing — which reads as a run that never did them.
   */
  @Test
  void a_chat_and_a_trajectory_are_two_paths_and_carry_the_page_they_asked_for() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"total":57,"offset":10,"limit":20,"entries":[
                          {"ordinal":11,"turnOrdinal":3,"kind":"tool_result",
                           "excerpt":"it says here","length":120000,"cut":true,
                           "supersededBy":9,"toolCallId":"c1","toolCalls":[],
                           "handle":"h-1","recordedAt":"2026-09-04T09:00:00Z",
                           "tookMillis":940}]}"""));

    ServerClient.Entries page = client.trajectory("cnv_1", 10, 20);

    assertEquals(
        "/v1/conversations/cnv_1/trajectory?offset=10&limit=20", server.takeRequest().getPath());
    assertEquals(57, page.total());
    assertEquals(20, page.limit());
    ServerClient.Entry entry = page.entries().get(0);
    assertEquals("tool_result", entry.kind());
    assertEquals(120000, entry.length());
    assertTrue(entry.cut());
    assertEquals(9, entry.supersededBy());
    assertEquals(940L, entry.tookMillis());
    assertEquals(Instant.parse("2026-09-04T09:00:00Z"), entry.recordedAt());

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"total":2,"offset":0,"limit":20,"entries":[]}"""));

    client.chat("cnv_1", null, null);
    assertEquals(
        "/v1/conversations/cnv_1/chat",
        server.takeRequest().getPath(),
        "no offset and no limit means no query at all: absent and 0 are different"
            + " requests, and a limit of 0 is refused as a page holding nothing");
  }

  /**
   * A search is its own path, carries the question as a parameter, and binds the counts that say
   * what it could not look at.
   *
   * <p><b>The reach is the half a wire test has to check.</b> Every other field here would be
   * noticed the moment a screen rendered it; three counts that quietly bound to zero would render
   * as "searched 0 entries" — which reads as an empty tier and is the exact wrong conclusion a
   * search of a full one could hand somebody.
   */
  @Test
  void a_search_sends_the_question_and_binds_what_it_could_not_reach() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"total":3,"offset":0,"limit":20,
                         "reach":{"searched":120,"ejected":4,"recordedOnly":31},
                         "hits":[
                          {"conversationId":"cnv_1","ordinal":7,"turnOrdinal":3,
                           "kind":"tool_result","rank":0.25,
                           "snippet":"the [retry] [budget] refilled","length":96000,
                           "supersededBy":4,"handle":"h-1",
                           "recordedAt":"2026-09-04T09:00:00Z"}]}"""));

    ServerClient.LogHits found = client.searchEntries("payments", "retry budget", 0, 20);

    assertEquals(
        "/v1/entries/search?offset=0&limit=20&q=retry%20budget&project=payments",
        server.takeRequest().getPath());
    assertEquals(3, found.total());
    assertEquals(120, found.reach().searched());
    assertEquals(4, found.reach().ejected());
    assertEquals(31, found.reach().recordedOnly());
    ServerClient.LogHit hit = found.hits().get(0);
    assertEquals("cnv_1", hit.conversationId());
    assertEquals(7, hit.ordinal());
    assertEquals(96000, hit.length());
    assertEquals(4, hit.supersededBy());
    assertEquals("h-1", hit.handle());
    assertEquals(Instant.parse("2026-09-04T09:00:00Z"), hit.recordedAt());

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"total":0,"offset":0,"limit":100,
                         "reach":{"searched":0,"ejected":0,"recordedOnly":0},"hits":[]}"""));

    client.searchEntries(null, "budget", null, null);
    assertEquals(
        "/v1/entries/search?q=budget",
        server.takeRequest().getPath(),
        "a tier nobody named is the global one, sent as an absent parameter and never"
            + " as an empty string");
  }

  /**
   * An entry whose timing is absent binds a null and not an epoch.
   *
   * <p>{@code recordedAt} and {@code tookMillis} are legitimately absent — an entry written before
   * the column existed, and a kind that records no operation — and a client that bound either to a
   * zero would report an instant measured at 1970 and an operation measured at nothing. The second
   * is the worse one: nought milliseconds is a possible duration.
   */
  @Test
  void an_entry_nobody_timed_binds_nulls_and_not_zeroes() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"total":1,"offset":0,"limit":20,"entries":[
                          {"ordinal":1,"turnOrdinal":1,"kind":"utterance",
                           "excerpt":"what broke it?","length":14,"cut":false,
                           "supersededBy":null,"toolCallId":null,"toolCalls":[],
                           "handle":null,"recordedAt":null,"tookMillis":null}]}"""));

    ServerClient.Entry entry = client.chat("cnv_1", 0, 20).entries().get(0);

    assertNull(entry.recordedAt());
    assertNull(entry.tookMillis());
    assertNull(entry.supersededBy());
  }

  /**
   * A listing of one tier omits the parameter for global rather than sending it empty, which is the
   * line every other tier-scoped read here draws.
   */
  @Test
  void listing_conversations_names_a_tier_or_omits_it_entirely() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        [{"id":"cnv_1","project":"payments","maxModelCalls":40,
                          "modelCallsSpent":3,"maxTurns":null,"noTurnCap":false}]"""));

    List<ServerClient.Conversation> open = client.conversations("payments");

    assertEquals("/v1/conversations?project=payments", server.takeRequest().getPath());
    assertEquals("cnv_1", open.get(0).id());
    assertEquals(40, open.get(0).maxModelCalls());

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("[]"));

    client.conversations(null);
    assertEquals("/v1/conversations", server.takeRequest().getPath());
  }

  /**
   * A context binds the measured number and every stated absence, and the absences bind as nulls.
   *
   * <p><b>The half that could quietly go wrong.</b> Jackson binds a missing or null {@code
   * toolTokens} to a null {@code Integer} either way, and a client that had declared it an {@code
   * int} would bind it to zero — reporting that the tool block was measured and found to be free,
   * which is the opposite of what the server said and exactly the plausible number this whole
   * surface exists to refuse.
   */
  @Test
  void a_context_binds_the_one_measured_number_and_the_reasons_for_the_rest() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"sent":18543,"sentAtTurn":12,"turns":14,"turnsMeasured":12,
                         "systemPromptTokens":null,"toolTokens":null,"messageTokens":null,
                         "cacheHitRate":null,
                         "unavailable":[{"component":"cacheHitRate",
                                         "reason":"usage carries no cached_tokens"}],
                         "prefix":{"agent":"interlocutor","model":"reasoning",
                                   "systemPromptCharacters":3341,"toolCharacters":12109,
                                   "tools":[{"name":"file_read","characters":1915}]}}"""));

    ServerClient.Context context = client.context("cnv_1", "interlocutor");

    assertEquals(
        "/v1/conversations/cnv_1/context?agent=interlocutor", server.takeRequest().getPath());
    assertEquals(18543, context.sent());
    assertNull(context.toolTokens());
    assertNull(context.cacheHitRate());
    assertEquals("cacheHitRate", context.unavailable().get(0).component());
    assertTrue(context.unavailable().get(0).reason().contains("cached_tokens"));
    assertEquals(12109, context.prefix().toolCharacters());
    assertEquals(1915, context.prefix().tools().get(0).characters());
  }

  // --- the job surface, on the wire ----------------------------------------

  /**
   * The paths and the bodies, asserted against the recorded request.
   *
   * <p>Not "does it return what the stub said" — {@code AgentToolsTest} measures that with no
   * socket at all. This is the half only a real request can see: that the agent's name is a path
   * segment, that the run's tier goes in the body, and that a null project is an explicit null
   * rather than an omitted key. The last one matters because the server's {@code RunAgentRequest}
   * reads a missing key and an explicit null identically — today. A key the server never sees is a
   * key that cannot be renamed safely.
   */
  @Test
  void starting_a_run_posts_the_task_and_the_tier_under_the_agents_name() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(202)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"id":"job_000001","agent":"promotion_judge"}"""));

    ServerClient.StartedJob started =
        client.run("promotion_judge", "rule on mem_000001", "payments", null, null);

    assertEquals("job_000001", started.id());
    var request = server.takeRequest();
    assertEquals("/v1/agents/promotion_judge/runs", request.getPath());
    String body = request.getBody().readUtf8();
    assertTrue(body.contains("\"task\":\"rule on mem_000001\""), body);
    assertTrue(body.contains("\"project\":\"payments\""), body);
  }

  @Test
  void a_run_with_no_project_sends_an_explicit_null_rather_than_omitting_the_key()
      throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(202)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"id":"job_000001","agent":"scribe"}"""));

    client.run("scribe", "go", null, null, null);

    assertTrue(server.takeRequest().getBody().readUtf8().contains("\"project\":null"));
  }

  /**
   * A finished job binds its outcome, and an unfinished one binds a null where the outcome would
   * be.
   *
   * <p>The pair is the claim. {@code AgentTools.result} branches on exactly this null, so a client
   * that bound a missing {@code outcome} to some empty object rather than to null would render a
   * run still going as one that answered nothing — the confident-empty-answer shape, on the job
   * surface.
   */
  @Test
  void a_job_still_going_binds_a_null_outcome_and_a_finished_one_binds_its_ending()
      throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"id":"job_000001","agent":"scribe","state":"RUNNING",
                         "cancelRequested":false}"""));

    ServerClient.JobStatus going = client.job("job_000001");
    assertEquals("RUNNING", going.state());
    assertTrue(going.outcome() == null, "an unfinished run must bind a null outcome");
    assertEquals("/v1/jobs/job_000001", server.takeRequest().getPath());

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"id":"job_000001","agent":"scribe","state":"DONE",
                         "cancelRequested":false,
                         "outcome":{"ending":"TURN_CAP","answered":false,"text":"stopped",
                                    "steps":4,"modelCalls":4,"detail":""}}"""));

    ServerClient.JobStatus done = client.job("job_000001");
    assertEquals("TURN_CAP", done.outcome().ending());
    assertFalse(done.outcome().answered());
    assertEquals(4, done.outcome().steps());
  }

  @Test
  void cancelling_posts_to_the_jobs_cancel_path() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"id":"job_000001","agent":"scribe","state":"RUNNING",
                         "cancelRequested":true}"""));

    assertTrue(client.cancelJob("job_000001").cancelRequested());
    assertEquals("/v1/jobs/job_000001/cancel", server.takeRequest().getPath());
  }

  @Test
  void curating_posts_the_project_and_the_budget() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(202)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"id":"job_000007","agent":"curator"}"""));

    client.curate("payments", 40);

    var request = server.takeRequest();
    assertEquals("/v1/curate", request.getPath());
    String body = request.getBody().readUtf8();
    assertTrue(body.contains("\"project\":\"payments\""), body);
    assertTrue(body.contains("\"maxModelCalls\":40"), body);
  }

  // --- the workspace surface, on the wire ----------------------------------

  @Test
  void defining_a_project_posts_its_name_workspace_and_exclusions() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"name":"payments","workspace":"/srv/repo",
                         "exclusions":["/etc/plowshare.yml","/srv/agents"]}"""));

    ServerClient.ProjectView view =
        client.defineProject("payments", "/srv/repo", List.of("/srv/repo/secrets"));

    var request = server.takeRequest();
    assertEquals("POST", request.getMethod());
    assertEquals("/v1/projects", request.getPath());
    String body = request.getBody().readUtf8();
    assertTrue(body.contains("\"name\":\"payments\""), body);
    assertTrue(body.contains("\"workspace\":\"/srv/repo\""), body);
    assertTrue(body.contains("\"exclusions\":[\"/srv/repo/secrets\"]"), body);
    // The answer's list, which is longer than the one just sent: the two
    // mandatory exclusions are the server's and are what this call is read
    // for.
    assertEquals(List.of("/etc/plowshare.yml", "/srv/agents"), view.exclusions());
  }

  /**
   * The project is a path segment on a move and a body field on a definition.
   *
   * <p>The difference is what the two calls mean — one addresses a project that exists, the other
   * writes one — and a client that confused them would post a whole definition at the move
   * endpoint, replacing the exclusions this call exists to keep.
   */
  @Test
  void moving_a_workspace_puts_the_project_in_the_path_and_only_the_workspace_in_the_body()
      throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"name":"payments","workspace":"/srv/moved","exclusions":[]}"""));

    client.setProjectWorkspace("payments", "/srv/moved");

    var request = server.takeRequest();
    assertEquals("POST", request.getMethod());
    assertEquals("/v1/projects/payments/workspace", request.getPath());
    String body = request.getBody().readUtf8();
    assertTrue(body.contains("\"workspace\":\"/srv/moved\""), body);
    assertFalse(body.contains("exclusions"), body);
  }

  /**
   * A 204 has no body, and this is the one call in the client that must not ask for one: {@code
   * send} refuses a blank body, rightly, because every other endpoint here promises JSON.
   */
  @Test
  void forgetting_a_project_is_a_delete_and_takes_no_answer() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(204));

    client.forgetProject("payments");

    var request = server.takeRequest();
    assertEquals("DELETE", request.getMethod());
    assertEquals("/v1/projects/payments", request.getPath());
  }

  /**
   * And the status is still checked, so a 404 does not read as a workspace successfully dropped —
   * the outcome an operator would not go back and verify.
   */
  @Test
  void forgetting_a_project_the_server_does_not_know_is_still_an_error() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(404)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"error":"not_found","detail":"no project named payments has a workspace"}"""));

    ServerClient.ServerError refused =
        assertThrows(ServerClient.ServerError.class, () -> client.forgetProject("payments"));

    assertEquals(404, refused.status());
    assertTrue(refused.getMessage().contains("no project named payments"), refused.getMessage());
  }

  /**
   * The project name is percent-encoded into one segment on both endpoints that carry it in the
   * path.
   *
   * <p>Both, and not one: a name holding a slash is what walks a request onto a different endpoint,
   * and a test covering only the delete leaves the same hole open on the move. Measured — a version
   * of this file that asserted only the delete left a hand-built {@code addPathSegments} on the
   * move entirely green.
   */
  @Test
  void a_project_name_with_a_slash_cannot_reach_another_endpoint() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(204));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"name":"x","workspace":"/srv/moved","exclusions":[]}"""));
    server.enqueue(new MockResponse().setResponseCode(204));

    client.forgetProject("payments/../agents");
    client.setProjectWorkspace("payments/../agents", "/srv/moved");
    client.moveProject("payments/../agents", "moved");

    assertEquals("/v1/projects/payments%2F..%2Fagents", server.takeRequest().getPath());
    assertEquals("/v1/projects/payments%2F..%2Fagents/workspace", server.takeRequest().getPath());
    assertEquals("/v1/projects/payments%2F..%2Fagents/move", server.takeRequest().getPath());
  }

  /**
   * The project being moved is a path segment and the name it is getting is a body field, which is
   * the endpoint's own shape: one addresses a project that exists, the other says what to call it.
   * A client that put both in the body would move whichever the server read first.
   *
   * <p><b>And the answer is a 204 with no body, which is the trap this test catches.</b> Every
   * other {@code POST} here is bound through {@code send}, which refuses a blank body — rightly,
   * since those endpoints promise JSON — so a move routed through it would report a healthy server
   * as one that failed without saying so.
   */
  @Test
  void moving_a_project_puts_the_old_name_in_the_path_and_the_new_one_in_the_body()
      throws Exception {
    server.enqueue(new MockResponse().setResponseCode(204));

    client.moveProject("payments", "bench.local/srv/payments/payments");

    var request = server.takeRequest();
    assertEquals("POST", request.getMethod());
    assertEquals("/v1/projects/payments/move", request.getPath());
    assertEquals(
        """
                {"to":"bench.local/srv/payments/payments"}""",
        request.getBody().readUtf8());
  }

  /**
   * A name another project already holds comes back as the server's sentence and not as a parse
   * failure over an empty body.
   */
  @Test
  void a_move_onto_a_name_that_is_taken_carries_the_servers_sentence() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(409)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"error":"conflict","detail":"a project is already called ledger"}"""));

    ServerClient.ServerError refused =
        assertThrows(
            ServerClient.ServerError.class, () -> client.moveProject("payments", "ledger"));

    assertEquals(409, refused.status());
    assertTrue(refused.getMessage().contains("ledger"), refused.getMessage());
  }

  /**
   * The project is a query parameter here and a body field on a run, which is the difference
   * between a listing and a submission; a client that confused them would list the global queue
   * while claiming to list a project's.
   */
  @Test
  void listing_proposals_puts_the_project_in_the_query() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        [{"id":"prp_000001","memoryId":"mem_000001","project":"payments",
                          "action":"promote","reason":"it holds everywhere","state":"pending",
                          "createdAt":"2026-08-29T12:00:00Z"}]"""));

    var waiting = client.proposals("payments");

    assertEquals(1, waiting.size());
    assertEquals("prp_000001", waiting.get(0).id());
    assertEquals("payments", waiting.get(0).project());
    assertEquals("/v1/proposals?project=payments", server.takeRequest().getPath());
  }

  @Test
  void resolving_posts_the_decision_and_who_made_it() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"proposal":{"id":"prp_000001","memoryId":"mem_000001",
                                     "project":"payments","action":"promote",
                                     "reason":"it holds everywhere","state":"accepted",
                                     "createdAt":"2026-08-29T12:00:00Z"},
                         "promotedId":"mem_000009","demoted":["mem_000002"]}"""));

    ServerClient.Resolution settled = client.resolve("prp_000001", true, "agreed", "enzo");

    assertEquals("mem_000009", settled.promotedId());
    assertEquals(java.util.List.of("mem_000002"), settled.demoted());
    var request = server.takeRequest();
    assertEquals("/v1/proposals/prp_000001/resolve", request.getPath());
    String body = request.getBody().readUtf8();
    assertTrue(body.contains("\"accept\":true"), body);
    assertTrue(body.contains("\"by\":\"enzo\""), body);
  }

  /**
   * A refusal out of the queue reaches the caller as a refusal, not as a failure to reach the
   * server.
   *
   * <p>Same guard as the memory half's, and it is worth a case of its own here because this is the
   * endpoint whose refusals a person acts on: "that proposal is already settled" and "the server is
   * down" have completely different next steps.
   */
  @Test
  void a_proposal_already_settled_reaches_the_caller_as_the_servers_own_sentence() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(404)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"error":"not_found","detail":"proposal prp_000001 was already rejected
                         by curator"}"""
                    .replace("\n", " ")));

    ServerClient.ServerError refused =
        assertThrows(
            ServerClient.ServerError.class, () -> client.resolve("prp_000001", true, null, "enzo"));

    assertTrue(refused.getMessage().contains("already rejected"), refused.getMessage());
    assertFalse(
        refused.getMessage().toLowerCase().contains("could not reach"), refused.getMessage());
  }

  // --- the server answered, and said no ------------------------------------

  /**
   * A 404 is a fact about the request, and it arrives as one.
   *
   * <p>The status is checked <em>before</em> the body is bound, so the error document never reaches
   * Jackson as a {@code Memory}. Both halves are asserted: that a {@code ServerError} is thrown,
   * and that the message a caller reads does not claim the server could not be reached.
   */
  @Test
  void an_unknown_id_is_a_server_error_carrying_the_status_and_the_detail() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(404)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"error":"not_found","detail":"no memory with id mem_nope"}"""));

    ServerClient.ServerError refused =
        assertThrows(ServerClient.ServerError.class, () -> client.read("mem_nope"));

    assertEquals(404, refused.status());
    // The server's own sentence, not a parser's complaint about it: this is
    // what the model ends up reading.
    assertTrue(refused.getMessage().contains("no memory with id mem_nope"), refused.getMessage());
    assertFalse(
        refused.getMessage().toLowerCase().contains("could not reach"), refused.getMessage());
    assertFalse(refused.getMessage().contains("Cannot construct instance"), refused.getMessage());
  }

  /**
   * And it is not an {@link IOException}, which is the distinction {@code MemoryTools.ask} branches
   * on: an IOException there becomes "could not reach the server", and a 404 is not that.
   */
  @Test
  void a_refusal_is_not_reported_as_a_failure_to_reach_the_server() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"error":"validation_failed","detail":"summary must be a single line"}"""));

    Throwable thrown =
        assertThrows(
            Throwable.class,
            () -> client.write("payments", new MemoryProposal("a\nb", "sc", "body", "scribe", "")));

    assertFalse(
        thrown instanceof IOException,
        "a server that answered is not a server that could not be reached: " + thrown);
    assertEquals(422, ((ServerClient.ServerError) thrown).status());
  }

  /**
   * A 503 from the embedding-unavailable mapping reaches the caller with its status intact, so "a
   * side service is down, retry" survives the trip.
   *
   * <p>The recall path binds a tree rather than a record, which is the one response shape where a
   * missing status check would <em>not</em> fail to bind — {@code path("memories")} on an error
   * document is simply an empty node. Without the guard this would return zero hits and no error at
   * all: an outage rendered as an empty archive, which is the exact failure the whole project is
   * organised around.
   */
  @Test
  void an_unavailable_embedding_endpoint_is_an_error_and_never_an_empty_recall() {

    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"error":"embedding_unavailable","detail":"no route to host"}"""));

    ServerClient.ServerError refused =
        assertThrows(
            ServerClient.ServerError.class,
            () -> client.recall("payments", "what is the retry budget?", 10));

    assertEquals(503, refused.status());
    assertTrue(refused.getMessage().contains("no route to host"), refused.getMessage());
  }

  /**
   * A corpus search: the question and the limit go over as a body, and every field of a hit comes
   * back bound.
   *
   * <p><b>The paragraph id is the assertion worth making.</b> It is the only identifier in the
   * response that is meant to be written down and used later, and a field silently missing from
   * this binding would produce hits whose citation was a null — which reads, in every rendering
   * above it, as a hit with nothing to cite rather than as a broken client.
   *
   * <p>The limit is sent and the response's own is read back rather than assumed: the server caps,
   * and this client holds no copy of the number.
   */
  @Test
  void a_corpus_search_sends_the_question_and_binds_every_field_of_a_hit() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"query":"retry budget","limit":10,"searchable":412,"unsearchable":3,
                         "hits":[{"chunkId":"11111111-1111-1111-1111-111111111111",
                                  "text":"Retries are budgeted.","similarity":0.81,
                                  "paragraphId":"22222222-2222-2222-2222-222222222222",
                                  "paragraphText":"Retries are budgeted per run.",
                                  "paragraphOrdinal":4,
                                  "documentId":"33333333-3333-3333-3333-333333333333",
                                  "sourceName":"retries.md","title":"The Retry Budget"}]}"""));

    ServerClient.DocumentSearch found = client.searchDocuments("retry budget", 100);

    okhttp3.mockwebserver.RecordedRequest sent = server.takeRequest();
    assertEquals("/v1/documents/search", sent.getPath());
    assertTrue(sent.getBody().readUtf8().contains("retry budget"));
    // What the server applied, not what was asked for.
    assertEquals(10, found.limit());
    assertEquals(412, found.searchable());
    assertEquals(3, found.unsearchable());
    ServerClient.DocumentHit hit = found.hits().get(0);
    assertEquals("22222222-2222-2222-2222-222222222222", hit.paragraphId().toString());
    assertEquals("Retries are budgeted per run.", hit.paragraphText());
    assertEquals(4, hit.paragraphOrdinal());
    assertEquals("retries.md", hit.sourceName());
    assertEquals(0.81, hit.similarity(), 1e-9);
  }

  /**
   * And a 503 from the embedding endpoint is an error rather than an empty corpus.
   *
   * <p>{@code an_unavailable_embedding_endpoint_is_an_error_and_never_an_empty_recall} one corpus
   * over. This path binds a record rather than a tree, so a missing status check would fail on the
   * shape instead of quietly returning nothing — but the sentence that must survive the trip is the
   * same one, and it is the status that carries it.
   */
  @Test
  void an_unavailable_embedding_endpoint_is_an_error_and_never_an_empty_corpus() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"error":"embedding_unavailable","detail":"no route to host"}"""));

    ServerClient.ServerError refused =
        assertThrows(
            ServerClient.ServerError.class, () -> client.searchDocuments("anything", null));

    assertEquals(503, refused.status());
    assertTrue(refused.getMessage().contains("no route to host"), refused.getMessage());
  }

  /**
   * A non-JSON error body — an HTML 502 from something in front of the server — is passed through
   * truncated rather than replaced by the parser's complaint about it. A message that says only
   * "502" does not tell the reader which server produced it.
   */
  @Test
  void an_html_error_page_still_reaches_the_caller_as_text() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(502)
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body><h1>502 Bad Gateway</h1></body></html>"));

    ServerClient.ServerError refused =
        assertThrows(ServerClient.ServerError.class, () -> client.index("payments"));

    assertEquals(502, refused.status());
    assertTrue(refused.getMessage().contains("502 Bad Gateway"), refused.getMessage());
  }

  /**
   * A 200 with nothing in it is refused rather than bound to an empty record. An index that came
   * back as "no memories" because a proxy ate the body would be an empty archive reported as fact.
   */
  @Test
  void a_success_with_an_empty_body_is_refused_rather_than_read_as_nothing() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(""));

    ServerClient.ServerError empty =
        assertThrows(ServerClient.ServerError.class, () -> client.index("payments"));

    assertTrue(empty.getMessage().contains("empty body"), empty.getMessage());
  }

  // --- uploading a picture --------------------------------------------------

  /**
   * <b>An image goes up as multipart, with the bytes in the {@code file} part.</b>
   *
   * <p>The one thing a stubbed {@code ImageUploads} can never measure. {@code ImageController}
   * takes multipart and only multipart — a route taking a <em>path</em> for the server to read off
   * somebody else's disk is the leash bypass the whole file channel exists to prevent — so a client
   * that sent JSON, or sent a path, would be refused by a server that is working perfectly.
   *
   * <p>The raw bytes are asserted in the body rather than a length or a hash, because what the far
   * side sniffs is the signature: a client that base64'd the part, or transcoded it as text, would
   * produce a 415 out of a perfectly good PNG.
   */
  @Test
  void an_image_is_uploaded_as_multipart_with_its_bytes_and_its_tier() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"id":"img_00000000000000000001","project":"payments",\
                        "format":"png","filename":"/repo/logo.png","bytes":9,\
                        "at":"2026-09-09T00:00:00Z"}"""));

    ServerClient.UploadedImage stored =
        client.uploadImage(
            "payments",
            "/repo/logo.png",
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 7});

    assertEquals("img_00000000000000000001", stored.id());
    assertEquals("png", stored.format());
    var request = server.takeRequest();
    assertEquals("/v1/images", request.getPath());
    assertTrue(
        request.getHeader("Content-Type").startsWith("multipart/form-data"),
        request.getHeader("Content-Type"));
    String body = request.getBody().readString(java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(body.contains("name=\"file\""), body);
    assertTrue(body.contains("filename=\"/repo/logo.png\""), body);
    assertTrue(body.contains("name=\"project\""), body);
    assertTrue(body.contains("payments"), body);
    assertTrue(body.contains("\u0089PNG"), "the PNG signature is not in the part as bytes");
  }

  /**
   * <b>A tier nobody named is an absent field and not an empty one.</b>
   *
   * <p>The opposite of {@link
   * #a_run_with_no_project_sends_an_explicit_null_rather_than_omitting_the_key} and for a reason
   * that is this route's own: a multipart part cannot carry a JSON null, and {@code POST
   * /v1/images} reads an <em>absent</em> project as the global tier while a blank one is a 400. So
   * the key is left out entirely, which is the only spelling of "global" this body shape has.
   */
  @Test
  void an_image_with_no_tier_omits_the_field_rather_than_sending_it_empty() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                        {"id":"img_00000000000000000002","project":null,"format":"gif"}"""));

    client.uploadImage(null, null, "GIF89a".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    String body =
        server.takeRequest().getBody().readString(java.nio.charset.StandardCharsets.ISO_8859_1);
    assertFalse(body.contains("name=\"project\""), body);
    assertFalse(
        body.contains("name=\"name\""),
        "a blank name is a 400 on this route, so a caller with nothing to say sends"
            + " no name part at all: "
            + body);
  }

  /**
   * <b>The three refusals reach the caller as three statuses.</b>
   *
   * <p>{@code ImageController} separates them because the remedies do — send something, convert it,
   * shrink it — and the class that turns a status into a sentence a model can act on is {@code
   * ClientEnforcer}. It can only do that if the number survives the transport, which is what this
   * measures: {@code ServerError} carries the status, and a client that reported them all as one
   * failure would put that decision back in the prose.
   */
  @Test
  void the_statuses_an_image_refusal_uses_reach_the_caller_as_themselves() {
    for (int status : new int[] {400, 413, 415}) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(status)
              .setHeader("Content-Type", "application/json")
              .setBody("{\"error\":\"no\",\"detail\":\"the server said " + status + "\"}"));

      ServerClient.ServerError refused =
          assertThrows(
              ServerClient.ServerError.class,
              () ->
                  client.uploadImage(
                      null,
                      "logo.png",
                      new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));

      assertEquals(status, refused.status());
      assertTrue(refused.getMessage().contains("the server said " + status), refused.getMessage());
    }
  }

  // --- the server was never reached ----------------------------------------

  /**
   * The other branch, so the distinction above is a distinction and not an assertion about one
   * case. Nothing is listening, and that really is an {@link IOException} — which is what {@code
   * MemoryTools} turns into "could not reach the Plowshare server".
   */
  @Test
  void a_server_that_is_not_listening_is_an_io_exception() throws IOException {
    HttpServerClient dead = new HttpServerClient("http://localhost:" + closedPort());

    assertThrows(IOException.class, () -> dead.index("payments"));
  }

  /** A port nothing is listening on: bound to claim it, then released. */
  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
