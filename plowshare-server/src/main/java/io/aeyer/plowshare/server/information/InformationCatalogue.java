package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.InformationReportDetails;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationCatalogueRepository.Command;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable catalogue. All mutations are short transactions; extraction, models and hooks run outside
 * them.
 */
public final class InformationCatalogue implements InformationReadAccess {
  public static final List<String> STAGES =
      List.of("extract", "derive", "embed", "summarise", "summary_embed", "autoTag", "tagGroups");
  private final InformationCatalogueRepository repository;
  private final UnitOfWork transactions;
  private final InformationAccess access;
  private final InformationLogAccess logs;
  private final InformationContentAuthority contentAuthority;
  private final InformationContentRepository contentRepository;
  private final InformationProjectionRepository projections;

  private InformationLifecycle.Gates gates = new InformationLifecycle.Gates() {};

  public InformationCatalogue withGates(InformationLifecycle.Gates gates) {
    this.gates = gates;
    return this;
  }

  private java.util.function.IntSupplier allowance = () -> 1000;
  private java.util.function.Supplier<Map<String, String>> configuration = Map::of;

  public void useConfiguration(java.util.function.Supplier<Map<String, String>> configuration) {
    this.configuration = configuration;
  }

  public String fingerprint(String stage) {
    return configuration.get().get(stage);
  }

  public String fingerprint(UUID revision, String stage) {
    String base = fingerprint(stage);
    if (base == null || !stage.equals("derive")) return base;
    return fingerprint(base, stage, row(revision).documentType());
  }

  private static String fingerprint(String base, String stage, String documentType) {
    return base != null && stage.equals("derive") && "code".equals(documentType)
        ? sha256(
            (base
                    + ":"
                    + io.aeyer.plowshare.server.documents.CodeDerivation.VERSION
                    + ":"
                    + io.aeyer.plowshare.server.documents.CodeOutline.VERSION)
                .getBytes(StandardCharsets.UTF_8))
        : base;
  }

  public InformationCatalogue withAllowance(java.util.function.IntSupplier allowance) {
    this.allowance = allowance;
    return this;
  }

  /**
   * Legacy composition keeps the original untyped service contract for existing isolated clients.
   */
  public InformationCatalogue(
      InformationCatalogueRepository repository,
      UnitOfWork transactions,
      InformationAccess access,
      InformationLogAccess logs) {
    this(repository, transactions, access, logs, null, null, null, null);
  }

  /** Production composition injects authored authority, persistence and transitions together. */
  public InformationCatalogue(
      InformationCatalogueRepository repository,
      UnitOfWork transactions,
      InformationAccess access,
      InformationLogAccess logs,
      InformationContentAuthority contentAuthority,
      InformationContentRepository contentRepository,
      InformationProjectionRepository projections,
      InformationTransitions transitions) {
    this.repository = java.util.Objects.requireNonNull(repository);
    this.transactions = java.util.Objects.requireNonNull(transactions);
    this.access = java.util.Objects.requireNonNull(access);
    this.logs = java.util.Objects.requireNonNull(logs);
    if ((contentAuthority == null) != (contentRepository == null)
        || (contentAuthority == null) != (projections == null)
        || contentAuthority != null && transitions == null)
      throw new IllegalArgumentException("Authored capabilities must be composed together");
    this.contentAuthority = contentAuthority;
    this.contentRepository = contentRepository;
    this.projections = projections;
    this.writeGates = transitions;
  }

  private InformationTransitions writeGates;

  public void useWriteGates(InformationTransitions writeGates) {
    this.writeGates = writeGates;
  }

  private <T extends InformationGateResult> T prepared(
      InformationContext context,
      UUID request,
      String operation,
      InformationGateIdentity identity,
      List<UUID> sources,
      String session,
      Class<T> type,
      java.util.function.Supplier<T> transition) {
    return writeGates == null
        ? transition.get()
        : writeGates.execute(
            context, request, operation, identity, sources, session, type, transition);
  }

  private InformationAcquisitions acquisitions;

  public void useAcquisitions(InformationAcquisitions acquisitions) {
    this.acquisitions = acquisitions;
  }

  public AcquisitionRepository.Status acquire(
      InformationContext context, UUID request, String url, String name, String session) {
    if (acquisitions == null) throw new CallerFault("durable acquisition is unavailable");
    return acquisitions.submit(context, request, url, name, session);
  }

  public AcquisitionRepository.Status acquisitionStatus(
      InformationContext context, UUID acquisition) {
    if (acquisitions == null) throw new CallerFault("durable acquisition is unavailable");
    return acquisitions.status(context, acquisition);
  }

  public Admission admit(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri) {
    return admitForSession(context, request, name, bytes, mediaType, sourceUri, null);
  }

  private Admission admit(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri,
      String kind,
      List<UUID> inputs,
      List<UUID> citations,
      UUID feedback,
      String session,
      int capturedAllowance) {
    return admit(
        context,
        request,
        name,
        bytes,
        mediaType,
        sourceUri,
        kind,
        inputs,
        citations,
        feedback,
        session,
        capturedAllowance,
        false);
  }

  private Admission admit(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri,
      String kind,
      List<UUID> inputs,
      List<UUID> citations,
      UUID feedback,
      String session,
      int capturedAllowance,
      boolean syntaxOnly) {
    return admit(
        context,
        request,
        name,
        bytes,
        mediaType,
        sourceUri,
        kind,
        inputs,
        citations,
        feedback,
        session,
        capturedAllowance,
        syntaxOnly,
        null,
        null,
        null);
  }

