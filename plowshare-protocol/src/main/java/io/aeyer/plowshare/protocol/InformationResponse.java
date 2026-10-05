package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.*;

/** Complete result alternatives for information operations; no arbitrary JSON result is exposed. */
public sealed interface InformationResponse {
  record Revision(@JsonValue Information.Revision value) implements InformationResponse {
    public Revision {
      Objects.requireNonNull(value, "value");
    }
  }

  record Revisions(@JsonValue List<Information.Revision> value) implements InformationResponse {
    public Revisions {
      value = ContractValues.list(value, "results", 100);
    }
  }

  record Acquisition(@JsonValue InformationAcquisition value) implements InformationResponse {
    public Acquisition {
      Objects.requireNonNull(value, "value");
    }
  }

  record Acquisitions(@JsonValue List<InformationAcquisition> value)
      implements InformationResponse {
    public Acquisitions {
      value = ContractValues.list(value, "results", 100);
    }
  }

  record Facets(@JsonValue Information.Facets value) implements InformationResponse {
    public Facets {
      Objects.requireNonNull(value, "value");
    }
  }

  record Readiness(@JsonValue Information.Readiness value) implements InformationResponse {
    public Readiness {
      Objects.requireNonNull(value, "value");
    }
  }

  record Window(@JsonValue Information.Window value) implements InformationResponse {
    public Window {
      Objects.requireNonNull(value, "value");
    }
  }

  record Outline(@JsonValue Information.Outline value) implements InformationResponse {
    public Outline {
      Objects.requireNonNull(value, "value");
    }
  }

  record Symbols(@JsonValue Information.Symbols value) implements InformationResponse {
    public Symbols {
      Objects.requireNonNull(value, "value");
    }
  }

  record Evidence(@JsonValue Information.Evidence value) implements InformationResponse {
    public Evidence {
      Objects.requireNonNull(value, "value");
    }
  }

  record Events(@JsonValue Information.Events value) implements InformationResponse {
    public Events {
      Objects.requireNonNull(value, "value");
    }
  }

  record MigrationInventory(@JsonValue InformationMigration.Inventory value)
      implements InformationResponse {
    public MigrationInventory {
      Objects.requireNonNull(value, "value");
    }
  }

  record MigrationInspection(@JsonValue InformationMigration.Inspection value)
      implements InformationResponse {
    public MigrationInspection {
      Objects.requireNonNull(value, "value");
    }
  }

  record SearchResult(@JsonValue List<Retrieved> value) implements InformationResponse {
    public SearchResult {
      value = ContractValues.list(value, "hits", 100);
    }
  }

  record RankResult(@JsonValue Ranking value) implements InformationResponse {
    public RankResult {
      Objects.requireNonNull(value, "value");
    }
  }

  record Admission(UUID revision, UUID resource, boolean created) implements InformationResponse {
    public Admission {
      Objects.requireNonNull(revision);
      Objects.requireNonNull(resource);
    }
  }

  record EvidenceRecorded(UUID evidence) implements InformationResponse {
    public EvidenceRecorded {
      Objects.requireNonNull(evidence);
    }
  }

  record Asked(String job, UUID revision) implements InformationResponse {
    public Asked {
      job = ContractValues.identity(job, "job", 1024);
      Objects.requireNonNull(revision);
    }
  }

  record Changed(boolean changed) implements InformationResponse {
    public Changed {
      if (!changed) throw new IllegalArgumentException("mutation was not acknowledged");
    }
  }

  record Queued(boolean queued) implements InformationResponse {
    public Queued {
      if (!queued) throw new IllegalArgumentException("queue was not acknowledged");
    }
  }

  record Finalised(boolean finalised) implements InformationResponse {
    public Finalised {
      if (!finalised) throw new IllegalArgumentException("finalisation was not acknowledged");
    }
  }

  record Released(boolean released) implements InformationResponse {
    public Released {
      if (!released) throw new IllegalArgumentException("release was not acknowledged");
    }
  }

  record Adopted(boolean adopted) implements InformationResponse {
    public Adopted {
      if (!adopted) throw new IllegalArgumentException("adoption was not acknowledged");
    }
  }

  record Availability(String availability) implements InformationResponse {
    public Availability {
      if (!Set.of("active", "withdrawn", "excluded", "included", "deleted").contains(availability))
        throw new IllegalArgumentException("invalid availability");
    }
  }

