package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * The cascade: what it asks, in what order, out of whose allowance, and what it leaves behind when
 * it stops part-way.
 *
 * <p><b>No test here reaches a model.</b> The transport is scripted, which is what lets the
 * assertions be about the thing that matters — how many calls a document costs, which agent each of
 * them went to, what was in front of it, and what a stopped cascade still owes. A real endpoint
 * could be asked for none of those.
 *
 * <p>Testcontainers because the cascade's resumption gate is a query: what a stopped run owes is
 * {@code paragraphs.summary IS NULL} and nothing else, so a stubbed store would be testing the
 * stub's memory of what it had been told rather than the property.
 *
 * <p>{@code Compaction} is absent, so every run here is on {@code Transcript.NONE} and no
 * conversation is opened. What conversations the cascade opens, of what origin, under what parent,
 * is {@code DocumentCascadeLoggingTest}'s subject — it needs the whole archive wired and this class
 * needs none of it.
 */
@Testcontainers
class SummariserTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;

  private DocumentStore store;
  private ScriptedChat transport;

  private static final Instant AT = Instant.parse("2026-09-04T09:00:00Z");
  private static final BooleanSupplier NEVER = () -> false;

  /**
   * Small, so a document of a handful of paragraphs still folds and the arithmetic in these tests
   * is readable. Production's number is plowshare.documents.span-size.
   */
  private static final int SPAN = 3;

  /**
   * What {@code application.yml} ships {@code plowshare.documents.span-size} as. Used by the one
   * test whose subject is the real cost of a real paper.
   */
  private static final int SHIPPED_SPAN = 12;

  /**
   * A document with a hierarchy in it: one chapter the parser invents over a paper that declares
   * none, and three sections the paper does head.
   *
   * <p>The three names are in {@code SectionDetector}'s list of the section names its first corpus
   * actually uses, which is what makes this a fixture about the cascade rather than about
   * detection.
   */
  private static final String PAPER =
      """
            Introduction

            Alpha claims a thing.

            Beta denies it.

            Methods

            Gamma describes a method.

            Results

            Delta reports a finding.
            """;

  /**
   * The same paper with what a paper actually opens on: a title line, an author block and an
   * unlabelled abstract, all of it above the first heading. {@code SectionDetector} makes that a
   * synthetic section at ordinal 1 — §3.3(b)'s fix — and it is the unit whose prompt §4 of the
   * evaluation found asserting something the rest of the hierarchy contradicts.
   *
   * <p>None of the first three lines is itself a heading: the title's first word is one letter,
   * which the title-case rule refuses, and the author line ends in a period.
   */
  private static final String PREAMBLED_PAPER =
      """
            A Paper About Approximation, Measured

            Ferrer and Osei, Institute of Things.

            We show that a thing holds under conditions, and we measure it.

            Introduction

            Alpha claims a thing.

            Methods

            Gamma describes a method.
            """;

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
    store = new DocumentStore(jdbc, transactions);
    transport = new ScriptedChat();
  }

  // --- the four tiers -------------------------------------------------------

  /**
   * <b>Four tiers, and each one reads only the tier below it.</b>
   *
   * <p>Anchor's cascade, restored: a paragraph is labelled from its own text, a section from its
   * paragraphs' labels, a chapter from its sections' and the document from its chapters'. The
   * middle two were a fold over a run of summaries while this server had no hierarchy to hang them
   * on; V26 gave it one, and {@code sections.summary} and {@code chapters.summary} are rows
   * something reads rather than an artefact of a batch size.
   */
  @Test
  void every_tier_is_summarised_from_the_tier_below_it() {
    UUID id = corpus("paper.md", PAPER);

    Outcome ran = summarise(id, "paper", Budget.of(40));

    assertEquals(Ending.ANSWERED, ran.ending(), ran.text());
    assertEquals(
        List.of(
            "paragraph_summariser",
            "paragraph_summariser",
            "paragraph_summariser",
            "paragraph_summariser",
            "section_summariser",
            "section_summariser",
            "section_summariser",
            "chapter_summariser",
            "document_summariser"),
        transport.agents());
    assertEquals(3, sectionSummaries(id).size());
    assertEquals(1, chapterSummaries(id).size());
  }

  /**
   * A section reads its own paragraphs' summaries, numbered, under its own title — and nothing else
   * in the document.
   */
  @Test
  void a_section_reads_its_own_paragraph_summaries_and_its_own_title() {
    UUID id = corpus("paper.md", PAPER);

    summarise(id, "paper", Budget.of(40));

    String first = sentTo("section_summariser").get(0);
    assertTrue(first.contains("Introduction"), first);
    assertTrue(first.contains("1. summary of Alpha claims a thing."), first);
    assertTrue(first.contains("2. summary of Beta denies it."), first);
    assertFalse(first.contains("Gamma"), "a section was shown another section's work: " + first);
  }

  /** And a chapter reads its sections' summaries, under its own title. */
  @Test
  void a_chapter_reads_its_section_summaries() {
    UUID id = corpus("paper.md", PAPER);

    summarise(id, "paper", Budget.of(40));

    String chapter = sentTo("chapter_summariser").get(0);
    assertTrue(chapter.contains("1. summary of"), chapter);
    assertTrue(chapter.contains("2. summary of"), chapter);
    assertTrue(chapter.contains("3. summary of"), chapter);
    // Three lines and no fourth: the chapter reads a summary per section and
    // never one per paragraph, which is the compression the tier exists for.
    assertFalse(chapter.contains("4. "), chapter);
  }

  /**
   * <b>Policy C: a title the parser invented never reaches a prompt.</b>
   *
   * <p>{@link StructuralRef}'s first production render site. A document with no detectable heading
   * derives one synthetic chapter over one synthetic section, both carrying a sentinel in {@code
   * title}; what the section and chapter runs are shown says the document named nothing, and
   * neither sentinel appears anywhere. Anchor's reason, which is about the tier above rather than
   * about tidiness: the model would echo the name back into the summary, and every level above
   * reads summaries instead of text.
   */
  @Test
  void a_synthetic_title_never_reaches_a_prompt() {
    UUID id = corpus("notes.md", paragraphs(2));

    summarise(id, "notes", Budget.of(40));

    for (ScriptedChat.Call call : transport.calls()) {
      String sent = String.join("\n", call.contents());
      assertFalse(sent.contains(SyntheticTitles.SECTION), call.agent() + ": " + sent);
      assertFalse(sent.contains(SyntheticTitles.CHAPTER), call.agent() + ": " + sent);
      assertFalse(sent.contains("SYNTHETIC"), call.agent() + ": " + sent);
    }
  }

  /**
   * <b>A preamble section is not told the document heads no sections.</b>
   *
   * <p>{@code implementation rationale} §4 caught the shipped opening telling this exact unit — the
   * synthetic section §3.3(b) introduced for the text above a chapter's first heading — that "This
   * document heads no sections in this chapter, so this section is the whole of it." On the paper
   * it was measured against, the document heads five of them and the section is the first three
   * paragraphs. <b>The sentence is false and that is why it changes</b>; the evaluation's
   * correlation with a runaway generation is one run against one run on a model that looped on 5 of
   * 42 calls, and is not what this test is about.
   *
   * <p>Two kinds of synthetic section now exist and one sentence described both. The one that tells
   * them apart is not carried on the unit: it is the shape of the stored hierarchy the summaries
   * came out of, which is what {@link Summariser} is walking when it renders.
   */
  @Test
  void a_section_above_a_chapters_first_heading_is_not_told_the_document_heads_none() {
    UUID id = corpus("paper.md", PREAMBLED_PAPER);

    summarise(id, "paper", Budget.of(40));

    List<String> sections = sentTo("section_summariser");
    assertEquals(3, sections.size(), sections.toString());
    String preamble = sections.get(0);
    assertTrue(
        preamble.contains(
            "This section is the text above this chapter's first"
                + " heading, so the document gives it no heading of its own."),
        preamble);
    assertFalse(
        preamble.contains("heads no sections"),
        "the document heads two sections in this chapter: " + preamble);
    assertTrue(preamble.contains("1. summary of A Paper About Approximation, Measured"), preamble);
  }

  /**
   * And the sentence written for the other kind is untouched. A document with no headings at all
   * still gets Anchor's Policy C opening in the words stage 5 shipped: it is true there, it is
   * model-visible text, and {@code implementation rationale} is this repository's record of what
   * rewriting such text costs.
   */
  @Test
  void a_section_that_really_is_the_whole_chapter_is_told_what_it_was_always_told() {
    UUID id = corpus("notes.md", paragraphs(2));

    summarise(id, "notes", Budget.of(40));

    assertTrue(
        sentTo("section_summariser")
            .get(0)
            .contains(
                "This document heads no sections in this chapter, so this section is"
                    + " the whole of it. These are the summaries of the paragraphs"
                    + " in it, in order:"),
        sentTo("section_summariser").get(0));
    assertTrue(
        sentTo("chapter_summariser")
            .get(0)
            .contains(
                "This document declares no chapters of its own, so this chapter is its"
                    + " whole body. These are the summaries of the sections in it,"
                    + " in order:"),
        sentTo("chapter_summariser").get(0));
  }

  /**
   * <b>The chapter tier had the same sentence and now has the same second case.</b>
   *
   * <p>{@code ChapterDetector}'s preamble chapter is new, and without this the fix to the chapter
   * level would have shipped the falsehood the section level is being cured of: a chapter that sits
   * above the document's first heading being told the document declares no chapters, on a document
   * that declares two.
   */
  @Test
  void a_chapter_above_a_documents_first_heading_is_not_told_the_document_declares_none() {
    UUID id =
        corpus(
            "book.md",
            """
                A Paper About Approximation, Measured

                Ferrer and Osei, Institute of Things.

                Chapter 1 Beginnings

                Alpha claims a thing.

                Chapter 2 Endings

                Beta denies it.
                """);

    summarise(id, "book", Budget.of(40));

    List<String> chapters = sentTo("chapter_summariser");
    assertEquals(3, chapters.size(), chapters.toString());
    String preamble = chapters.get(0);
    assertTrue(
        preamble.contains(
            "This chapter is the text above this document's first"
                + " chapter heading, so the document gives it no heading of its own."),
        preamble);
    assertFalse(
        preamble.contains("declares no chapters"),
        "the document declares two chapters: " + preamble);
  }

  /**
   * <b>The fold is demoted, not deleted: it compresses a tier's children until they fit one
   * call.</b>
   *
   * <p>§4.2, and the reason it is not simply dropped. Anchor's degenerate path puts a whole
   * document's paragraph summaries into ONE section prompt — two hundred of them on a real paper —
   * and that this holds was never established. Here a tier is called once with its children when
   * they fit and the fold compresses them first when they do not, which keeps the tier addressable
   * without assuming an unbounded prompt.
   */
  @Test
  void a_tier_with_more_children_than_fit_folds_them_first() {
    UUID id = corpus("notes.md", paragraphs(10));

    Outcome ran = summarise(id, "notes", Budget.of(40));

    assertEquals(Ending.ANSWERED, ran.ending(), ran.text());
    // Ten paragraphs, all in one synthetic section at a span of three: ten
    // paragraph calls; ten summaries fold to four (three folds and the tenth
    // carried), four fold to two (one fold and the fourth carried); two fits,
    // so ONE section call reads them. Then one chapter call over that one
    // section summary and one document call over that one chapter summary.
    assertEquals(4, transport.agents().stream().filter("span_summariser"::equals).count());
    assertEquals(1, transport.agents().stream().filter("section_summariser"::equals).count());
    assertEquals(17, transport.calls().size());
  }

  /**
   * <b>A tier is always called for its own unit, even over a single child — and that is the
   * opposite of the fold's rule on purpose.</b>
   *
   * <p>This class used to name Anchor's chapter-over-one-section and document-over-one-chapter
   * calls as the two wasted calls its three-level shape declined, and with no {@code chapters}
   * table that was right: the output went nowhere but into the next prompt, so paraphrasing it
   * bought nothing. It is wrong now. A chapter summary is a row the deliberation reads directly —
   * Anchor gives every one of its three ask agents the chapter summaries and gives none of them a
   * paragraph summary — so the call writes something addressable rather than rewriting a sentence
   * in transit. A fold's output has no row and no reader, which is why a run of one is still
   * carried there and only there.
   */
  @Test
  void a_tier_over_one_child_is_still_called_and_a_fold_of_one_is_still_carried() {
    UUID id = corpus("notes.md", paragraphs(2));

    summarise(id, "notes", Budget.of(40));

    assertEquals(
        List.of(
            "paragraph_summariser",
            "paragraph_summariser",
            "section_summariser",
            "chapter_summariser",
            "document_summariser"),
        transport.agents());
    assertEquals(0, transport.agents().stream().filter("span_summariser"::equals).count());
  }

  /**
   * <b>A labelled paragraph in no section stops the cascade rather than being summarised
   * around.</b>
   *
   * <p>{@code paragraphs.section_id} is nullable — V18 froze {@code document_id NOT NULL} and V26
   * could only add beside it — so this is a state the schema allows and V26 names: "derived before
   * the hierarchy existed". A document ingested before V26 is exactly it, and so, for one statement
   * inside a re-ingest, is every paragraph of every document, which is why {@code ON DELETE SET
   * NULL} is what makes the identity rule survive a replaced hierarchy at all.
   *
   * <p>What the cascade cannot do is summarise upward over one. The tiers read through sections, so
   * an orphan is labelled at the bottom and then read by nothing — and a document summary written
   * over that is a summary of a document with a paragraph missing, which nothing downstream could
   * see because every level above reads summaries rather than text. It is the same hole the class
   * refuses when a run stops part-way, one edge over.
   */
  @Test
  void a_labelled_paragraph_in_no_section_stops_the_cascade() {
    UUID id = corpus("notes.md", paragraphs(3));
    // The pre-V26 state, written directly because no writer produces it any
    // more: DocumentStore.write points every paragraph at a section in the
    // same transaction that inserts one.
    jdbc.update(
        "UPDATE paragraphs SET section_id = NULL WHERE document_id = ?" + " AND ordinal = 2", id);

    Outcome ran = summarise(id, "notes", Budget.of(20));

    assertEquals(Ending.STUCK, ran.ending(), ran.text());
    assertTrue(ran.text().contains("in no section"), ran.text());
    assertEquals(
        3,
        store.paragraphSummaries(id).size(),
        "the paragraphs were still labelled; it is the tiers above them that stopped");
    assertEquals(null, store.find("notes.md").orElseThrow().summary());
  }

  /**
   * <b>What a real paper costs, measured at the shipped span rather than at this class's small one
   * — and whether 300 still holds.</b>
   *
   * <p>§4.3 sized {@code plowshare.documents.ingest-budget} against the fold's ~220 and said the
   * four-tier hierarchy "is not more expensive", then corrected itself twice: Anchor's own bottom
   * tier is page-slices rather than paragraphs, so its cascade is nearer 40 calls than 210, and
   * this port is more expensive than Anchor for the right reason. What neither version did was
   * measure the four tiers. This does.
   *
   * <p>Seven sections of twenty-nine paragraphs, which is the 30-page paper the budget was written
   * against, at {@code span-size: 12}. Each section folds twenty-nine summaries into three and is
   * then called once: four calls a section, twenty-eight in all. The one chapter reads seven
   * section summaries without folding, and the document reads that one chapter. <b>Two hundred and
   * thirty-three against an allowance of three hundred</b> — so it holds, with room for a paper a
   * quarter longer again and not for one twice as long, which is what "the binding constraint
   * rather than a comfortable one" means in a number.
   *
   * <p><b>What would break it is a shape and not a length</b>, and it is worth naming because it is
   * the one the arithmetic hides: the tiers cost {@code sections + chapters + 1}, so a document of
   * two hundred one-paragraph sections costs four hundred and not two hundred and thirty. That is a
   * document whose every paragraph carries a heading, which no paper is and some generated
   * documents are.
   */
  @Test
  void a_thirty_page_paper_costs_two_hundred_and_thirty_three_calls_of_an_allowance_of_300() {
    UUID id = corpus("paper.md", paper(7, 29));
    Budget budget = Budget.of(300);

    Outcome ran =
        new Summariser(store, runtime(), SummariserTest::registry, SHIPPED_SPAN)
            .summarise(id, "paper", budget, NEVER);

    assertEquals(Ending.ANSWERED, ran.ending(), ran.text());
    assertEquals(203, transport.agents().stream().filter("paragraph_summariser"::equals).count());
    assertEquals(
        28,
        transport.agents().stream().filter("span_summariser"::equals).count()
            + transport.agents().stream().filter("section_summariser"::equals).count());
    assertEquals(1, transport.agents().stream().filter("chapter_summariser"::equals).count());
    assertEquals(
        233, budget.spent(), "the four-tier cost of a 30-page paper at the shipped span size");
    assertTrue(
        budget.spent() < 300,
        "plowshare.documents.ingest-budget ships at 300 in application.yml, and this"
            + " cascade spent "
            + budget.spent());
  }

  // --- the shape of a cascade -----------------------------------------------

  /**
   * A document smaller than one span costs one call a paragraph and one for each unit above them.
   *
   * <p><b>No fold, and that is the design rather than an optimisation.</b> The fold fires when a
   * tier has more children than fit one call and not otherwise, so a two-paragraph document reaches
   * its summary through four tiers and no compression at all.
   */
  @Test
  void a_short_document_is_a_call_a_paragraph_and_one_for_each_unit_above() {
    UUID id = corpus("notes.md", "Alpha.\n\nBeta.");

    Outcome ran = summarise(id, "notes", Budget.of(20));

    assertEquals(Ending.ANSWERED, ran.ending(), ran.text());
    assertEquals(5, transport.calls().size());
    assertEquals(
        List.of(
            "paragraph_summariser",
            "paragraph_summariser",
            "section_summariser",
            "chapter_summariser",
            "document_summariser"),
        transport.agents());
    assertEquals(List.of("summary of Alpha.", "summary of Beta."), store.paragraphSummaries(id));
    // The scripted transport answers "summary of <what it was asked>", so
    // the stored summaries are a readable record of the whole chain: the
    // section read the two paragraph summaries and never the paragraphs, and
    // what reached the document was one chapter summary.
    String document = store.find("notes.md").orElseThrow().summary();
    assertTrue(document.contains("1. summary of "), document);
    assertTrue(
        sectionSummaries(id).get(0).contains("1. summary of Alpha.\n2. summary of" + " Beta."),
        sectionSummaries(id).toString());
  }

  /**
   * <b>Raw text enters at the bottom and nowhere else.</b>
   *
   * <p>Anchor calls this the critical invariant and enforces it by construction; so does this, and
   * this is where that is checked rather than asserted. Every level above the paragraph reads
   * summaries, so no message sent to a span or a document run may contain a paragraph's text.
   */
  @Test
  void no_level_above_the_paragraph_is_ever_shown_the_document() {
    UUID id =
        corpus(
            "notes.md",
            "Alpha claims a thing.\n\nBeta denies it.\n\nGamma qualifies.\n\nDelta concludes.");
    // An answer that does not quote its input, unlike this class's default.
    // The echoing fallback is what makes the arithmetic tests readable and
    // it is exactly wrong here: a summary containing the paragraph it was
    // written from would fail this for the fixture's reason rather than the
    // cascade's.
    transport.thenAlways("Something is claimed.");

    summarise(id, "notes", Budget.of(20));

    for (ScriptedChat.Call call : transport.calls()) {
      if (call.agent().equals("paragraph_summariser")) {
        continue;
      }
      String sent = String.join("\n", call.contents());
      for (String paragraph :
          List.of(
              "Alpha claims a thing.", "Beta denies it.", "Gamma qualifies.", "Delta concludes.")) {
        assertFalse(
            sent.contains(paragraph),
            call.agent() + " was shown the raw text of a paragraph: " + paragraph);
      }
    }
  }

  /**
   * The document's own call is the last one, after everything it reads.
   *
   * <p><b>The depth of the fold is a property of the unit and not a number in a file</b>, and the
   * tiers above it are fixed because the document's own shape fixes them: one section here, in one
   * chapter, because the fixture heads nothing.
   */
  @Test
  void the_document_call_is_the_last_one_and_the_fold_depth_is_the_unit_s() {
    UUID id = corpus("long.md", paragraphs(10));

    Outcome ran = summarise(id, "long", Budget.of(40));

    assertEquals(Ending.ANSWERED, ran.ending(), ran.text());
    // Ten paragraphs, all in the one section this fixture derives. Ten
    // calls; then runs of three fold to four (three calls, and the tenth
    // summary CARRIED rather than summarised on its own); then four fold to
    // two (one call, and the fourth carried); then two is not more than the
    // span of three, so the section call reads those two. One chapter call
    // over that one section summary, one document call over that one chapter
    // summary. Seventeen calls, of which four are folds.
    assertEquals(17, transport.calls().size());
    assertEquals(10, transport.agents().stream().filter("paragraph_summariser"::equals).count());
    assertEquals(4, transport.agents().stream().filter("span_summariser"::equals).count());
    assertEquals(1, transport.agents().stream().filter("document_summariser"::equals).count());
    assertEquals("document_summariser", transport.agents().get(16));
  }

  /**
   * <b>A run of one summary is carried and never summarised.</b>
   *
   * <p>Four paragraphs at a span of three is one fold of three and one remainder. The remainder is
   * passed up untouched: summarising a single summary rewrites a sentence into another sentence and
   * calls it compression. It is the tier calls above it that are <em>not</em> declined on that
   * argument, and {@link #a_tier_over_one_child_is_still_called_and_a_fold_of_one_is_still_carried}
   * is where the difference is argued.
   */
  @Test
  void a_remainder_of_one_is_carried_rather_than_paraphrased() {
    UUID id = corpus("four.md", paragraphs(4));

    summarise(id, "four", Budget.of(40));

    assertEquals(1, transport.agents().stream().filter("span_summariser"::equals).count());
    // Four paragraphs, one fold, and one call each for the section, the
    // chapter and the document.
    assertEquals(8, transport.calls().size());
  }

  // --- the allowance --------------------------------------------------------

  /**
   * <b>One allowance for the whole ingest, shared by every run in it.</b>
   *
   * <p>{@code AgentController.curate} is the precedent named in the survey: a curator pass mints
   * one {@code Budget} and hands the same object to every ruling, so a ruling's spending is
   * spending the pass no longer has. An ingest is that shape and not an exception — which is what
   * stops the 242-against-40 collision being answered either by charging a conversation's whole
   * allowance six times over or by exempting the most expensive operation in the system from the
   * one honest cost bound it has.
   */
  @Test
  void every_run_in_a_cascade_spends_one_budget() {
    UUID id = corpus("notes.md", paragraphs(4));
    Budget budget = Budget.of(20);

    summarise(id, "notes", budget);

    // Four paragraph calls, one fold of the first three, and one call each
    // for the section, the chapter and the document. Every one of them
    // claimed from the same object.
    assertEquals(transport.calls().size(), budget.spent());
    assertEquals(8, budget.spent());
  }

  /**
   * A cascade that spends its allowance stops, says so, and keeps every summary it already wrote.
   *
   * <p>The Curator's rule, and here it buys something Curator does not have: a paragraph summary is
   * attached the moment it comes back, so the next ingest of the same document finds exactly the
   * paragraphs that were never reached — from their own rows, needing nothing outside Postgres to
   * have survived. That matters at this scale in a way it does not at a curator's: {@code JobStore}
   * is in memory, so a ~26-minute ingest is precisely the job whose handle a restart loses.
   */
  @Test
  void a_cascade_that_runs_out_of_allowance_keeps_what_it_paid_for() {
    UUID id = corpus("notes.md", paragraphs(6));

    Outcome ran = summarise(id, "notes", Budget.of(2));

    assertEquals(Ending.CALL_BUDGET, ran.ending());
    assertTrue(ran.text().contains("2"), ran.text());
    assertEquals(2, store.paragraphSummaries(id).size());
    assertEquals(4, store.unsummarised(id).size());
    assertEquals(null, store.find("notes.md").orElseThrow().summary());
  }

  /** And the next ingest owes only what the first did not reach. */
  @Test
  void the_next_run_summarises_only_what_the_stopped_one_did_not() {
    UUID id = corpus("notes.md", paragraphs(6));
    summarise(id, "notes", Budget.of(2));
    transport = new ScriptedChat();

    Outcome ran = summarise(id, "notes", Budget.of(40));

    assertEquals(Ending.ANSWERED, ran.ending(), ran.text());
    assertEquals(4, transport.agents().stream().filter("paragraph_summariser"::equals).count());
    assertEquals(6, store.paragraphSummaries(id).size());
  }

  /**
   * <b>The upper tiers are re-derived after a re-ingest, and this is the number stage 2 decided on
   * rather than the intuition it decided with.</b>
   *
   * <p>The identity rule's payoff is intact and is the reason a corpus is worth re-ingesting: an
   * unchanged paragraph keeps its id and therefore keeps its summary, so <b>not one paragraph is
   * summarised again</b>. What is not kept is the hierarchy: {@code DocumentStore.write} deletes
   * the chapters and re-derives them, because the detectors have run again over text that may have
   * moved, so {@code sections.summary} and {@code chapters.summary} go with them.
   *
   * <p><b>What that costs is bounded by the shape of a hierarchy and not by the size of a
   * document.</b> The upper tiers are {@code sections + chapters + 1} calls against one per
   * paragraph at the bottom, and a section holds several paragraphs by construction — nine calls of
   * two hundred and thirty on the 30-page paper this port is sized against. Keeping them would need
   * an identity rule for a unit that has no content hash, and an invalidation rule for the
   * paragraphs under it; that ratio is what says it is not worth owning yet.
   */
  @Test
  void a_re_ingest_that_changed_nothing_still_pays_for_the_hierarchy_it_replaced() {
    UUID id = corpus("notes.md", paragraphs(3));
    summarise(id, "notes", Budget.of(20));
    transport = new ScriptedChat();
    corpus("notes.md", paragraphs(3));

    Outcome again = summarise(id, "notes", Budget.of(20));

    assertEquals(Ending.ANSWERED, again.ending(), again.text());
    assertEquals(
        List.of("section_summariser", "chapter_summariser", "document_summariser"),
        transport.agents(),
        "no paragraph was summarised again, and every unit above one was");
  }

  /**
   * And a cascade over a document whose whole hierarchy is already summarised costs nothing at all.
   *
   * <p><b>The gate is asked of all four tiers and not of the two ends.</b> Before the tiers existed
   * it was "no paragraph is waiting and the document has a sentence", and after a re-ingest that is
   * true of a document whose chapters and sections hold nothing — the state the per-document ask
   * reads from. {@code DocumentStore.unsummarisedUnits} is the middle of the question.
   */
  @Test
  void a_document_summarised_to_the_top_costs_nothing_to_summarise_again() {
    UUID id = corpus("notes.md", paragraphs(3));
    summarise(id, "notes", Budget.of(20));
    transport = new ScriptedChat();

    Outcome again = summarise(id, "notes", Budget.of(20));

    assertEquals(Ending.ANSWERED, again.ending(), again.text());
    assertEquals(List.of(), transport.agents());
    assertEquals(0, again.modelCalls());
  }

  /**
   * An edit costs the paragraph that changed, and the units above it.
   *
   * <p>Two hundred model calls against a handful is the difference this makes on a real document,
   * and it is the reason {@code DocumentStore.write} touches a kept row's ordinal and nothing else.
   */
  @Test
  void an_edited_paragraph_costs_itself_and_the_units_above_it() {
    UUID id = corpus("notes.md", "One.\n\nTwo.\n\nThree.");
    summarise(id, "notes", Budget.of(20));
    transport = new ScriptedChat();
    corpus("notes.md", "One.\n\nTwo, revised.\n\nThree.");

    summarise(id, "notes", Budget.of(20));

    assertEquals(
        List.of(
            "paragraph_summariser",
            "section_summariser",
            "chapter_summariser",
            "document_summariser"),
        transport.agents());
  }

  // --- stopping -------------------------------------------------------------

  /**
   * Cancellation lands at a paragraph boundary, exactly as it lands at a turn boundary inside a run
   * and at a batch boundary inside embedding.
   */
  @Test
  void a_cancelled_cascade_stops_at_a_paragraph_and_keeps_what_it_wrote() {
    UUID id = corpus("notes.md", paragraphs(5));
    // Flipped by the work itself rather than by a clock or a sleep: it
    // becomes true exactly when the second summary is on its row, which is a
    // paragraph boundary and nowhere else.
    BooleanSupplier afterTwo = () -> store.paragraphSummaries(id).size() >= 2;

    Outcome ran = summariser().summarise(id, "notes", Budget.of(40), afterTwo);

    assertEquals(Ending.CANCELLED, ran.ending());
    assertEquals(2, store.paragraphSummaries(id).size());
    assertEquals(3, store.unsummarised(id).size());
    assertEquals(null, store.find("notes.md").orElseThrow().summary());
  }

  /**
   * A dead endpoint stops the cascade rather than being summarised around.
   *
   * <p>Curator's rule and its reason, at a scale where it costs more: carrying on would spend the
   * rest of the ingest's allowance rediscovering that the endpoint is still down, one failed run
   * per remaining paragraph, every one of them charged to the same budget.
   */
  @Test
  void a_dead_endpoint_stops_the_cascade_rather_than_being_summarised_around() {
    UUID id = corpus("notes.md", paragraphs(5));
    transport
        .then("summary of one")
        .then(
            () -> {
              throw new LlmException("the node is not answering");
            });

    Outcome ran = summarise(id, "notes", Budget.of(40));

    assertEquals(Ending.UNAVAILABLE, ran.ending());
    assertEquals(1, store.paragraphSummaries(id).size());
  }

  /**
   * <b>A blank summary is never written, and the run that produced one is treated as a run that
   * answered nothing.</b>
   *
   * <p>Anchor guards the same failure — blank content, retry once at temperature 0, then throw —
   * and the reason ports exactly: a blank stored as a summary is folded upward as though it were a
   * claim, and every level above reads summaries rather than text, so nothing downstream could
   * tell. Here the schema refuses it too ({@code paragraphs_summary_is_not_blank}), which is the
   * half that cannot be forgotten at a later call site.
   */
  @Test
  void a_blank_answer_is_not_a_summary() {
    UUID id = corpus("notes.md", paragraphs(2));
    transport.then("   ").thenAlways("summary of the rest");

    Outcome ran = summarise(id, "notes", Budget.of(40));

    assertEquals(Ending.STUCK, ran.ending(), ran.text());
    assertEquals(0, store.paragraphSummaries(id).size());
  }

  /**
   * A document with no agent registry is a boot whose wiring has not landed, and it says so rather
   * than storing nothing and reporting success.
   */
  @Test
  void a_server_that_defines_no_summarisers_says_so() {
    UUID id = corpus("notes.md", paragraphs(2));
    Summariser without = new Summariser(store, runtime(), () -> null, SPAN);

    Outcome ran = without.summarise(id, "notes", Budget.of(20), NEVER);

    assertEquals(Ending.UNAVAILABLE, ran.ending());
    assertTrue(
        ran.text().contains("paragraph_summariser") || ran.text().contains("agent"), ran.text());
    assertEquals(0, transport.calls().size());
  }

  @Test
  void the_arguments_it_cannot_work_without_are_named() {
    Summariser summariser = summariser();
    assertThrows(
        NullPointerException.class, () -> summariser.summarise(null, "notes", Budget.of(1), NEVER));
    assertThrows(
        NullPointerException.class,
        () -> summariser.summarise(UUID.randomUUID(), "notes", null, NEVER));
    assertThrows(
        NullPointerException.class,
        () -> summariser.summarise(UUID.randomUUID(), "notes", Budget.of(1), null));
  }

  // --- fixtures -------------------------------------------------------------

  private UUID corpus(String sourceName, String text) {
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

  private List<String> sectionSummaries(UUID documentId) {
    return jdbc.queryForList(
        "SELECT s.summary FROM sections s JOIN chapters c ON c.id = s.chapter_id"
            + " WHERE s.document_id = ? AND s.summary IS NOT NULL"
            + " ORDER BY c.ordinal, s.ordinal",
        String.class,
        documentId);
  }

  private List<String> chapterSummaries(UUID documentId) {
    return jdbc.queryForList(
        "SELECT summary FROM chapters WHERE document_id = ? AND summary IS NOT NULL"
            + " ORDER BY ordinal",
        String.class,
        documentId);
  }

  /** Every message one level was sent, in the order the level was called. */
  private List<String> sentTo(String agent) {
    return transport.calls().stream()
        .filter(call -> call.agent().equals(agent))
        .map(call -> String.join("\n", call.contents()))
        .toList();
  }

  /**
   * A paper's shape: headed sections, each holding the same number of paragraphs, and a
   * bibliography the detectors drop.
   *
   * <p>The seven names are the ones a paper uses and {@code SectionDetector} knows, so this fixture
   * derives the hierarchy rather than falling back to the synthetic one. {@code References} is here
   * because a real paper has one and because dropping it is what makes the count honest: it is
   * excluded after serving as a boundary, so its entries cost no model call.
   */
  private static String paper(int sections, int paragraphsEach) {
    List<String> headings =
        List.of(
            "Abstract",
            "Introduction",
            "Background",
            "Methods",
            "Results",
            "Discussion",
            "Conclusion");
    StringBuilder text = new StringBuilder();
    for (int section = 0; section < sections; section++) {
      text.append(headings.get(section)).append("\n\n");
      for (int i = 1; i <= paragraphsEach; i++) {
        text.append("In ")
            .append(headings.get(section))
            .append(", paragraph ")
            .append(i)
            .append(" claims something in particular.\n\n");
      }
    }
    text.append("References\n\n[1] Somebody, A paper, 2020.\n\n");
    return text.toString();
  }

  private static String paragraphs(int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= count; i++) {
      text.append("Paragraph ").append(i).append(" claims something.\n\n");
    }
    return text.toString();
  }

  private Outcome summarise(UUID documentId, String title, Budget budget) {
    return summariser().summarise(documentId, title, budget, NEVER);
  }

  private Summariser summariser() {
    return new Summariser(store, runtime(), () -> registry(), SPAN);
  }

  private JobRuntime runtime() {
    return new JobRuntime(transport.dispatcher(), List.of());
  }

  /** The three as they are shipped: no tools, one turn, one call. */
  private static AgentRegistry registry() {
    Map<String, AgentDefinition> agents = new HashMap<>();
    agents.put(Summariser.PARAGRAPH, definition(Summariser.PARAGRAPH, "fast"));
    agents.put(Summariser.SPAN, definition(Summariser.SPAN, "reasoning"));
    agents.put(Summariser.SECTION, definition(Summariser.SECTION, "reasoning"));
    agents.put(Summariser.CHAPTER, definition(Summariser.CHAPTER, "reasoning"));
    agents.put(Summariser.DOCUMENT, definition(Summariser.DOCUMENT, "reasoning"));
    return new AgentRegistry(agents);
  }

  private static AgentDefinition definition(String name, String model) {
    return new AgentDefinition(
        name,
        "summarises",
        model,
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "You are the " + name + ".");
  }
}
