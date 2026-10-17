package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.*;

/**
 * Pure authority/validation orchestration; registry SQL is confined to its specialist repository.
 */
public final class InformationContentTypeService implements InformationContentTypes {
  private final InformationContentTypeRepository repository;
  private final InformationAccess access;
  private final ProjectMembers members;
  private final InformationReadAccess reads;
  private final UnitOfWork work;
  private final InformationTransitions gates;
  private final InformationContentSettings settings;

  public InformationContentTypeService(
      InformationContentTypeRepository repository,
      InformationAccess access,
      ProjectMembers members,
      InformationReadAccess reads,
      UnitOfWork work,
      InformationTransitions gates) {
    this(repository, access, members, reads, work, gates, new InformationContentSettings(true));
  }

  public InformationContentTypeService(
      InformationContentTypeRepository repository,
      InformationAccess access,
      ProjectMembers members,
      InformationReadAccess reads,
      UnitOfWork work,
      InformationTransitions gates,
      InformationContentSettings settings) {
    this.settings = Objects.requireNonNull(settings);
    this.repository = Objects.requireNonNull(repository);
    this.access = Objects.requireNonNull(access);
    this.members = Objects.requireNonNull(members);
    this.reads = Objects.requireNonNull(reads);
    this.work = Objects.requireNonNull(work);
    this.gates = Objects.requireNonNull(gates);
  }

  static String namespace(InformationContext context) {
    if (context.selection().scope() == InformationContext.Scope.SHARED
        || context.selection().scope() == InformationContext.Scope.ACCOUNT) throw absent();
    return context.selection().scope() == InformationContext.Scope.PROJECT
        ? "project:" + context.selection().project()
        : "account:" + context.account();
  }

  private void manage(InformationContext context) {
    access.requireSelection(context);
    if (context.selection().scope() == InformationContext.Scope.PROJECT)
      members.requireRole(context.selection().project(), context.account(), ProjectRole.MANAGER);
    else if (context.selection().scope() != InformationContext.Scope.PERSONAL) throw absent();
  }

  private boolean manager(InformationContext context) {
    return context.selection().scope() == InformationContext.Scope.PERSONAL
        || context.selection().scope() == InformationContext.Scope.PROJECT
            && members
                .role(context.selection().project(), context.account())
                .filter(role -> role.allows(ProjectRole.MANAGER))
                .isPresent();
  }

  static NotFoundFault absent() {
    return new NotFoundFault("Content type is unavailable");
  }

  @Override
  public InformationContent.Versions list(
      InformationContext context, boolean drafts, int limit, int offset) {
    access.requireSelection(context);
    if (drafts) manage(context);
    return repository.list(namespace(context), drafts, limit, offset);
  }

  @Override
  public InformationContent.Contract get(
      InformationContext context, UUID version, UUID attachedRevision) {
    access.requireSelection(context);
    if (attachedRevision != null) {
      reads.requireReadable(context, attachedRevision);
      return repository
          .attached(attachedRevision, version)
          .orElseThrow(InformationContentTypeService::absent);
    }
    var record =
        repository
            .version(namespace(context), version, false)
            .orElseThrow(InformationContentTypeService::absent);
    if (record.state().equals("draft") && !manager(context)) throw absent();
    return record.contract();
  }

  @Override
  public InformationContent.Contract published(
      InformationContext context, UUID version, UUID attachedRevision) {
    if (attachedRevision != null) return get(context, version, attachedRevision);
    access.requireSelection(context);
    var record =
        repository
            .version(namespace(context), version, false)
            .orElseThrow(InformationContentTypeService::absent);
    if (record.state().equals("draft")) throw absent();
    return record.contract();
  }

  @Override
  public InformationContent.Version create(
      InformationContext context,
      UUID request,
      UUID type,
      Long expected,
      String slug,
      String label,
      String description,
      SchemaDefinition schema,
      SchemaValue.ObjectValue presentation,
      String session) {
    manage(context);
    settings.requireWrites();
    if (type == null && description == null) description = "";
    final String retainedDescription = description;
    if (type == null) {
      InformationContent.slug(slug);
      InformationContent.text(label, "label", 120, true);
      InformationContent.text(description, "description", 4096, false);
      if (expected != null)
        throw new IllegalArgumentException("New type cannot name expected control revision");
    } else if (expected == null
        || expected < 1
        || slug != null
        || label != null
        || description != null)
      throw new IllegalArgumentException(
          "Existing type needs expected control revision and no replacement identity");
    DocumentMetadata.definition(schema);
    var shown =
        DocumentMetadata.presentation(
            schema, presentation == null ? DocumentMetadata.empty() : presentation);
    return gates
        .execute(
            context,
            request,
            "types.version.create",
            new InformationGateIdentity.ContentTypeDraft(
                type, expected, slug, label, retainedDescription, schema, shown),
            List.of(),
            session,
            InformationGateResult.ContentTypeVersion.class,
            () ->
                work.inTransaction(
                    () -> {
                      manage(context);
                      settings.requireWrites();
                      return new InformationGateResult.ContentTypeVersion(
                          repository.create(
                              namespace(context),
                              context.account(),
                              type,
                              expected,
                              slug,
                              label,
                              retainedDescription,
                              schema,
                              shown));
                    }))
        .value();
  }

  @Override
  public InformationContent.Version transition(
      InformationContext context,
      UUID request,
      UUID version,
      long expected,
      boolean activate,
      String session) {
    manage(context);
    settings.requireWrites();
    return gates
        .execute(
            context,
            request,
            activate ? "types.activate" : "types.deprecate",
            new InformationGateIdentity.ContentTypeLifecycle(version, expected, activate),
            List.of(),
            session,
            InformationGateResult.ContentTypeVersion.class,
            () ->
                work.inTransaction(
                    () -> {
                      manage(context);
                      settings.requireWrites();
                      return new InformationGateResult.ContentTypeVersion(
                          repository.transition(namespace(context), version, expected, activate));
                    }))
        .value();
  }

  @Override
  public SchemaValidation.Result validate(
      InformationContext context,
      UUID version,
      UUID attachedRevision,
      SchemaValue.ObjectValue metadata) {
    return DocumentMetadata.validate(get(context, version, attachedRevision).schema(), metadata);
  }

  @Override
  public InformationContent.Contract assignable(
      InformationContext context, UUID version, boolean inherit) {
    access.requireWork(context);
    return work.inTransaction(
        () -> {
          var record =
              repository
                  .version(namespace(context), version, true)
                  .orElseThrow(InformationContentTypeService::absent);
          if (!record.state().equals("active") && !(inherit && record.state().equals("deprecated")))
            throw absent();
          return record.contract();
        });
  }
}
