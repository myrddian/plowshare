package io.aeyer.plowshare.client.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The three corpus reads over MCP.
 *
 * <p>{@code AskToolsTest}'s harness and its reason: what these tools do is one
 * request and one rendering, so what is worth pinning is which request goes out
 * and <b>what the rendering does with an absence</b>. MockWebServer answers
 * both, on a port it chooses.
 *
 * <p><b>The absences are most of this file.</b> A structural unit the document
 * never named arrives as {@code title: null} beside {@code synthetic: true}, and
 * Anchor's own shell — given the identical JSON — prints the four letters "null"
 * as a heading. A chunk whose paragraph is in no section arrives with both units
 * null, which is a third state again. Neither may reach a model as a null, an
 * empty string, or a sentinel.
 */
class RetrieveToolsTest {

    private static final String DOCUMENT = "11111111-2222-3333-4444-555555555555";
    private static final String CHUNK = "22222222-3333-4444-5555-666666666666";
    private static final String PARAGRAPH = "33333333-4444-5555-6666-777777777777";

    private MockWebServer server;
    private RetrieveTools tools;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        tools = new RetrieveTools(new HttpServerClient(server.url("/").toString()));
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    // --- what goes out --------------------------------------------------------

    @Test
    void a_retrieve_posts_the_question_and_leaves_the_document_null_for_the_corpus()
            throws Exception {
        server.enqueue(json(hits(namedUnits())));

        tools.retrieve(args("question", "eleven vertices"));

        RecordedRequest sent = server.takeRequest();
        assertEquals("/v1/documents/retrieve", sent.getPath());
        assertEquals("POST", sent.getMethod());
        String body = sent.getBody().readUtf8();
        assertTrue(body.contains("\"query\":\"eleven vertices\""), body);
        // Sent as an explicit null rather than omitted: the two mean the same
        // thing to the server, and sending it says the caller chose the corpus
        // rather than forgot the document.
        assertTrue(body.contains("\"document\":null"), body);
    }

