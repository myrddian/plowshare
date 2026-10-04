package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database leases are the work queue. Expired running work resumes from committed checkpoints. */
public final class InformationLifecycle implements AutoCloseable {
  private static final Duration LEASE = Duration.ofMinutes(5);
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final InformationCatalogue catalogue;
  private final Clock clock;
  private final Processor processor;
  private final Gates gates;
  private ScheduledExecutorService worker;

  @FunctionalInterface
  public interface Processor {
    void process(Lease lease, BooleanSupplier cancelled, Runnable fence);
  }

  public interface Gates {
    default Gate before(Lease lease) {
      return Gate.NOTHING;
    }

    default Gate after(Lease lease) {
      return Gate.NOTHING;
    }

    default void finished(Lease lease) {}
  }

  public record Lease(
      UUID revision,
      UUID resource,
      long generation,
      String stage,
      int attempt,
      UUID token,
      String owner,
      Long project) {}

  public InformationLifecycle(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      InformationCatalogue catalogue,
      Clock clock,
      Processor processor,
      Gates gates) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.catalogue = catalogue;
    this.clock = clock;
    this.processor = processor;
    this.gates = gates;
  }

  public synchronized void start() {
    if (worker != null) return;
    worker =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("information-lifecycle").factory());
    worker.scheduleWithFixedDelay(
        () -> {
          try {
            sweepUntagged();
            sweepTagGroups();
          } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(InformationLifecycle.class)
                .warn("information tag sweep could not be serviced", failure);
          }
        },
        0,
        60,
        TimeUnit.SECONDS);
    worker.scheduleWithFixedDelay(
        () -> {
          try {
            drainOne();
          } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(InformationLifecycle.class)
                .warn("information queue could not be serviced", failure);
          }
        },
        0,
        1,
        TimeUnit.SECONDS);
  }

  /** Enqueue a bounded sweep of retained, readable, previously untagged revisions. */
  public int sweepUntagged() {
    return transactions.inTransaction(
        () -> {
          var candidates =
              jdbc.queryForList(
                  "SELECT r.id,r.generation,q.owner_handle FROM information_revisions r"
                      + " JOIN information_resources q ON q.id=r.resource_id"
                      + " JOIN information_steps s ON s.revision_id=r.id AND s.generation=r.generation AND s.stage='autoTag'"
                      + " WHERE r.availability='active' AND NOT r.excluded AND q.owner_handle IS NOT NULL"
                      + " AND jsonb_array_length(q.tags)=0 AND jsonb_array_length(r.auto_tag)=0 AND NOT r.auto_tag_generated"
                      + " AND r.allowance_spent<r.allowance_total AND r.extracted_text IS NOT NULL AND btrim(r.extracted_text)<>''"
                      + " AND s.state IN ('skipped','ready')"
                      + " AND (SELECT count(*) FROM information_steps prior WHERE prior.revision_id=r.id AND prior.generation=r.generation"
                      + " AND prior.stage IN ('extract','derive') AND prior.state IN ('ready','skipped'))=2"
                      + " AND NOT EXISTS(SELECT 1 FROM information_acquisitions a WHERE a.revision_id=r.id AND a.state<>'succeeded')"
                      + " AND (q.project_id IS NULL OR EXISTS(SELECT 1 FROM project_members m WHERE m.project_id=q.project_id AND m.handle=q.owner_handle))"
                      + " AND ((q.namespace<>'legacy' AND NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=r.id))"
                      + " OR information_readable(r.id,q.owner_handle,CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,"
                      + " (SELECT p.name FROM projects p WHERE p.id=q.project_id),true))"
                      + " AND NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision=r.id AND NOT information_readable(i.input_revision,"
                      + " q.owner_handle,CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,(SELECT p.name FROM projects p WHERE p.id=q.project_id),true))"
                      + " ORDER BY r.created_at,r.id LIMIT 100 FOR UPDATE OF r,s SKIP LOCKED");
          for (var candidate : candidates) {
            jdbc.update(
                "UPDATE information_steps SET state='pending',error=NULL,fingerprint=NULL,finished_at=NULL WHERE revision_id=? AND generation=? AND stage='autoTag'",
                candidate.get("id"),
                candidate.get("generation"));
            catalogue.event(
                (UUID) candidate.get("id"),
                ((Number) candidate.get("generation")).longValue(),
                (String) candidate.get("owner_handle"),
                "autoTag",
                "sweep.queued",
                "No user or automatic tags; retained text is ready.");
          }
          return candidates.size();
        });
  }

  /** Existing tagged documents can acquire categories without re-reading/replacing their tags. */
  public int sweepTagGroups() {
    return transactions.inTransaction(
        () -> {
          String tags = InformationTagGroups.tags("r", "q");
          var candidates =
              jdbc.queryForList(
                  "SELECT r.id,r.generation,q.owner_handle FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id"
                      + " JOIN information_steps s ON s.revision_id=r.id AND s.generation=r.generation AND s.stage='tagGroups'"
                      + " WHERE r.availability='active' AND NOT r.excluded AND q.owner_handle IS NOT NULL AND NOT q.tag_groups_manual"
                      + " AND jsonb_array_length("
                      + tags
                      + ")>0 AND (s.state='skipped' OR NOT r.tag_groups_generated OR r.tag_groups_input_tags<>"
                      + tags
                      + ")"
                      + " AND (r.allowance_spent<r.allowance_total OR (r.tag_groups_generated AND r.tag_groups_input_tags="
                      + tags
                      + ")) AND s.state IN ('skipped','ready')"
                      + " AND NOT EXISTS(SELECT 1 FROM information_acquisitions a WHERE a.revision_id=r.id AND a.state<>'succeeded')"
                      + " AND (q.project_id IS NULL OR EXISTS(SELECT 1 FROM project_members m WHERE m.project_id=q.project_id AND m.handle=q.owner_handle))"
                      + " AND ((q.namespace<>'legacy' AND NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=r.id)) OR information_readable(r.id,q.owner_handle,"
                      + " CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,(SELECT p.name FROM projects p WHERE p.id=q.project_id),true))"
                      + " AND NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision=r.id AND NOT information_readable(i.input_revision,q.owner_handle,"
                      + " CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,(SELECT p.name FROM projects p WHERE p.id=q.project_id),true))"
                      + " ORDER BY r.created_at,r.id LIMIT 100 FOR UPDATE OF r,s SKIP LOCKED");
          for (var candidate : candidates) {
            jdbc.update(
                "UPDATE information_steps SET state='pending',error=NULL,fingerprint=NULL,finished_at=NULL WHERE revision_id=? AND generation=? AND stage='tagGroups'",
                candidate.get("id"),
                candidate.get("generation"));
            catalogue.event(
                (UUID) candidate.get("id"),
                ((Number) candidate.get("generation")).longValue(),
                (String) candidate.get("owner_handle"),
                "tagGroups",
                "sweep.queued",
                "Existing tags need category membership.");
          }
          return candidates.size();
        });
  }

  public boolean drainOne() {
    return drainOne(null);
  }

  /** Service just this revision using the normal queue leases and stage gates. */
  public boolean drainOne(UUID revision) {
    return drain(revision, false);
  }

  public boolean drainCodeSyntax(UUID revision) {
    return drain(java.util.Objects.requireNonNull(revision), true);
  }

  private boolean drain(UUID revision, boolean syntaxOnly) {
    Lease lease = claim(revision, syntaxOnly);
    if (lease == null) return false;
    try {
      if (lease.stage().equals("autoTag")) requireTaggingEligible(jdbc, lease.revision());
      if (lease.stage().equals("tagGroups")) requireGroupingEligible(jdbc, lease.revision());
      Gate pre = safeGate(lease, false);
      record(lease, "stage.pre", pre);
      if (pre.isDenied()) {
        finish(lease, "blocked", pre.denied());
        return true;
      }
      processor.process(lease, () -> !renew(lease), () -> requireLease(lease));
      if (!renew(lease)) return true;
      Gate post = safeGate(lease, true);
      record(lease, "stage.post", post);
      finish(lease, post.isDenied() ? "blocked" : "ready", post.denied());
    } catch (StaleLease invalidated) {
      // Availability or generation changed. No obsolete checkpoint or completion may commit.
    } catch (RuntimeException failure) {
      finish(
          lease,
          failure instanceof SkippedStage
              ? "skipped"
              : failure instanceof io.aeyer.plowshare.server.documents.DocumentStages.Blocked
                  ? "blocked"
                  : "failed",
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    } finally {
      gates.finished(lease);
    }
    return true;
  }

  private Gate safeGate(Lease lease, boolean post) {
    try {
      return post ? gates.after(lease) : gates.before(lease);
    } catch (RuntimeException failure) {
      return new Gate(
          "stage hook failed: " + failure.getClass().getSimpleName(), List.of(), List.of());
    }
  }

  private void record(Lease lease, String stage, Gate gate) {
    transactions.inTransaction(
        () -> {
          requireLease(lease);
          catalogue.event(
              lease.revision(), lease.generation(), lease.owner(), stage, "gate", json(gate));
          return null;
        });
  }

  public Lease claim() {
    return claim(null);
  }

  private Lease claim(UUID revision) {
    return claim(revision, false);
  }

  private Lease claim(UUID revision, boolean syntaxOnly) {
    return transactions.inTransaction(
        () -> {
          List<Map<String, Object>> rows =
              jdbc.queryForList(
                  "SELECT s.*,r.resource_id,q.owner_handle,q.project_id FROM information_steps s"
                      + " JOIN information_revisions r ON r.id=s.revision_id JOIN information_resources q ON q.id=r.resource_id"
                      + " WHERE r.availability='active' AND r.generation=s.generation AND q.owner_handle IS NOT NULL"
                      + " AND NOT EXISTS(SELECT 1 FROM information_acquisitions a WHERE a.revision_id=r.id AND a.state<>'succeeded')"
                      + " AND (q.project_id IS NULL OR EXISTS(SELECT 1 FROM project_members m WHERE m.project_id=q.project_id AND m.handle=q.owner_handle))"
                      + " AND (s.state='pending' OR (s.state='running' AND s.lease_until<?))"
                      + " AND NOT EXISTS(SELECT 1 FROM information_steps prior WHERE prior.revision_id=s.revision_id AND prior.generation=s.generation"
                      + " AND ((s.stage='autoTag' AND prior.stage IN ('extract','derive') AND prior.state NOT IN ('ready','skipped'))"
                      + " OR (s.stage='tagGroups' AND prior.stage IN ('extract','derive','autoTag') AND prior.state IN ('pending','running','blocked'))"
                      + " OR (s.stage NOT IN ('autoTag','tagGroups') AND array_position(ARRAY['extract','derive','embed','summarise','summary_embed'],prior.stage)"
                      + " < array_position(ARRAY['extract','derive','embed','summarise','summary_embed'],s.stage) AND prior.state NOT IN ('ready','skipped'))))"
                      + (revision == null ? "" : " AND r.id=?")
                      + (syntaxOnly
                          ? " AND r.document_type='code' AND s.stage IN ('extract','derive')"
                          : "")
                      + " ORDER BY array_position(ARRAY['extract','derive','embed','summarise','summary_embed','autoTag','tagGroups'],s.stage),r.created_at,r.id"
                      + " LIMIT 1 FOR UPDATE OF s SKIP LOCKED",
                  revision == null ? new Object[] {now()} : new Object[] {now(), revision});
          if (rows.isEmpty()) return null;
          Map<String, Object> row = rows.getFirst();
          Lease lease =
              new Lease(
                  (UUID) row.get("revision_id"),
                  (UUID) row.get("resource_id"),
                  ((Number) row.get("generation")).longValue(),
                  (String) row.get("stage"),
                  ((Number) row.get("attempt")).intValue() + 1,
                  UUID.randomUUID(),
                  (String) row.get("owner_handle"),
                  (Long) row.get("project_id"));
          String expected = catalogue.fingerprint(lease.revision(), lease.stage());
          if (expected != null
              && row.get("fingerprint") != null
              && !expected.equals(row.get("fingerprint"))) {
            jdbc.update(
                "UPDATE information_steps SET state='failed',error='configuration changed; explicitly rebuild this projection' WHERE revision_id=? AND generation=? AND stage=?",
                lease.revision(),
                lease.generation(),
                lease.stage());
            return null;
          }
          jdbc.update(
              "UPDATE information_steps SET state='running',attempt=?,lease_token=?,lease_until=?,error=NULL,fingerprint=?,started_at=?,finished_at=NULL"
                  + " WHERE revision_id=? AND generation=? AND stage=?",
              lease.attempt(),
              lease.token(),
              expires(),
              expected,
              now(),
              lease.revision(),
              lease.generation(),
              lease.stage());
          catalogue.event(
              lease.revision(),
              lease.generation(),
              lease.owner(),
              lease.stage(),
              "started",
              Integer.toString(lease.attempt()));
          return lease;
        });
  }

  private boolean renew(Lease lease) {
    return jdbc.update(
            "UPDATE information_steps s SET lease_until=? FROM information_revisions r"
                + " WHERE r.id=s.revision_id AND r.availability='active' AND r.generation=s.generation AND s.revision_id=?"
                + " AND s.generation=? AND s.stage=? AND s.lease_token=? AND s.state='running' AND s.lease_until>=?",
            expires(),
            lease.revision(),
            lease.generation(),
            lease.stage(),
            lease.token(),
            now())
        == 1;
  }

  /**
   * Called inside the same transaction that writes a checkpoint; row lock excludes invalidation.
   */
  void requireLease(Lease lease) {
    if (lease.project() != null
        && jdbc.queryForObject(
                "SELECT count(*) FROM project_members WHERE project_id=? AND handle=?",
                Integer.class,
                lease.project(),
                lease.owner())
            == 0) throw new StaleLease();
    String selectedProject =
        lease.project() == null
            ? null
            : jdbc.queryForObject(
                "SELECT name FROM projects WHERE id=?", String.class, lease.project());
    if (jdbc.queryForObject(
            "SELECT count(*) FROM information_inputs WHERE derived_revision=? AND NOT information_readable(input_revision,?,?,?,true)",
            Integer.class,
            lease.revision(),
            lease.owner(),
            selectedProject == null ? "personal" : "project",
            selectedProject)
        != 0) throw new StaleLease();
    List<Map<String, Object>> valid =
        jdbc.queryForList(
            "SELECT r.id FROM information_revisions r JOIN information_steps s ON s.revision_id=r.id"
                + " WHERE r.id=? AND r.availability='active' AND r.generation=? AND s.generation=r.generation AND s.stage=?"
                + " AND s.lease_token=? AND s.state='running' AND s.lease_until>=? FOR UPDATE OF r,s",
            lease.revision(),
            lease.generation(),
            lease.stage(),
            lease.token(),
            now());
    if (valid.isEmpty()) throw new StaleLease();
  }

  private void finish(Lease lease, String state, String detail) {
    transactions.inTransaction(
        () -> {
          try {
            requireLease(lease);
          } catch (StaleLease gone) {
            return null;
          }
          jdbc.update(
              "UPDATE information_steps SET state=?,error=?,finished_at=?,lease_token=NULL,lease_until=NULL WHERE revision_id=? AND generation=? AND stage=?",
              state,
              detail,
              now(),
              lease.revision(),
              lease.generation(),
              lease.stage());
          catalogue.event(
              lease.revision(), lease.generation(), lease.owner(), lease.stage(), state, detail);
          return null;
        });
  }

  private OffsetDateTime now() {
    return clock.instant().atOffset(ZoneOffset.UTC);
  }

  private OffsetDateTime expires() {
    return clock.instant().plus(LEASE).atOffset(ZoneOffset.UTC);
  }

  @Override
  public synchronized void close() {
    if (worker != null) worker.shutdownNow();
  }

  static final class StaleLease extends RuntimeException {}

  static final class SkippedStage extends RuntimeException {
    SkippedStage(String reason) {
      super(reason);
    }
  }

  private static void requireTaggingEligible(JdbcTemplate jdbc, UUID revision) {
    Boolean eligible =
        jdbc.queryForObject(
            "SELECT r.auto_tag_requested OR r.auto_tag_generated OR (NOT r.excluded AND jsonb_array_length(q.tags)=0 AND jsonb_array_length(r.auto_tag)=0)"
                + " FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
            Boolean.class,
            revision);
    if (!Boolean.TRUE.equals(eligible))
      throw new SkippedStage(
          "Automatic tagging skipped: this document already has tags or is excluded from discovery.");
  }

  private static void requireGroupingEligible(JdbcTemplate jdbc, UUID revision) {
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT q.tag_groups_manual OR r.excluded FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
            Boolean.class,
            revision)))
      throw new SkippedStage(
          "Automatic grouping skipped: owner category overrides or discovery exclusion apply.");
  }

  private static boolean validVector(float[] vector, int width) {
    if (vector == null || vector.length != width) return false;
    boolean nonzero = false;
    for (float value : vector) {
      if (!Float.isFinite(value)) return false;
      if (value != 0) nonzero = true;
    }
    return nonzero;
  }

  /** Reuses Anchor's derivation and cascade, with separate, observable readiness for each stage. */
  public static Processor processing(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      InformationCatalogue catalogue,
      DocumentStore store,
      EmbeddingClient embeddings,
      Chunking chunking,
      int batch,
      int expectedDim,
      java.util.function.Supplier<Summariser> summariser,
      DocumentsProperties properties) {
    return processing(
        jdbc,
        transactions,
        catalogue,
        store,
        embeddings,
        chunking,
        batch,
        expectedDim,
        summariser,
        properties,
        null);
  }

  public static Processor processing(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      InformationCatalogue catalogue,
      DocumentStore store,
      EmbeddingClient embeddings,
      Chunking chunking,
      int batch,
      int expectedDim,
      java.util.function.Supplier<Summariser> summariser,
      DocumentsProperties properties,
      InformationModelStages stages) {
    return processing(
        jdbc,
        transactions,
        catalogue,
        store,
        embeddings,
        chunking,
        batch,
        expectedDim,
        summariser,
        properties,
        stages,
        UsageOwners.NONE);
  }

  public static Processor processing(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      InformationCatalogue catalogue,
      DocumentStore store,
      EmbeddingClient embeddings,
      Chunking chunking,
      int batch,
      int expectedDim,
      java.util.function.Supplier<Summariser> summariser,
      DocumentsProperties properties,
      InformationModelStages stages,
      UsageOwners owners) {
    return (lease, cancelled, fence) -> {
      Map<String, Object> row = catalogue.row(lease.revision());
      DocumentStore writer = store.fenced(fence);

      switch (lease.stage()) {
        case "extract" -> {
          if (row.get("extracted_text") != null) return;
          byte[] bytes = (byte[]) row.get("source_bytes");
          if (bytes == null)
            throw new IllegalStateException("no retained source bytes; supply a new revision");
          Extracted extracted = extract(row, bytes);
          transactions.inTransaction(
              () -> {
                fence.run();
                jdbc.update(
                    "UPDATE information_revisions SET extracted_text=?,text_hash=?,title=?,converter=?,outline_top_level=CAST(? AS jsonb) WHERE id=?",
                    extracted.text(),
                    InformationCatalogue.sha256(
                        extracted.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    extracted.title(),
                    extracted.converter(),
                    json(extracted.outlineTopLevel()),
                    lease.revision());
                return null;
              });
        }
        case "derive" -> {
          if (store.find(lease.revision()).isPresent()) return;
          // Extraction's outline/converter provenance must survive into the derivation.
          Extracted extracted =
              new Extracted(
                  (String) row.get("title"),
                  (String) row.get("content_hash"),
                  (String) row.get("extracted_text"),
                  outline(row.get("outline_top_level")),
                  (String) row.get("converter"));
          boolean code = "code".equals(row.get("document_type"));
          DerivedDocument derived =
              code
                  ? CodeDerivation.derive(extracted, chunking)
                  : Derivation.derive(extracted, chunking);
          CodeOutline codeOutline =
              code
                  ? CodeOutline.parse(
                      extracted.text(),
                      (String) row.get("document_subtype"),
                      (String) row.get("source_name"))
                  : null;
          if (derived.paragraphs().isEmpty() && !(code && extracted.text().isBlank()))
            throw new IllegalStateException("extraction derived no paragraphs");
          transactions.inTransaction(
              () -> {
                fence.run();
                writer.writeRevision(
                    lease.revision(),
                    (String) row.get("source_name"),
                    extracted,
                    ((Number) row.get("byte_size")).longValue(),
                    lease.owner(),
                    java.time.Instant.now(),
                    derived);
                if (code) {
                  writer.locateCodePassages(lease.revision(), extracted.text());
                  writer.writeCodeOutline(lease.revision(), codeOutline);
                }
                jdbc.update(
                    "INSERT INTO information_document_policies(document_id,owner_handle,visibility,project_id,assigned_at)"
                        + " VALUES(?,?,?,?,now())",
                    lease.revision(),
                    lease.owner(),
                    lease.project() == null ? "personal" : "project",
                    lease.project());
                return null;
              });
        }
        case "embed" -> {
          List<DocumentStore.UnembeddedChunk> waiting = store.unembedded(lease.revision());
          for (int from = 0; from < waiting.size(); from += batch) {
            if (cancelled.getAsBoolean()) throw new StaleLease();
            List<DocumentStore.UnembeddedChunk> part =
                waiting.subList(from, Math.min(from + batch, waiting.size()));
            List<float[]> vectors =
                EmbeddingClient.owned(
                    embeddings,
                    part.stream().map(DocumentStore.UnembeddedChunk::text).toList(),
                    processingOwner(owners, row));
            if (vectors.size() != part.size()
                || vectors.stream().anyMatch(v -> !validVector(v, expectedDim)))
              throw new IllegalStateException(
                  "embedding batch shape differs from the configured corpus space");
            for (int i = 0; i < part.size(); i++) writer.attach(part.get(i).id(), vectors.get(i));
          }
        }
        case "summarise" -> {
          Summariser cascade = summariser.get();
          if (cascade == null)
            throw new IllegalStateException("no summariser cascade is configured");
          String project =
              lease.project() == null
                  ? null
                  : jdbc.queryForObject(
                      "SELECT name FROM projects WHERE id=?", String.class, lease.project());
          Home home = project == null ? Home.global() : Home.of(project);
          int remaining =
              ((Number) row.get("allowance_total")).intValue()
                  - ((Number) row.get("allowance_spent")).intValue();
          if (remaining < 1)
            throw new IllegalStateException(
                "retained processing allowance exhausted; increase it explicitly before retrying");
          Budget budget =
              Budget.of(
                  remaining,
                  () ->
                      transactions.inTransaction(
                          () -> {
                            fence.run();
                            if (jdbc.update(
                                    "UPDATE information_revisions SET allowance_spent=allowance_spent+1 WHERE id=? AND allowance_spent<allowance_total",
                                    lease.revision())
                                != 1)
                              throw new IllegalStateException("processing allowance exhausted");
                            jdbc.update(
                                "UPDATE conversations c SET budget_total=r.allowance_total,budget_spent=r.allowance_spent"
                                    + " FROM information_revisions r WHERE r.id=? AND c.id=r.processing_log",
                                lease.revision());
                            return null;
                          }));
          Summariser pass =
              cascade.forRevision(writer, home, lease.owner(), (String) row.get("processing_log"));
          if (stages != null) pass.withStages(stages.forLease(lease, fence));
          Outcome outcome =
              pass.summarise(lease.revision(), (String) row.get("title"), budget, cancelled);
          if (outcome.detail().equals("document.stage.denied"))
            throw new io.aeyer.plowshare.server.documents.DocumentStages.Blocked(outcome.text());
          if (outcome.ending() != Outcome.Ending.ANSWERED
              || store.documentSummary(lease.revision()) == null
              || store.unsummarisedUnits(lease.revision()) != 0)
            throw new IllegalStateException(outcome.ending() + ": " + outcome.text());
        }
        case "autoTag" -> {
          if (Boolean.TRUE.equals(row.get("auto_tag_generated"))) return;
          requireTaggingEligible(jdbc, lease.revision());
          Summariser tagger = summariser.get();
          if (tagger == null)
            throw new IllegalStateException("no information tagger runtime is configured");
          String project =
              lease.project() == null
                  ? null
                  : jdbc.queryForObject(
                      "SELECT name FROM projects WHERE id=?", String.class, lease.project());
          Home home = project == null ? Home.global() : Home.of(project);
          int remaining =
              ((Number) row.get("allowance_total")).intValue()
                  - ((Number) row.get("allowance_spent")).intValue();
          // A cached response can finish after a post gate denial even with zero allowance left.
          Budget budget =
              Budget.of(
                  Math.max(1, remaining),
                  () ->
                      transactions.inTransaction(
                          () -> {
                            fence.run();
                            requireTaggingEligible(jdbc, lease.revision());
                            if (jdbc.update(
                                    "UPDATE information_revisions SET allowance_spent=allowance_spent+1 WHERE id=? AND allowance_spent<allowance_total",
                                    lease.revision())
                                != 1)
                              throw new IllegalStateException(
                                  "processing allowance exhausted; increase it explicitly before retrying");
                            jdbc.update(
                                "UPDATE conversations c SET budget_total=r.allowance_total,budget_spent=r.allowance_spent FROM information_revisions r WHERE r.id=? AND c.id=r.processing_log",
                                lease.revision());
                            return null;
                          }));
          var pass =
              tagger.forRevision(writer, home, lease.owner(), (String) row.get("processing_log"));
          if (stages != null) pass.withStages(stages.forLease(lease, fence));
          String source = (String) row.get("extracted_text");
          String content = source;
          if (content == null)
            throw new IllegalStateException("retained extraction is unavailable for tagging");
          content = content.substring(0, Math.min(content.length(), 12000));
          var metadata =
              pass.autoTag(
                  "Source: "
                      + row.get("source_name")
                      + "\nType: "
                      + row.get("document_type")
                      + "/"
                      + row.get("document_subtype")
                      + "\nRetained content (bounded excerpt):\n"
                      + content,
                  source,
                  budget,
                  cancelled);
          transactions.inTransaction(
              () -> {
                fence.run();
                jdbc.update(
                    "UPDATE information_revisions SET auto_tag=CAST(? AS jsonb),auto_tag_generated=true,auto_tag_requested=false,document_author=?,document_author_source=?,document_author_evidence=? WHERE id=?",
                    InformationFacets.json(metadata.tags()),
                    metadata.author(),
                    metadata.authorSource(),
                    metadata.authorEvidence(),
                    lease.revision());
                if (metadata.groups() != null)
                  jdbc.update(
                      "UPDATE information_revisions r SET auto_tag_groups=CAST(? AS jsonb),tag_groups_input_tags=information_visible_tags('[]'::jsonb,r.auto_tag),tag_groups_generated=(jsonb_array_length(q.tags)=0) FROM information_resources q WHERE q.id=r.resource_id AND r.id=?",
                      InformationFacets.json(metadata.groups()),
                      lease.revision());
                return null;
              });
        }
        case "tagGroups" -> {
          requireGroupingEligible(jdbc, lease.revision());
          String tagJson =
              jdbc.queryForObject(
                  "SELECT "
                      + InformationTagGroups.tags("r", "q")
                      + " FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
                  String.class,
                  lease.revision());
          List<String> tags;
          try {
            tags =
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(
                        tagJson,
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
          } catch (java.io.IOException invalid) {
            throw new IllegalStateException("invalid existing document tags", invalid);
          }
          if (Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT tag_groups_generated AND tag_groups_input_tags=CAST(? AS jsonb) FROM information_revisions WHERE id=?",
                  Boolean.class,
                  tagJson,
                  lease.revision()))) return;
          if (tags.isEmpty()) {
            transactions.inTransaction(
                () -> {
                  fence.run();
                  jdbc.update(
                      "UPDATE information_revisions SET auto_tag_groups='{}'::jsonb,tag_groups_input_tags='[]'::jsonb,tag_groups_generated=true WHERE id=?",
                      lease.revision());
                  return null;
                });
            return;
          }
          Summariser grouper = summariser.get();
          if (grouper == null)
            throw new IllegalStateException("no information tag grouping runtime is configured");
          String project =
              lease.project() == null
                  ? null
                  : jdbc.queryForObject(
                      "SELECT name FROM projects WHERE id=?", String.class, lease.project());
          Home home = project == null ? Home.global() : Home.of(project);
          int remaining =
              ((Number) row.get("allowance_total")).intValue()
                  - ((Number) row.get("allowance_spent")).intValue();
          Budget budget =
              Budget.of(
                  Math.max(1, remaining),
                  () ->
                      transactions.inTransaction(
                          () -> {
                            fence.run();
                            requireGroupingEligible(jdbc, lease.revision());
                            if (jdbc.update(
                                    "UPDATE information_revisions SET allowance_spent=allowance_spent+1 WHERE id=? AND allowance_spent<allowance_total",
                                    lease.revision())
                                != 1)
                              throw new IllegalStateException(
                                  "processing allowance exhausted; increase it explicitly before retrying");
                            jdbc.update(
                                "UPDATE conversations c SET budget_total=r.allowance_total,budget_spent=r.allowance_spent FROM information_revisions r WHERE r.id=? AND c.id=r.processing_log",
                                lease.revision());
                            return null;
                          }));
          var pass =
              grouper.forRevision(writer, home, lease.owner(), (String) row.get("processing_log"));
          if (stages != null) pass.withStages(stages.forLease(lease, fence));
          var groups =
              pass.tagGroups(
                  "Existing document tags (untrusted data):\n" + InformationFacets.json(tags),
                  tags,
                  budget,
                  cancelled);
          transactions.inTransaction(
              () -> {
                fence.run();
                jdbc.update(
                    "UPDATE information_revisions SET auto_tag_groups=CAST(? AS jsonb),tag_groups_input_tags=CAST(? AS jsonb),tag_groups_generated=true WHERE id=?",
                    InformationFacets.json(groups),
                    tagJson,
                    lease.revision());
                return null;
              });
        }
        case "summary_embed" -> {
          String summary = store.documentSummary(lease.revision());
          if (summary == null || summary.isBlank())
            throw new IllegalStateException("document summary is unavailable");
          boolean waiting =
              store.summariesAwaitingAVector().stream()
                  .anyMatch(s -> s.documentId().equals(lease.revision()));
          if (!waiting) return;
          if (cancelled.getAsBoolean()) throw new StaleLease();
          float[] vector = EmbeddingClient.owned(embeddings, summary, processingOwner(owners, row));
          if (!io.aeyer.plowshare.server.documents.SummaryEmbeddings.valid(vector, expectedDim))
            throw new IllegalStateException(
                "summary vector is invalid for the configured corpus space");
          writer.attachSummaryEmbedding(lease.revision(), vector);
        }
        default -> throw new IllegalStateException("unknown document stage");
      }
    };
  }

  private static UsageAttribution processingOwner(
      UsageOwners owners, Map<String, Object> revision) {
    if (owners == UsageOwners.NONE) return UsageAttribution.LEGACY;
    String log =
        java.util.Objects.requireNonNull(
            (String) revision.get("processing_log"), "processing usage requires a durable log");
    return owners.conversation(log, 0, UsageAttribution.Operation.EMBEDDING_WRITE);
  }

  private static String json(Object value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static List<String> outline(Object value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(
              value.toString(),
              new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  static Extracted extract(Map<String, Object> row, byte[] bytes) {
    if ("code".equals(row.get("document_type"))) {
      try {
        String text =
            java.nio.charset.StandardCharsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("code contains NUL");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return new Extracted(
            (String) row.get("source_name"),
            (String) row.get("content_hash"),
            text.replace("\r\n", "\n").replace('\r', '\n'),
            List.of(),
            Extracted.CODE_UTF8);
      } catch (java.nio.charset.CharacterCodingException invalid) {
        throw new IllegalArgumentException("code is not UTF-8", invalid);
      }
    }
    String type = (String) row.get("media_type");
    if (type.startsWith("text/html") || type.startsWith("application/xhtml+xml")) {
      java.nio.charset.Charset charset = java.nio.charset.StandardCharsets.UTF_8;
      okhttp3.MediaType media = okhttp3.MediaType.parse(type);
      if (media != null) charset = media.charset(charset);
      var page =
          io.aeyer.plowshare.server.fetch.PageExtractor.extract(
              new String(bytes, charset), (String) row.get("source_uri"));
      String title = page.title().isBlank() ? (String) row.get("source_name") : page.title();
      return new Extracted(
          title,
          (String) row.get("content_hash"),
          page.text(),
          List.of(),
          "jsoup-readable-html-v1");
    }
    Extracted extracted = TextExtraction.extract((String) row.get("source_name"), bytes);
    // Report headings are presentation, while source_name remains the collision-safe resource key.
    if ("report".equals(row.get("kind")) && type.startsWith("text/markdown")) {
      String first = extracted.text().lines().findFirst().orElse("").strip();
      if (first.startsWith("# ")) {
        String title = first.substring(2).strip();
        if (!title.isEmpty())
          return new Extracted(
              title.substring(0, Math.min(title.length(), 512)),
              extracted.contentHash(),
              extracted.text(),
              extracted.outlineTopLevel(),
              extracted.converter());
      }
    }
    return extracted;
  }
}