  private Admission admit(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri,
      String kind,
      List<UUID> inputs,
      List<UUID> citations,
      UUID feedback,
      String session,
      int capturedAllowance,
      boolean syntaxOnly,
      InformationContent.Binding binding,
      UUID expectedRevision,
      InformationContent.Typing typing) {
    access.requireWork(context);
    if (context.selection().scope() == InformationContext.Scope.GLOBAL_READ)
      throw new CallerFault(
          "create in a personal or project namespace, then explicitly share a revision");
    if (request == null
        || name == null
        || name.isBlank()
        || bytes == null
        || (!syntaxOnly && bytes.length == 0))
      throw new CallerFault("requestId, source name and nonempty source bytes are required");
    if (bytes.length > 32 * 1024 * 1024)
      throw new CallerFault("information sources may be at most 32 MiB");
    bytes = bytes.clone();
    final byte[] retained = bytes;
    int modelAllowance = capturedAllowance > 0 ? capturedAllowance : allowance.getAsInt();
    if (modelAllowance < 1)
      throw new CallerFault("processing allowance must be positive before accepting information");
    String hash = sha256(retained);
    var classified = io.aeyer.plowshare.protocol.DocumentType.classify(name, mediaType);
    var documentType =
        kind.equals("report")
            ? new io.aeyer.plowshare.protocol.DocumentType("document", "text")
            : context.corpus() == InformationContext.Corpus.CODE && !classified.isCode()
                ? new io.aeyer.plowshare.protocol.DocumentType("code", "unknown")
                : classified;
    String namespace = context.namespace();
    String fingerprint =
        InformationCatalogueFingerprint.admission(
            namespace,
            name,
            hash,
            mediaType,
            sourceUri,
            kind,
            inputs,
            citations,
            feedback,
            documentType,
            syntaxOnly);
    final String identity =
        typing == null
            ? fingerprint
            : sha256(
                InformationJson.json(
                        java.util.Arrays.asList(
                            "typed-admission-v1",
                            fingerprint,
                            typing.mode(),
                            expectedRevision,
                            binding == null ? null : binding.contract().version(),
                            binding == null ? null : binding.contract().profile(),
                            binding == null ? null : binding.metadataHash()))
                    .getBytes(StandardCharsets.UTF_8));
    return transactions.inTransaction(
        () -> {
          access.requireWork(context);
          if (binding != null)
            contentAuthority.recheck(context, binding, typing.mode().equals("inherit"));
          return repository.admit(
              context,
              request,
              new InformationCatalogueRepository.AdmissionWrite(
                  name,
                  retained,
                  mediaType,
                  sourceUri,
                  kind,
                  inputs,
                  session,
                  modelAllowance,
                  capturedAllowance,
                  syntaxOnly,
                  documentType,
                  identity,
                  binding,
                  expectedRevision,
                  typing != null));
        });
  }

  public io.aeyer.plowshare.server.agents.Outcome awaitProcessing(
      InformationContext context, UUID revision, java.util.function.BooleanSupplier cancelled) {
    while (true) {
      if (cancelled.getAsBoolean()) {
        transactions.inTransaction(
            () -> {
              requireWrite(context, revision);
              repository.cancelProcessing(context, revision);
              return null;
            });
        return new io.aeyer.plowshare.server.agents.Outcome(
            io.aeyer.plowshare.server.agents.Outcome.Ending.CANCELLED,
            "Processing cancelled; source bytes and checkpoints are retained.",
            0,
            0,
            "");
      }
      var current = generation(revision);
      var steps =
          repository.steps(revision).stream().filter(step -> step.generation() == current).toList();
      if (steps.isEmpty())
        return new io.aeyer.plowshare.server.agents.Outcome(
            io.aeyer.plowshare.server.agents.Outcome.Ending.CANCELLED,
            "Processing was invalidated; inspect information.status.",
            0,
            0,
            "");
      var stopped =
          steps.stream()
              .filter(step -> List.of("failed", "blocked", "cancelled").contains(step.state()))
              .findFirst();
      if (stopped.isPresent())
        return new io.aeyer.plowshare.server.agents.Outcome(
            io.aeyer.plowshare.server.agents.Outcome.Ending.UNAVAILABLE,
            "Revision "
                + revision
                + " retained with incomplete processing; inspect information.status and retry.",
            0,
            0,
            String.valueOf(stopped.get().error()));
      if (steps.stream().allMatch(step -> List.of("ready", "skipped").contains(step.state())))
        return new io.aeyer.plowshare.server.agents.Outcome(
            io.aeyer.plowshare.server.agents.Outcome.Ending.ANSWERED,
            "Revision "
                + revision
                + (steps.stream().anyMatch(step -> step.state().equals("skipped"))
                    ? " completed its applicable processing stages; skipped capabilities remain unavailable."
                    : " is fully processed."),
            0,
            0,
            "");
      try {
        Thread.sleep(200);
      } catch (InterruptedException stoppedThread) {
        Thread.currentThread().interrupt();
        return new io.aeyer.plowshare.server.agents.Outcome(
            io.aeyer.plowshare.server.agents.Outcome.Ending.CANCELLED,
            "Processing observer interrupted; durable processing continues.",
            0,
            0,
            "");
      }
    }
  }

  public Admission admitForSession(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri,
      String session) {
    return admitForSession(
        context, request, name, bytes, mediaType, sourceUri, session, null, null);
  }

  public Admission admitForSession(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri,
      String session,
      UUID expectedRevision,
      InformationContent.Typing typing) {
    if (expectedRevision != null && typing == null)
      throw new CallerFault("expectedRevision requires explicit typing");
    if (typing != null)
      return typedSource(
          context, request, name, bytes, mediaType, sourceUri, session, expectedRevision, typing);
    if (bytes == null
        || bytes.length == 0
        || bytes.length > 32 * 1024 * 1024
        || name == null
        || name.isBlank())
      throw new CallerFault("nonempty source name and bytes within 32 MiB are required");
    byte[] retained = bytes.clone();
    var identity =
        new InformationGateIdentity.Intake(
            name, sha256(retained), mediaType, sourceUri, context.corpus());
    if (contentAuthority != null && writeGates != null) {
      return writeGates.executePrepared(
          context,
          request,
          "intake",
          identity,
          List.of(),
          session,
          Admission.class,
          () -> {
            contentAuthority.resolve(context, name, null, null);
            return new InformationTransitions.Prepared<>(
                () ->
                    admit(
                        context, request, name, retained, mediaType, sourceUri, "source", List.of(),
                        List.of(), null, session, 0),
                null);
          });
    }
    return prepared(
        context,
        request,
        "intake",
        identity,
        List.of(),
        session,
        Admission.class,
        () ->
            admit(
                context, request, name, retained, mediaType, sourceUri, "source", List.of(),
                List.of(), null, session, 0));
  }

