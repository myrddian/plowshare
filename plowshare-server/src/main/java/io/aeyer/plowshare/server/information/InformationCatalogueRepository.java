package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.information.InformationCatalogue.Admission;
import java.util.*;

/**
 * Catalogue persistence and permission-scoped reads. Mutating locks require the caller's
 * transaction.
 */
public interface InformationCatalogueRepository {
  /**
   * Metadata admitted under this exact selection in its retrieval statement. Reuse only within the
   * current operation unless the owning projection cache additionally supplies complete durable
   * version and current authority fences. Processing generation does not version resource tags,
   * reports, steps, content-type display metadata or authority. The DTO grants no authority.
   */
  record AuthorizedMetadata(InformationContext context, Information.Revision revision) {
    public AuthorizedMetadata {
      Objects.requireNonNull(context);
      Objects.requireNonNull(revision);
    }
  }

  /**
   * One authorized extraction and its classification. Source bytes (contentHash), extraction
   * (textHash) and processingGeneration describe distinct identities; none versions current policy
   * or availability. Hashes are opaque retained fingerprints: V83 imported older documents without
   * a SHA-256 format constraint. A null extraction/textHash is allowed before extraction. This
   * immutable value alone is operation-local: later disclosure must reauthorize. Shared projection
   * reuse additionally fences deletion/redaction, extraction replacement and all durable changes;
   * generation alone does not establish those fences.
   */
  record AuthorizedText(
      InformationContext context,
      UUID revision,
      UUID resource,
      String documentType,
      String documentSubtype,
      String contentHash,
      String textHash,
      long processingGeneration,
      String text) {
    public AuthorizedText {
      Objects.requireNonNull(context);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(resource);
      if (!Set.of("document", "code").contains(documentType)
          || documentSubtype == null
          || !documentSubtype.matches("[a-zA-Z0-9_.+-]{1,128}")
          || processingGeneration < 1)
        throw new IllegalArgumentException("invalid authorized extraction identity");
      retainedHash(contentHash, false);
      retainedHash(textHash, true);
    }

    private static void retainedHash(String value, boolean optional) {
      if (value == null && optional) return;
      if (value == null
          || value.isBlank()
          || value.length() > 1024
          || value.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("invalid retained extraction fingerprint");
    }
  }

  record Snapshot(
      UUID id,
      UUID resourceId,
      String documentType,
      String documentSubtype,
      String availability,
      String extractedText,
      String ownerHandle,
      long generation,
      int allowanceSpent) {}

  record Resource(
      UUID id,
      String namespace,
      String sourceName,
      String ownerHandle,
      Long projectId,
      String projectName,
      io.aeyer.plowshare.server.access.AuthorityIds.ScopeId home,
      Optional<io.aeyer.plowshare.server.access.AuthorityIds.AccountId> createdBy) {}

  record Span(int start, int end) {
    public Span {
      if (start < 0 || end < start) throw new IllegalArgumentException("invalid code span");
    }
  }

  Snapshot row(UUID revision);

  /** Locks the explicit home and rechecks live authority plus mandatory restrictions. */
  Resource lockResource(InformationContext context, UUID revision, boolean audience);

  boolean readable(InformationContext context, UUID revision);

  /** Applies current readability in the same SQL snapshot as the metadata projection. */
  Optional<AuthorizedMetadata> authorizedMetadata(InformationContext context, UUID revision);

  /**
   * Applies current readability in the same SQL snapshot as extraction/classification retrieval.
   */
  Optional<AuthorizedText> authorizedText(InformationContext context, UUID revision);

  Information.Revision metadata(UUID revision);

  List<Information.Revision> list(InformationContext context, int limit, int offset, String kind);

  List<Information.Revision> inventory(InformationContext context, int limit, int offset);

  List<Information.Step> steps(UUID revision);

  Information.Progress progress(UUID revision);

  List<Information.Event> revisionEvents(UUID revision);

  List<UUID> inputs(UUID revision);

  Optional<Information.Report> report(UUID revision);

  List<UUID> citations(UUID revision);

  String text(UUID revision);

  byte[] bytes(UUID revision);

  Optional<Span> codeSpan(UUID revision, UUID chunk);

  List<UUID> discoveryRevisions(InformationContext context);

  Information.Facets facets(InformationContext context, String kind, List<UUID> pinned);

  Information.Events events(InformationContext context, long after, int limit);

  Information.Evidence evidence(InformationContext context, UUID id);

  io.aeyer.plowshare.server.documents.CodeProjection codeProjection(
      InformationContext context, UUID revision, String rawHash);

  Information.Outline outline(InformationContext context, UUID revision, int offset, int limit);

  Information.Symbols symbols(
      InformationContext context, String query, UUID revision, int offset, int limit);

  void event(
      UUID revision, long generation, String actor, String stage, String action, String detail);

  Admission admit(InformationContext context, UUID request, AdmissionWrite write);

  record AdmissionWrite(
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri,
      String kind,
      List<UUID> inputs,
      String session,
      int allowance,
      int capturedAllowance,
      boolean syntaxOnly,
      io.aeyer.plowshare.protocol.DocumentType documentType,
      String fingerprint,
      io.aeyer.plowshare.protocol.InformationContent.Binding binding,
      UUID expectedRevision,
      boolean typingExplicit,
      String processingPolicy) {
    public AdmissionWrite(
        String name,
        byte[] bytes,
        String mediaType,
        String sourceUri,
        String kind,
        List<UUID> inputs,
        String session,
        int allowance,
        int capturedAllowance,
        boolean syntaxOnly,
        io.aeyer.plowshare.protocol.DocumentType documentType,
        String fingerprint,
        io.aeyer.plowshare.protocol.InformationContent.Binding binding,
        UUID expectedRevision,
        boolean typingExplicit) {
      this(
          name,
          bytes,
          mediaType,
          sourceUri,
          kind,
          inputs,
          session,
          allowance,
          capturedAllowance,
          syntaxOnly,
          documentType,
          fingerprint,
          binding,
          expectedRevision,
          typingExplicit,
          "automatic");
    }

    public AdmissionWrite(
        String name,
        byte[] bytes,
        String mediaType,
        String sourceUri,
        String kind,
        List<UUID> inputs,
        String session,
        int allowance,
        int capturedAllowance,
        boolean syntaxOnly,
        io.aeyer.plowshare.protocol.DocumentType documentType,
        String fingerprint) {
      this(
          name,
          bytes,
          mediaType,
          sourceUri,
          kind,
          inputs,
          session,
          allowance,
          capturedAllowance,
          syntaxOnly,
          documentType,
          fingerprint,
          null,
          null,
          false,
          "automatic");
    }

    public AdmissionWrite {
      if (!Set.of("automatic", "explicit_only").contains(processingPolicy))
        throw new IllegalArgumentException("Invalid admission processing policy");
      if (name == null
          || name.isBlank()
          || name.length() > 1024
          || name.indexOf('\0') >= 0
          || bytes == null
          || bytes.length > 32 * 1024 * 1024
          || !syntaxOnly && bytes.length == 0
          || allowance < 1
          || capturedAllowance < 0
          || !Set.of("source", "report").contains(kind)
          || fingerprint == null
          || !fingerprint.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("invalid information admission");
      bytes = bytes.clone();
      inputs = List.copyOf(inputs);
      if (inputs.size() > 10000) throw new IllegalArgumentException("too many source dependencies");
      Objects.requireNonNull(documentType);
      if (binding != null && (!typingExplicit || documentType.isCode()))
        throw new IllegalArgumentException(
            "Code or implicit admissions cannot bind a content type");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  void link(InformationContext context, UUID revision, String project, boolean remove);

  void share(InformationContext context, UUID revision, boolean shared);

  void availability(InformationContext context, UUID revision, String state);

  void retry(InformationContext context, UUID revision);

  void rebuild(InformationContext context, UUID revision, String from);

  void allowance(InformationContext context, UUID revision, int total);

  void cancelProcessing(InformationContext context, UUID revision);

  void callerSession(Admission admission, String session);

  void tagGroups(InformationContext context, UUID revision, Map<String, List<String>> groups);

  void tags(InformationContext context, UUID revision, List<String> tags);

  Command reserve(InformationContext context, UUID revision, UUID request, String fingerprint);

  void commandState(InformationContext context, Command command, CommandState state, String error);

  void lockReserved(InformationContext context, Command command);

  /**
   * Locks the exact live command attempt while either reserved or awaiting post-gate completion.
   */
  void requireCommand(InformationContext context, UUID revision, Command command);

  UUID recordEvidence(
      InformationContext context,
      UUID revision,
      int start,
      int end,
      String quote,
      String locator,
      UUID request);

  void recordReport(
      Admission result,
      UUID feedback,
      io.aeyer.plowshare.protocol.InformationReportDetails details,
      String producer,
      List<UUID> evidence);

  void finalise(InformationContext context, UUID revision, Map<String, String> fingerprints);

  record Command(UUID request, UUID token, boolean applied, boolean replay) {
    public Command {
      Objects.requireNonNull(request);
      Objects.requireNonNull(token);
      if (replay && !applied)
        throw new IllegalArgumentException("replayed command must have applied");
    }
  }

  enum CommandState {
    RESERVED,
    APPLIED,
    COMPLETED,
    BLOCKED,
    FAILED
  }
}
