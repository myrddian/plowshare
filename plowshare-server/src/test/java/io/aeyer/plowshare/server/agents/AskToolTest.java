package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code document_ask} as a model reads it, and as a person eventually reads it.
 *
 * <h2>What is under test is the ser and the des, and not the deliberation</h2>
 *
 * <p>The pass itself is {@code DeliberationTest}'s subject and needs three
 * agents, a corpus and a runtime. What this class is about is the two halves
 * bolted either side of it: <b>turning a name somebody typed into a document
 * id</b>, and <b>handing the answer back without losing the part that makes it
 * checkable</b>. Both are pure rendering and argument-reading over a fake {@link
 * Asking}, in {@code DocumentToolsTest}'s shape and for its reason — a mock of
 * the deliberation would make every assertion here an assertion about a mock.
 *
 * <p>{@link RetrievalService} is mocked rather than faked because it is the
 * resolution's only evidence and the interesting cases are what a <em>ranking</em>
 * came back as: one document, two documents, and the three kinds of empty.
 */
class AskToolTest {

    private static final Home GLOBAL = Home.global();

    /** What the system allows a pass, in the test's own number, so that an
     *  assertion about the budget cannot pass by matching a default. */
    private static final int ALLOWANCE = 7;

    /**
     * A deliberation's answer as {@code Deliberation.Tally.answered} builds one:
     * the synthesiser's prose at column zero, then the blocks the orchestrator
     * appends. <b>The failed-attribution block is in it deliberately</b> — it is
     * the thing this tool may not drop.
     */
    private static final String ANSWERED = """
            The paper argues that the retry budget is refilled per run.

            GROUNDED IN

            paragraph 11111111-1111-1111-1111-111111111111
            > refilled at the start of each run

            ATTRIBUTION FAILED — this answer named these paragraphs and these words are not in\
             them

            paragraph 22222222-2222-2222-2222-222222222222
            > and never refilled between them

            THE DELIBERATION

            The critic raised 2 challenge(s) from the document's summaries alone.""";

    private RetrievalService retrieval;

    /** What the seam was handed, so that a test can assert on the call rather
     *  than on the rendering of it. */
    private final AtomicReference<UUID> asked = new AtomicReference<>();
    private final AtomicReference<String> question = new AtomicReference<>();
    private final AtomicReference<Budget> spent = new AtomicReference<>();
    private final AtomicReference<Boolean> wasCancelled = new AtomicReference<>();

    private Outcome answer = new Outcome(Outcome.Ending.ANSWERED, ANSWERED, 3, 3, "");

    private AskTool tool;

    @BeforeEach
    void setUp() {
        retrieval = mock(RetrievalService.class);
        Asking asking = (documentId, q, budget, cancelled) -> {
            asked.set(documentId);
            question.set(q);
            spent.set(budget);
            wasCancelled.set(cancelled.getAsBoolean());
            return answer;
        };
        tool = new AskTool(asking, retrieval, () -> ALLOWANCE);
    }

    // --- the ser: a person's words become a document id ---------------------------

    /**
     * An id is asked directly, and nothing is searched for.
     *
     * <p>The route a caller takes on its <em>second</em> turn: a first ask that
     * was ambiguous handed back the candidates' ids, and this is what spending
     * one of them looks like. It must reach the corpus for nothing at all — a
     * resolution run over an id would be an embedding call bought to confirm a
     * value that was already exact.
     */
    @Test
    void a_uuid_is_asked_directly_and_costs_no_search() {
        UUID document = UUID.randomUUID();

        String out = tool.run(
                "{\"document\": \"" + document + "\", \"question\": \"how is it refilled\"}",
                GLOBAL);

        assertEquals(document, asked.get(), out);
        assertEquals("how is it refilled", question.get());
        org.mockito.Mockito.verify(retrieval, org.mockito.Mockito.never())
                .search(anyString(), anyInt());
    }

    /**
     * <b>A name is resolved through the corpus, and the answer says which
     * document it ended up asking and by which id.</b>
     *
     * <p>This is the whole of what the gap was. A person knows "the Wagner
     * paper"; every surface of this system that starts a deliberation demands a
     * uuid, and no surface of it renders one. Saying the id back is what makes
     * the resolution auditable and what makes a second ask in the same
     * conversation cost no search at all.
     */
    @Test
    void a_name_is_resolved_through_the_corpus_and_the_answer_says_which_document() {
        UUID wagner = UUID.randomUUID();
        when(retrieval.search(anyString(), anyInt())).thenReturn(found(
                hit(wagner, "wagner-2019.pdf", "Refilling Retry Budgets"),
                hit(wagner, "wagner-2019.pdf", "Refilling Retry Budgets")));

        String out = tool.run(
                "{\"document\": \"the Wagner paper\", \"question\": \"how is it refilled\"}",
                GLOBAL);

        assertEquals(wagner, asked.get(), out);
        assertTrue(out.contains(wagner.toString()), out);
        assertTrue(out.contains("wagner-2019.pdf"), out);
        assertTrue(out.contains("Refilling Retry Budgets"), out);
    }

