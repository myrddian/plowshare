package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>The per-document deliberation, and above all the evidence asymmetry.</b>
 *
 * <p>Anchor's class comment is the design this file exists to hold:
 *
 * <blockquote>
 *
 * evidence asymmetry is the design. Proposer and synthesiser see the full hierarchy plus the top-K
 * retrieved chunks; critic sees only chapter summaries + doc summary. Giving critic the same
 * evidence as proposer turns it into a paraphrase generator — the asymmetry is what catches
 * macro-vs-local contradictions.
 *
 * </blockquote>
 *
 * <p>Anchor holds that by being careful in one method, so nothing in its suite would notice it
 * being lost. Here it is held twice: {@code ask_critic.md} declares {@code tools: []} — which
 * {@code AskDefinitionsTest} pins against the shipped files, and which means the critic could not
 * fetch the passages even if it wanted them — and this file reads what each stage was actually
 * shown off the wire.
 *
 * <p>Everything but the model is real: Postgres behind Flyway, the real store, the real {@link
 * JobRuntime} loop, the real {@link RetrievalService}. {@link ScriptedChat} is the endpoint, so
 * what a stage was shown is recorded rather than asserted about a fixture.
 */
@Testcontainers
class DeliberationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant AT = Instant.parse("2026-09-05T09:00:00Z");
  private static final BooleanSupplier NEVER = () -> false;

  /**
   * Room for a pass and its retry, so nothing here ends at a ceiling unless the test is about one.
   */
  private static final int ROOMY = 40;

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;

  private DocumentStore store;
  private CitationStore citations;
  private ScriptedChat transport;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    TransactionTemplate template =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactions =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> work) {
            return template.execute(status -> work.get());
          }
        };
  }

  @BeforeEach
  void freshCorpus() {
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, documents CASCADE");
    jdbc.update(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, entries, compactions, turns, conversations CASCADE");
    store = new DocumentStore(jdbc, transactions);
    citations = new CitationStore(jdbc);
    transport = new ScriptedChat();
  }

  // --- the asymmetry --------------------------------------------------------

  /**
   * <b>The critic is shown no passage and no mid-level summary; the other two are shown both.</b>
   *
   * <p>This is the whole design in one assertion, and it is read off the wire rather than off the
   * code: {@link ScriptedChat} records every message each stage was sent, so a refactor that
   * started handing the critic the chunk block would fail here whatever the prompt composer looks
   * like.
   */
  @Test
  void the_critic_is_shown_neither_a_passage_nor_a_mid_level_summary() {
    UUID document = paper();
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());

    deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertTrue(
        shown(Deliberation.PROPOSER).contains("The bound is tight for antichains."),
        "the proposer is given the passages");
    assertTrue(
        shown(Deliberation.PROPOSER).contains("What the introduction claims."),
        "the proposer is given the summaries of the sections owning them");

    assertFalse(
        shown(Deliberation.CRITIC).contains("The bound is tight for antichains."),
        "the critic saw a passage, so it is a paraphrase generator and not a critic");
    assertFalse(
        shown(Deliberation.CRITIC).contains("What the introduction claims."),
        "the critic saw a mid-level summary, which is the half of the hierarchy the"
            + " asymmetry withholds");

    assertTrue(
        shown(Deliberation.SYNTHESISER).contains("The bound is tight for antichains."),
        "the synthesiser is given the passages the proposer had");
  }

  /**
   * And it keeps what Anchor keeps: the macro summaries, the identity facts and the bibliography,
   * which are document-level and do not break the contract.
   */
  @Test
  void the_critic_keeps_the_top_level_summaries_the_bibliography_and_the_question() {
    UUID document = paper();
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());

    deliberation().ask(document, "is the bound tight", budget(), NEVER);

    String critic = shown(Deliberation.CRITIC);
    assertTrue(critic.contains("What the whole paper argues."), "the document's own summary");
    assertTrue(critic.contains("What this chapter argues."), "every top-level summary");
    assertTrue(critic.contains("Wagner, A. Something."), "the document's bibliography");
    assertTrue(critic.contains("is the bound tight"), "the reader's question");
    assertTrue(critic.contains("I argue that the bound is tight."), "the proposed answer");
  }

  // --- the retrieval numbers ------------------------------------------------

  /**
   * <b>Fifteen, in one flat search, and the five is used a second and independent time.</b>
   *
   * <p>Anchor asks for {@code topSections * topChunksPerSection} chunks in a single vector search
   * over the document — it does <em>not</em> group by section — and then uses {@code topSections}
   * again, on its own, as a cap on how many distinct sections get a summary bullet. Two uses of one
   * number that are not the same use.
   */
  @Test
  void the_search_is_flat_and_the_section_bullets_are_capped_independently() {
    assertEquals(5, Deliberation.TOP_SECTIONS);
    assertEquals(3, Deliberation.TOP_CHUNKS_PER_SECTION);
    assertEquals(15, Deliberation.TOP_PASSAGES);

    UUID document = sevenSections();
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());

    deliberation().ask(document, "anything", budget(), NEVER);

    String proposer = shown(Deliberation.PROPOSER);
    assertEquals(
        5,
        countOf(proposer, "S-summary-"),
        "the section bullets are capped at TOP_SECTIONS however many the fifteen"
            + " passages touch");
    assertEquals(
        7,
        countOf(proposer, "P-text-"),
        "and the passages themselves are not grouped or capped by section: this"
            + " document has seven paragraphs and all seven are inside fifteen");
  }

  /**
   * <b>Fifteen and not more, whatever the document holds.</b>
   *
   * <p>The other half of the flat search, and the half a document of two paragraphs cannot show:
   * the number is a fixed cost of one deliberation rather than the length of the paper. This is the
   * assertion that fails if {@link Deliberation#TOP_PASSAGES} stops being what reaches the
   * retrieval.
   */
  @Test
  void a_document_with_more_passages_than_the_bound_is_cut_to_the_bound() {
    UUID document = twentyParagraphs();
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());

    deliberation().ask(document, "anything", budget(), NEVER);

    assertEquals(
        Deliberation.TOP_PASSAGES,
        countOf(shown(Deliberation.PROPOSER), "P-text-"),
        shown(Deliberation.PROPOSER));
  }

  // --- grounding ------------------------------------------------------------

  @Test
  void a_quote_that_occurs_in_the_paragraph_it_names_is_recorded_as_a_citation() {
    UUID document = paper();
    UUID paragraph = paragraphSaying("The bound is tight for antichains.");
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then(grounding(paragraph, "bound is tight for antichains"));

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.ANSWERED, answered.ending());
    assertEquals(
        List.of(paragraph),
        citations.of(document, 10).stream().map(CitationStore.Cited::paragraphId).toList());
    assertTrue(answered.text().contains("GROUNDED IN"), answered.text());
    assertTrue(answered.text().contains(paragraph.toString()), answered.text());
  }

  /**
   * <b>The match is on normalised whitespace and not on bytes.</b> A paragraph's stored text is
   * re-flowed from line wrapping, so a model that quotes it correctly will not reproduce the
   * spacing; anything stricter fails on quotes that are right.
   */
  @Test
  void whitespace_is_collapsed_on_both_sides_before_the_words_are_looked_for() {
    UUID document = paper();
    UUID paragraph = paragraphSaying("The bound is tight for antichains.");
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then(grounding(paragraph, "bound   is\\ntight\\n  for antichains"));

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.ANSWERED, answered.ending());
    assertEquals(
        1,
        citations.of(document, 10).size(),
        "a correct quotation re-spaced by the model is still a correct quotation");
  }

  /**
   * <b>And the case is folded, which the design document does not say and its stated principle
   * requires.</b>
   *
   * <p>"Anything stricter fails on correct quotes" is the rule; a model that writes "the bound is
   * tight" where the paragraph opens "The bound is tight" has quoted the paragraph, and a
   * fabrication whose words differ from the document's only in case is not a fabrication. What
   * folding buys is every quotation that starts a sentence — and what refusing them would cost is
   * the credibility of the loudest signal this system produces.
   */
  @Test
  void a_quotation_that_differs_only_in_case_is_the_documents_words() {
    UUID document = paper();
    UUID paragraph = paragraphSaying("The bound is tight for antichains.");
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then(grounding(paragraph, "the bound is tight for antichains"));

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(1, citations.of(document, 10).size(), answered.text());
    assertFalse(answered.text().contains("ATTRIBUTION FAILED"), answered.text());
  }

  /**
   * <b>A quote that does not occur is refused and said, not dropped.</b>
   *
   * <p>V25's rule is that the table under-reports and never mis-reports, so no citation is written.
   * But silence would hide exactly the failure this whole system exists to find — a claim the
   * document does not support — so the answer carries the failed attribution.
   */
  @Test
  void a_quote_the_paragraph_does_not_contain_is_refused_in_the_answer_and_cited_nowhere() {
    UUID document = paper();
    UUID paragraph = paragraphSaying("The bound is tight for antichains.");
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then(grounding(paragraph, "the bound is provably loose"));

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.ANSWERED, answered.ending());
    assertTrue(
        citations.of(document, 10).isEmpty(),
        "a quotation that is not in the paragraph is not a citation of it");
    assertTrue(answered.text().contains("ATTRIBUTION FAILED"), answered.text());
    assertTrue(answered.text().contains("the bound is provably loose"), answered.text());
    assertTrue(answered.text().contains(paragraph.toString()), answered.text());
  }

  /**
   * <b>A paragraph of another document is a failed attribution and not a citation</b>, and nothing
   * else in this system would catch it: {@code CitationStore.record} validates against the corpus,
   * which that paragraph genuinely is in. The ask is per-document, so the scope is the check.
   */
  @Test
  void a_paragraph_of_a_different_document_is_a_failed_attribution() {
    UUID document = paper();
    UUID elsewhere = otherDocument();
    UUID hers =
        jdbc.queryForObject(
            "SELECT id FROM paragraphs WHERE document_id = ? LIMIT 1", UUID.class, elsewhere);
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then(grounding(hers, "somebody else's words"));

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertTrue(
        citations.recent(10).isEmpty(),
        "the ask is per-document, so a paragraph of another paper grounds nothing");
    assertTrue(answered.text().contains("ATTRIBUTION FAILED"), answered.text());
  }

  /** An answer that grounded in nothing is an answer, and says nothing about attributions. */
  @Test
  void an_answer_that_grounds_in_nothing_carries_no_grounding_block() {
    UUID document = paper();
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.ANSWERED, answered.ending());
    assertFalse(answered.text().contains("GROUNDED IN"), answered.text());
    assertFalse(answered.text().contains("ATTRIBUTION FAILED"), answered.text());
    // AND IT IS NOT REPORTED AS A RATIO. "0 of 0 attributions were found"
    // reads as a clean bill of health on an answer nothing verified, which
    // is the opposite of what happened.
    assertTrue(answered.text().contains("named no paragraph"), answered.text());
    assertFalse(answered.text().contains("0 of 0"), answered.text());
  }

  // --- the critic's retry, and what it costs --------------------------------

  /**
   * Four stages, one model call each, out of one shared allowance. The synthesiser is two of them —
   * objections, then the answer.
   */
  @Test
  void a_pass_that_goes_cleanly_is_four_model_calls_of_one_shared_budget() {
    UUID document = paper();
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());
    Budget budget = budget();

    Outcome answered = deliberation().ask(document, "is the bound tight", budget, NEVER);

    assertEquals(
        List.of(
            Deliberation.PROPOSER,
            Deliberation.CRITIC,
            Deliberation.REVIEWER,
            Deliberation.SYNTHESISER),
        transport.agents());
    assertEquals(4, budget.spent());
    assertEquals(4, answered.modelCalls());
  }

  /**
   * <b>The retry is a fourth model call out of the same allowance, and it is forced to temperature
   * zero whatever the critic's file declares.</b>
   *
   * <p>Two things Anchor gets wrong here, both ported as behaviour rather than as code. Its retry
   * is not budgeted at all — nothing in Anchor counts model calls — and its "retry once at
   * temperature 0" is, on its own defaults, the <em>identical</em> request: {@code criticTemp}
   * already defaults to 0.0, so the hardcoded zero in {@code parseCriticOrRetry} has never changed
   * anything. Forcing it here is what makes the log line true, and it survives an operator warming
   * {@code ask_critic.md}.
   */
  @Test
  void an_unparseable_critic_is_retried_once_as_declared_and_costs_a_fifth_call() {
    UUID document = paper();
    transport
        .then(proposed())
        .then("The proposer looks fine to me.")
        .then(critique())
        .then(reviewed())
        .then(synthesised());
    Budget budget = budget();

    deliberation(warmCritic()).ask(document, "is the bound tight", budget, NEVER);

    assertEquals(
        List.of(
            Deliberation.PROPOSER,
            Deliberation.CRITIC,
            Deliberation.CRITIC,
            Deliberation.REVIEWER,
            Deliberation.SYNTHESISER),
        transport.agents());
    assertEquals(5, budget.spent());
    assertEquals(
        Sampling.NONE.withTemperature(0.7d),
        transport.calls().get(1).sampling(),
        "the first critic call samples at what its definition resolved to");
    assertEquals(
        Sampling.NONE.withTemperature(0.7d),
        transport.calls().get(2).sampling(),
        "AND SO DOES THE RETRY, which is a correction rather than a simplification."
            + " Anchor forces a hardcoded 0.0 on the retry, and on Anchor's own"
            + " defaults that is the identical request because its critic is"
            + " already at 0.0 — so the number in its log line has never changed"
            + " anything there. Ported literally it became a real hazard: with"
            + " sampling resolved per model, the one call made BECAUSE the model"
            + " had already failed to produce parseable output was the one call"
            + " made greedily, and greedy decoding is what this project measured"
            + " returning 3 997 reasoning tokens and empty content. A retry that"
            + " samples differently from the call it retries has to justify the"
            + " difference, and this one could not: what makes a retry worth"
            + " anything is a second draw from a real distribution, which is"
            + " exactly what a temperature of zero removes.");
  }

  /**
   * A critic unreadable twice over is not fatal: the synthesiser proceeds with no challenges, and
   * the answer says the critic was not read.
   */
  @Test
  void a_critic_unreadable_twice_leaves_the_synthesiser_no_challenges_and_is_said() {
    UUID document = paper();
    transport.then(proposed()).then("nope").then("still nope").then(reviewed()).then(synthesised());

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.ANSWERED, answered.ending());
    assertTrue(
        shown(Deliberation.SYNTHESISER).contains("(no challenges raised)"),
        shown(Deliberation.SYNTHESISER));
    assertTrue(answered.text().contains("could not be read"), answered.text());
  }

  /**
   * An allowance too small for the whole pass stops it at the stage that ran out, and says so
   * rather than answering from two thirds of a deliberation.
   */
  @Test
  void a_pass_that_runs_out_of_allowance_stops_and_does_not_answer() {
    UUID document = paper();
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());

    Outcome answered = deliberation().ask(document, "is the bound tight", Budget.of(2), NEVER);

    assertEquals(Ending.CALL_BUDGET, answered.ending());
    assertFalse(
        answered.text().contains("I argue that the bound is tight."),
        "a stopped pass must not be dressed as an answer");
  }

  /**
   * <b>A cancelled pass stops between stages and is never dressed as an answer</b>, which is the
   * Anchor bug §7 names first.
   *
   * <p>{@code AskController.cancel} there claims the result <i>"just gets discarded by the
   * orchestrator's terminal-status check"</i>. There is no such check: {@code runDeliberation}
   * never reads {@code job.status()}, so the next {@code transitionWithEvent} overwrites CANCELLED
   * and the pass runs to completion. Here the question is asked at every stage boundary and handed
   * to every run, so a cancellation between the proposer and the critic costs one model call and
   * produces no answer.
   */
  @Test
  void a_cancelled_pass_stops_at_the_next_stage_and_answers_nothing() {
    UUID document = paper();
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());
    Outcome answered =
        deliberation()
            .ask(
                document,
                "is the bound tight",
                budget(),
                // Cancelled the moment one model call has been made, which is
                // the proposer's. Its own run answers on that call and never
                // reaches another boundary, so the question is next asked by the
                // pass, before the critic.
                () -> transport.calls().size() >= 1);

    assertEquals(Ending.CANCELLED, answered.ending(), answered.text());
    assertEquals(List.of(Deliberation.PROPOSER), transport.agents());
    assertFalse(
        answered.text().contains("I argue that the bound is tight."),
        "a cancelled pass returned the proposer's draft as though it were an answer");
  }

  /**
   * <b>A non-breaking space is a space, and a correct quotation that contains one is not a failed
   * attribution.</b>
   *
   * <p>Java's {@code \s} is ASCII-only, and nothing in this package normalises U+00A0 out of {@code
   * paragraphs.text} at ingest — a PDF or a DOCX leaves them behind. A model quoting such a
   * paragraph renders an ordinary space, so an ASCII-only flatten would fire the loudest signal
   * this system produces on a character nobody reading the answer can see.
   */
  @Test
  void a_paragraph_holding_a_non_breaking_space_is_still_quotable() {
    UUID document = paper();
    UUID paragraph = paragraphSaying("The bound is tight for antichains.");
    // U+00A0 where the space was, spelled as an escape because an
    // invisible character in a fixture is a trap. Java's whitespace class
    // does not match it and String.strip() does not remove it; a PDF puts
    // it in paragraphs.text and nothing in this package takes it out.
    jdbc.update(
        "UPDATE paragraphs SET text = ? WHERE id = ?",
        "The bound is\u00A0tight for antichains.",
        paragraph);
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then(grounding(paragraph, "bound is tight for antichains"));

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(1, citations.of(document, 10).size(), answered.text());
    assertFalse(answered.text().contains("ATTRIBUTION FAILED"), answered.text());
  }

  /**
   * <b>One paragraph cited for two claims is two attributions and one citation, and the answer says
   * both numbers.</b>
   *
   * <p>V25's rule is that the table under-reports and never mis-reports, and a single count
   * reported as "recorded as citations" would make the one sentence about that table the place this
   * system over-reports it.
   */
  @Test
  void two_claims_on_one_paragraph_are_two_attributions_and_one_citation() {
    UUID document = paper();
    UUID paragraph = paragraphSaying("The bound is tight for antichains.");
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then(
            "RESPONSE:\nTwo claims.\n\nGROUNDING:\n{\"grounded_in\": ["
                + "{\"paragraph\": \""
                + paragraph
                + "\", \"quote\": \"bound is tight\"},"
                + "{\"paragraph\": \""
                + paragraph
                + "\", \"quote\": \"tight for"
                + " antichains.\"}"
                + "]}");

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(1, citations.of(document, 10).size());
    assertTrue(answered.text().contains("2 of 2 attribution(s)"), answered.text());
    assertTrue(answered.text().contains("1 citation(s) were recorded"), answered.text());
  }

  /**
   * <b>A synthesiser that emits a grounding block and no prose has not answered.</b>
   *
   * <p>The run is not blank, so the per-run guard cannot see it; what a person would get is an
   * ANSWERED outcome opening with a grounding heading. That is a stopped stage dressed as an answer
   * by a route {@code Outcome}'s own rule does not name.
   */
  @Test
  void a_synthesiser_that_wrote_only_a_grounding_block_has_not_answered() {
    UUID document = paper();
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then("RESPONSE:\n\nGROUNDING:\n{\"grounded_in\": []}");

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.STUCK, answered.ending(), answered.text());
    assertFalse(answered.text().contains("GROUNDING"), answered.text());
  }

  // --- ruling on the critic ---------------------------------------------------

  /**
   * <b>The defect this whole reconciliation exists for.</b>
   *
   * <p>Observed on real papers across two models with reasoning on and off: the critic raises a
   * challenge the answer never engages, and the answer renders exactly as one that overruled it
   * would. Four of thirty-two calls did this with reasoning at zero, so it is not a budget failure
   * and not a skeleton failure — the synthesiser reached the block and left the challenge out of
   * it.
   *
   * <p>The challenge is quoted rather than counted, for the reason a failed attribution is: a
   * reader who is told "1 unanswered" has to go and find which, and the one thing this apparatus is
   * for is putting the failure in front of them.
   */
  @Test
  void a_challenge_the_answer_never_ruled_on_is_quoted_to_the_reader() {
    UUID document = paper();
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.ANSWERED, answered.ending(), answered.text());
    assertTrue(answered.text().contains("CHALLENGE UNANSWERED"), answered.text());
    assertTrue(answered.text().contains("you overstate it"), answered.text());
    assertTrue(answered.text().contains("1 went unanswered"), answered.text());
  }

  /**
   * And an answer that ruled on every one says so, and says what it set aside and why — which is
   * the fact the reader could not previously have.
   */
  @Test
  void an_answer_that_rules_on_every_challenge_says_so_and_gives_its_reasons() {
    UUID document = paper();
    transport
        .then(proposed())
        .then(twoChallenges())
        .then(reviewed())
        .then(
            ruling(
                "\"incorporated_critic_challenges\": [1],"
                    + " \"rejected_critic_challenges\": [{\"challenge\": 2,"
                    + " \"reason\": \"the appendix states the exception\"}]"));

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertFalse(answered.text().contains("CHALLENGE UNANSWERED"), answered.text());
    assertTrue(answered.text().contains("took up 1 and set aside 1"), answered.text());
    assertTrue(answered.text().contains("ruling on every one"), answered.text());
    assertTrue(answered.text().contains("the appendix states the exception"), answered.text());
  }

  /**
   * <b>A rejection with no reason is not an adjudication, end to end.</b>
   *
   * <p>Anchor's prompt asks for <i>"indices of challenges rejected, with reason"</i> in a field
   * that is a list of bare numbers, so the only compliant answer says which and never why. Here
   * that reads as a challenge nothing ruled on, which is the safe direction: it reaches the reader
   * rather than being recorded as answered.
   */
  @Test
  void a_challenge_dismissed_without_a_reason_is_still_unanswered() {
    UUID document = paper();
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then(ruling("\"rejected_critic_challenges\": [1]"));

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertTrue(answered.text().contains("CHALLENGE UNANSWERED"), answered.text());
    assertTrue(answered.text().contains("you overstate it"), answered.text());
  }

  /**
   * A critic that raised nothing leaves nothing to rule on, and the answer says neither that
   * challenges were answered nor that any were missed.
   */
  @Test
  void an_answer_to_a_critic_that_raised_nothing_carries_no_ruling_at_all() {
    UUID document = paper();
    transport
        .then(proposed())
        .then(
            "{\"challenges\": [], \"challenges_count\": 0,"
                + " \"macro_view_supports_proposer\": true}")
        .then(reviewed())
        .then(synthesised());

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertFalse(answered.text().contains("CHALLENGE UNANSWERED"), answered.text());
    assertFalse(answered.text().contains("took up"), answered.text());
  }

  /**
   * <b>The draft back, word for word, with challenges against it.</b>
   *
   * <p>Measured over twenty deliberations: two of the eight that had live challenges returned the
   * proposer's prose unchanged, and <b>one of those reported incorporating all three of them</b>.
   * That is the hole in the reconciliation — {@code unruled} checks every challenge was
   * <em>accounted</em> for, and an answer satisfies it by listing each one as incorporated while
   * changing nothing. Accounting is not effect.
   */
  @Test
  void a_draft_returned_unchanged_under_challenge_is_not_an_answer() {
    UUID document = paper();
    transport
        .then(proposed())
        .then(critique())
        .then(reviewed())
        .then(
            "RESPONSE:\n"
                + proposed()
                + "\n\nGROUNDING:\n"
                + "{\"grounded_in\": [], \"incorporated_critic_challenges\": [1]}");

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.STUCK, answered.ending(), answered.text());
    assertTrue(answered.text().contains("word for word"), answered.text());
  }

  /**
   * And an unchallenged draft returned unchanged is a synthesiser that agreed with a proposer
   * nothing argued against, which is not the same fact.
   */
  @Test
  void a_draft_returned_unchanged_with_no_challenges_still_answers() {
    UUID document = paper();
    transport
        .then(proposed())
        .then(
            "{\"challenges\": [], \"challenges_count\": 0,"
                + " \"macro_view_supports_proposer\": true}")
        .then(reviewed())
        .then("RESPONSE:\n" + proposed() + "\n\nGROUNDING:\n{\"grounded_in\": []}");

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.ANSWERED, answered.ending(), answered.text());
  }

  /**
   * <b>A reviewer that failed is not fatal, and making it fatal was a mistake this pins.</b>
   *
   * <p>The critic has carried Anchor's rule from the start — a critic that fails costs the
   * challenges, not the answer. The review pass was added without it, and measured on
   * arXiv:1504.04279 it ran past the 600s stream cap and took the whole deliberation down as
   * UNAVAILABLE, discarding a proposer and a critic that had both succeeded. Without objections
   * this is the one-shot pass that ran before the split: a worse answer, and still an answer.
   */
  @Test
  void a_review_pass_that_could_not_run_costs_the_objections_and_not_the_answer() {
    UUID document = paper();
    transport.then(proposed()).then(critique()).then("").then(synthesised());

    Outcome answered = deliberation().ask(document, "is the bound tight", budget(), NEVER);

    assertEquals(Ending.ANSWERED, answered.ending(), answered.text());
    assertTrue(answered.text().contains("bound is tight"), answered.text());
  }

  /**
   * <b>Two numbered lists in one prompt must not share a numbering.</b>
   *
   * <p>The review pass returns objections numbered from one. The critic's challenges used to be
   * rendered the same way, and measured over twenty deliberations the synthesiser put objection
   * numbers into {@code incorporated_critic_challenges} — one answer reported three incorporations
   * against a critic that had raised none. {@code unruled} resolves those against the critic's
   * list, so the check that catches a synthesiser ignoring its critic was being satisfied by
   * numbers meaning something else.
   *
   * <p>Naming the number is the half of the fix that does not depend on a model reading an
   * instruction, which is why it is the half with a test.
   */
  @Test
  void the_critics_challenges_are_numbered_by_name_so_two_lists_cannot_collide() {
    UUID document = paper();
    transport.then(proposed()).then(twoChallenges()).then(reviewed()).then(synthesised());

    deliberation().ask(document, "is the bound tight", budget(), NEVER);

    String shown = shown(Deliberation.SYNTHESISER);
    assertTrue(shown.contains("CHALLENGE 1."), shown);
    assertTrue(shown.contains("CHALLENGE 2."), shown);
  }

  // --- the guards -----------------------------------------------------------

  /**
   * <b>A document nothing has summarised cannot be asked</b>, because the critic's entire evidence
   * is the summaries: an ask over one would run a critic against empty prompts and call whatever
   * came back a macro view.
   */
  @Test
  void a_document_with_no_summary_of_its_own_is_refused_before_a_model_is_called() {
    UUID document = paper();
    jdbc.update("UPDATE documents SET summary = NULL WHERE id = ?", document);

    Outcome answered = deliberation().ask(document, "anything", budget(), NEVER);

    assertEquals(Ending.UNAVAILABLE, answered.ending());
    assertEquals(List.of(), transport.agents());
  }

  /**
   * <b>The hole the summaries cannot show, asked here as well as in the cascade.</b>
   *
   * <p>{@code DocumentStore.summarisedParagraphsInNoSection} is a narrower predicate than "this
   * document has a hole" — a re-ingest detaches paragraphs after the cascade has run, and {@code ON
   * DELETE SET NULL} is what leaves them attached to the document and to no section. So the
   * cascade's guard having passed once says nothing about now, and this asks again rather than
   * assuming.
   */
  @Test
  void a_labelled_paragraph_in_no_section_stops_the_ask_rather_than_answering_round_it() {
    UUID document = paper();
    jdbc.update(
        "UPDATE paragraphs SET section_id = NULL, summary = 'orphaned'"
            + " WHERE document_id = ? AND ordinal = 1",
        document);

    Outcome answered = deliberation().ask(document, "anything", budget(), NEVER);

    assertEquals(Ending.STUCK, answered.ending());
    assertEquals(List.of(), transport.agents());
  }

  @Test
  void a_document_id_naming_nothing_is_unavailable_and_costs_no_model_call() {
    Outcome answered = deliberation().ask(UUID.randomUUID(), "anything", budget(), NEVER);

    assertEquals(Ending.UNAVAILABLE, answered.ending());
    assertEquals(List.of(), transport.agents());
  }

  /** A server with no registry, or one missing any of the three, is told which. */
  @Test
  void a_server_that_defines_no_critic_says_so_and_does_not_half_deliberate() {
    UUID document = paper();
    Map<String, AgentDefinition> two = new HashMap<>(definitions());
    two.remove(Deliberation.CRITIC);

    Outcome answered =
        new Deliberation(
                store,
                retrieval(),
                citations,
                new JobRuntime(transport.dispatcher(), List.of()),
                () -> new AgentRegistry(two),
                Clock.fixed(AT, ZoneOffset.UTC))
            .ask(document, "anything", budget(), NEVER);

    assertEquals(Ending.UNAVAILABLE, answered.ending());
    assertTrue(answered.text().contains(Deliberation.CRITIC), answered.text());
    assertEquals(List.of(), transport.agents());
  }

  /**
   * <b>An unembedded document is told apart from one that had nothing close.</b> The corpus-wide
   * {@code coverage} says nothing about one paper; {@code countUnembedded} is the per-document
   * counterpart, and an ask that retrieved nothing from a document holding every word of its text
   * is a fact about this server rather than about the question.
   */
  @Test
  void a_document_whose_chunks_were_never_embedded_is_refused_and_says_which() {
    UUID document = paper();
    jdbc.update(
        "UPDATE chunks SET embedding = NULL WHERE paragraph_id IN"
            + " (SELECT id FROM paragraphs WHERE document_id = ?)",
        document);

    Outcome answered = deliberation().ask(document, "anything", budget(), NEVER);

    assertEquals(Ending.UNAVAILABLE, answered.ending());
    assertEquals(List.of(), transport.agents());
  }

  /**
   * <b>A passage whose paragraph is in no section says so, and does not borrow the sentinel's
   * words.</b>
   *
   * <p>{@code (unnamed segment)} means the parser invented this unit; <em>in no section</em> means
   * there is no unit. {@code DocumentStore.Attribution} refuses to fold the two and so does the
   * renderer. The state is reachable here precisely because the guard above it is narrower than the
   * hole: an orphan paragraph that was never summarised is invisible to {@code
   * summarisedParagraphsInNoSection} and its chunks are still retrieved.
   */
  @Test
  void a_passage_in_no_section_is_marked_as_that_and_not_as_an_unnamed_one() {
    UUID document = paper();
    jdbc.update(
        "UPDATE paragraphs SET section_id = NULL, summary = NULL"
            + " WHERE document_id = ? AND ordinal = 1",
        document);
    transport.then(proposed()).then(critique()).then(reviewed()).then(synthesised());

    deliberation().ask(document, "anything", budget(), NEVER);

    String proposer = shown(Deliberation.PROPOSER);
    assertTrue(proposer.contains("(in no section)"), proposer);
  }

  // --- fixtures -------------------------------------------------------------

  private Deliberation deliberation() {
    return deliberation(definitions());
  }

  private Deliberation deliberation(Map<String, AgentDefinition> agents) {
    return new Deliberation(
        store,
        retrieval(),
        citations,
        new JobRuntime(transport.dispatcher(), List.of()),
        () -> new AgentRegistry(agents),
        Clock.fixed(AT, ZoneOffset.UTC));
  }

  private RetrievalService retrieval() {
    EmbeddingClient embeddings =
        new EmbeddingClient() {

          @Override
          public float[] embed(String text) {
            return axis(0);
          }

          @Override
          public List<float[]> embedAll(List<String> texts) {
            return texts.stream().map(text -> axis(0)).toList();
          }
        };
    return new RetrievalService(store, embeddings, "nomic-embed-text", 768);
  }

  private static Budget budget() {
    return Budget.of(ROOMY);
  }

  private static Map<String, AgentDefinition> definitions() {
    return definitions(0.0d);
  }

  /**
   * The critic at a temperature nothing ships, so that "the retry is at zero" is an assertion
   * rather than two zeroes agreeing.
   */
  private static Map<String, AgentDefinition> warmCritic() {
    return definitions(0.7d);
  }

  private static Map<String, AgentDefinition> definitions(double criticTemperature) {
    Map<String, AgentDefinition> agents = new HashMap<>();
    agents.put(Deliberation.PROPOSER, definition(Deliberation.PROPOSER, 0.3d));
    agents.put(Deliberation.CRITIC, definition(Deliberation.CRITIC, criticTemperature));
    agents.put(Deliberation.REVIEWER, definition(Deliberation.REVIEWER, 0.0d));
    agents.put(Deliberation.SYNTHESISER, definition(Deliberation.SYNTHESISER, 0.2d));
    return agents;
  }

  private static AgentDefinition definition(String name, double temperature) {
    return new AgentDefinition(
            name,
            "deliberates",
            "reasoning",
            List.of(),
            List.of(),
            List.of(),
            1,
            1,
            "You are the " + name + ".",
            false,
            false)
        .sampling(Sampling.NONE.withTemperature(temperature));
  }

  private String shown(String agent) {
    return transport.calls().stream()
        .filter(call -> agent.equals(call.agent()))
        .map(call -> String.join("\n", call.contents()))
        .reduce("", (a, b) -> a + "\n" + b);
  }

  private static int countOf(String text, String needle) {
    int found = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
      found++;
    }
    return found;
  }

  // --- the corpus -----------------------------------------------------------

  private static final String PAPER =
      """
            1. Introduction

            The bound is tight for antichains.

            2. Results

            We disprove the conjecture.

            References

            [1] Wagner, A. Something.
            """;

  /**
   * A paper with two headed sections, every tier summarised, every chunk embedded — the state an
   * ask is legal over.
   */
  private UUID paper() {
    UUID document = ingest("paper.md", PAPER);
    summarise(
        document,
        "What the whole paper argues.",
        "What this chapter argues.",
        "What the introduction claims.");
    embedEverything(document);
    return document;
  }

  private UUID otherDocument() {
    UUID document = ingest("other.md", "1. Elsewhere\n\nSomebody else's words entirely.\n");
    summarise(document, "Another paper.", "Another chapter.", "Another section.");
    embedEverything(document);
    return document;
  }

  /**
   * Seven headed sections, one paragraph each: seven passages inside the fifteen, touching seven
   * distinct sections against a cap of five.
   */
  private UUID sevenSections() {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 7; i++) {
      text.append(i)
          .append(". Heading ")
          .append(i)
          .append("\n\nP-text-")
          .append(i)
          .append(" claims something.\n\n");
    }
    UUID document = ingest("many.md", text.toString());
    jdbc.update(
        "UPDATE documents SET summary = 'What the whole paper argues.' WHERE id = ?", document);
    jdbc.update(
        "UPDATE chapters SET summary = 'What this chapter argues.'" + " WHERE document_id = ?",
        document);
    jdbc.update(
        "UPDATE sections SET summary = 'S-summary-' || ordinal" + " WHERE document_id = ?",
        document);
    jdbc.update("UPDATE paragraphs SET summary = 'a claim' WHERE document_id = ?", document);
    embedEverything(document);
    return document;
  }

  /**
   * One headed section holding twenty paragraphs: more passages than the bound, in one section,
   * which is a legal answer to a flat search.
   */
  private UUID twentyParagraphs() {
    StringBuilder text = new StringBuilder("1. Heading\n\n");
    for (int i = 1; i <= 20; i++) {
      text.append("P-text-").append(i).append(" claims something.\n\n");
    }
    UUID document = ingest("long.md", text.toString());
    summarise(
        document,
        "What the whole paper argues.",
        "What this chapter argues.",
        "What the section claims.");
    embedEverything(document);
    return document;
  }

  private UUID ingest(String sourceName, String text) {
    Extracted extracted = TextExtraction.extract(sourceName, text.getBytes(StandardCharsets.UTF_8));
    return store
        .write(
            sourceName,
            extracted,
            text.length(),
            "test",
            AT,
            Derivation.derive(extracted, ShippedChunking.SHIPPED))
        .documentId();
  }

  private void summarise(UUID document, String top, String chapter, String section) {
    jdbc.update("UPDATE documents SET summary = ? WHERE id = ?", top, document);
    jdbc.update("UPDATE chapters SET summary = ? WHERE document_id = ?", chapter, document);
    jdbc.update("UPDATE sections SET summary = ? WHERE document_id = ?", section, document);
    jdbc.update("UPDATE paragraphs SET summary = 'a claim' WHERE document_id = ?", document);
  }

  private void embedEverything(UUID document) {
    for (DocumentStore.UnembeddedChunk chunk : store.unembedded(document)) {
      store.attach(chunk.id(), axis(0));
    }
  }

  private UUID paragraphSaying(String text) {
    return jdbc.queryForObject("SELECT id FROM paragraphs WHERE text = ?", UUID.class, text);
  }

  private static float[] axis(int i) {
    float[] embedding = new float[768];
    embedding[i] = 1f;
    return embedding;
  }

  // --- what the model says --------------------------------------------------

  private static String proposed() {
    return "I argue that the bound is tight.";
  }

  /**
   * What the review pass returns: objections, in prose, for the synthesiser to act on. Prose and
   * not JSON, which is the stage's own point.
   */
  private static String reviewed() {
    return "1. The draft asserts the bound is tight without naming the"
        + " qualification the appendix adds.";
  }

  private static String critique() {
    return "{\"challenges\": [\"you overstate it\"], \"challenges_count\": 1,"
        + " \"macro_view_supports_proposer\": \"partially\"}";
  }

  /**
   * <b>Deliberately not {@link #proposed()}.</b> A synthesiser that returns the draft word for word
   * with challenges against it is refused — see {@code
   * a_draft_returned_unchanged_under_challenge_is_not_an_answer} — so a fixture that echoed the
   * draft would make every test using it a test of that branch.
   */
  private static String synthesised() {
    return "RESPONSE:\nI argue that the bound is tight, with the qualification my"
        + " appendix adds.\n\nGROUNDING:\n"
        + "{\"grounded_in\": [], \"confidence\": \"high\"}";
  }

  private static String twoChallenges() {
    return "{\"challenges\": [\"you overstate it\", \"the appendix says otherwise\"],"
        + " \"challenges_count\": 2, \"macro_view_supports_proposer\": \"partially\"}";
  }

  /** A synthesiser answer carrying whatever ruling a test needs beside an empty grounding block. */
  private static String ruling(String rulings) {
    return "RESPONSE:\nI argue that the bound is tight, with the qualification my"
        + " appendix adds.\n\nGROUNDING:\n"
        + "{\"grounded_in\": [], \"confidence\": \"high\", "
        + rulings
        + "}";
  }

  private static String grounding(UUID paragraph, String quote) {
    return "RESPONSE:\nI argue that the bound is tight, with the qualification my"
        + " appendix adds.\n\nGROUNDING:\n"
        + "{\"grounded_in\": [{\"paragraph\": \""
        + paragraph
        + "\", \"quote\": \""
        + quote
        + "\"}], \"confidence\": \"high\"}";
  }
}
