package io.aeyer.plowshare.client.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.HttpServerClient;
import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.SearchPage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What {@code search} does with its arguments and what it renders back.
 *
 * <p>Translated from the task brief's AssertJ sketch into this repo's own
 * convention — {@link org.junit.jupiter.api.Assertions}, never AssertJ, which
 * is not a dependency of any module here.
 *
 * <p>The first four tests are the ones the brief specifies verbatim (as JUnit
 * assertions): the schema names four parameters and no provider, the
 * description never names a vendor, a refusal renders as its own text, and a
 * title carrying a newline cannot forge a line of its own. The rest exercise
 * the handler's argument defaults and its wiring to {@link
 * io.aeyer.plowshare.client.HttpServerClient} over a real socket, on {@code
 * PresenceToolsTest}'s own precedent for testing a narrow tool surface against
 * {@link MockWebServer} rather than a hand-written {@link ServerClient} stub.
 */
class SearchToolsTest {

    // --- the brief's four tests, translated -------------------------------------

    @Test
    void the_schema_names_four_parameters_and_no_provider() {
        String schema = SearchTools.searchSchema().toString();

        assertTrue(schema.contains("query"));
        assertTrue(schema.contains("page_size"));
        assertTrue(schema.contains("max"));
        assertTrue(schema.contains("page"));
        assertFalse(schema.toLowerCase().contains("provider"));
    }

    @Test
    void the_description_never_names_a_vendor() {
        String d = SearchTools.SEARCH_DESCRIPTION.toLowerCase();

        assertFalse(d.contains("brave"));
        assertFalse(d.contains("searxng"));
        assertFalse(d.contains("google"));
        assertFalse(d.contains("tavily"));
    }

    @Test
    void a_refusal_is_rendered_as_the_text_a_model_reads() {
        String rendered = SearchTools.render(pageWithRefusal("no search provider is configured"));
        assertTrue(rendered.startsWith("SEARCH FAILED — "));
        assertTrue(rendered.contains("no search provider is configured"));
    }

    @Test
    void a_title_carrying_a_newline_cannot_forge_a_line_of_its_own() {
        String rendered = SearchTools.render(pageWithHit(
                "https://a.example", "A\nnot a real heading", "snippet\nwith a newline"));

        assertEquals(0, rendered.lines()
                .filter(l -> l.startsWith("not a real heading"))
                .count());
    }

    // --- every provider-supplied string is flattened, not only the title -------

    @Test
    void a_url_or_snippet_carrying_a_newline_also_cannot_forge_a_line() {
        String rendered = SearchTools.render(pageWithHit(
                "https://a.example/\nnot-a-url-line", "title", "one\ntwo\nthree"));

        assertEquals(0, rendered.lines().filter(l -> l.startsWith("not-a-url-line")).count());
        assertEquals(0, rendered.lines().filter(l -> l.equals("two")).count());
        assertEquals(0, rendered.lines().filter(l -> l.equals("three")).count());
    }

    // --- an empty page is not the same as a refused one -------------------------

    @Test
    void an_empty_result_set_says_so_without_reading_as_a_refusal() {
        SearchPage empty = new SearchPage(List.of(), 1, 10, 0, false, null);

        String rendered = SearchTools.render(empty);

        assertTrue(rendered.toLowerCase().contains("no results"), rendered);
    }

    /**
     * The whole rendered sentence, not a digit somewhere in it.
     *
     * <p>This used to assert only that {@code "4"} appeared, which is the
     * weakest possible reading of the property: {@code total} is the one
     * number here, but a renderer that printed the page, the page size or a
     * count of its own would satisfy it just as well, and so would one that
     * dropped the distinction between "nothing was found" and "nothing on
     * this page" while happening to mention a 4. The distinction is the whole
     * point of the branch — a model told "no results" rephrases a query that
     * already worked — so the assertion is the sentence, singular/plural and
     * all.
     */
    @Test
    void a_page_past_the_end_is_not_reported_as_no_results_at_all() {
        SearchPage pastTheEnd = new SearchPage(List.of(), 3, 10, 4, false, null);

        String rendered = SearchTools.render(pastTheEnd);

        assertEquals("nothing on page 3 of this search — it holds 4 results in all.", rendered);
    }

    // --- registration ------------------------------------------------------------

    @Test
    void the_tool_is_registered_under_its_own_name_and_nothing_else() {
        ToolRegistry registry = new ToolRegistry();
        new SearchTools(new RefusingServerClient()).registerOn(registry);

        assertEquals(List.of("search"),
                registry.tools().stream().map(ToolRegistry.Tool::name).toList());
    }

    // --- arguments -----------------------------------------------------------------

