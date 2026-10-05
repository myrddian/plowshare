package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.information.InformationCatalogue.Admission;
import java.util.*;

/**
 * Catalogue persistence and permission-scoped reads. Mutating locks require the caller's
 * transaction.
 */
public interface InformationCatalogueRepository {
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

  record Owner(
      UUID id,
      String namespace,
      String sourceName,
      String ownerHandle,
      Long projectId,
      String projectName) {}

  record Span(int start, int end) {
    public Span {
      if (start < 0 || end < start) throw new IllegalArgumentException("invalid code span");
    }
  }

  Snapshot row(UUID revision);

  Owner lockOwner(String account, UUID revision);

  boolean readable(InformationContext context, UUID revision);

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
      String fingerprint) {
    public AdmissionWrite {
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

  void tagGroups(UUID resource, Map<String, List<String>> groups);

  void tags(UUID resource, List<String> tags);

  Command reserve(InformationContext context, UUID revision, UUID request, String fingerprint);

  void commandState(InformationContext context, Command command, CommandState state, String error);

  void lockReserved(InformationContext context, Command command);

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
