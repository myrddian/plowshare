package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.Test;

class InformationContentAdmissionTest {
  @Test
  void projectWriteDoesNotBypassExistingResourceOwnerAdmission() {
    var members = mock(ProjectMembers.class);
    when(members.role("research", "author"))
        .thenReturn(Optional.of(io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR));
    var scoped = new InformationAccess(new EffectiveProjectPermissions(members));
    var project = scoped.resolve("author", InformationContext.Selection.project("research"));
    var projectAdmission = new InformationContentAdmission(repository, types, scoped);
    when(repository.head("project:research", "incident.md", false))
        .thenReturn(
            Optional.of(
                new InformationContentRepository.Head(
                    UUID.randomUUID(), revision, "other", "source", "active", true)));
    assertThrows(
        RuntimeException.class,
        () ->
            projectAdmission.resolve(
                project,
                "incident.md",
                revision,
                new InformationContent.Typing("clear", null, null)));
    verify(repository, never()).binding(any());
    verifyNoInteractions(types);
  }

  @Test
  void projectWriteDoesNotReplacePerResourceReadabilityOrOwnerFences() {
    var members = mock(ProjectMembers.class);
    var scoped = new InformationAccess(new EffectiveProjectPermissions(members));
    var catalogueRepository = mock(InformationCatalogueRepository.class);
    var catalogue =
        new InformationCatalogue(
            catalogueRepository,
            io.aeyer.plowshare.server.archive.UnitOfWork.NONE,
            scoped,
            mock(InformationLogAccess.class));
    var project =
        new InformationContext("author", InformationContext.Selection.project("research"));
    // Resource readability includes the repository's input/reference/mandatory/lifecycle policy.
    when(catalogueRepository.readable(project, revision)).thenReturn(false);
    doThrow(new io.aeyer.plowshare.server.faults.NotFoundFault("unavailable"))
        .when(catalogueRepository)
        .lockOwner("author", revision);
    for (var role : io.aeyer.plowshare.server.archive.ProjectRole.values()) {
      when(members.role("research", "author")).thenReturn(Optional.of(role));
      assertThrows(
          io.aeyer.plowshare.server.faults.NotFoundFault.class,
          () -> catalogue.bytes(project, revision));
      assertThrows(
          RuntimeException.class, () -> catalogue.availability(project, revision, "deleted"));
    }
    verify(catalogueRepository, times(2)).lockOwner("author", revision);
    verify(catalogueRepository, never()).bytes(any());
    verify(catalogueRepository, never()).availability(any(), any(), any());
  }

  private final InformationContentRepository repository = mock(InformationContentRepository.class);
  private final InformationContentTypes types = mock(InformationContentTypes.class);
  private final InformationAccess access =
      new InformationAccess(new EffectiveProjectPermissions(mock(ProjectMembers.class)));
  private final InformationContentAuthority admission =
      new InformationContentAdmission(repository, types, access);
  private final InformationContext own =
      new InformationContext("author", InformationContext.Selection.personal()).withView(true);
  private final UUID revision = UUID.randomUUID();