    @Test
    void a_missing_query_is_refused_before_anything_is_asked() {
        SearchTools tools = new SearchTools(new RefusingServerClient());

        assertThrows(IllegalArgumentException.class, () -> tools.search(Map.of()));
    }

    // --- wired to the server, defaults included -----------------------------------

    private MockWebServer server;
    private HttpServerClient http;
    private SearchTools tools;
    private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"hits\":[{\"url\":\"https://a.example\",\"title\":\"A\","
                                + "\"snippet\":\"a snippet\"}],\"page\":1,\"pageSize\":10,"
                                + "\"total\":1,\"hasMore\":false,\"refusal\":null}");
            }
        });
        server.start();
        http = new HttpServerClient(server.url("/").toString());
        tools = new SearchTools(http);
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    void omitted_page_size_max_and_page_send_the_documented_defaults() throws Exception {
        tools.search(args("query", "plowshare"));

        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/search", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"pageSize\":" + SearchTools.DEFAULT_PAGE_SIZE), body);
        assertTrue(body.contains("\"max\":" + SearchTools.DEFAULT_MAX), body);
        assertTrue(body.contains("\"page\":" + SearchTools.DEFAULT_PAGE), body);
        assertTrue(body.contains("\"query\":\"plowshare\""), body);
    }

    @Test
    void explicit_page_size_max_and_page_override_the_defaults() throws Exception {
        tools.search(args("query", "plowshare", "page_size", 5, "max", 40, "page", 2));

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"pageSize\":5"), body);
        assertTrue(body.contains("\"max\":40"), body);
        assertTrue(body.contains("\"page\":2"), body);
    }

    @Test
    void a_result_from_the_server_is_rendered_with_its_hit() {
        String rendered = tools.search(args("query", "plowshare")).toString();

        assertTrue(rendered.contains("https://a.example"), rendered);
        assertTrue(rendered.contains("a snippet"), rendered);
    }

    // --- helpers -------------------------------------------------------------------

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static SearchPage pageWithRefusal(String refusal) {
        return new SearchPage(List.of(), 1, 10, 0, false, refusal);
    }

    private static SearchPage pageWithHit(String url, String title, String snippet) {
        return new SearchPage(List.of(new Hit(url, title, snippet)), 1, 10, 1, false, null);
    }

    /** A {@link ServerClient} that fails any call it is asked to make, for the
     *  tests above that must never reach the network at all — a missing query
     *  is refused before {@link SearchTools#search} ever calls it. */
    private static final class RefusingServerClient implements ServerClient {
        @Override
        public String baseUrl() {
            return "http://unused.example";
        }

        @Override
        public SearchPage search(String query, int pageSize, int max, int page) {
            throw new AssertionError("the server must not be called: " + query);
        }

        // Every other method on ServerClient is either default or unused by
        // this test; nothing here calls them.
        // Added when the images slice's uploadImage reached ServerClient: this
        // double predates it, so the merge compiled everywhere except here.
        @Override
        public UploadedImage uploadImage(String project, String filename, byte[] bytes) {
            throw new UnsupportedOperationException("not the subject of this test");
        }

        @Override
        public io.aeyer.plowshare.protocol.WriteResult write(
                String project, io.aeyer.plowshare.protocol.MemoryProposal proposal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Recall recall(String project, String question, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.aeyer.plowshare.protocol.Memory read(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IndexEntry> index(String project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentSearch searchDocuments(String query, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Retrieved retrieve(String query, String documentId, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentPage listDocuments(String naming, Integer limit, Integer offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentOutline describeDocument(String documentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Ranking rankDocuments(String query, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stance documentStance(String documentId, String claim) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Citations citations(String conversationId, String documentId, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StartedJob run(String agent, String task, String project, String session,
                String conversation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Conversation openConversation(String project, Integer maxModelCalls) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Conversation> conversations(String project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entries chat(String conversationId, Integer offset, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entries trajectory(String conversationId, Integer offset, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LogHits searchEntries(String project, String query, Integer offset, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Context context(String conversationId, String agent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Seam> compactions(String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StartedJob curate(String project, Integer maxModelCalls) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StartedJob askDocument(String documentId, String question, Integer maxModelCalls) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JobStatus job(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JobStatus cancelJob(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProjectView defineProject(String name, String workspace, List<String> exclusions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProjectView lendProject(String name, List<String> roots) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProjectView unlendProject(String name, List<String> roots) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProjectView setProjectWorkspace(String name, String workspace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void moveProject(String name, String to) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forgetProject(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProposalRow> proposals(String project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Resolution resolve(String id, boolean accept, String reason, String by) {
            throw new UnsupportedOperationException();
        }
    }
}
