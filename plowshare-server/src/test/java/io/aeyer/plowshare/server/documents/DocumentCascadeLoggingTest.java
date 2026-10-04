package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
 * <b>What the cascade leaves in the log, which is the whole reason it is agents and not a
 * service.</b>
 *
 * <p>Anchor's {@code SummariserService} makes direct model calls and records nothing but a token
 * count. If that had been ported, the largest workload in this system would produce no
 * conversation, no entry, no turn row and no trajectory — and the claim that Plowshare's
 * architecture <em>carries</em> a document pipeline rather than merely hosting one would rest on
 * nothing anybody could read back. This file is where it rests on something.
 *
 * <p>Everything but the model is real: Postgres behind Flyway, the real stores, the real {@link
 * JobRuntime} loop and the real {@link Compaction}. Folds run on the calling thread for {@code
 * UniversalLoggingTest}'s reason — a fold on a thread this class has no handle on is one the
 * teardown cannot join — and the histories here are far too small to trip one.
 */
@Testcontainers
class DocumentCascadeLoggingTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /**
   * Room for every history here, so nothing folds and these assertions are about logging rather
   * than about compaction.
   */
  private static final int ROOMY = 1_000_000;

  private static final int SPAN = 3;
  private static final Instant AT = Instant.parse("2026-09-04T09:00:00Z");
  private static final BooleanSupplier NEVER = () -> false;

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;

  private DocumentStore store;
  private ConversationStore conversations;
  private EntryStore entries;
  private TurnStore turns;
  private Compaction compaction;
  private ScriptedChat transport;
  private final List<Compaction> folding = new ArrayList<>();

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
  void freshTables() {
    jdbc.update(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, entries, compactions, turns, conversations CASCADE");
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, documents CASCADE");
    store = new DocumentStore(jdbc, transactions);
    conversations = new ConversationStore(jdbc);
    entries = new EntryStore(jdbc);
    turns = new TurnStore(jdbc);
    transport = new ScriptedChat();
  }

  @AfterEach
  void close() {
    folding.forEach(Compaction::close);
  }

  // --- the tree -------------------------------------------------------------

  /**
   * <b>One conversation for the ingest, and every summariser run a child of it.</b>
   *
   * <p>This is the origin decision, asserted. Two shapes were available and V17's constraints
   * choose between them: 242 parentless roots of origin {@code submission} would each have to carry
   * a copy of the ingest's allowance — {@code conversations_an_allowance_is_owned_or_shared} makes
   * a submission a row that owns one — and the first thing to sum that column would count every
   * model call two hundred times, off two hundred rows all holding a plausible number. One root
   * that owns the allowance, with the runs as delegations, is the only shape the frozen vocabulary
   * permits without a migration and the only one whose arithmetic is right.
   */
  @Test
  void an_ingest_is_one_conversation_and_every_summary_is_a_delegation_under_it() {
    UUID id = corpus("notes.md", paragraphs(4));

    Outcome ran = summariser().summarise(id, "notes", Budget.of(40), NEVER);
    assertEquals(Ending.ANSWERED, ran.ending(), ran.text());

    List<ConversationRecord> all = cascade();
    List<ConversationRecord> roots = all.stream().filter(row -> row.parentId() == null).toList();
    assertEquals(1, roots.size(), "an ingest opens exactly one conversation of its own");

    ConversationRecord root = roots.get(0);
    assertEquals(Origin.SUBMISSION, root.origin());
    assertEquals(
        Summariser.DOCUMENT,
        root.agent(),
        "the root is the run that actually happens in it: the document-level call");

    // Four paragraphs, one fold of the first three, one section call, one
    // chapter call, one document call. The document call is the ROOT's own
    // turn, so eight runs are eight rows, seven of them children.
    //
    // THE SHAPE IS WHAT THIS ASSERTS AND THE COUNT IS ONLY ITS ARITHMETIC.
    // Four tiers rather than three changed the number of children and
    // nothing else: still one root of origin `submission` named for the
    // document level and owning the whole allowance, still every other run a
    // `delegation` under it. V17's
    // conversations_an_allowance_is_owned_or_shared is what forbids any
    // other shape, and it does not care how many tiers there are.
    List<ConversationRecord> children = all.stream().filter(row -> row.parentId() != null).toList();
    assertEquals(7, children.size(), all.toString());
    for (ConversationRecord child : children) {
      assertEquals(Origin.DELEGATION, child.origin());
      assertEquals(root.id(), child.parentId());
    }
    assertEquals(
        4, children.stream().filter(child -> Summariser.PARAGRAPH.equals(child.agent())).count());
    assertEquals(
        1, children.stream().filter(child -> Summariser.SPAN.equals(child.agent())).count());
    assertEquals(
        1, children.stream().filter(child -> Summariser.SECTION.equals(child.agent())).count());
    assertEquals(
        1, children.stream().filter(child -> Summariser.CHAPTER.equals(child.agent())).count());
  }

  /**
   * <b>The allowance is recorded once, on the row that owns it.</b>
   *
   * <p>The other half of the origin decision. A delegation holds no numbers at all — not zero,
   * nothing — so there is no second allowance to double-count, and {@code
   * conversations_an_allowance_is_owned_or_shared} refuses to let one be written even if this class
   * stopped asking.
   */
  @Test
  void only_the_root_holds_an_allowance_and_the_children_hold_none() {
    UUID id = corpus("notes.md", paragraphs(3));

    summariser().summarise(id, "notes", Budget.of(40), NEVER);

    for (ConversationRecord row : cascade()) {
      if (row.parentId() == null) {
        assertNotNull(row.budget(), "the ingest's own conversation owns the allowance");
        assertEquals(40, row.budget().limit());
      } else {
        assertNull(
            row.budget(),
            "a summariser run spends the ingest's allowance by reference, and a copy"
                + " on its row would be a second allowance of the same size");
      }
    }
  }

  /**
   * <b>What the ingest actually spent is on its row, and this test used to assert the opposite.</b>
   *
   * <p>It was called {@code
   * what_an_ingest_actually_spent_is_not_on_its_row_and_that_is_a_known_hole} and it asserted a
   * zero, because {@code implementation rationale} §11 was open: a {@code submission} owned an
   * allowance and nothing ever wrote the number back, so every such row said nought spent for ever.
   * On this path that row is the only durable record of half an hour of model calls, and "0 of 300"
   * is not a record of it.
   *
   * <p>It is closed in one place for every submission this server runs, and that place is not the
   * documents slice: a transcript opened over a conversation that <em>owns</em> its allowance
   * writes the spending back when the run closes — {@code
   * Compaction.TurnTranscript.writeBackWhatWasSpent}. So this class needs no store it otherwise has
   * no use for, and the plain {@code JobStore} submission was fixed by the same three lines.
   *
   * <p><b>The two numbers are asserted together and that is the point.</b> The {@link Budget}
   * object always knew; the row is what a person reads a week later, and the test that matters is
   * that they agree.
   */
  @Test
  void what_an_ingest_spent_is_written_back_onto_its_row() {
    UUID id = corpus("notes.md", paragraphs(4));
    Budget budget = Budget.of(40);

    summariser().summarise(id, "notes", budget, NEVER);

    ConversationRecord root =
        cascade().stream().filter(row -> row.parentId() == null).findFirst().orElseThrow();
    assertEquals(8, budget.spent(), "the object knows what the cascade cost");
    assertEquals(
        8, root.budget().spent(), "and so does the row now — implementation rationale §11, closed");
    assertEquals(40, root.budget().limit(), "on the allowance it was granted");
  }

  // --- the log --------------------------------------------------------------

  /**
   * A summariser run writes a real trajectory: what it was asked, and what it answered.
   *
   * <p>This is what a direct model call could not have. The paragraph is the run's opening
   * utterance and the summary is its answer, both in the child's own log, so "why does paragraph 12
   * say that?" is a question with an answer.
   */
  @Test
  void every_summariser_run_writes_what_it_was_asked_and_what_it_said() {
    UUID id = corpus("notes.md", "Alpha claims a thing.\n\nBeta denies it.");

    summariser().summarise(id, "notes", Budget.of(40), NEVER);

    ConversationRecord first =
        cascade().stream()
            .filter(row -> Summariser.PARAGRAPH.equals(row.agent()))
            .findFirst()
            .orElseThrow();
    List<EntryRecord> log = entries.forConversation(first.id());

    assertTrue(
        log.stream()
            .anyMatch(
                entry ->
                    entry.kind() == EntryKind.UTTERANCE
                        && entry.content().contains("Alpha claims a thing.")),
        "the paragraph is the run's opening utterance — " + log);
    assertTrue(
        log.stream().anyMatch(entry -> entry.kind() == EntryKind.ANSWER),
        "and the summary is its answer — " + log);

    List<TurnRecord> spoken = turns.forConversation(first.id());
    assertEquals(1, spoken.size(), "one run, one turn");
    assertEquals(Summariser.PARAGRAPH, spoken.get(0).agent());
    assertEquals(Ending.ANSWERED, spoken.get(0).ending());
  }

  /**
   * <b>Two hundred paragraphs is two hundred conversations, and nothing here mitigates that.</b>
   *
   * <p>It is the owner's decision and the point of the slice: this is the first workload that puts
   * per-origin retention, {@code GET /v1/conversations} and the learner's window under real volume.
   * V17 said of a curator pass that "a pass over a few hundred memories is now a few hundred
   * conversations ... that volume is the owner's decision and is not mitigated here"; an ingest is
   * that sentence again, per document, at a document a person can upload in a second.
   *
   * <p>Twenty paragraphs rather than two hundred, because a test that made two hundred model calls
   * through a scripted transport would be slow for no more information. The arithmetic is linear
   * and stated: <b>one conversation per paragraph, plus one per fold, plus one per section and per
   * chapter, plus the root.</b>
   *
   * <p>What that costs the listing is asserted here too, and it is the reason this matters rather
   * than merely being large: {@code GET /v1/conversations} reads a home's {@code turn} rows, so a
   * person's listing is untouched — but every one of these rows is in {@code conversations} and in
   * the tree walks that read it, and none of them is a person's.
   */
  @Test
  void an_ingest_puts_one_conversation_in_the_log_for_every_paragraph_in_the_document() {
    UUID id = corpus("long.md", paragraphs(20));

    summariser().summarise(id, "long", Budget.of(400), NEVER);

    List<ConversationRecord> all = cascade();
    // Twenty paragraphs, all in the one section a document with no heading
    // derives; runs of three fold to seven (six folds of three and one of
    // the remaining two); seven fold to three (two folds, and the seventh
    // summary CARRIED rather than paraphrased); three is not more than the
    // span, so ONE section call reads those three. Then one chapter call
    // over that one section summary, and the root's own document call over
    // that one chapter summary. Thirty-two runs in thirty-two conversations
    // -- one root and thirty-one children.
    assertEquals(
        32,
        all.size(),
        "one conversation per run, the document-level call being" + " the root's own");
    assertEquals(20, all.stream().filter(row -> Summariser.PARAGRAPH.equals(row.agent())).count());
    assertEquals(
        31,
        conversations
                .treeOf(
                    all.stream()
                        .filter(row -> row.parentId() == null)
                        .findFirst()
                        .orElseThrow()
                        .id())
                .size()
            - 1,
        "and the whole of it is one walkable tree");
  }

  /**
   * <b>What the volume does to the listing: nothing, and that is measured rather than hoped.</b>
   *
   * <p>{@code GET /v1/conversations} answers {@code ConversationStore.inHome}, which filters {@code
   * origin = 'turn'} — so thirty-two rows from one ingest, or two hundred and thirty from a real
   * one, leave a person's opening screen exactly as it was. V17 rebuilt {@code conversations_home}
   * with {@code origin} in the middle for precisely this, and the curator pass was the workload it
   * was written against; an ingest is that workload again, an order of magnitude up, and the index
   * holds.
   *
   * <p><b>What it does NOT say</b> is that the rows are free. They are in {@code conversations}, in
   * {@code turns}, and in {@code entries} — one paragraph of text and one summary per run — and
   * every tree walk, every retention sweep and every {@code rootsAt} reads them. That is the cost,
   * it is not mitigated here, and the sweep is the surface to watch.
   */
  @Test
  void none_of_an_ingest_s_conversations_reach_a_person_s_listing() {
    UUID id = corpus("notes.md", paragraphs(6));

    summariser().summarise(id, "notes", Budget.of(40), NEVER);

    assertFalse(cascade().isEmpty(), "the cascade did open conversations");
    assertEquals(
        List.of(),
        conversations.inHome(Home.global()),
        "and not one of them is in the listing a person opens");
  }

  /**
   * <b>A child's log never reaches its parent's prompt, and here that is load-bearing rather than
   * tidy.</b>
   *
   * <p>The root's own run is the document-level call, and it happens after two hundred children
   * have written into their own conversations. If a child's entries could reach a parent's
   * projection, the top of the cascade would open with every paragraph of the document — which is
   * exactly the compression invariant defeated, through the log rather than through the prompt
   * somebody wrote.
   */
  @Test
  void the_document_call_opens_with_the_summaries_and_not_with_its_children_s_logs() {
    UUID id = corpus("notes.md", "Alpha claims a thing.\n\nBeta denies it.\n\nGamma qualifies it.");
    transport.thenAlways("Something is claimed.");

    summariser().summarise(id, "notes", Budget.of(40), NEVER);

    ScriptedChat.Call top =
        transport.calls().stream()
            .filter(call -> call.agent().equals(Summariser.DOCUMENT))
            .findFirst()
            .orElseThrow();
    String sent = String.join("\n", top.contents());
    for (String paragraph :
        List.of("Alpha claims a thing.", "Beta denies it.", "Gamma qualifies it.")) {
      assertFalse(
          sent.contains(paragraph),
          "the document-level call was shown a paragraph through the log: " + sent);
    }
    assertTrue(sent.contains("Something is claimed."), sent);
  }

  /**
   * Every conversation the cascade opened, root first.
   *
   * <p><b>Not {@code inHome}, and the reason is a finding rather than a detail.</b> {@code
   * ConversationStore.inHome} — which is what {@code GET /v1/conversations} answers — filters
   * {@code origin = 'turn'}, so none of these rows appears in a person's listing at all. That is
   * the answer to "what does this volume do to the listing": nothing, and V17's index is why. See
   * {@link #none_of_an_ingest_s_conversations_reach_a_person_s_listing}.
   *
   * <p>So the tree is read the way a retention sweep reads one: the roots of an origin, and then
   * the tree under each.
   */
  private List<ConversationRecord> cascade() {
    List<ConversationRecord> found = new ArrayList<>();
    for (String rootId :
        conversations.roots(Origin.SUBMISSION, ConversationLifecycle.ACTIVE, null)) {
      found.add(conversations.find(rootId).orElseThrow());
      for (String id : conversations.treeOf(rootId)) {
        if (!id.equals(rootId)) {
          found.add(conversations.find(id).orElseThrow());
        }
      }
    }
    return found;
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

  private static String paragraphs(int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= count; i++) {
      text.append("Paragraph ").append(i).append(" claims something.\n\n");
    }
    return text.toString();
  }

  private Summariser summariser() {
    LlmDispatcher models = transport.dispatcher();
    // The public constructor, which owns virtual threads for folds. The
    // executor-taking one is package-private to `agents` and this class is
    // not in it -- which costs nothing here, because ROOMY is far above any
    // history this file builds and no fold ever starts.
    compaction =
        new Compaction(
            models,
            DocumentCascadeLoggingTest::folder,
            turns,
            new CompactionStore(jdbc),
            entries,
            ROOMY,
            conversations);
    folding.add(compaction);
    return new Summariser(
        store, new JobRuntime(models, List.of()), () -> registry(), SPAN, compaction);
  }

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

  /**
   * The agent a fold runs as, which nothing in this class asks for.
   *
   * <p>Named rather than left out all the same. A fold that could not say what it runs as would run
   * as the agent being folded, which is the defect {@code Compaction.FOLDER} exists to end --
   * {@code implementation rationale} §6.1 -- and a fixture that quietly had no folder would be the
   * one place that could not tell the two apart.
   */
  private static AgentDefinition folder() {
    return new AgentDefinition(
        Compaction.FOLDER,
        "a fixture folder",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "You summarise a span of a recorded conversation.");
  }
}
