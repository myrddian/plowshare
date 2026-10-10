package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.documents.Extracted;
import io.aeyer.plowshare.server.information.InformationLifecycle.Lease;
import java.util.*;

/** Durable queue leases and processing checkpoints; no model, network, or hook is called here. */
public interface InformationProcessingRepository {
  record Queued(UUID revision, long generation, String owner) {}

  record Candidate(Lease lease, String fingerprint) {}

  record Grouping(List<String> tags, boolean generated) {
    public Grouping {
      tags = List.copyOf(tags);
    }
  }

  record Revision(
      String sourceName,
      String sourceUri,
      String mediaType,
      String documentType,
      String documentSubtype,
      String kind,
      String contentHash,
      byte[] sourceBytes,
      String extractedText,
      String title,
      String converter,
      List<String> outline,
      long byteSize,
      int allowance,
      int spent,
      String processingLog,
      boolean autoTagGenerated) {
    public Revision {
      outline = List.copyOf(outline);
      if (sourceBytes != null) sourceBytes = sourceBytes.clone();
      if (byteSize < 0 || allowance < 0 || spent < 0)
        throw new IllegalArgumentException("negative revision counts");
    }

    @Override
    public byte[] sourceBytes() {
      return sourceBytes == null ? null : sourceBytes.clone();
    }
  }

  List<Queued> sweepUntagged();

  List<Queued> sweepTagGroups();

  /**
   * Locks one eligible, currently authorized stage with SKIP LOCKED. Call start before the
   * transaction ends.
   */
  Optional<Candidate> candidate(UUID revision, boolean syntaxOnly);

  void configurationChanged(Lease lease);

  void start(Lease lease, String expected);

  /** Extend only a current lease whose owner and inputs remain authorized. */
  boolean renew(Lease lease);

  /**
   * Checks current principal authority (including service scope and Application grants), dependency
   * readability and the lease token while locking revision and stage. Throws StaleLease on loss.
   */
  void requireLease(Lease lease);

  void finish(Lease lease, String state, String detail);

  boolean taggingEligible(UUID revision);

  boolean groupingEligible(UUID revision);

  Revision readRevision(UUID revision);

  String projectName(Long id);

  void extracted(UUID revision, Extracted extracted);

  void documentPolicy(Lease lease);

  void spend(UUID revision);

  void autoTags(UUID revision, InformationMetadata metadata);

  Grouping grouping(UUID revision);

  void groups(UUID revision, Map<String, List<String>> groups, List<String> tags);
}
