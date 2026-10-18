package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationNavigation.*;
import io.aeyer.plowshare.protocol.InformationNavigation.Readable;
import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.protocol.InformationRequest.*;
import io.aeyer.plowshare.protocol.InformationResponse;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.*;
import java.util.function.Supplier;

/** Policy writes are human administration, never model calls or implicit document processing. */
public final class DocumentNavigation implements InformationNavigationService {
  private final InformationNavigationRepository repository;
  private final InformationAccess access;
  private final UnitOfWork work;
  private final boolean writesEnabled;

  public DocumentNavigation(
      InformationNavigationRepository repository,
      InformationAccess access,
      UnitOfWork work,
      boolean writesEnabled) {
    this.repository = Objects.requireNonNull(repository);
    this.access = Objects.requireNonNull(access);
    this.work = Objects.requireNonNull(work);
    this.writesEnabled = writesEnabled;
  }

  private InformationContext context(String account, InformationRequest.Selection selection) {
    var scope = selection.scope();
    var c =
        access
            .resolve(
                account,
                new InformationContext.Selection(
                    InformationContext.Scope.valueOf(scope.kind().toUpperCase(Locale.ROOT)),
                    scope.project(),
                    scope.includeGlobal() == null
                        ? !scope.kind().equals("global_read")
                        : scope.includeGlobal()))
            .withView(true);
    var f = selection.filter();
    if (f != null)
      c =
          c.withFacets(
              new InformationFacets(
                  f.kind(),
                  f.tags(),
                  f.autoTag(),
                  f.tagGroup(),
                  f.author(),
                  f.documentAuthor(),
                  f.when(),
                  f.subtype(),
                  f.search(),
                  f.contentType(),
                  f.contentTypeVersion(),
                  f.untyped()));
    return c;
  }

  private static int limit(Integer n) {
    return n == null ? 50 : n;
  }

  private static int offset(Integer n) {
    return n == null ? 0 : n;
  }

  public InformationResponse execute(
      String account,
      InformationRequest request,
      io.aeyer.plowshare.server.access.KnowledgeReadScope readBoundary) {
    Objects.requireNonNull(readBoundary);
    return new DocumentNavigation(repository, access.within(readBoundary), work, writesEnabled)
        .execute(account, request);
  }

  public InformationResponse execute(String account, InformationRequest request) {
    Objects.requireNonNull(account);
    Objects.requireNonNull(request);
    return switch (request) {
      case NavigationCapabilities q -> {
        context(account, q.selection());
        yield new InformationResponse.NavigationCapabilities(
            new Capabilities(
                io.aeyer.plowshare.protocol.InformationNavigation.CAPABILITY,
                writesEnabled,
                32,
                1024));
      }
      case DocumentList q ->
          read(
              account,
              () ->
                  new InformationResponse.NavigationDocuments(
                      repository.documents(
                          context(account, q.selection()),
                          q.relatedTo(),
                          q.relation(),
                          q.direction(),
                          limit(q.limit()),
                          offset(q.offset()))));
      case DocumentFacets q ->
          read(
              account,
              () ->
                  new InformationResponse.Facets(
                      repository.facets(
                          context(account, q.selection()),
                          q.relatedTo(),
                          q.relation(),
                          q.direction())));
      case DocumentGet q ->
          read(
              account,
              () ->
                  new InformationResponse.NavigationSelection(
                      repository.select(context(account, q.selection()), q.target())));
      case RelationshipList q ->
          read(
              account,
              () ->
                  new InformationResponse.NavigationConnections(
                      repository.relationships(
                          context(account, q.selection()),
                          q.resource(),
                          q.direction(),
                          q.relation(),
                          q.otherResource(),
                          limit(q.limit()),
                          offset(q.offset()))));
      case NavigationPolicyGet q ->
          read(
              account,
              () -> {
                requirePolicyRead(account, q.selection(), q.target());
                return new InformationResponse.NavigationPolicy(
                    repository.policy(account, q.target()));
              });
      case NavigationViews q ->
          read(
              account,
              () -> {
                context(account, q.selection());
                return new InformationResponse.NavigationViews(repository.views(account));
              });
      case NavigationViewGet q ->
          read(
              account,
              () -> {
                context(account, q.selection());
                var value = repository.view(account, q.id());
                requirePath(account, value.path());
                return new InformationResponse.NavigationView(value);
              });
      case NavigationPolicySet q ->
          mutate(
              account,
              q,
              q.requestId(),
              () -> {
                repository.requireManager(account, q.target());
                return Optional.of(
                    new InformationNavigationRepository.Receipt(
                        repository.policy(account, q.target()), null, null, null));
              },
              () ->
                  new InformationNavigationRepository.Receipt(
                      repository.setPolicy(account, q.target(), q.expectedVersion(), q.settings()),
                      null,
                      null,
                      null),
              q.target().toString(),
              q.reason());
      case RelationshipCreate q ->
          mutate(
              account,
              q,
              q.requestId(),
              () -> {
                access.requireWork(context(account, q.selection()));
                return Optional.empty();
              },
              () ->
                  new InformationNavigationRepository.Receipt(
                      null,
                      repository.create(
                          context(account, q.selection()),
                          UUID.randomUUID(),
                          q.source(),
                          q.target(),
                          q.relation(),
                          q.expectedSourceRevision()),
                      null,
                      null),
              q.source().toString(),
              "Create document relationship");
      case RelationshipRemove q ->
          mutate(
              account,
              q,
              q.requestId(),
              () -> {
                access.requireWork(context(account, q.selection()));
                return Optional.empty();
              },
              () ->
                  new InformationNavigationRepository.Receipt(
                      null,
                      null,
                      null,
                      repository.remove(
                          context(account, q.selection()), q.relationship(), q.expectedVersion())),
              q.relationship().toString(),
              "Remove navigation; retain access obligations");
      case NavigationViewSave q ->
          mutate(
              account,
              q,
              q.requestId(),
              () -> {
                context(account, q.selection());
                requirePath(account, q.path());
                return Optional.empty();
              },
              () ->
                  new InformationNavigationRepository.Receipt(
                      null,
                      null,
                      repository.save(account, q.id(), q.expectedVersion(), q.label(), q.path()),
                      null),
              q.id().toString(),
              "Save account navigation");
      case NavigationViewDelete q ->
          mutate(
              account,
              q,
              q.requestId(),
              () -> {
                context(account, q.selection());
                return Optional.empty();
              },
              () ->
                  new InformationNavigationRepository.Receipt(
                      null, null, null, repository.delete(account, q.id(), q.expectedVersion())),
              q.id().toString(),
              "Delete account navigation");
      default -> throw new IllegalArgumentException("not a document navigation command");
    };
  }

