package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.protocol.InformationInterchange.*;
import io.aeyer.plowshare.protocol.InformationInterchange.Provenance;
import io.aeyer.plowshare.protocol.InformationInterchange.Readable;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/**
 * Export uses one permission snapshot. Import prepares outside the transaction, then atomically
 * rechecks and publishes through existing write gates. No projection/model is restored or
 * scheduled.
 */
public final class PortableInformation implements InformationInterchangeService {
  private final InformationReadAccess catalogue;
  private final InformationCatalogueRepository records;
  private final InformationContentRepository content;
  private final InformationContentTypes types;
  private final InformationContentSettings settings;
  private final InformationInterchangeRepository repository;
  private final InformationNavigationRepository navigationRecords;
  private final InformationNavigationService navigation;
  private final InformationAccess access;
  private final UnitOfWork work;
  private final InformationTransitions gates;

  public PortableInformation(
      InformationReadAccess catalogue,
      InformationCatalogueRepository records,
      InformationContentRepository content,
      InformationContentTypes types,
      InformationContentSettings settings,
      InformationInterchangeRepository repository,
      InformationNavigationRepository navigationRecords,
      InformationNavigationService navigation,
      InformationAccess access,
      UnitOfWork work,
      InformationTransitions gates) {
    this.catalogue = Objects.requireNonNull(catalogue);
    this.records = Objects.requireNonNull(records);
    this.content = Objects.requireNonNull(content);
    this.types = Objects.requireNonNull(types);
    this.settings = Objects.requireNonNull(settings);
    this.repository = Objects.requireNonNull(repository);
    this.navigationRecords = Objects.requireNonNull(navigationRecords);
    this.navigation = Objects.requireNonNull(navigation);
    this.access = Objects.requireNonNull(access);
    this.work = Objects.requireNonNull(work);
    this.gates = Objects.requireNonNull(gates);
  }

  public InformationResponse execute(String account, InformationRequest request, String session) {
    var scope = request.selection().scope();
    var context =
        access
            .resolve(
                account,
                new InformationContext.Selection(
                    InformationContext.Scope.valueOf(scope.kind().toUpperCase(Locale.ROOT)),
                    scope.project(),
                    scope.includeShared() == null
                        ? !scope.kind().equals("shared")
                        : scope.includeShared()))
            .withView(true);
    if (request instanceof InformationRequest.InterchangeImport q)
      return new InformationResponse.InterchangeImported(importBundle(context, q, session));
    return work.inTransaction(
        () -> {
          navigationRecords.snapshot();
          navigationRecords.requireActive(account);
          return switch (request) {
            case InformationRequest.InterchangeCapabilities q ->
                new InformationResponse.InterchangeCapabilities(
                    new Capabilities(
                        InformationInterchange.FORMAT,
                        InformationInterchange.MAX_DOCUMENTS,
                        InformationInterchange.MAX_BYTES,
                        InformationInterchange.MAX_JSON_BYTES));
            case InformationRequest.InterchangeExport q ->
                new InformationResponse.InterchangeBundle(exportBundle(context, q.revisions()));
            case InformationRequest.InterchangeReadable q ->
                new InformationResponse.InterchangeReadable(readable(context, q.revision()));
            case InformationRequest.InterchangeOrigin q -> {
              catalogue.requireReadable(context, q.revision());
              yield new InformationResponse.InterchangeOrigin(
                  repository.origin(q.revision()).orElseThrow(PortableInformation::unavailable));
            }
            default -> throw new IllegalArgumentException("Not an interchange operation");
          };
        });
  }

  private Bundle exportBundle(InformationContext context, List<UUID> selected) {
    var visited = new LinkedHashSet<UUID>();
    var queue = new ArrayDeque<>(selected);
    while (!queue.isEmpty()) {
      UUID revision = queue.removeFirst();
      if (!visited.add(revision)) continue;
      if (visited.size() > InformationInterchange.MAX_DOCUMENTS)
        throw new CallerFault("Portable dependency closure exceeds 16 revisions");
      catalogue.requireReadable(context, revision);
      queue.addAll(repository.dependencies(revision));
    }
    var docs = new ArrayList<Document>();
    int size = 0;
    for (UUID revision : visited) {
      var descriptor = descriptor(context, revision);
      byte[] bytes = records.bytes(revision);
      if (bytes == null || bytes.length == 0)
        throw new CallerFault("Original bytes unavailable for portable export");
      size += bytes.length;
      if (size > InformationInterchange.MAX_BYTES)
        throw new CallerFault("Portable original bytes exceed 2 MiB");
      docs.add(new Document(descriptor, Base64.getEncoder().encodeToString(bytes)));
    }
    var bundle =
        new Bundle(
            InformationInterchange.FORMAT,
            UUID.randomUUID(),
            docs,
            repository.relationships(context, List.copyOf(visited)),
            InformationInterchange.COVERAGE);
    bounded(bundle);
    return bundle;
  }