  private InformationContent.Contract contract() {
    var schema =
        SchemaDefinition.fromWire(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("severity", Map.of("type", "string")),
                "required",
                List.of("severity"),
                "additionalProperties",
                false));
    return new InformationContent.Contract(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        "incident",
        "Incident",
        "",
        DocumentMetadata.PROFILE,
        SchemaCodec.documentFingerprint(schema, DocumentMetadata.PROFILE, DocumentMetadata.empty()),
        schema,
        DocumentMetadata.empty());
  }

  private void head(String owner, boolean typed) {
    when(repository.head("account:author", "incident.md", false))
        .thenReturn(
            Optional.of(
                new InformationContentRepository.Head(
                    UUID.randomUUID(), revision, owner, "source", "active", typed)));
  }

  @Test
  void legacy_typed_and_redacted_heads_refuse_before_schema_or_processing_work() {
    head("author", true);
    when(repository.binding(revision)).thenReturn(Optional.empty());
    assertEquals(
        "INFORMATION_TYPING_REQUIRED",
        assertThrows(CallerFault.class, () -> admission.resolve(own, "incident.md", null, null))
            .getMessage());
    verifyNoInteractions(types);
  }

  @Test
  void guessed_foreign_or_stale_head_never_resolves_a_contract() {
    head("other", true);
    assertThrows(
        RuntimeException.class,
        () ->
            admission.resolve(
                own, "incident.md", revision, new InformationContent.Typing("clear", null, null)));
    head("author", true);
    assertEquals(
        "INFORMATION_REVISION_CONFLICT",
        assertThrows(
                CallerFault.class,
                () ->
                    admission.resolve(
                        own,
                        "incident.md",
                        UUID.randomUUID(),
                        new InformationContent.Typing("clear", null, null)))
            .getMessage());
    verifyNoInteractions(types);
  }

  @Test
  void invalid_metadata_has_private_bounded_diagnostics_and_no_binding() {
    head("author", false);
    var contract = contract();
    when(types.assignable(own, contract.version(), false)).thenReturn(contract);
    var invalid =
        assertThrows(
            InformationMetadataFault.class,
            () ->
                admission.resolve(
                    own,
                    "incident.md",
                    revision,
                    new InformationContent.Typing(
                        "set", contract.version(), DocumentMetadata.empty())));
    assertEquals(SchemaValidation.Status.INVALID, invalid.validation().status());
    assertTrue(invalid.validation().issues().size() <= 32);
    verify(repository, never()).attach(any(), any());
  }

  @Test
  void inheritance_pins_the_exact_published_version_and_complete_metadata() {
    head("author", true);
    var contract = contract();
    var metadata = DocumentMetadata.instance(SchemaValue.fromWire(Map.of("severity", "high")));
    var old = new InformationContent.Binding(contract, SchemaCodec.fingerprint(metadata), metadata);
    when(repository.binding(revision)).thenReturn(Optional.of(old));
    when(types.assignable(own, contract.version(), true)).thenReturn(contract);
    assertEquals(
        old,
        admission
            .resolve(
                own, "incident.md", revision, new InformationContent.Typing("inherit", null, null))
            .orElseThrow());
    assertTrue(
        admission
            .resolve(
                own, "incident.md", revision, new InformationContent.Typing("clear", null, null))
            .isEmpty());
    verify(types).assignable(own, contract.version(), true);
  }

  @Test
  void rollback_disables_typed_changes_without_removing_legacy_guard() {
    var disabled =
        new InformationContentAdmission(
            repository, types, access, new InformationContentSettings(false));
    assertThrows(
        CallerFault.class,
        () ->
            disabled.resolve(
                own, "incident.md", revision, new InformationContent.Typing("clear", null, null)));
    verifyNoInteractions(repository, types);
    head("author", true);
    assertThrows(CallerFault.class, () -> disabled.resolve(own, "incident.md", null, null));
  }

  @Test
  void compatibility_requires_complete_ready_prefix_and_current_configuration() {
    String fingerprint = "a".repeat(64);
    var configuration =
        InformationCatalogue.STAGES.stream()
            .collect(java.util.stream.Collectors.toMap(stage -> stage, stage -> fingerprint));
    var all =
        InformationCatalogue.STAGES.stream()
            .map(stage -> new InformationProjectionReuse.Stage(stage, "ready", fingerprint, true))
            .toList();
    assertEquals(
        InformationCatalogue.STAGES.subList(0, 5),
        InformationProjectionReuse.select(all, configuration));
    var incomplete = new ArrayList<>(all);
    incomplete.set(2, new InformationProjectionReuse.Stage("embed", "ready", fingerprint, false));
    assertEquals(
        List.of("extract", "derive"), InformationProjectionReuse.select(incomplete, configuration));
    var changed = new HashMap<>(configuration);
    changed.put("derive", "b".repeat(64));
    assertEquals(List.of("extract"), InformationProjectionReuse.select(all, changed));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InformationProjectionReuse.select(
                List.of(all.getFirst(), all.getFirst()), configuration));
  }

  @Test
  void completed_receipts_return_before_preparing_a_new_candidate_or_running_hooks() {
    var receipts = mock(InformationGateRepository.class);
    var hooks = mock(io.aeyer.plowshare.server.hooks.Hooks.class);
    var harness = mock(io.aeyer.plowshare.server.harness.Harness.class);
    var work =
        new io.aeyer.plowshare.server.archive.UnitOfWork() {
          public <T> T inTransaction(java.util.function.Supplier<T> operation) {
            return operation.get();
          }
        };
    var result = new InformationCatalogue.Admission(UUID.randomUUID(), UUID.randomUUID(), true);
    UUID request = UUID.randomUUID();
    when(receipts.claim(
            eq("author"),
            eq(request),
            anyString(),
            eq("content.revise"),
            eq(InformationCatalogue.Admission.class)))
        .thenReturn(
            new InformationGateRepository.Receipt<>(
                UUID.randomUUID(), InformationGateRepository.State.COMPLETED, result, null));
    var gates =
        new InformationWriteGates(
            receipts,
            work,
            access,
            mock(InformationJobs.class),
            mock(io.aeyer.plowshare.server.archive.ConversationStore.class),
            io.aeyer.plowshare.server.agents.LogStages.NONE,
            hooks,
            harness);
    assertEquals(
        result,
        gates.executePrepared(
            own,
            request,
            "content.revise",
            new InformationGateIdentity.MetadataRevision(
                revision, new InformationContent.Typing("clear", null, null)),
            List.of(revision),
            null,
            InformationCatalogue.Admission.class,
            () -> {
              throw new AssertionError(
                  "completed receipt must precede schema/head/candidate revalidation");
            }));
    verifyNoInteractions(hooks, harness);
  }

  @Test
  void project_drafts_require_management_and_model_reads_never_expose_drafts() {
    var members = mock(ProjectMembers.class);
    var registry = mock(InformationContentTypeRepository.class);
    var scoped = new InformationAccess(new EffectiveProjectPermissions(members));
    var work =
        new io.aeyer.plowshare.server.archive.UnitOfWork() {
          public <T> T inTransaction(java.util.function.Supplier<T> operation) {
            return operation.get();
          }
        };
    var service =
        new InformationContentTypeService(
            registry,
            scoped,
            members,
            mock(InformationReadAccess.class),
            work,
            mock(InformationTransitions.class));
    var project =
        new InformationContext("author", InformationContext.Selection.project("research"))
            .withView(true);
    when(members.role("research", "author"))
        .thenReturn(Optional.of(io.aeyer.plowshare.server.archive.ProjectRole.VIEWER));
    doThrow(new CallerFault("MANAGER required"))
        .when(members)
        .requireRole("research", "author", io.aeyer.plowshare.server.archive.ProjectRole.MANAGER);
    assertThrows(CallerFault.class, () -> service.list(project, true, 10, 0));
    verifyNoInteractions(registry);
    var contract = contract();
    when(registry.version("account:author", contract.version(), false))
        .thenReturn(
            Optional.of(
                new InformationContent.Version(contract, "draft", 2, "2026-10-15T00:00:00Z")));
    assertEquals(contract, service.get(own, contract.version(), null));
    assertThrows(
        io.aeyer.plowshare.server.faults.NotFoundFault.class,
        () -> service.published(own, contract.version(), null));
  }
}