    /**
     * Two documents matched, so nothing was asked and both ids came back.
     *
     * <p>Anchor's {@code AnchorClient.use} refuses the same case in the same
     * direction — <i>"Ambiguous title substring ... disambiguate or pass the
     * UUID"</i> — and the reason is sharper here than there. A deliberation is
     * four model calls; picking the top-ranked of two candidates would spend all
     * four answering about the wrong paper, and the answer would be fluent,
     * grounded, and about something nobody asked.
     */
    @Test
    void two_documents_matching_one_name_are_refused_with_both_ids_and_nothing_is_asked() {
        UUID wagner = UUID.randomUUID();
        UUID walsh = UUID.randomUUID();
        when(retrieval.search(anyString(), anyInt())).thenReturn(found(
                hit(wagner, "wagner-2019.pdf", "Refilling Retry Budgets"),
                hit(walsh, "walsh-2021.pdf", "Retry Budgets Reconsidered")));

        String out = tool.run(
                "{\"document\": \"retry budgets\", \"question\": \"how is it refilled\"}",
                GLOBAL);

        assertNull(asked.get(), "an ambiguous name must not spend a pass: " + out);
        assertTrue(out.contains(wagner.toString()), out);
        assertTrue(out.contains(walsh.toString()), out);
        assertTrue(out.contains("wagner-2019.pdf"), out);
        assertTrue(out.contains("walsh-2021.pdf"), out);
    }

    /**
     * The three kinds of empty are three different sentences here, exactly as
     * they are on {@code document_search}.
     *
     * <p>One empty ranking means the name matched nothing, or that the corpus
     * holds no document at all, or that it holds documents nothing embedded —
     * and only the first is worth naming the document differently for. A single
     * "no such document" would send a caller rephrasing at a corpus that has
     * already said it cannot be searched.
     */
    @Test
    void a_name_that_matches_nothing_says_which_kind_of_empty_it_is() {
        when(retrieval.search(anyString(), anyInt()))
                .thenReturn(found(new DocumentStore.Coverage(412, 0)));
        String nothingClose = tool.run(ask("the Wagner paper"), GLOBAL);

        when(retrieval.search(anyString(), anyInt()))
                .thenReturn(found(new DocumentStore.Coverage(0, 0)));
        String nothingIngested = tool.run(ask("the Wagner paper"), GLOBAL);

        when(retrieval.search(anyString(), anyInt()))
                .thenReturn(found(new DocumentStore.Coverage(0, 88)));
        String nothingSearchable = tool.run(ask("the Wagner paper"), GLOBAL);

        assertNull(asked.get(), "an unresolved name must not spend a pass");
        assertFalse(nothingClose.equals(nothingIngested), nothingClose);
        assertFalse(nothingIngested.equals(nothingSearchable), nothingIngested);
        assertTrue(nothingIngested.contains("no document has been ingested"), nothingIngested);
        assertTrue(nothingSearchable.contains("88"), nothingSearchable);
    }

    // --- the des: the answer reaches a person with its grounding intact -----------

    /**
     * <b>The failed-attribution block survives the tool.</b>
     *
     * <p>The one thing this whole capability exists to produce. A quotation the
     * synthesiser named and the paragraph does not contain is reported loudly
     * rather than dropped, <i>"because silence would hide the exact failure this
     * system exists to find"</i> — and a tool that summarised the pass, or
     * returned only its first paragraph, would have destroyed it one hop before
     * anybody read it.
     */
    @Test
    void the_failed_attribution_block_and_its_words_survive_whole() {
        String out = tool.run(ask(UUID.randomUUID().toString()), GLOBAL);

        assertTrue(out.contains("ATTRIBUTION FAILED"), out);
        assertTrue(out.contains("22222222-2222-2222-2222-222222222222"), out);
        assertTrue(out.contains("and never refilled between them"), out);
        assertTrue(out.contains("GROUNDED IN"), out);
        assertTrue(out.contains("The critic raised 2 challenge(s)"), out);
    }

    /**
     * <b>No line of the deliberation's answer reaches column zero.</b>
     *
     * <p>{@code DocumentTools}' rule, and it binds harder here than it does
     * there. {@code Deliberation.Tally.answered} takes the opposite trade on
     * purpose and says so: it leaves the synthesiser's prose unquoted because
     * <i>"the reader of this text is a person, not an agent"</i>. This tool is
     * what makes that sentence false — the first reader is now a model deciding
     * what to do next — so the quoting the deliberation could afford to skip has
     * to be put back at this boundary, over the <em>whole</em> of it: the prose
     * is a model's, the quotations in it are somebody's uploaded document, and
     * the blocks are built out of both.
     */
    @Test
    void every_line_of_the_answer_is_quoted_so_the_document_cannot_forge_this_tool() {
        String out = tool.run(ask(UUID.randomUUID().toString()), GLOBAL);

        for (String line : ANSWERED.split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            assertFalse(out.contains("\n" + line), "a line of the answer reached column zero: "
                    + line + "\n\n" + out);
            assertTrue(out.contains("> " + line), "a line of the answer was lost: " + line);
        }
    }

