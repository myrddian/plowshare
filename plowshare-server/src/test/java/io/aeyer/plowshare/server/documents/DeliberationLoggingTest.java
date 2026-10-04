package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * <b>What a deliberation leaves in the log, which is §6's whole divergence from Anchor's
 * envelope.</b>
 *
 * <p>Anchor records a pass as three JSONB columns on an {@code AskJob} — a proposer slot, a critic
 * slot and a synthesiser slot, each with an {@code evidence_access} label on the wire. Ported that
 * way, the three stages would be three blobs on a row and the argument that Plowshare's log
 * <em>is</em> the trajectory machinery would rest on nothing anybody could read back. So a pass is
 * one conversation with the stages under it, and this file is where that rests on something.
 *
 * <p>{@code DocumentCascadeLoggingTest}'s shape and its wiring: everything but the model is real,
 * and folds run on the calling thread because a fold on a thread this class has no handle on is one
 * the teardown cannot join.
 */
@Testcontainers
class DeliberationLoggingTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /**
   * Room for every history here, so nothing folds and these assertions are about logging rather
   * than about compaction.
   */
  private static final int ROOMY = 1_000_000;

  private static final Instant AT = Instant.parse("2026-09-05T09:00:00Z");
  private static final BooleanSupplier NEVER = () -> false;

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;

  private DocumentStore store;
  private CitationStore citations;
  private ConversationStore conversations;
  private EntryStore entries;
  private TurnStore turns;
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
    citations = new CitationStore(jdbc);
    conversations = new ConversationStore(jdbc);
    entries = new EntryStore(jdbc);
    turns = new TurnStore(jdbc);
    transport = new ScriptedChat();
  }

  @AfterEach
  void close() {
    folding.forEach(Compaction::close);
  }

  /**
   * <b>One conversation for the pass, and the synthesiser's run is the conversation itself.</b>
   *
   * <p>{@link Summariser}'s tree exactly and for its reasons. The root is a {@link
   * Origin#SUBMISSION} — the origin V17 lets own an allowance — named for the agent whose run it
   * <em>is</em>; the proposer and the critic are {@link Origin#DELEGATION} children, which is the
   * origin that has a parent and holds no numbers. A root named for the proposer would be a
   * conversation whose own turn is the last thing in it and whose name is the first.
   */
  @Test
  void a_pass_is_one_conversation_with_the_three_earlier_stages_under_it() {
    UUID document = paper();
    transport
        .then("I argue that the bound is tight.")
        .then(critique())
        .then(reviewed())
        .then(synthesised());

    deliberation().ask(document, "is the bound tight", Budget.of(8), NEVER);

    List<ConversationRecord> opened = pass();
    assertEquals(4, opened.size(), opened.toString());

    ConversationRecord root =
        opened.stream().filter(row -> row.parentId() == null).findFirst().orElseThrow();
    assertEquals(Origin.SUBMISSION, root.origin());
    assertEquals(Deliberation.SYNTHESISER, root.agent());
    assertNotNull(root.budget(), "the root owns the pass's allowance");
    assertEquals(8, root.budget().limit());

    List<ConversationRecord> children =
        opened.stream().filter(row -> row.parentId() != null).toList();
    assertEquals(3, children.size(), opened.toString());
    assertEquals(
        List.of(Deliberation.PROPOSER, Deliberation.CRITIC, Deliberation.REVIEWER),
        children.stream()
            .map(ConversationRecord::agent)
            .sorted(
                java.util.Comparator.comparingInt(
                    List.of(Deliberation.PROPOSER, Deliberation.CRITIC, Deliberation.REVIEWER)
                        ::indexOf))
            .toList(),
        opened.toString());
    for (ConversationRecord child : children) {
      assertEquals(Origin.DELEGATION, child.origin());
      assertEquals(root.id(), child.parentId());
      assertNull(
          child.budget(),
          child.agent()
              + " holds an allowance of its own, so two rows in one tree"
              + " carry the same number and anything summing the column counts a"
              + " pass twice");
    }
  }

  /**
   * The critic's retry is one more conversation under the same root, and it spends the same
   * allowance.
   */
  @Test
  void the_critics_retry_is_another_child_of_the_same_root() {
    UUID document = paper();
    transport
        .then("I argue that the bound is tight.")
        .then("not json at all")
        .then(critique())
        .then(reviewed())
        .then(synthesised());
    Budget budget = Budget.of(8);

    deliberation().ask(document, "is the bound tight", budget, NEVER);

    assertEquals(5, pass().size());
    assertEquals(5, budget.spent());
    assertEquals(2, pass().stream().filter(row -> Deliberation.CRITIC.equals(row.agent())).count());
  }

  /**
   * <b>A citation from a deliberation is written against the conversation the answer was spoken
   * in.</b>
   *
   * <p>It cannot come through {@code Citing}: {@code ask_synthesiser} declares {@code tools: []},
   * so {@code AgentDefinition.canCite} is false and that seam correctly writes nothing — the corpus
   * was never granted to that agent, the orchestrator handed it the passages. So {@link
   * Deliberation} writes its own, and {@code Transcript.spokenIn} is what stops those rows claiming
   * to be from a run started on nobody's behalf, which would leave {@code CitationStore.madeIn}
   * unable to find them.
   */
  @Test
  void a_deliberations_citation_names_the_conversation_and_the_turn_it_was_spoken_in() {
    UUID document = paper();
    UUID paragraph =
        jdbc.queryForObject(
            "SELECT id FROM paragraphs WHERE text = ?",
            UUID.class,
            "The bound is tight for antichains.");
    transport
        .then("I argue that the bound is tight.")
        .then(critique())
        .then(reviewed())
        .then(
            "RESPONSE:\nI argue that the bound is tight.\n\nGROUNDING:\n"
                + "{\"grounded_in\": [{\"paragraph\": \""
                + paragraph
                + "\", \"quote\": \"the bound is tight for antichains\"}]}");

    deliberation().ask(document, "is the bound tight", Budget.of(8), NEVER);

    List<CitationStore.Cited> recorded = citations.of(document, 10);
    assertEquals(1, recorded.size());
    assertEquals(Deliberation.SYNTHESISER, recorded.get(0).agent());
    assertNotNull(recorded.get(0).conversationId());
    assertEquals(1, recorded.get(0).turnOrdinal());

    ConversationRecord root =
        pass().stream().filter(row -> row.parentId() == null).findFirst().orElseThrow();
    assertEquals(root.id(), recorded.get(0).conversationId());
    assertTrue(
        citations.madeIn(root.id(), 10).size() == 1,
        "the citation is not reachable through the conversation it was made in");
  }

  // --- fixtures -------------------------------------------------------------

  /**
   * Every conversation this pass opened, root first. {@code DocumentCascadeLoggingTest.cascade}'s
   * twin.
   */
  private List<ConversationRecord> pass() {
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

  private Deliberation deliberation() {
    Compaction compaction =
        new Compaction(
            transport.dispatcher(),
            DeliberationLoggingTest::folder,
            turns,
            new CompactionStore(jdbc),
            entries,
            ROOMY,
            conversations);
    folding.add(compaction);
    return new Deliberation(
        store,
        retrieval(),
        citations,
        new JobRuntime(transport.dispatcher(), List.of()),
        DeliberationLoggingTest::registry,
        Clock.fixed(AT, ZoneOffset.UTC),
        compaction);
  }

  private RetrievalService retrieval() {
    EmbeddingClient embeddings =
        new EmbeddingClient() {

          @Override
          public float[] embed(String text) {
            return axis();
          }

          @Override
          public List<float[]> embedAll(List<String> texts) {
            return texts.stream().map(text -> axis()).toList();
          }
        };
    return new RetrievalService(store, embeddings, "nomic-embed-text", 768);
  }

  private static AgentRegistry registry() {
    Map<String, AgentDefinition> agents = new HashMap<>();
    for (String name :
        List.of(
            Deliberation.PROPOSER,
            Deliberation.CRITIC,
            Deliberation.REVIEWER,
            Deliberation.SYNTHESISER)) {
      agents.put(
          name,
          new AgentDefinition(
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
              false));
    }
    return new AgentRegistry(agents);
  }

  private UUID paper() {
    String text =
        """
                1. Introduction

                The bound is tight for antichains.

                2. Results

                We disprove the conjecture.
                """;
    Extracted extracted = TextExtraction.extract("paper.md", text.getBytes(StandardCharsets.UTF_8));
    UUID document =
        store
            .write(
                "paper.md",
                extracted,
                text.length(),
                "test",
                AT,
                Derivation.derive(extracted, ShippedChunking.SHIPPED))
            .documentId();
    jdbc.update("UPDATE documents SET summary = 'What the paper argues.' WHERE id = ?", document);
    jdbc.update(
        "UPDATE chapters SET summary = 'What the chapter argues.'" + " WHERE document_id = ?",
        document);
    jdbc.update(
        "UPDATE sections SET summary = 'What the section claims.'" + " WHERE document_id = ?",
        document);
    jdbc.update("UPDATE paragraphs SET summary = 'a claim' WHERE document_id = ?", document);
    for (DocumentStore.UnembeddedChunk chunk : store.unembedded(document)) {
      store.attach(chunk.id(), axis());
    }
    return document;
  }

  private static float[] axis() {
    float[] embedding = new float[768];
    embedding[0] = 1f;
    return embedding;
  }

  private static String critique() {
    return "{\"challenges\": [], \"macro_view_supports_proposer\": true}";
  }

  private static String synthesised() {
    return "RESPONSE:\nI argue that the bound is tight.\n\nGROUNDING:\n" + "{\"grounded_in\": []}";
  }

  /**
   * What the review pass returns: objections in prose, for the synthesiser to act on. Prose and not
   * JSON, which is that stage's own point.
   */
  private static String reviewed() {
    return "1. The draft asserts the bound is tight without naming the"
        + " qualification the appendix adds.";
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
