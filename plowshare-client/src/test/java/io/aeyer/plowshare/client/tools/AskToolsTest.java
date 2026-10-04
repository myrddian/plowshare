package io.aeyer.plowshare.client.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.HttpServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-document ask over MCP.
 *
 * <p>{@code HttpServerClientTest}'s harness rather than a hand-written stub of the whole {@code
 * ServerClient}: what this tool does is one POST and one rendering, and the two things worth
 * pinning are <em>which</em> request goes out — the document is a path segment, so a client that
 * put it in the body would bind cleanly and ask the wrong route — and that what comes back is a
 * handle rather than an answer. MockWebServer answers both, on a port it chooses.
 */
class AskToolsTest {

  private static final String DOCUMENT = "11111111-2222-3333-4444-555555555555";

  private MockWebServer server;
  private AskTools tools;

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.start();
    tools = new AskTools(new HttpServerClient(server.url("/").toString()));
  }

  @AfterEach
  void stop() throws IOException {
    server.shutdown();
  }

  @Test
  void the_document_is_the_route_and_the_question_is_the_body() throws Exception {
    server.enqueue(started());

    tools.ask(args("document", DOCUMENT, "question", "is the bound tight"));

    RecordedRequest sent = server.takeRequest();
    assertEquals("/v1/documents/" + DOCUMENT + "/ask", sent.getPath());
    assertEquals("POST", sent.getMethod());
    String body = sent.getBody().readUtf8();
    assertTrue(body.contains("\"question\":\"is the bound tight\""), body);
  }

  /**
   * <b>What comes back is a handle, and the answer says so twice.</b>
   *
   * <p>{@code AgentTools}' rule, and it matters more here than on a search: a pass is three model
   * calls in series, so a caller told "here is what the document says" would be reading a sentence
   * about a job that has not run.
   */
  @Test
  void the_answer_is_a_handle_and_names_the_two_verbs_that_read_it() {
    server.enqueue(started());

    String answered = tools.ask(args("document", DOCUMENT, "question", "anything")).toString();

    assertTrue(answered.contains("job_000001"), answered);
    assertTrue(answered.contains("agent_poll"), answered);
    assertTrue(answered.contains("agent_result"), answered);
    assertTrue(answered.contains("did not wait"), answered);
  }

  /**
   * And it says what the answer will carry, because a failed attribution is the one signal this
   * whole capability exists to produce.
   */
  @Test
  void the_answer_says_that_quotations_are_checked_against_the_paragraphs_they_name() {
    server.enqueue(started());

    String answered = tools.ask(args("document", DOCUMENT, "question", "anything")).toString();

    assertTrue(answered.contains("checked against the paragraph it names"), answered);
  }

  @Test
  void an_ask_with_no_document_or_no_question_is_refused_before_the_server_is_asked() {
    assertThrows(IllegalArgumentException.class, () -> tools.ask(args("question", "anything")));
    assertThrows(
        IllegalArgumentException.class,
        () -> tools.ask(args("document", DOCUMENT, "question", "  ")));
    assertEquals(0, server.getRequestCount());
  }

  /**
   * <b>The description says this is not a search, and names the tool that is.</b>
   *
   * <p>A calling model chooses from the description alone. The failure this guards is the plausible
   * one: an agent reaching for a per-document deliberation because it wanted a passage, paying
   * three model calls and minutes for a question {@code document_search} answers in one embedding
   * call.
   */
  @Test
  void the_description_says_what_it_costs_and_which_tool_to_reach_for_first() {
    assertTrue(AskTools.ASK_DESCRIPTION.contains("document_search"), AskTools.ASK_DESCRIPTION);
    assertTrue(
        AskTools.ASK_DESCRIPTION.contains("It is not a narrower search"), AskTools.ASK_DESCRIPTION);
    assertTrue(
        AskTools.ASK_DESCRIPTION.contains("minutes rather than seconds"), AskTools.ASK_DESCRIPTION);
  }

  /**
   * And that the document's own words are somebody's upload, which is the third rule every family
   * on this surface keeps.
   */
  @Test
  void the_description_says_the_documents_words_are_not_this_systems_claims() {
    assertTrue(
        AskTools.ASK_DESCRIPTION.contains("not this system's claims"), AskTools.ASK_DESCRIPTION);
  }

  @Test
  void the_tool_registers_under_one_name() {
    ToolRegistry registry = new ToolRegistry();
    tools.registerOn(registry);

    assertEquals(
        List.of("document_ask"), registry.tools().stream().map(ToolRegistry.Tool::name).toList());
  }

  private static MockResponse started() {
    return new MockResponse()
        .setResponseCode(202)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"id\":\"job_000001\",\"agent\":\"ask\"}");
  }

  private static Map<String, Object> args(String... pairs) {
    Map<String, Object> args = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      args.put(pairs[i], pairs[i + 1]);
    }
    return args;
  }
}
