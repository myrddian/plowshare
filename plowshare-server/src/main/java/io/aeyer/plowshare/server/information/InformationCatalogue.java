package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
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
    return "code".equals(row(revision).documentType())
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

  public InformationCatalogue(
      InformationCatalogueRepository repository,
      UnitOfWork transactions,
      InformationAccess access,
      InformationLogAccess logs) {
    this.repository = java.util.Objects.requireNonNull(repository);
    this.transactions = java.util.Objects.requireNonNull(transactions);
    this.access = java.util.Objects.requireNonNull(access);
    this.logs = java.util.Objects.requireNonNull(logs);
  }

  private InformationWriteGates writeGates;

  public void useWriteGates(InformationWriteGates writeGates) {
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
    access.requireWork(context);
    if (context.selection().scope() == InformationContext.Scope.SHARED)
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
    String namespace =
        context.selection().scope() == InformationContext.Scope.PROJECT
            ? "project:" + context.selection().project()
            : "account:" + context.account();
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
    return transactions.inTransaction(
        () ->
            repository.admit(
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
                    fingerprint)));
  }

  public io.aeyer.plowshare.server.agents.Outcome awaitProcessing(
      InformationContext context, UUID revision, java.util.function.BooleanSupplier cancelled) {
    while (true) {
      if (cancelled.getAsBoolean()) {
        transactions.inTransaction(
            () -> {
              requireOwner(context, revision);
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
    var resource = requireOwner(context, revision);
    String namespace =
        context.selection().scope() == InformationContext.Scope.PROJECT
            ? "project:" + context.selection().project()
            : "account:" + context.account();
    if (!namespace.equals(resource.namespace()))
      throw new CallerFault("select the resource's original namespace before revising it");
    if ("deleted".equals(row(revision).availability()))
      throw new CallerFault("a deleted resource cannot be revised");
    return resource.sourceName();
  }

  /** An indexed passage is only evidence when it can be matched back to retained source text. */
  public Information.Window locateWindow(
      InformationContext context, UUID revision, String passage) {
    requireReadable(context, revision);
    String source = row(revision).extractedText();
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
    requireReadable(context, revision);
    var span = repository.codeSpan(revision, chunk);
    if (span.isEmpty())
      return Information.Window.unmatched(revision, "code passage has no source location");
    String source = text(context, revision);
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
        row(revision).documentSubtype());
  }

  public Information.Window window(
      InformationContext context, UUID revision, int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 32768)
      throw new CallerFault(
          "read offset must be nonnegative and limit must be between 1 and 32768 characters");
    String value = text(context, revision);
    if (offset > value.length()) throw new CallerFault("read offset is beyond the retained text");
    int end = (int) Math.min((long) offset + limit, value.length());
    var row = row(revision);
    return new Information.Window(
        revision,
        null,
        null,
        offset,
        end,
        value.length(),
        value.substring(offset, end),
        row.documentType(),
        row.documentSubtype());
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
          var owner = requireOwner(context, revision);
          var shown = status(context, revision);
          var tags = new java.util.HashSet<String>();
          tags.addAll(shown.tags());
          if (shown.autoTag() != null) tags.addAll(shown.autoTag());
          if (groups != null) InformationTagGroups.from(groups, tags);
          repository.tagGroups(owner.id(), groups);
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
          var row = requireOwner(context, revision);
          repository.tags(row.id(), tags);
          event(
              revision,
              generation(revision),
              context.account(),
              "tags",
              "updated",
              io.aeyer.plowshare.server.information.InformationJson.json(tags));
        });
  }

  public List<Information.Revision> inventory(InformationContext context, int limit, int offset) {
    access.requireSelection(context);
    return repository.inventory(context, limit, offset);
  }

  public Information.Revision status(InformationContext context, UUID revision) {
    boolean readable = true;
    try {
      requireReadable(context, revision);
    } catch (NotFoundFault hidden) {
      requireOwner(context, revision);
      readable = false;
    }
    var metadata = repository.metadata(revision);
    boolean owner = context.account().equals(row(revision).ownerHandle());
    boolean visible = readable;
    var steps =
        repository.steps(revision).stream()
            .map(
                step ->
                    step.visible(
                        visible, owner, visible ? fingerprint(revision, step.stage()) : null))
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
    requireReadable(context, revision);
    String value = repository.text(revision);
    if (value == null) throw new CallerFault("this revision has not been extracted");
    return value;
  }

  /** Bounded, syntax-only outline; source/evidence continue to use the existing read API. */
  public io.aeyer.plowshare.server.documents.CodeProjection codeProjection(
      InformationContext context, UUID revision, String rawHash) {
    requireCode(context);
    return transactions.inTransaction(
        () -> repository.codeProjection(access.admitted(context), revision, rawHash));
  }

  /** Bounded, syntax-only outline; source/evidence continue to use the existing read API. */
  public Information.Outline outline(
      InformationContext context, UUID revision, int offset, int limit) {
    requireCode(context);
    return repository.outline(access.admitted(context), revision, offset, limit);
  }

  /** Literal, case-insensitive declaration-name prefix search, with permissions before LIMIT. */
  public Information.Symbols symbols(
      InformationContext context, String query, UUID revision, int offset, int limit) {
    requireCode(context);
    return repository.symbols(access.admitted(context), query, revision, offset, limit);
  }

  private void requireCode(InformationContext context) {
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
                requireOwner(context, revision);
                repository.link(context, revision, project, remove);
                return null;
              });
        });
  }

  /** Sharing is per revision and never overrides restrictions inherited from any input. */
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
                requireOwner(context, revision);
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
                requireOwner(context, revision);
                repository.availability(context, revision, state);
                return null;
              });
        });
  }

  public void retry(InformationContext context, UUID revision) {
    retry(context, revision, null);
  }

  public void retry(InformationContext context, UUID revision, UUID request) {
    managed(
        context,
        revision,
        "retry",
        request,
        new InformationCommandParameters.None(),
        () -> {
          transactions.inTransaction(
              () -> {
                requireOwner(context, revision);
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
    managed(
        context,
        revision,
        "rebuild",
        request,
        new InformationCommandParameters.Rebuild(from),
        () -> {
          int first = STAGES.indexOf(from);
          if (first < 2)
            throw new CallerFault(
                "rebuild starts at embed, summarise, summary_embed, autoTag or tagGroups; changed extraction or derivation requires a new immutable revision");
          transactions.inTransaction(
              () -> {
                requireOwner(context, revision);
                requireReadable(context, revision);
                repository.rebuild(context, revision, from);
                return null;
              });
        });
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
                requireOwner(context, revision);
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
    var resource = requireOwner(context, revision);
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
    if (safety) apply(context, command, work);
    try {
      if (command == null || !command.applied()) {
        var pre = gates.before(lease);
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
      var post = gates.after(lease);
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
                  0);
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
                requireOwner(context, revision);
                requireReadable(context, revision);
                repository.finalise(context, revision, configuration.get());
                return null;
              });
        });
  }

  public void requireReadable(InformationContext context, UUID revision) {
    access.requireSelection(context);
    if (!repository.readable(context, revision)) throw absent();
  }

  private InformationCatalogueRepository.Owner requireOwner(
      InformationContext context, UUID revision) {
    access.requireWork(context);
    var resource = repository.lockOwner(context.account(), revision);
    if (resource.projectId() != null)
      access.requireWork(
          access.resolve(
              context.account(),
              InformationAccess.projectSelection(context.account(), resource.projectName())));
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