  private Admission typedSource(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri,
      String session,
      UUID expected,
      InformationContent.Typing typing) {
    if (contentAuthority == null || writeGates == null)
      throw new CallerFault("Typed information is unavailable");
    if (bytes == null
        || bytes.length == 0
        || bytes.length > 32 * 1024 * 1024
        || name == null
        || name.isBlank())
      throw new CallerFault("Information needs a bounded name and nonempty source bytes");
    byte[] retained = bytes.clone();
    if (context.corpus() == InformationContext.Corpus.CODE
        || io.aeyer.plowshare.protocol.DocumentType.classify(name, mediaType).isCode())
      throw new CallerFault("Content types are not supported on code revisions");
    var identity =
        new InformationGateIdentity.TypedIntake(
            new InformationGateIdentity.Intake(
                name, sha256(retained), mediaType, sourceUri, context.corpus()),
            expected,
            typing);
    return writeGates.executePrepared(
        context,
        request,
        "intake",
        identity,
        List.of(),
        session,
        Admission.class,
        () -> {
          var binding = contentAuthority.resolve(context, name, expected, typing).orElse(null);
          var candidate =
              new InformationContent.Candidate(
                  expected, typing, binding, "automatic", null, List.of());
          return new InformationTransitions.Prepared<>(
              () ->
                  admit(
                      context, request, name, retained, mediaType, sourceUri, "source", List.of(),
                      List.of(), null, session, 0, false, binding, expected, typing),
              candidate);
        });
  }

  /**
   * Copies immutable source/provenance and compatible committed outputs without scheduling models.
   */
  public Admission reviseMetadata(
      InformationContext context,
      UUID request,
      UUID expected,
      InformationContent.Typing typing,
      String session) {
    if (contentAuthority == null || projections == null || writeGates == null)
      throw new CallerFault("Typed information is unavailable");
    access.requireWork(context);
    String name = revisionName(context, expected);
    requireReadable(context, expected);
    var sources = new ArrayList<>(repository.inputs(expected));
    if (!sources.contains(expected)) sources.add(expected);
    return writeGates.executePrepared(
        context,
        request,
        "content.revise",
        new InformationGateIdentity.MetadataRevision(expected, typing),
        sources,
        session,
        Admission.class,
        () -> {
          var binding = contentAuthority.resolve(context, name, expected, typing).orElse(null);
          var prepared =
              transactions.inTransaction(
                  () ->
                      projections.prepare(
                          context, request, expected, binding, configuration.get()));
          var candidate =
              new InformationContent.Candidate(
                  expected,
                  typing,
                  binding,
                  "explicit_only",
                  prepared.manifest().snapshotHash(),
                  prepared.manifest().reusable());
          return new InformationTransitions.Prepared<>(
              () -> {
                access.requireWork(context);
                // Match ordinary typed admission: lock registry authority before the resource.
                if (binding != null)
                  contentAuthority.recheck(context, binding, typing.mode().equals("inherit"));
                requireWrite(context, expected);
                requireReadable(context, expected);
                var admission = projections.copy(context, request, prepared, configuration.get());
                repository.event(
                    admission.revision(),
                    1,
                    context.account(),
                    "metadata",
                    "retained",
                    "Metadata revision retained; processing requires explicit selection.");
                return admission;
              },
              candidate);
        });
  }

  public InformationContent.View content(InformationContext context, UUID revision) {
    if (contentRepository == null) throw new CallerFault("Typed information is unavailable");
    requireReadable(context, revision);
    // The view's existing header projection rejects deleted content. Do not hydrate a full
    // extraction merely to inspect that lifecycle field before the typed view reads it again.
    return contentRepository.view(revision);
  }

  AcquisitionRepository.Intent acquisitionIntent(
      InformationContext context, String name, UUID expected, InformationContent.Typing typing) {
    if (contentAuthority == null) {
      if (typing != null) throw new CallerFault("Typed information is unavailable");
      return null;
    }
    var binding = contentAuthority.resolve(context, name, expected, typing).orElse(null);
    return typing == null ? null : new AcquisitionRepository.Intent(expected, typing, binding);
  }

  Admission acquired(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri,
      String session,
      int allowance,
      AcquisitionRepository.Intent intent) {
    if (intent == null)
      return acquired(context, request, name, bytes, mediaType, sourceUri, session, allowance);
    var current = acquisitionIntent(context, name, intent.expectedRevision(), intent.typing());
    if (!java.util.Objects.equals(current, intent))
      throw new CallerFault("Acquisition typing candidate changed");
    return admit(
        context,
        request,
        name,
        bytes,
        mediaType,
        sourceUri,
        "source",
        List.of(),
        List.of(),
        null,
        session,
        allowance,
        false,
        intent.binding(),
        intent.expectedRevision(),
        intent.typing());
  }

  Admission acquired(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String mediaType,
      String sourceUri,
      String session,
      int allowance) {
    return admit(
        context, request, name, bytes, mediaType, sourceUri, "source", List.of(), List.of(), null,
        session, allowance);
  }

  /**
   * Automatic workspace intake uses the same gates; paid stages are skipped before queue admission.
   */
  public Admission admitCodeSnapshot(
      InformationContext context,
      UUID request,
      String name,
      byte[] bytes,
      String sourceUri,
      String session) {
    requireCode(context);
    if (name == null
        || name.isBlank()
        || bytes == null
        || bytes.length > 1024 * 1024
        || !io.aeyer.plowshare.protocol.DocumentType.classify(name, null).isCode())
      throw new CallerFault("a bounded named code snapshot is required");
    byte[] retained = bytes.clone();
    return prepared(
        context,
        request,
        "intake",
        new InformationGateIdentity.CodeSnapshot(name, sha256(retained), sourceUri),
        List.of(),
        session,
        Admission.class,
        () ->
            admit(
                context,
                request,
                name,
                retained,
                "text/plain",
                sourceUri,
                "source",
                List.of(),
                List.of(),
                null,
                session,
                0,
                true));
  }