    @Test
    void a_retrieve_can_name_one_document() throws Exception {
        server.enqueue(json(hits(namedUnits())));

        tools.retrieve(args("question", "q", "document", DOCUMENT, "limit", "2"));

        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"document\":\"" + DOCUMENT + "\""), body);
        assertTrue(body.contains("\"limit\":2"), body);
    }

    @Test
    void a_listing_puts_its_naming_and_its_page_in_the_query_string() throws Exception {
        server.enqueue(json(page()));

        tools.list(args("naming", "graph", "limit", "5", "offset", "10"));

        RecordedRequest sent = server.takeRequest();
        assertEquals("GET", sent.getMethod());
        assertEquals("/v1/documents?q=graph&limit=5&offset=10", sent.getPath());
    }

    @Test
    void a_listing_with_no_naming_asks_for_the_whole_corpus() throws Exception {
        server.enqueue(json(page()));

        tools.list(args());

        assertEquals("/v1/documents", server.takeRequest().getPath());
    }

    @Test
    void an_outline_is_the_document_as_a_path_segment() throws Exception {
        server.enqueue(json(outline()));

        tools.outline(args("document", DOCUMENT));

        RecordedRequest sent = server.takeRequest();
        assertEquals("GET", sent.getMethod());
        assertEquals("/v1/documents/" + DOCUMENT, sent.getPath());
    }

    @Test
    void an_outline_without_a_document_is_refused_before_the_server_is_asked() {
        assertThrows(IllegalArgumentException.class, () -> tools.outline(args()));
        assertEquals(0, server.getRequestCount());
    }

    // --- what comes back ------------------------------------------------------

    @Test
    void a_retrieved_passage_arrives_under_the_argument_it_sits_inside() throws Exception {
        server.enqueue(json(hits(namedUnits())));

        String rendered = tools.retrieve(args("question", "eleven vertices")).toString();

        assertTrue(rendered.contains("paper.md"), rendered);
        assertTrue(rendered.contains("cite paragraph " + PARAGRAPH), rendered);
        assertTrue(rendered.contains("the document argues: refutes the conjecture"), rendered);
        assertTrue(rendered.contains("in Part II"), rendered);
        assertTrue(rendered.contains("in 3. Results"), rendered);
        assertTrue(rendered.contains("the paragraph claims: eleven vertices suffice"), rendered);
        // The stack is above the words, because a passage read before the
        // argument around it is the failure this tool exists to prevent.
        assertTrue(rendered.indexOf("the document argues")
                < rendered.indexOf("The counterexample"), rendered);
    }

    /**
     * <b>The assertion this file is for.</b> Given the same JSON, Anchor's shell
     * prints "null".
     */
    @Test
    void a_unit_the_document_never_named_is_words_and_not_a_null() throws Exception {
        server.enqueue(json(hits(syntheticUnits())));

        String rendered = tools.retrieve(args("question", "q")).toString();

        assertTrue(rendered.contains("in " + Structural.UNNAMED), rendered);
        assertFalse(rendered.contains("null"), rendered);
        assertFalse(rendered.contains("__SYNTHETIC"), rendered);
    }

    @Test
    void a_passage_the_hierarchy_cannot_place_says_so_in_its_own_words() throws Exception {
        server.enqueue(json(hits("\"section\":null,\"chapter\":null")));

        String rendered = tools.retrieve(args("question", "q")).toString();

        assertTrue(rendered.contains(Structural.NO_SECTION), rendered);
        // Not folded into "(unnamed segment)": the document named nothing is a
        // different fact from there being nothing to name, and the server keeps
        // the two apart all the way down.
        assertFalse(rendered.contains(Structural.UNNAMED), rendered);
        assertFalse(rendered.contains("null"), rendered);
    }

    /** Every line of a document is pushed off column zero, which is {@code
     *  DocumentTools}' rule and the same class of text. */
    @Test
    void a_passage_never_reaches_column_zero() throws Exception {
        server.enqueue(json(hits(namedUnits(), "Line one.\nLine two.")));

        String rendered = tools.retrieve(args("question", "q")).toString();

        assertTrue(rendered.contains("Line one."), rendered);
        for (String line : rendered.split("\n", -1)) {
            assertFalse(line.startsWith("Line one.") || line.startsWith("Line two."), rendered);
        }
    }

    @Test
    void an_empty_retrieve_says_which_corpus_it_found_nothing_in() throws Exception {
        server.enqueue(json("{\"query\":\"q\",\"document\":null,\"limit\":5,\"hits\":[]}"));

        String rendered = tools.retrieve(args("question", "q")).toString();

        assertTrue(rendered.contains("the whole corpus"), rendered);
        // An empty answer is a claim about the corpus, so it names the other
        // ways of being empty rather than letting one reading stand.
        assertTrue(rendered.contains("document_list"), rendered);
    }

    @Test
    void a_listing_names_what_the_corpus_holds_and_what_nothing_has_summarised()
            throws Exception {
        server.enqueue(json(page()));

        String rendered = tools.list(args()).toString();

        assertTrue(rendered.contains("paper.md"), rendered);
        assertTrue(rendered.contains(DOCUMENT), rendered);
        assertTrue(rendered.contains("12 paragraphs"), rendered);
        assertTrue(rendered.contains("nothing has summarised it yet"), rendered);
    }

    @Test
    void an_empty_listing_says_whether_the_corpus_is_empty_or_the_naming_matched_nothing()
            throws Exception {
        server.enqueue(json("{\"documents\":[],\"total\":0,\"limit\":50,\"offset\":0,"
                + "\"naming\":null}"));
        assertTrue(tools.list(args()).toString().contains("holds no documents"));

        server.enqueue(json("{\"documents\":[],\"total\":0,\"limit\":50,\"offset\":0,"
                + "\"naming\":\"graph\"}"));
        assertTrue(tools.list(args("naming", "graph")).toString().contains("named anything like"));
    }

    /** V26's {@code top_level_label} has a second reader, and this is it: a paper
     *  that calls its parts sections is never told it has chapters. */
    @Test
    void an_outline_uses_the_documents_own_word_for_its_parts() throws Exception {
        server.enqueue(json(outline()));

        String rendered = tools.outline(args("document", DOCUMENT)).toString();

        assertTrue(rendered.contains("1 sections:"), rendered);
        assertTrue(rendered.contains(Structural.UNNAMED), rendered);
        assertTrue(rendered.contains("1. Introduction"), rendered);
        assertFalse(rendered.contains("null"), rendered);
    }

    // --- ranking whole documents ------------------------------------------------

    @Test
    void a_ranking_posts_the_question_and_reads_documents_back() throws Exception {
        server.enqueue(json(ranking()));

        String rendered = tools.rank(args("question", "graph counterexamples")).toString();

        RecordedRequest sent = server.takeRequest();
        assertEquals("/v1/documents/rank", sent.getPath());
        assertEquals("POST", sent.getMethod());
        assertTrue(rendered.contains("graphs.md"), rendered);
        assertTrue(rendered.contains(DOCUMENT), rendered);
        assertTrue(rendered.contains("it argues: refutes the conjecture"), rendered);
    }

    /**
     * How much of the corpus could be ranked is said on every answer, not only
     * on an empty one.
     *
     * <p>A top result out of two rankable documents in a corpus of nine is a
     * different fact from a top result out of nine, and a model that cannot see
     * which is reading a ranking as a survey.
     */
    @Test
    void a_ranking_says_how_much_of_the_corpus_it_could_see() throws Exception {
        server.enqueue(json(ranking()));
        assertTrue(tools.rank(args("question", "q")).toString()
                .contains("7 have a summary that has not been embedded"));

        server.enqueue(json("{\"query\":\"q\",\"limit\":20,\"documents\":[],"
                + "\"rankable\":0,\"unranked\":9}"));
        String empty = tools.rank(args("question", "q")).toString();
        assertTrue(empty.contains("No document in the corpus is about"), empty);
        assertTrue(empty.contains("9 have a summary that has not been embedded"), empty);
    }

    // --- the surface ----------------------------------------------------------

    @Test
    void all_four_are_registered_with_a_schema_a_model_can_read() {
        ToolRegistry registry = new ToolRegistry();
        tools.registerOn(registry);

        assertEquals(
                List.of("document_retrieve", "document_list", "document_rank",
                        "document_outline"),
                registry.tools().stream().map(ToolRegistry.Tool::name).toList());
        assertEquals(List.of("question"),
                registry.find("document_retrieve").orElseThrow().inputSchema().get("required"));
        assertEquals(List.of(),
                registry.find("document_list").orElseThrow().inputSchema().get("required"));
    }

    // --- fixtures -------------------------------------------------------------

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static String namedUnits() {
        return "\"section\":{\"id\":\"44444444-5555-6666-7777-888888888888\","
                + "\"title\":\"3. Results\",\"synthetic\":false,\"summary\":null},"
                + "\"chapter\":{\"id\":\"55555555-6666-7777-8888-999999999999\","
                + "\"title\":\"Part II\",\"synthetic\":false,\"summary\":null}";
    }

    private static String syntheticUnits() {
        return "\"section\":{\"id\":\"44444444-5555-6666-7777-888888888888\","
                + "\"title\":null,\"synthetic\":true,\"summary\":null},"
                + "\"chapter\":{\"id\":\"55555555-6666-7777-8888-999999999999\","
                + "\"title\":null,\"synthetic\":true,\"summary\":null}";
    }

    private static String hits(String units) {
        return hits(units, "The counterexample has eleven vertices.");
    }

    private static String hits(String units, String text) {
        return "{\"query\":\"q\",\"document\":null,\"limit\":5,\"hits\":[{"
                + "\"score\":0.81,\"chunk\":{"
                + "\"chunkId\":\"" + CHUNK + "\","
                + "\"text\":\"" + text.replace("\n", "\\n") + "\","
                + "\"paragraphId\":\"" + PARAGRAPH + "\","
                + "\"paragraphOrdinal\":3,"
                + "\"paragraphSummary\":\"eleven vertices suffice\","
                + units + ","
                + "\"documentId\":\"" + DOCUMENT + "\","
                + "\"sourceName\":\"paper.md\","
                + "\"title\":\"paper\","
                + "\"documentSummary\":\"refutes the conjecture\"}}]}";
    }

    private static String page() {
        return "{\"documents\":[{"
                + "\"documentId\":\"" + DOCUMENT + "\","
                + "\"sourceName\":\"paper.md\",\"title\":\"paper\",\"summary\":null,"
                + "\"vocabulary\":\"SECTION\",\"ingestedAt\":\"2026-09-05T09:00:00Z\","
                + "\"ingestedBy\":\"operator\",\"byteSize\":120,"
                + "\"chapters\":1,\"sections\":3,\"paragraphs\":12,\"chunks\":30"
                + "}],\"total\":1,\"limit\":50,\"offset\":0,\"naming\":null}";
    }

    private static String ranking() {
        return "{\"query\":\"q\",\"limit\":20,\"rankable\":2,\"unranked\":7,\"documents\":[{"
                + "\"documentId\":\"" + DOCUMENT + "\",\"sourceName\":\"graphs.md\","
                + "\"title\":\"The Counterexample\",\"summary\":\"refutes the conjecture\","
                + "\"ingestedAt\":\"2026-09-05T09:00:00Z\",\"score\":0.74}]}";
    }

    private static String outline() {
        return "{\"documentId\":\"" + DOCUMENT + "\",\"sourceName\":\"paper.md\","
                + "\"title\":\"paper\",\"summary\":\"refutes the conjecture\","
                + "\"vocabulary\":\"SECTION\",\"ingestedAt\":\"2026-09-05T09:00:00Z\","
                + "\"ingestedBy\":\"operator\",\"byteSize\":120,\"chapters\":[{"
                + "\"id\":\"55555555-6666-7777-8888-999999999999\",\"title\":null,"
                + "\"synthetic\":true,\"summary\":null,\"sections\":[{"
                + "\"id\":\"44444444-5555-6666-7777-888888888888\","
                + "\"title\":\"1. Introduction\",\"synthetic\":false,"
                + "\"summary\":\"states the conjecture\"}]}]}";
    }

    private static Map<String, Object> args(String... pairs) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            args.put(pairs[i], pairs[i + 1]);
        }
        return args;
    }
}
