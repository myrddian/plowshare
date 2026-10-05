package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Database leases are the work queue. Expired running work resumes from committed checkpoints. */
public final class InformationLifecycle implements AutoCloseable {
  private final InformationProcessingRepository repository;
  private final UnitOfWork transactions;
  private final InformationCatalogue catalogue;
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
      InformationProcessingRepository repository,
      UnitOfWork transactions,
      InformationCatalogue catalogue,
      Processor processor,
      Gates gates) {
    this.repository = java.util.Objects.requireNonNull(repository);
    this.transactions = transactions;
    this.catalogue = catalogue;
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
          var queued = repository.sweepUntagged();
          for (var item : queued)
            catalogue.event(
                item.revision(),
                item.generation(),
                item.owner(),
                "autoTag",
                "sweep.queued",
                "No user or automatic tags; retained text is ready.");
          return queued.size();
        });
  }

  /** Existing tagged documents can acquire categories without re-reading/replacing their tags. */
  public int sweepTagGroups() {
    return transactions.inTransaction(
        () -> {
          var queued = repository.sweepTagGroups();
          for (var item : queued)
            catalogue.event(
                item.revision(),
                item.generation(),
                item.owner(),
                "tagGroups",
                "sweep.queued",
                "Existing tags need category membership.");
          return queued.size();
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
      if (lease.stage().equals("autoTag")) requireTaggingEligible(repository, lease.revision());
      if (lease.stage().equals("tagGroups")) requireGroupingEligible(repository, lease.revision());
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
          var selected = repository.candidate(revision, syntaxOnly);
          if (selected.isEmpty()) return null;
          var candidate = selected.get();
          Lease lease = candidate.lease();
          String expected = catalogue.fingerprint(lease.revision(), lease.stage());
          if (expected != null
              && candidate.fingerprint() != null
              && !expected.equals(candidate.fingerprint())) {
            repository.configurationChanged(lease);
            return null;
          }
          repository.start(lease, expected);
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
    return repository.renew(lease);
  }

  /**
   * Called inside the same transaction that writes a checkpoint; row lock excludes invalidation.
   */
  void requireLease(Lease lease) {
    repository.requireLease(lease);
  }

  private void finish(Lease lease, String state, String detail) {
    transactions.inTransaction(
        () -> {
          try {
            requireLease(lease);
          } catch (StaleLease gone) {
            return null;
          }
          repository.finish(lease, state, detail);
          catalogue.event(
              lease.revision(), lease.generation(), lease.owner(), lease.stage(), state, detail);
          return null;
        });
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

  private static void requireTaggingEligible(
      InformationProcessingRepository repository, UUID revision) {
    if (!repository.taggingEligible(revision))
      throw new SkippedStage(
          "Automatic tagging skipped: this document already has tags or is excluded from discovery.");
  }

  private static void requireGroupingEligible(
      InformationProcessingRepository repository, UUID revision) {
    if (!repository.groupingEligible(revision))
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
      InformationProcessingRepository repository,
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
        repository,
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
      InformationProcessingRepository repository,
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
        repository,
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
      InformationProcessingRepository repository,
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
    return processing(
        repository,
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
        owners,
        null);
  }

  public static Processor processing(
      InformationProcessingRepository repository,
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
      UsageOwners owners,
      DualEmbeddings dual) {
    return (lease, cancelled, fence) -> {
      var row = repository.readRevision(lease.revision());
      DocumentStore writer = store.fenced(fence);

      switch (lease.stage()) {
        case "extract" -> {
          if (row.extractedText() != null) return;
          byte[] bytes = row.sourceBytes();
          if (bytes == null)
            throw new IllegalStateException("no retained source bytes; supply a new revision");
          Extracted extracted = extract(row, bytes);
          transactions.inTransaction(
              () -> {
                fence.run();
                repository.extracted(lease.revision(), extracted);
                return null;
              });
        }
        case "derive" -> {
          if (store.find(lease.revision()).isPresent()) return;
          // Extraction's outline/converter provenance must survive into the derivation.
          Extracted extracted =
              new Extracted(
                  row.title(),
                  row.contentHash(),
                  row.extractedText(),
                  row.outline(),
                  row.converter());
          boolean code = "code".equals(row.documentType());
          DerivedDocument derived =
              code
                  ? CodeDerivation.derive(extracted, chunking)
                  : Derivation.derive(extracted, chunking);
          CodeOutline codeOutline =
              code
                  ? CodeOutline.parse(extracted.text(), row.documentSubtype(), row.sourceName())
                  : null;
          if (derived.paragraphs().isEmpty() && !(code && extracted.text().isBlank()))
            throw new IllegalStateException("extraction derived no paragraphs");
          transactions.inTransaction(
              () -> {
                fence.run();
                writer.writeRevision(
                    lease.revision(),
                    row.sourceName(),
                    extracted,
                    row.byteSize(),
                    lease.owner(),
                    java.time.Instant.now(),
                    derived);
                if (code) {
                  writer.locateCodePassages(lease.revision(), extracted.text());
                  writer.writeCodeOutline(lease.revision(), codeOutline);
                }
                repository.documentPolicy(lease);
                return null;
              });
        }
        case "embed" -> {
          List<DocumentStore.UnembeddedChunk> waiting = store.unembedded(lease.revision());
          for (int from = 0; from < waiting.size(); from += batch) {
            if (cancelled.getAsBoolean()) throw new StaleLease();
            List<DocumentStore.UnembeddedChunk> part =
                waiting.subList(from, Math.min(from + batch, waiting.size()));
            if (dual != null) {
              var result =
                  dual.repairAll(
                      part.stream()
                          .map(
                              chunk ->
                                  EmbeddingWorkRepository.Key.of(
                                      EmbeddingWorkRepository.Store.CHUNKS, chunk.id().toString()))
                          .toList(),
                      processingOwner(owners, row));
              if (result.completeSources() != part.size()) throw new StaleLease();
              continue;
            }
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
          String project = repository.projectName(lease.project());
          Home home = project == null ? Home.global() : Home.of(project);
          int remaining = row.allowance() - row.spent();
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
                            repository.spend(lease.revision());
                            return null;
                          }));
          Summariser pass = cascade.forRevision(writer, home, lease.owner(), row.processingLog());
          if (stages != null) pass.withStages(stages.forLease(lease, fence));
          Outcome outcome = pass.summarise(lease.revision(), row.title(), budget, cancelled);
          if (outcome.detail().equals("document.stage.denied"))
            throw new io.aeyer.plowshare.server.documents.DocumentStages.Blocked(outcome.text());
          if (outcome.ending() != Outcome.Ending.ANSWERED
              || store.documentSummary(lease.revision()) == null
              || store.unsummarisedUnits(lease.revision()) != 0)
            throw new IllegalStateException(outcome.ending() + ": " + outcome.text());
        }
        case "autoTag" -> {
          if (Boolean.TRUE.equals(row.autoTagGenerated())) return;
          requireTaggingEligible(repository, lease.revision());
          Summariser tagger = summariser.get();
          if (tagger == null)
            throw new IllegalStateException("no information tagger runtime is configured");
          String project = repository.projectName(lease.project());
          Home home = project == null ? Home.global() : Home.of(project);
          int remaining = row.allowance() - row.spent();
          // A cached response can finish after a post gate denial even with zero allowance left.
          Budget budget =
              Budget.of(
                  Math.max(1, remaining),
                  () ->
                      transactions.inTransaction(
                          () -> {
                            fence.run();
                            requireTaggingEligible(repository, lease.revision());
                            repository.spend(lease.revision());
                            return null;
                          }));
          var pass = tagger.forRevision(writer, home, lease.owner(), row.processingLog());
          if (stages != null) pass.withStages(stages.forLease(lease, fence));
          String source = row.extractedText();
          String content = source;
          if (content == null)
            throw new IllegalStateException("retained extraction is unavailable for tagging");
          content = content.substring(0, Math.min(content.length(), 12000));
          var metadata =
              pass.autoTag(
                  "Source: "
                      + row.sourceName()
                      + "\nType: "
                      + row.documentType()
                      + "/"
                      + row.documentSubtype()
                      + "\nRetained content (bounded excerpt):\n"
                      + content,
                  source,
                  budget,
                  cancelled);
          transactions.inTransaction(
              () -> {
                fence.run();
                repository.autoTags(lease.revision(), metadata);
                return null;
              });
        }
        case "tagGroups" -> {
          requireGroupingEligible(repository, lease.revision());
          var grouping = repository.grouping(lease.revision());
          List<String> tags = grouping.tags();
          if (grouping.generated()) return;
          if (tags.isEmpty()) {
            transactions.inTransaction(
                () -> {
                  fence.run();
                  repository.groups(lease.revision(), Map.of(), List.of());
                  return null;
                });
            return;
          }
          Summariser grouper = summariser.get();
          if (grouper == null)
            throw new IllegalStateException("no information tag grouping runtime is configured");
          String project = repository.projectName(lease.project());
          Home home = project == null ? Home.global() : Home.of(project);
          int remaining = row.allowance() - row.spent();
          Budget budget =
              Budget.of(
                  Math.max(1, remaining),
                  () ->
                      transactions.inTransaction(
                          () -> {
                            fence.run();
                            requireGroupingEligible(repository, lease.revision());
                            repository.spend(lease.revision());
                            return null;
                          }));
          var pass = grouper.forRevision(writer, home, lease.owner(), row.processingLog());
          if (stages != null) pass.withStages(stages.forLease(lease, fence));
          var groups =
              pass.tagGroups(
                  "Existing document tags (untrusted data):\n"
                      + io.aeyer.plowshare.server.information.InformationJson.json(tags),
                  tags,
                  budget,
                  cancelled);
          transactions.inTransaction(
              () -> {
                fence.run();
                repository.groups(lease.revision(), groups, tags);
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
          if (dual != null) {
            if (!dual.repair(
                EmbeddingWorkRepository.Key.of(
                    EmbeddingWorkRepository.Store.DOCUMENTS, lease.revision().toString()),
                processingOwner(owners, row))) throw new StaleLease();
            return;
          }
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
      UsageOwners owners, InformationProcessingRepository.Revision revision) {
    if (owners == UsageOwners.NONE) return UsageAttribution.LEGACY;
    String log =
        java.util.Objects.requireNonNull(
            revision.processingLog(), "processing usage requires a durable log");
    return owners.processing(log, UsageAttribution.Operation.EMBEDDING_WRITE);
  }

  private static String json(Object value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  static Extracted extract(InformationProcessingRepository.Revision row, byte[] bytes) {
    if ("code".equals(row.documentType())) {
      try {
        String text =
            java.nio.charset.StandardCharsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("code contains NUL");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return new Extracted(
            row.sourceName(),
            row.contentHash(),
            text.replace("\r\n", "\n").replace('\r', '\n'),
            List.of(),
            Extracted.CODE_UTF8);
      } catch (java.nio.charset.CharacterCodingException invalid) {
        throw new IllegalArgumentException("code is not UTF-8", invalid);
      }
    }
    String type = row.mediaType();
    if (type.startsWith("text/html") || type.startsWith("application/xhtml+xml")) {
      java.nio.charset.Charset charset = java.nio.charset.StandardCharsets.UTF_8;
      okhttp3.MediaType media = okhttp3.MediaType.parse(type);
      if (media != null) charset = media.charset(charset);
      var page =
          io.aeyer.plowshare.server.fetch.PageExtractor.extract(
              new String(bytes, charset), row.sourceUri());
      String title = page.title().isBlank() ? row.sourceName() : page.title();
      return new Extracted(
          title, row.contentHash(), page.text(), List.of(), "jsoup-readable-html-v1");
    }
    Extracted extracted = TextExtraction.extract(row.sourceName(), bytes);
    // Report headings are presentation, while source_name remains the collision-safe resource key.
    if ("report".equals(row.kind()) && type.startsWith("text/markdown")) {
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