  public String revisionName(InformationContext context, UUID revision) {
    var resource = requireWrite(context, revision);
    if ("deleted".equals(row(revision).availability()))
      throw new CallerFault("a deleted resource cannot be revised");
    return resource.sourceName();
  }

  /** An indexed passage is only evidence when it can be matched back to retained source text. */
  public Information.Window locateWindow(
      InformationContext context, UUID revision, String passage) {
    String source = authorizedText(context, revision).text();
    if (source == null)
      return Information.Window.unmatched(revision, "retained extraction is unavailable");
    if (passage == null || passage.isBlank() || passage.length() > 32768)
      return Information.Window.unmatched(
          revision, "indexed passage is not a bounded source quote");
    int start = source.indexOf(passage);
    if (start < 0)
      return Information.Window.unmatched(
          revision, "indexed passage differs from retained extraction");
    return new Information.Window(
        revision, true, null, start, start + passage.length(), null, passage, null, null);
  }

  public Information.Window locateCodeWindow(
      InformationContext context, UUID revision, UUID chunk) {
    var admitted = access.admitted(context);
    // This is an internal locator, not a disclosure of passage existence. Both branches must
    // authorize the revision before returning anything. A missing locator needs no extraction.
    var span = repository.codeSpan(revision, chunk);
    if (span.isEmpty()) {
      if (!repository.readable(admitted, revision)) throw absent();
      return Information.Window.unmatched(revision, "code passage has no source location");
    }
    var retained =
        repository.authorizedText(admitted, revision).orElseThrow(InformationCatalogue::absent);
    String source = extracted(retained);
    var location = span.get();
    if (location.end() > source.length())
      throw new IllegalStateException("code passage lies outside retained source");
    return new Information.Window(
        revision,
        true,
        null,
        location.start(),
        location.end(),
        null,
        source.substring(location.start(), location.end()),
        "code",
        retained.documentSubtype());
  }

