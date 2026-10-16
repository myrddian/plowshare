package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Admission policy over repository and registry contracts; no transport bags or SQL enter here. */
public final class InformationContentAdmission implements InformationContentAuthority {
  private final InformationContentRepository repository;
  private final InformationContentTypes types;
  private final InformationAccess access;
  private final InformationContentSettings settings;

  public InformationContentAdmission(
      InformationContentRepository repository,
      InformationContentTypes types,
      InformationAccess access) {
    this(repository, types, access, new InformationContentSettings(true));
  }

  public InformationContentAdmission(
      InformationContentRepository repository,
      InformationContentTypes types,
      InformationAccess access,
      InformationContentSettings settings) {
    this.settings = Objects.requireNonNull(settings);
    this.repository = Objects.requireNonNull(repository);
    this.types = Objects.requireNonNull(types);
    this.access = Objects.requireNonNull(access);
  }

  @Override
  public Optional<InformationContent.Binding> resolve(
      InformationContext context, String name, UUID expected, InformationContent.Typing typing) {
    access.requireWork(context);
    if (typing != null) settings.requireWrites();
    var head = repository.head(InformationContentTypeService.namespace(context), name, false);
    if (head.isPresent() && !context.account().equals(head.get().owner()))
      throw InformationContentTypeService.absent();
    if (expected != null && (head.isEmpty() || !expected.equals(head.get().revision())))
      throw new CallerFault("INFORMATION_REVISION_CONFLICT");
    var previous = head.flatMap(value -> repository.binding(value.revision()));
    if (typing == null) {
      if (previous.isPresent() || head.filter(InformationContentRepository.Head::typed).isPresent())
        throw new CallerFault("INFORMATION_TYPING_REQUIRED");
      return Optional.empty();
    }
    if (head.isPresent() && expected == null)
      throw new CallerFault("INFORMATION_REVISION_CONFLICT");
    if (head.isPresent() && head.get().availability().equals("deleted"))
      throw InformationContentTypeService.absent();
    if (typing.mode().equals("clear")) return Optional.empty();
    if (typing.mode().equals("inherit") && previous.isEmpty()) {
      if (typing.metadata() != null)
        throw new CallerFault("Untyped inheritance cannot supply metadata");
      return Optional.empty();
    }
    var contract =
        types.assignable(
            context,
            typing.mode().equals("set")
                ? typing.version()
                : previous.orElseThrow().contract().version(),
            typing.mode().equals("inherit"));
    var metadata =
        typing.metadata() == null ? previous.orElseThrow().metadata() : typing.metadata();
    var validation = DocumentMetadata.validate(contract.schema(), metadata);
    if (validation.status() != SchemaValidation.Status.VALID)
      throw new InformationMetadataFault(validation);
    return Optional.of(
        new InformationContent.Binding(contract, SchemaCodec.fingerprint(metadata), metadata));
  }

  @Override
  public void recheck(
      InformationContext context, InformationContent.Binding binding, boolean inherit) {
    settings.requireWrites();
    var actual = types.assignable(context, binding.contract().version(), inherit);
    if (!actual.definitionHash().equals(binding.contract().definitionHash()))
      throw new CallerFault("INFORMATION_TYPE_CONFLICT");
  }
}