  private void requirePath(String account, Path path) {
    var step = (ScopeStep) path.steps().getFirst();
    context(account, new InformationRequest.Selection(step.scope(), "documents", null));
  }

  private void requirePolicyRead(
      String account, InformationRequest.Selection selected, PolicyTarget target) {
    if (repository.canManage(account, target)) return;
    var c = context(account, selected);
    if (target.kind().equals("document")
        && repository.select(c, new Target(target.resource(), null)) instanceof Readable) return;
    if (target.kind().equals("project") && target.project().equals(c.selection().project())) return;
    throw new NotFoundFault("information is unavailable in this selection");
  }

  private InformationResponse read(String account, Supplier<InformationResponse> action) {
    return work.inTransaction(
        () -> {
          repository.snapshot();
          repository.requireActive(account);
          return action.get();
        });
  }

  private InformationResponse mutate(
      String account,
      InformationRequest request,
      UUID requestId,
      Supplier<Optional<InformationNavigationRepository.Receipt>> authorise,
      Supplier<InformationNavigationRepository.Receipt> action,
      String subject,
      String reason) {
    if (!writesEnabled) throw new CallerFault("document-navigation-writes-disabled");
    return work.inTransaction(
        () -> {
          repository.lock();
          repository.lockAccount(account);
          var previous = authorise.get();
          String fingerprint = repository.fingerprint(request);
          var old = repository.receipt(account, requestId);
          if (old.isPresent()) {
            if (!old.get().fingerprint().equals(fingerprint))
              throw new CallerFault("request-conflict: request ID already has another payload");
            if (old.get().result().relationship() != null)
              repository.requireReceiptReadable(
                  context(account, request.selection()), old.get().result());
            return response(old.get().result());
          }
          var result = action.get();
          authorise.get();
          repository.record(
              account,
              requestId,
              fingerprint,
              result,
              request.operation(),
              subject,
              reason,
              previous);
          return response(result);
        });
  }

  private static InformationResponse response(InformationNavigationRepository.Receipt result) {
    if (result.policy() != null) return new InformationResponse.NavigationPolicy(result.policy());
    if (result.relationship() != null)
      return new InformationResponse.NavigationRelationship(result.relationship());
    if (result.view() != null) return new InformationResponse.NavigationView(result.view());
    return new InformationResponse.NavigationChanged(result.changed());
  }
}