  public Information.Window window(
      InformationContext context, UUID revision, int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 32768)
      throw new CallerFault(
          "read offset must be nonnegative and limit must be between 1 and 32768 characters");
    var retained = authorizedText(context, revision);
    String value = extracted(retained);
    if (offset > value.length()) throw new CallerFault("read offset is beyond the retained text");
    int end = (int) Math.min((long) offset + limit, value.length());
    return new Information.Window(
        revision,
        null,
        null,
        offset,
        end,
        value.length(),
        value.substring(offset, end),
        retained.documentType(),
        retained.documentSubtype());
  }

  public void callerSession(Admission admission, String session) {
    repository.callerSession(admission, session);
  }

  public List<Information.Revision> list(InformationContext context, int limit, int offset) {
    return list(context, limit, offset, null);
  }

  public List<Information.Revision> list(
      InformationContext context, int limit, int offset, String kind) {
    access.requireSelection(context);
    return repository.list(context, limit, offset, kind);
  }

  /** Counts and available values describe this exact intersection of readable revisions. */
  public Information.Facets facets(InformationContext context, String kind) {
    access.requireSelection(context);
    return repository.facets(context, kind, null);
  }

  /** Pin every contributing revision to the run before exposing aggregate metadata. */
  public Information.Facets facetsForRun(
      InformationContext context, java.util.function.Consumer<UUID> reads) {
    access.requireSelection(context);
    var revisions = repository.discoveryRevisions(context);
    revisions.forEach(reads);
    return repository.facets(context, null, revisions);
  }

  /** Owner category overrides are distinct from both tag vocabularies and generated membership. */
  public void tagGroups(
      InformationContext context, UUID revision, Map<String, List<String>> raw, UUID request) {
    var groups = raw == null ? null : InformationTagGroups.from(raw, null);
    managed(
        context,
        revision,
        "tagGroups",
        request,
        new InformationCommandParameters.Groups(groups),
        () -> {
          requireReadable(context, revision);
          requireWrite(context, revision);
          var shown = status(context, revision);
          var tags = new java.util.HashSet<String>();
          tags.addAll(shown.tags());
          if (shown.autoTag() != null) tags.addAll(shown.autoTag());
          if (groups != null) InformationTagGroups.from(groups, tags);
          repository.tagGroups(context, revision, groups);
          event(
              revision,
              generation(revision),
              context.account(),
              "tagGroups",
              groups == null ? "automatic" : "owner.updated",
              io.aeyer.plowshare.server.information.InformationJson.json(groups));
        });
  }

  public void tags(InformationContext context, UUID revision, List<String> raw, UUID request) {
    var tags = InformationFacets.tags(raw);
    managed(
        context,
        revision,
        "tags",
        request,
        new InformationCommandParameters.Tags(tags),
        () -> {
          requireWrite(context, revision);
          repository.tags(context, revision, tags);
          event(
              revision,
              generation(revision),
              context.account(),
              "tags",
              "updated",
              io.aeyer.plowshare.server.information.InformationJson.json(tags));
        });
  }

  /** Resource tags have a separate version; a content revision is only the resource selector. */
  public void replaceTags(
      InformationContext context,
      UUID revision,
      List<String> raw,
      long expectedVersion,
      UUID request) {
    var parameters = new InformationCommandParameters.TagsReplace(raw, expectedVersion);
    managed(
        context,
        revision,
        "tags",
        request,
        parameters,
        () -> {
          var current = requireWrite(context, revision);
          repository.replaceTags(current, parameters.values(), expectedVersion);
          event(
              revision,
              generation(revision),
              context.account(),
              "tags",
              "updated",
              InformationJson.json(parameters.values()));
        });
  }

  public List<Information.Revision> inventory(InformationContext context, int limit, int offset) {
    access.requireSelection(context);
    return repository.inventory(context, limit, offset);
  }

  /**
   * Reuses metadata admitted by its own retrieval statement. Checkpoints remain separate live reads
   * as before: this view does not claim a common transaction snapshot or a durable display version.
   * The hidden-owner fallback must still acquire write authority and its existing locks.
   */
  public Information.Revision status(InformationContext context, UUID revision) {
    boolean readable = true;
    Information.Revision metadata;
    try {
      metadata =
          repository
              .authorizedMetadata(access.admitted(context), revision)
              .orElseThrow(InformationCatalogue::absent)
              .revision();
    } catch (NotFoundFault hidden) {
      requireWrite(context, revision);
      readable = false;
      metadata = repository.metadata(revision);
    }
    if (!context.typedView()
        && (metadata.contentType() != null || "explicit_only".equals(metadata.processingPolicy())))
      throw new CallerFault("unsupported-view");
    boolean owner = context.account().equals(metadata.author());
    boolean visible = readable;
    var storedSteps = repository.steps(revision);
    // Resolve provider/embedding configuration once for this status view, not once per historical
    // stage. Hidden status never resolves processing configuration or exposes compatibility.
    var fingerprints =
        readable && !storedSteps.isEmpty()
            ? Map.copyOf(configuration.get())
            : Map.<String, String>of();
    String documentType = metadata.documentType();
    var steps =
        storedSteps.stream()
            .map(
                step ->
                    step.visible(
                        visible,
                        owner,
                        visible
                            ? fingerprint(
                                fingerprints.get(step.stage()), step.stage(), documentType)
                            : null))
            .toList();
    boolean report = readable && "report".equals(metadata.kind());
    return metadata.withStatus(
        readable,
        owner,
        steps,
        readable ? repository.progress(revision) : null,
        readable && owner ? repository.revisionEvents(revision) : List.of(),
        readable ? repository.inputs(revision) : List.of(),
        report ? repository.report(revision).orElse(null) : null,
        report ? repository.citations(revision) : null);
  }

  public String text(InformationContext context, UUID revision) {
    return extracted(authorizedText(context, revision));
  }

  private InformationCatalogueRepository.AuthorizedText authorizedText(
      InformationContext context, UUID revision) {
    return repository
        .authorizedText(access.admitted(context), revision)
        .orElseThrow(InformationCatalogue::absent);
  }

  private static String extracted(InformationCatalogueRepository.AuthorizedText retained) {
    if (retained.text() == null) throw new CallerFault("this revision has not been extracted");
    return retained.text();
  }

  /** Bounded, syntax-only outline; source/evidence continue to use the existing read API. */
  public io.aeyer.plowshare.server.documents.CodeProjection codeProjection(
      InformationContext context, UUID revision, String rawHash) {
    return transactions.inTransaction(
        () -> {
          requireCode(context);
          return repository.codeProjection(context, revision, rawHash);
        });
  }

  /** Bounded, syntax-only outline; source/evidence continue to use the existing read API. */
  public Information.Outline outline(
      InformationContext context, UUID revision, int offset, int limit) {
    requireCode(context);
    return repository.outline(context, revision, offset, limit);
  }

  /** Literal, case-insensitive declaration-name prefix search, with permissions before LIMIT. */
  public Information.Symbols symbols(
      InformationContext context, String query, UUID revision, int offset, int limit) {
    requireCode(context);
    return repository.symbols(context, query, revision, offset, limit);
  }

  private void requireCode(InformationContext context) {
    // Reuse this admission only for the immediately following repository call. It is not a lease:
    // later operations and checks after other work must observe current Application authority.
    access.requireSelection(context);
    if (context.corpus() != InformationContext.Corpus.CODE)
      throw new CallerFault("select corpus code for syntax navigation");
  }

  private static void page(int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 100)
      throw new CallerFault("offset must be nonnegative and limit must be between 1 and 100");
  }

  public byte[] bytes(InformationContext context, UUID revision) {
    requireReadable(context, revision);
    var value = repository.bytes(revision);
    if (value == null) throw new CallerFault("this revision has no retained source bytes");
    return value;
  }

  /**
   * Unlink never deletes. A private link is visible only to its owner; project material needs a
   * project revision.
   */
  public void link(InformationContext context, UUID revision, String project, boolean remove) {
    link(context, revision, project, remove, null);
  }

  public void link(
      InformationContext context, UUID revision, String project, boolean remove, UUID request) {
    managed(
        context,
        revision,
        remove ? "unlink" : "link",
        request,
        new InformationCommandParameters.Link(project, remove),
        () -> {
          access.requireWork(
              access.resolve(
                  context.account(),
                  InformationAccess.projectSelection(context.account(), project)));
          transactions.inTransaction(
              () -> {
                requireWrite(context, revision);
                repository.link(context, revision, project, remove);
                return null;
              });
        });
  }

  /** Changes the current root audience; every revision retains mandatory input restrictions. */
  public void share(InformationContext context, UUID revision, boolean shared) {
    share(context, revision, shared, null);
  }

  public void share(InformationContext context, UUID revision, boolean shared, UUID request) {
    managed(
        context,
        revision,
        shared ? "share" : "unshare",
        request,
        new InformationCommandParameters.Sharing(shared),
        () -> {
          transactions.inTransaction(
              () -> {
                requireMutation(context, revision, true);
                if (shared) requireReadable(context, revision);
                repository.share(context, revision, shared);
                return null;
              });
        });
  }

  /**
   * Safety invalidation happens immediately. A hook cannot grant access to withdrawn or deleted
   * content.
   */
  public void availability(InformationContext context, UUID revision, String state) {
    availability(context, revision, state, null);
  }

  public void availability(InformationContext context, UUID revision, String state, UUID request) {
    managed(
        context,
        revision,
        state,
        request,
        new InformationCommandParameters.Availability(state),
        () -> {
          if (!List.of("active", "excluded", "included", "withdrawn", "deleted").contains(state))
            throw new CallerFault("unknown availability");
          transactions.inTransaction(
              () -> {
                requireWrite(context, revision);
                repository.availability(context, revision, state);
                return null;
              });
        });
  }

  public void retry(InformationContext context, UUID revision) {
    retry(context, revision, null);
  }

  public void retry(InformationContext context, UUID revision, UUID request) {
    retry(context, revision, request, null);
  }

  public void retry(InformationContext context, UUID revision, UUID request, List<String> stages) {
    managed(
        context,
        revision,
        "retry",
        request,
        stages == null
            ? new InformationCommandParameters.None()
            : new InformationCommandParameters.Processing(null, stages),
        () -> {
          transactions.inTransaction(
              () -> {
                requireWrite(context, revision);
                selectedProcessing(context, revision, request, stages);
                repository.retry(context, revision);
                return null;
              });
        });
  }

  /** Rebuild derived checkpoints without changing source/evidence identity. */
  public void rebuild(InformationContext context, UUID revision, String from) {
    rebuild(context, revision, from, null);
  }

  public void rebuild(InformationContext context, UUID revision, String from, UUID request) {
    rebuild(context, revision, from, request, null);
  }

  public void rebuild(
      InformationContext context, UUID revision, String from, UUID request, List<String> stages) {
    managed(
        context,
        revision,
        "rebuild",
        request,
        stages == null
            ? new InformationCommandParameters.Rebuild(from)
            : new InformationCommandParameters.Processing(from, stages),
        () -> {
          int first = STAGES.indexOf(from);
          if (first < 2)
            throw new CallerFault(
                "rebuild starts at embed, summarise, summary_embed, autoTag or tagGroups; changed extraction or derivation requires a new immutable revision");
          transactions.inTransaction(
              () -> {
                requireWrite(context, revision);
                requireReadable(context, revision);
                requireStageSelection(revision, stages);
                repository.rebuild(context, revision, from);
                selectedProcessing(context, revision, request, stages);
                return null;
              });
        });
  }

  private void requireStageSelection(UUID revision, List<String> stages) {
    if (contentRepository != null && contentRepository.explicitOnly(revision) && stages == null)
      throw new CallerFault("stage-selection-required");
  }

  /** Only missing prerequisites are authorised; compatible ready projections create no work. */
  private void selectedProcessing(
      InformationContext context, UUID revision, UUID request, List<String> stages) {
    requireStageSelection(revision, stages);
    if (stages == null) return;
    new InformationCommandParameters.Processing(null, stages);
    var selected = new java.util.LinkedHashSet<>(stages);
    var status = repository.steps(revision);
    int furthestContent =
        stages.stream().mapToInt(STAGES::indexOf).filter(index -> index < 5).max().orElse(-1);
    // Compute the union of prerequisites for mixed content/enrichment selections.
    int contentEnd =
        Math.max(
            furthestContent,
            stages.stream().anyMatch(stage -> List.of("autoTag", "tagGroups").contains(stage))
                ? 1
                : -1);
    for (int i = 0; i <= contentEnd; i++) {
      String stage = STAGES.get(i);
      if (status.stream()
          .noneMatch(
              step ->
                  step.stage().equals(stage) && List.of("ready", "skipped").contains(step.state())))
        selected.add(stage);
    }
    if (stages.contains("tagGroups")
        && status.stream()
            .noneMatch(step -> step.stage().equals("autoTag") && step.state().equals("ready")))
      selected.add("autoTag");
    contentRepository.authorise(context, revision, request, List.copyOf(selected));
  }

  public void allowance(InformationContext context, UUID revision, int total) {
    allowance(context, revision, total, null);
  }

  public void allowance(InformationContext context, UUID revision, int total, UUID request) {
    managed(
        context,
        revision,
        "allowance",
        request,
        new InformationCommandParameters.Allowance(total),
        () -> {
          if (total < 1) throw new CallerFault("processing allowance must be positive");
          transactions.inTransaction(
              () -> {
                requireWrite(context, revision);
                repository.allowance(context, revision, total);
                return null;
              });
        });
  }

  public Information.Events events(InformationContext context, long after, int limit) {
    access.requireSelection(context);
    return repository.events(context, after, limit);
  }

  private Command reserve(
      InformationContext context,
      UUID revision,
      UUID request,
      String operation,
      InformationCommandParameters parameters) {
    if (request == null)
      return null; // Trusted in-process callers; socket mutations always supply a key.
    String fingerprint =
        InformationCatalogueFingerprint.command(
            operation, revision, context.selection(), parameters);
    return transactions.inTransaction(
        () -> repository.reserve(context, revision, request, fingerprint));
  }

  private void commandState(
      InformationContext context, Command command, String state, String error) {
    repository.commandState(
        context,
        command,
        InformationCatalogueRepository.CommandState.valueOf(
            state.toUpperCase(java.util.Locale.ROOT)),
        error);
  }

  private void apply(InformationContext context, Command command, Runnable work) {
    if (command != null && command.applied()) return;
    transactions.inTransaction(
        () -> {
          repository.lockReserved(context, command);
          work.run();
          commandState(context, command, "applied", null);
          return null;
        });
  }

  private void managed(
      InformationContext context,
      UUID revision,
      String operation,
      UUID request,
      InformationCommandParameters parameters,
      Runnable work) {
    var resource =
        requireMutation(
            context, revision, operation.equals("share") || operation.equals("unshare"));
    Command command = reserve(context, revision, request, operation, parameters);
    if (command != null && command.replay()) return;
    boolean safety =
        List.of("withdrawn", "excluded", "deleted", "unshare", "unlink").contains(operation);
    // Publication commands have no expensive checkpoint: approve the prepared transition
    // before exposing its grant or final state. Safety reductions always commit first.
    boolean publication =
        List.of("share", "finalise", "link", "active", "included").contains(operation);
    var lease =
        new InformationLifecycle.Lease(
            revision,
            resource.id(),
            generation(revision),
            operation,
            1,
            UUID.randomUUID(),
            context.account(),
            resource.projectId());
    // Controls are authenticated commands, not queued processing stages. Recheck
    // both their retained attempt and the caller's current knowledge authority.
    // A retry/rebuild may change generation itself, so its command token (not a
    // fabricated processing-generation lease) fences the pre/post hook lifetime.
    var control =
        new InformationLifecycle.Control(
            lease,
            () ->
                transactions.inTransaction(
                    () -> {
                      repository.requireCommand(context, revision, command);
                      var current =
                          requireMutation(
                              context,
                              revision,
                              operation.equals("share") || operation.equals("unshare"));
                      if (!current.id().equals(resource.id())
                          || !current.home().equals(resource.home()))
                        throw new CallerFault("information command knowledge home changed");
                      return null;
                    }));
    if (safety) apply(context, command, work);
    try {
      if (command == null || !command.applied()) {
        var pre = gates.beforeControl(control);
        event(
            revision,
            generation(revision),
            context.account(),
            operation,
            "stage.pre",
            InformationJson.json(pre));
        if (pre.isDenied() && !safety) throw new CallerFault(pre.denied());
      }
      if (!safety && !publication) apply(context, command, work);
      var post = gates.afterControl(control);
      event(
          revision,
          generation(revision),
          context.account(),
          operation,
          "stage.post",
          InformationJson.json(post));
      if (post.isDenied() && !safety)
        throw new CallerFault(
            (publication
                    ? "stage.post denied publication: "
                    : "checkpoint retained; stage.post denied completion: ")
                + post.denied());
      if (publication) apply(context, command, work);
      commandState(context, command, "completed", null);
    } catch (RuntimeException failure) {
      if (!safety) {
        commandState(context, command, "blocked", failure.getMessage());
        throw failure;
      }
      event(
          revision,
          generation(revision),
          context.account(),
          operation,
          "hook.failed",
          failure.getClass().getSimpleName());
      commandState(context, command, "completed", null);
    } finally {
      gates.finished(lease);
    }
  }

  public UUID evidence(
      InformationContext context, UUID revision, int start, int end, String quote, String locator) {
    return evidence(context, revision, start, end, quote, locator, null);
  }

  public UUID evidence(
      InformationContext context,
      UUID revision,
      int start,
      int end,
      String quote,
      String locator,
      UUID request) {
    return evidenceForSession(context, revision, start, end, quote, locator, request, null);
  }

  public UUID evidenceForSession(
      InformationContext context,
      UUID revision,
      int start,
      int end,
      String quote,
      String locator,
      UUID request,
      String session) {
    String retained = text(context, revision);
    if (start < 0
        || end <= start
        || end > retained.length()
        || !retained.substring(start, end).equals(quote)
        || !"extracted-text:utf16".equals(locator))
      throw new CallerFault("evidence must quote retained UTF-16 text at valid offsets");
    if (request == null && writeGates == null)
      return evidenceUnchecked(context, revision, start, end, quote, locator, null);
    return prepared(
            context,
            request,
            "evidence.record",
            new InformationGateIdentity.Evidence(revision, start, end, quote, locator),
            List.of(revision),
            session,
            InformationGateResult.Recorded.class,
            () ->
                new InformationGateResult.Recorded(
                    evidenceUnchecked(context, revision, start, end, quote, locator, request)))
        .id();
  }

  private UUID evidenceUnchecked(
      InformationContext context,
      UUID revision,
      int start,
      int end,
      String quote,
      String locator,
      UUID request) {
    return transactions.inTransaction(
        () -> {
          requireReadable(context, revision);
          return repository.recordEvidence(context, revision, start, end, quote, locator, request);
        });
  }

  public Information.Evidence evidence(InformationContext context, UUID id) {
    access.requireSelection(context);
    var evidence = repository.evidence(context, id);
    requireReadable(context, evidence.revisionId());
    return evidence;
  }

  /** All supplied inputs are dependencies, even those that the report did not cite. */
  public Admission report(
      InformationContext context,
      UUID request,
      String name,
      String text,
      List<UUID> inputs,
      List<UUID> evidence,
      UUID feedback) {
    return reportForSession(context, request, name, text, inputs, evidence, feedback, null);
  }

  public Admission reportForSession(
      InformationContext context,
      UUID request,
      String name,
      String text,
      List<UUID> inputs,
      List<UUID> evidence,
      UUID feedback,
      String session) {
    return reportDetailsForSession(
        context,
        request,
        name,
        text,
        inputs,
        evidence,
        feedback,
        session,
        InformationReportDetails.empty(),
        null);
  }

  public Admission reportDetailsForSession(
      InformationContext context,
      UUID request,
      String name,
      String text,
      List<UUID> inputs,
      List<UUID> evidence,
      UUID feedback,
      String session,
      InformationReportDetails details,
      String producer) {
    return reportDetailsForSession(
        context, request, name, text, inputs, evidence, feedback, session, details, producer, null,
        null);
  }

  public Admission reportDetailsForSession(
      InformationContext context,
      UUID request,
      String name,
      String text,
      List<UUID> inputs,
      List<UUID> evidence,
      UUID feedback,
      String session,
      InformationReportDetails details,
      String producer,
      UUID expected,
      InformationContent.Typing typing) {
    if (expected != null && typing == null)
      throw new CallerFault("expectedRevision requires explicit typing");
    if (typing != null
        && (context.corpus() == InformationContext.Corpus.CODE
            || io.aeyer.plowshare.protocol.DocumentType.classify(name, "text/markdown").isCode()))
      throw new CallerFault("Content types are not supported on code revisions");
    if (InformationJson.json(details).getBytes(StandardCharsets.UTF_8).length > 512 * 1024)
      throw new CallerFault("report findings and review metadata must fit within 512 KiB");
    var citations = new java.util.LinkedHashSet<>(evidence);
    citations.addAll(details.evidence());
    var dependencies = new java.util.LinkedHashSet<>(inputs);
    if (feedback != null) dependencies.add(feedback);
    for (UUID input : dependencies) requireReadable(context, input);
    if (feedback != null) {
      var previous = status(context, feedback);
      if (!"report".equals(previous.kind())
          || !java.util.Objects.equals(name, previous.sourceName()))
        throw new CallerFault("feedback must name a retained revision of this report");
    }
    if (producer != null) logs.requireLog(producer, context.account());
    if (text == null || text.isBlank() || name == null || name.isBlank() || dependencies.isEmpty())
      throw new CallerFault("a research report needs a name, text and input revisions");
    for (UUID id : citations)
      if (!dependencies.contains(evidence(context, id).revisionId()))
        throw new CallerFault("cited evidence must belong to a supplied input revision");
    if (contentAuthority != null && writeGates != null) {
      var source =
          new InformationGateIdentity.Report(
              name,
              sha256(text.getBytes(StandardCharsets.UTF_8)),
              List.copyOf(dependencies),
              List.copyOf(citations),
              feedback,
              details,
              producer);
      InformationGateIdentity identity =
          typing == null
              ? source
              : new InformationGateIdentity.TypedReport(source, expected, typing);
      return writeGates.executePrepared(
          context,
          request,
          "record.report",
          identity,
          List.copyOf(dependencies),
          session,
          Admission.class,
          () -> {
            var binding = contentAuthority.resolve(context, name, expected, typing).orElse(null);
            var candidate =
                typing == null
                    ? null
                    : new InformationContent.Candidate(
                        expected, typing, binding, "automatic", null, List.of());
            return new InformationTransitions.Prepared<>(
                () ->
                    reportUnchecked(
                        context,
                        request,
                        name,
                        text,
                        List.copyOf(dependencies),
                        List.copyOf(citations),
                        feedback,
                        session,
                        details,
                        producer,
                        binding,
                        expected,
                        typing),
                candidate);
          });
    }
    if (typing != null) throw new CallerFault("Typed information is unavailable");
    return prepared(
        context,
        request,
        "record.report",
        new InformationGateIdentity.Report(
            name,
            sha256(text.getBytes(StandardCharsets.UTF_8)),
            List.copyOf(dependencies),
            List.copyOf(citations),
            feedback,
            details,
            producer),
        List.copyOf(dependencies),
        session,
        Admission.class,
        () ->
            reportUnchecked(
                context,
                request,
                name,
                text,
                List.copyOf(dependencies),
                List.copyOf(citations),
                feedback,
                session,
                details,
                producer));
  }

  private Admission reportUnchecked(
      InformationContext context,
      UUID request,
      String name,
      String text,
      List<UUID> inputs,
      List<UUID> evidence,
      UUID feedback,
      String session,
      InformationReportDetails details,
      String producer) {
    return reportUnchecked(
        context, request, name, text, inputs, evidence, feedback, session, details, producer, null,
        null, null);
  }

  private Admission reportUnchecked(
      InformationContext context,
      UUID request,
      String name,
      String text,
      List<UUID> inputs,
      List<UUID> evidence,
      UUID feedback,
      String session,
      InformationReportDetails details,
      String producer,
      InformationContent.Binding binding,
      UUID expected,
      InformationContent.Typing typing) {
    if (text == null || text.isBlank() || inputs.isEmpty())
      throw new CallerFault("a research report needs text and input revisions");
    return transactions.inTransaction(
        () -> {
          List<UUID> dependencies = new ArrayList<>(inputs);
          if (feedback != null && !dependencies.contains(feedback)) dependencies.add(feedback);
          for (UUID id : evidence) {
            UUID revision = evidence(context, id).revisionId();
            if (!dependencies.contains(revision))
              throw new CallerFault("cited evidence must belong to a supplied input revision");
          }
          Admission result =
              admit(
                  context,
                  request,
                  name,
                  text.getBytes(StandardCharsets.UTF_8),
                  "text/markdown",
                  null,
                  "report",
                  dependencies,
                  evidence,
                  feedback,
                  session,
                  0,
                  false,
                  binding,
                  expected,
                  typing);
          if (result.created()) {
            repository.recordReport(result, feedback, details, producer, evidence);
          }
          return result;
        });
  }

  public void finalise(InformationContext context, UUID revision) {
    finalise(context, revision, null);
  }

  public void finalise(InformationContext context, UUID revision, UUID request) {
    managed(
        context,
        revision,
        "finalise",
        request,
        new InformationCommandParameters.None(),
        () -> {
          transactions.inTransaction(
              () -> {
                requireWrite(context, revision);
                requireReadable(context, revision);
                repository.finalise(context, revision, configuration.get());
                return null;
              });
        });
  }

  /**
   * Admit the selection once and load only extraction-stage facts for the requested readiness set.
   */
  public java.util.Map<UUID, InformationCatalogueRepository.ExtractionState> extractionStates(
      InformationContext context, List<UUID> revisions) {
    return repository.extractionStates(access.admitted(context), revisions);
  }

  public void requireReadable(InformationContext context, UUID revision) {
    access.requireSelection(context);
    if (!repository.readable(context, revision)) throw absent();
  }

  private InformationCatalogueRepository.Resource requireWrite(
      InformationContext context, UUID revision) {
    return requireMutation(context, revision, false);
  }

  private InformationCatalogueRepository.Resource requireMutation(
      InformationContext context, UUID revision, boolean audience) {
    if (audience) access.requireAudience(context);
    else access.requireWork(context);
    var resource = repository.lockResource(context, revision, audience);
    if (resource.projectId() != null) {
      var home =
          access.resolve(
              context.account(),
              InformationAccess.projectSelection(context.account(), resource.projectName()));
      if (audience) access.requireAudience(home);
      else access.requireWork(home);
    }
    return resource;
  }

  InformationCatalogueRepository.Snapshot row(UUID revision) {
    return repository.row(revision);
  }

  long generation(UUID revision) {
    return row(revision).generation();
  }

  void event(
      UUID revision, long generation, String actor, String stage, String action, String detail) {
    transactions.inTransaction(
        () -> {
          repository.event(revision, generation, actor, stage, action, detail);
          return null;
        });
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static NotFoundFault absent() {
    return new NotFoundFault("information is unavailable in this selection");
  }

  public record Admission(UUID revision, UUID resource, boolean created)
      implements InformationGateResult {
    public Admission {
      java.util.Objects.requireNonNull(revision);
      java.util.Objects.requireNonNull(resource);
    }
  }
}