    /**
     * A pass that did not answer says so in this renderer's own words, above the
     * quoting.
     *
     * <p>{@code Outcome.text} for every ending but {@code ANSWERED} is the
     * runtime's or the orchestrator's sentence rather than a model's, and it is
     * still quoted — it can carry a document's title, which is uploaded text.
     * What is not quoted is the one line saying that no answer was reached, and
     * that line has to exist: a refusal rendered as an answer is the confident
     * empty answer this project is about.
     */
    @Test
    void a_pass_that_could_not_answer_says_so_before_it_quotes_anything() {
        answer = new Outcome(Outcome.Ending.UNAVAILABLE,
                "This document could not be asked: the corpus holds no document with that id.",
                0, 0, "");

        String out = tool.run(ask(UUID.randomUUID().toString()), GLOBAL);

        assertTrue(out.startsWith("No answer"), out);
        assertTrue(out.contains("> This document could not be asked"), out);
    }

    // --- what it costs ------------------------------------------------------------

    /**
     * <b>The pass spends the system's allowance and not the calling agent's.</b>
     *
     * <p>{@code Curator.pass}' precedent and the owner's rule that <i>"budget and
     * things shouldnt be an agent thing - thats the system"</i>. A deliberation
     * costs four model calls whoever asked for it, and charging them to the
     * caller's {@code max-model-calls} would make one tool call able to end a
     * conversation's whole allowance — with the answer already paid for and
     * nowhere to put it.
     */
    @Test
    void the_pass_spends_the_systems_allowance_and_the_caller_pays_one_turn() {
        tool.run(ask(UUID.randomUUID().toString()), GLOBAL);

        assertEquals(ALLOWANCE, spent.get().remaining(),
                "a fresh budget of the operator's number, not the caller's");
    }

    /**
     * A run cancelled while this is blocked stops the pass at its next stage
     * boundary.
     *
     * <p>The reason this tool is built per run at all. A deliberation is minutes
     * long; a shared instance would have nothing but {@code () -> false} to hand
     * down, so cancelling a job would leave three or four model calls running on
     * a machine nobody is waiting for.
     */
    @Test
    void the_runs_cancellation_flag_reaches_the_pass() {
        AtomicBoolean stop = new AtomicBoolean(false);
        BooleanSupplier cancelled = stop::get;

        tool.forRun(cancelled).run(ask(UUID.randomUUID().toString()), GLOBAL);
        assertEquals(Boolean.FALSE, wasCancelled.get());

        stop.set(true);
        tool.forRun(cancelled).run(ask(UUID.randomUUID().toString()), GLOBAL);
        assertEquals(Boolean.TRUE, wasCancelled.get());
    }

    /** A bound copy is offered under the same name and the same description, so
     *  what a context prices is what a run is offered. */
    @Test
    void a_bound_copy_is_the_same_schema() {
        assertEquals(tool.schema(), tool.forRun(() -> false).schema());
        assertEquals(AskTool.NAME, tool.schema().name());
    }

    // --- what a model got wrong is a result, never a throw -------------------------

    /** The schema requires both, and a call missing one is a sentence the model
     *  can act on rather than the end of a run. */
    @Test
    void a_call_missing_either_argument_is_answered_and_nothing_is_asked() {
        String noQuestion = tool.run("{\"document\": \"the Wagner paper\"}", GLOBAL);
        String noDocument = tool.run("{\"question\": \"how is it refilled\"}", GLOBAL);

        assertTrue(noQuestion.contains("question"), noQuestion);
        assertTrue(noDocument.contains("document"), noDocument);
        assertNull(asked.get(), "nothing may be asked over arguments that did not parse");
    }

    // --- helpers ------------------------------------------------------------------

    private static String ask(String document) {
        return "{\"document\": \"" + document + "\", \"question\": \"how is it refilled\"}";
    }

    private static DocumentStore.Hit hit(UUID document, String source, String title) {
        return new DocumentStore.Hit(UUID.randomUUID(), "some words", 0.2, UUID.randomUUID(),
                "some words in a paragraph", 4, document, source, title, null);
    }

    private static RetrievalService.Found found(DocumentStore.Hit... hits) {
        return new RetrievalService.Found("q", 10, 10, RetrievalService.Mode.HYBRID,
                List.of(hits), new DocumentStore.Coverage(412, 0));
    }

    private static RetrievalService.Found found(DocumentStore.Coverage coverage) {
        return new RetrievalService.Found("q", 10, 10, RetrievalService.Mode.HYBRID,
                List.of(), coverage);
    }
}