  /** A source title or an explicitly synthetic structural unit; invented titles stay absent. */
  record StructuralTitle(String title, Boolean synthetic) {
    public StructuralTitle {
      Objects.requireNonNull(synthetic, "synthetic");
      if (synthetic) {
        if (title != null) throw new IllegalArgumentException("synthetic title must be absent");
      } else {
        title = ContractValues.text(title, "title", 32768, true);
      }
    }
  }

  /** Empty means unplaced; any structural reference requires the complete section/chapter pair. */
  record Placement(
      UUID sectionId,
      StructuralTitle sectionTitle,
      String sectionSummary,
      UUID chapterId,
      StructuralTitle chapterTitle,
      String chapterSummary) {
    public Placement {
      if (sectionId != null
          || sectionTitle != null
          || sectionSummary != null
          || chapterId != null
          || chapterTitle != null
          || chapterSummary != null) {
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(sectionTitle, "sectionTitle");
        Objects.requireNonNull(chapterId, "chapterId");
        Objects.requireNonNull(chapterTitle, "chapterTitle");
      }
      sectionSummary = ContractValues.text(sectionSummary, "sectionSummary", 1048576, false);
      chapterSummary = ContractValues.text(chapterSummary, "chapterSummary", 1048576, false);
    }
  }

  /** The scoped search wire result retains the passage's full document ancestry. */
  record SearchChunk(
      UUID chunkId,
      String chunkText,
      UUID paragraphId,
      int paragraphOrdinal,
      String paragraphSummary,
      Placement placement,
      UUID documentId,
      String sourceName,
      String documentTitle,
      String documentSummary) {
    public SearchChunk {
      Objects.requireNonNull(chunkId, "chunkId");
      Objects.requireNonNull(paragraphId, "paragraphId");
      Objects.requireNonNull(documentId, "documentId");
      Objects.requireNonNull(placement, "placement");
      if (paragraphOrdinal < 0) throw new IllegalArgumentException("invalid paragraph ordinal");
      chunkText = ContractValues.text(chunkText, "chunkText", 1048576, true);
      paragraphSummary = ContractValues.text(paragraphSummary, "paragraphSummary", 1048576, false);
      sourceName = ContractValues.text(sourceName, "sourceName", 1024, true);
      documentTitle = ContractValues.text(documentTitle, "documentTitle", 32768, false);
      documentSummary = ContractValues.text(documentSummary, "documentSummary", 1048576, false);
    }
  }

  record Retrieved(SearchChunk chunk, double distance) {
    public Retrieved {
      Objects.requireNonNull(chunk, "chunk");
      if (!Double.isFinite(distance)) throw new IllegalArgumentException("invalid distance");
    }
  }

  record Document(
      UUID id,
      String sourceName,
      String title,
      String contentHash,
      String textHash,
      long byteSize,
      Instant ingestedAt,
      String ingestedBy,
      String summary,
      String vocabulary,
      String documentType,
      String documentSubtype) {
    public Document {
      Objects.requireNonNull(id);
      Objects.requireNonNull(ingestedAt);
      sourceName = ContractValues.text(sourceName, "sourceName", 1024, true);
      title = ContractValues.text(title, "title", 32768, false);
      contentHash = ContractValues.optionalIdentity(contentHash, "contentHash", 128);
      textHash = ContractValues.optionalIdentity(textHash, "textHash", 128);
      ingestedBy = ContractValues.optionalIdentity(ingestedBy, "ingestedBy", 256);
      summary = ContractValues.text(summary, "summary", 1048576, false);
      if (byteSize < 0
          || vocabulary != null && !Set.of("CHAPTER", "SECTION", "PART").contains(vocabulary))
        throw new IllegalArgumentException("invalid document metadata");
      documentType = ContractValues.optionalIdentity(documentType, "documentType", 128);
      documentSubtype = ContractValues.optionalIdentity(documentSubtype, "documentSubtype", 128);
    }
  }

  record Ranked(Document document, double distance) {
    public Ranked {
      Objects.requireNonNull(document);
      if (!Double.isFinite(distance))
        throw new IllegalArgumentException("invalid document distance");
    }
  }

  record Rankable(int rankable, int unranked) {
    public Rankable {
      if (rankable < 0 || unranked < 0)
        throw new IllegalArgumentException("negative rank coverage");
    }
  }

  record Ranking(List<Ranked> documents, Rankable corpus) {
    public Ranking {
      documents = ContractValues.list(documents, "documents", 100);
      Objects.requireNonNull(corpus);
    }
  }
}