  private Descriptor descriptor(InformationContext context, UUID revision) {
    catalogue.requireReadable(context, revision);
    var row = records.metadata(revision);
    if (!row.documentType().equals("document")
        || !row.availability().equals("active")
        || row.excluded()) throw unavailable();
    var evidence = new ArrayList<Evidence>();
    for (UUID id : records.citations(revision)) {
      var e = records.evidence(context, id);
      evidence.add(
          new Evidence(
              e.id(), e.revisionId(), e.startOffset(), e.endOffset(), e.quote(), e.locator()));
    }
    String media = row.mediaType();
    String path =
        "documents/"
            + row.resourceId()
            + "/"
            + row.id()
            + ((row.documentSubtype().equals("markdown")
                    || media.split(";", 2)[0].equalsIgnoreCase("text/markdown"))
                ? ".md"
                : ".bin");
    var report = records.report(revision).orElse(null);
    return new Descriptor(
        row.resourceId(),
        row.id(),
        row.ordinal(),
        row.sourceName(),
        row.title(),
        path,
        media,
        row.contentHash(),
        row.tags(),
        content.binding(revision).orElse(null),
        new Provenance(
            row.author(),
            row.kind(),
            row.createdAt().toString(),
            row.contentHash(),
            repository.dependencies(revision),
            evidence,
            repository
                .origin(revision)
                .map(
                    origin -> {
                      var foreign = origin.foreignDocument();
                      return foreign.provenance().importedFrom() != null
                          ? foreign.provenance().importedFrom()
                          : new OriginReference(
                              origin.bundle(),
                              origin.manifestHash(),
                              foreign.resource(),
                              foreign.revision(),
                              foreign.provenance().owner(),
                              foreign.provenance().sourceHash());
                    })
                .orElse(null),
            report == null ? null : report.producedBy(),
            report == null ? null : report.definitionHash()));
  }

  private Readable readable(InformationContext context, UUID revision) {
    var descriptor = descriptor(context, revision);
    String text, representation;
    if (descriptor.path().endsWith(".md")) {
      byte[] bytes = records.bytes(revision);
      if (bytes == null || bytes.length > InformationInterchange.MAX_BYTES)
        throw new CallerFault("Readable original is unavailable or exceeds bounds");
      try {
        text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
      } catch (CharacterCodingException invalid) {
        throw new CallerFault("Original Markdown is not valid UTF-8");
      }
      representation = "original_markdown";
    } else {
      text = records.text(revision);
      if (text == null || text.isBlank())
        throw new CallerFault(
            "No retained extraction; request processing explicitly or export original bytes");
      representation = "extraction";
    }
    var result =
        new Readable(
            InformationInterchange.FORMAT,
            "documents/" + descriptor.resource() + "/" + revision + ".md",
            representation,
            text,
            InformationInterchange.hash(text.getBytes(StandardCharsets.UTF_8)),
            descriptor);
    bounded(result);
    return result;
  }

  private Imported importBundle(
      InformationContext context, InformationRequest.InterchangeImport request, String session) {
    access.requireWork(context);
    settings.requireWrites();
    bounded(request.plan());
    var sources =
        new ArrayList<>(
            request.plan().destinations().stream()
                .map(Destination::expectedRevision)
                .filter(Objects::nonNull)
                .toList());
    sources.addAll(request.plan().reuse().stream().map(Reuse::localRevision).toList());
    // Completion receipts carry only local identity mappings. Even recovery rechecks current reads.
    var result =
        gates
            .executePrepared(
                context,
                request.requestId(),
                "interchange.import",
                new InformationGateIdentity.InterchangeImport(
                    InformationInterchangeFingerprint.of(request.plan())),
                sources,
                session,
                InformationGateResult.Imported.class,
                () -> {
                  validateTypes(context, request.plan());
                  return new InformationTransitions.Prepared<>(
                      () -> new InformationGateResult.Imported(commit(context, request, session)),
                      null);
                })
            .value();
    for (var mapping : result.mappings()) catalogue.requireReadable(context, mapping.revision());
    return result;
  }

  private Map<UUID, InformationContent.Contract> validateTypes(
      InformationContext context, ImportPlan plan) {
    var mapped = new HashMap<UUID, InformationContent.Contract>();
    // Stable ordering matches registry-before-resource admission and avoids opposite type locks.
    for (var mapping :
        plan.types().stream()
            .sorted(Comparator.comparing(t -> t.localVersion().toString()))
            .toList())
      mapped.put(
          mapping.foreignVersion(), types.assignable(context, mapping.localVersion(), false));
    var used = new HashSet<UUID>();
    var reused =
        plan.reuse().stream()
            .map(Reuse::foreignRevision)
            .collect(java.util.stream.Collectors.toSet());
    for (var doc : plan.bundle().documents()) {
      if (reused.contains(doc.descriptor().revision())) continue;
      var binding = doc.descriptor().contentType();
      if (binding == null) continue;
      used.add(binding.contract().version());
      var local = mapped.get(binding.contract().version());
      if (local == null || !local.definitionHash().equals(binding.contract().definitionHash()))
        throw new CallerFault("Explicit identical active local type mapping required");
    }
    if (!used.equals(mapped.keySet()))
      throw new CallerFault("Unused type mappings are not admitted");
    return mapped;
  }

  private Imported commit(
      InformationContext context, InformationRequest.InterchangeImport request, String session) {
    access.requireWork(context);
    settings.requireWrites();
    var plan = request.plan();
    // One navigation lock covers the bundle's graph; registry locks precede destination locks.
    navigationRecords.lock();
    navigationRecords.lockAccount(context.account());
    var contracts = validateTypes(context, plan);
    var mappings = new ArrayList<Mapping>();
    var byRevision = new HashMap<UUID, Mapping>();
    for (var reuse : plan.reuse()) {
      catalogue.requireReadable(context, reuse.localRevision());
      var actual = records.metadata(reuse.localRevision());
      var foreign =
          plan.bundle().documents().stream()
              .map(Document::descriptor)
              .filter(d -> d.revision().equals(reuse.foreignRevision()))
              .findFirst()
              .orElseThrow();
      if (!actual.contentHash().equals(foreign.contentHash())
          || !actual.mediaType().equals(foreign.mediaType()))
        throw new CallerFault("Reused revision bytes/media differ from portable descriptor");
      var binding = content.binding(reuse.localRevision()).orElse(null);
      if ((binding == null) != (foreign.contentType() == null)
          || binding != null
              && (!binding
                      .contract()
                      .definitionHash()
                      .equals(foreign.contentType().contract().definitionHash())
                  || !binding.metadataHash().equals(foreign.contentType().metadataHash())))
        throw new CallerFault("Reused revision metadata differs");
      var mapping =
          new Mapping(foreign.resource(), foreign.revision(), actual.resourceId(), actual.id());
      mappings.add(mapping);
      byRevision.put(foreign.revision(), mapping);
    }
    var destinations = new HashMap<UUID, Destination>();
    for (var d :
        plan.destinations().stream().sorted(Comparator.comparing(Destination::name)).toList()) {
      repository.lockDestination(context, d.name());
      var head = content.head(InformationContentTypeService.namespace(context), d.name(), true);
      if (d.mode().equals("create")
          ? head.isPresent()
          : head.isEmpty() || !head.get().revision().equals(d.expectedRevision()))
        throw new CallerFault("INFORMATION_REVISION_CONFLICT");
      if (head.isPresent()) {
        content.requireWrite(context, head.get().revision());
        catalogue.requireReadable(context, head.get().revision());
      }
      destinations.put(d.resource(), d);
    }
    var heads = new HashMap<UUID, UUID>();
    var previous = new HashMap<UUID, UUID>();
    for (var doc :
        plan.bundle().documents().stream()
            .sorted(
                Comparator.comparing((Document d) -> d.descriptor().resource().toString())
                    .thenComparingInt(d -> d.descriptor().ordinal()))
            .toList()) {
      var foreign = doc.descriptor();
      if (byRevision.containsKey(foreign.revision())) continue;
      var destination = destinations.get(foreign.resource());
      UUID expected = heads.getOrDefault(foreign.resource(), destination.expectedRevision());
      InformationContent.Binding binding = null;
      if (foreign.contentType() != null)
        binding =
            new InformationContent.Binding(
                contracts.get(foreign.contentType().contract().version()),
                foreign.contentType().metadataHash(),
                foreign.contentType().metadata());
      var destinationKind = DocumentType.classify(destination.name(), foreign.mediaType());
      var kind = DocumentType.classify(foreign.sourceName(), foreign.mediaType());
      if (destinationKind.isCode() || kind.isCode())
        throw new CallerFault("Portable interchange admits document formats only");
      var admitted =
          records.admit(
              context,
              child(request.requestId(), foreign.revision().toString()),
              new InformationCatalogueRepository.AdmissionWrite(
                  destination.name(),
                  InformationInterchange.bytes(doc.bytesBase64()),
                  foreign.mediaType(),
                  null,
                  "source",
                  List.of(),
                  session,
                  1,
                  0,
                  false,
                  kind,
                  InformationInterchangeFingerprint.of(doc),
                  binding,
                  expected,
                  true,
                  "explicit_only"));
      var mapping =
          new Mapping(
              foreign.resource(), foreign.revision(), admitted.resource(), admitted.revision());
      mappings.add(mapping);
      byRevision.put(foreign.revision(), mapping);
      heads.put(foreign.resource(), admitted.revision());
      if (expected != null) previous.put(admitted.revision(), expected);
      records.tags(context, admitted.revision(), foreign.tags());
    }
    var resourceMap = new HashMap<UUID, UUID>();
    for (var mapping : mappings) {
      UUID old = resourceMap.putIfAbsent(mapping.foreignResource(), mapping.resource());
      if (old != null && !old.equals(mapping.resource()))
        throw new CallerFault("Reused history must belong to the explicit destination resource");
    }
    String manifestHash = InformationInterchangeFingerprint.of(plan.bundle());
    for (var doc : plan.bundle().documents()) {
      var d = doc.descriptor();
      var local = byRevision.get(d.revision());
      if (plan.reuse().stream().anyMatch(r -> r.foreignRevision().equals(d.revision()))) continue;
      var dependencies = new LinkedHashSet<UUID>();
      for (UUID id : d.provenance().dependencies()) dependencies.add(byRevision.get(id).revision());
      if (previous.containsKey(local.revision())) dependencies.add(previous.get(local.revision()));
      repository.retain(
          local.revision(),
          new Origin(plan.bundle().id(), manifestHash, d),
          List.copyOf(dependencies));
    }
    for (var link : plan.restoreRelationships() ? plan.bundle().relationships() : List.<Link>of()) {
      var source =
          mappings.stream()
              .filter(m -> m.foreignResource().equals(link.source()))
              .reduce((a, b) -> b)
              .orElseThrow();
      var target =
          mappings.stream()
              .filter(m -> m.foreignResource().equals(link.target()))
              .reduce((a, b) -> b)
              .orElseThrow();
      navigation.execute(
          context.account(),
          new InformationRequest.RelationshipCreate(
              request.selection(),
              child(request.requestId(), "link:" + link),
              source.resource(),
              new InformationNavigation.Target(
                  target.resource(),
                  link.pinnedRevision() == null
                      ? null
                      : byRevision.get(link.pinnedRevision()).revision()),
              link.relation(),
              heads.get(link.source())));
    }
    access.requireWork(context);
    return new Imported(plan.bundle().id(), mappings);
  }

  private static UUID child(UUID parent, String component) {
    return UUID.nameUUIDFromBytes(
        ("interchange/1:" + parent + ":" + component).getBytes(StandardCharsets.UTF_8));
  }

  private static void bounded(Object value) {
    if (InformationJson.json(value).getBytes(StandardCharsets.UTF_8).length
        > InformationInterchange.MAX_JSON_BYTES)
      throw new CallerFault("Portable JSON exceeds 8 MiB");
  }

  private static NotFoundFault unavailable() {
    return new NotFoundFault("Portable information is unavailable in this selection");
  }
}
