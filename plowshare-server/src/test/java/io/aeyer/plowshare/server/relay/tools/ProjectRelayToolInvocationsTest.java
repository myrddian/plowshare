package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Permission and uncertainty behavior uses mocked persistence; no database is required. */
class ProjectRelayToolInvocationsTest {
  private static final Instant NOW = Instant.parse("2026-10-08T01:00:00Z");
  private final RelayToolDefinition binding =
      new RelayToolDefinition(
          "fixture",
          "scanner",
          "provider",
          "network_scope",
          "Read the configured scope",
          List.of(),
          1);
  private final UsageAttribution owner =
      UsageAttribution.project("caller", "42", UsageAttribution.Operation.AGENT_CHAT)
          .withExecution(
              UsageLineage.root("conversation"),
              UsageLineage.root("run"),
              UsageLineage.NONE,
              "coordinator",
              1L,
              1L);
  private final RelayToolRepository repository = mock(RelayToolRepository.class);
  private final Relay relay = mock(Relay.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final AtomicReference<RelayToolRepository.Stored> stored = new AtomicReference<>();
  private final AtomicReference<Optional<Relay.Publication>> publication =
      new AtomicReference<>(Optional.empty());
  private ProjectRelayToolInvocations service;

  @BeforeEach
  void setup() {
    when(members.mayWork(eq("fixture"), anyString())).thenReturn(true);
    when(projects.id("fixture")).thenReturn(42L);
    when(projects.personalOwner("fixture")).thenReturn(Optional.empty());
    when(repository.ancestry(42, owner)).thenReturn(Optional.of(RelayCausation.root("job:run")));
    when(repository.submit(any()))
        .thenAnswer(
            answer -> {
              RelayToolRepository.Intent intent = answer.getArgument(0);
              if (stored.get() == null) stored.set(new RelayToolRepository.Stored(intent, null));
              else if (!stored.get().intent().fingerprint().equals(intent.fingerprint()))
                throw new IllegalArgumentException("conflict");
              return stored.get();
            });
    when(repository.finish(any(), any()))
        .thenAnswer(
            answer -> {
              var completed =
                  new RelayToolRepository.Stored(answer.getArgument(0), answer.getArgument(1));
              stored.set(completed);
              return completed;
            });
    when(repository.find(eq(42L), eq("caller"), any()))
        .thenAnswer(answer -> Optional.ofNullable(stored.get()));
    when(relay.retained(any(), any())).thenAnswer(answer -> publication.get());
    service = new ProjectRelayToolInvocations(repository, relay, members, projects, () -> NOW, 8);
  }

  private RelayToolInvocations.Outcome submit() {
    // The first false allows submission; the second true stops waiting after intent is committed.
    var before = new java.util.concurrent.atomic.AtomicBoolean(true);
    return service.invoke(
        binding,
        RelayToolCodec.arguments("{}"),
        owner,
        "model-call",
        () -> !before.getAndSet(false));
  }

  private void complete(String publisher, String project, RelayCausation causation) {
    var intent = stored.get().intent();
    var id = intent.id();
    var result =
        new RelayToolCodec.Result(
            RelayToolCodec.VERSION,
            id.toString(),
            project,
            binding.provider(),
            binding.name(),
            RelayToolCodec.State.COMPLETED,
            "retained evidence");
    publication.set(
        Optional.of(
            new Relay.Publication(
                new Relay.TopicKey(42, binding.results()),
                1,
                NOW,
                new Relay.Draft(
                    RelayToolCodec.resultRequestId(id).toString(),
                    publisher,
                    NOW,
                    id.toString(),
                    id.toString(),
                    new RelayPayload.Text(RelayToolCodec.write(result)),
                    causation))));
  }

  @Test
  void
      cancellation_after_submission_is_unknown_and_read_settles_late_result_without_resubmission() {
    var outcome = submit();
    assertEquals(RelayToolCodec.State.UNKNOWN, outcome.state());
    assertTrue(outcome.text().contains("Do not repeat"));
    assertEquals("job:run", stored.get().intent().causation().rootId());
    assertEquals(1, stored.get().intent().causation().depth());
    complete(
        RelayPort.publisher("provider"),
        "fixture",
        stored.get().intent().causation().next(outcome.id().toString(), 8));
    assertEquals(
        RelayToolCodec.State.COMPLETED, service.read("fixture", "caller", outcome.id()).state());
    publication.set(Optional.empty());
    assertEquals("retained evidence", service.read("fixture", "caller", outcome.id()).text());
    verify(repository, times(1)).submit(any());
    verify(repository, times(1)).finish(any(), any());
    verify(relay, never()).publish(any(), any());
  }

  @Test
  void no_request_is_submitted_after_cancellation_or_membership_refusal() {
    assertThrows(
        CallerFault.class,
        () -> service.invoke(binding, RelayToolCodec.arguments("{}"), owner, "call", () -> true));
    when(members.mayWork("fixture", "provider")).thenReturn(false);
    assertThrows(
        CallerFault.class,
        () -> service.invoke(binding, RelayToolCodec.arguments("{}"), owner, "call", () -> false));
    verify(repository, never()).submit(any());
  }

  @Test
  void wrong_project_and_missing_execution_identity_are_refused_before_intake() {
    assertThrows(
        CallerFault.class,
        () ->
            service.invoke(
                binding,
                RelayToolCodec.arguments("{}"),
                UsageAttribution.project("caller", "other", UsageAttribution.Operation.AGENT_CHAT),
                "call",
                () -> false));
    assertThrows(
        CallerFault.class,
        () ->
            service.invoke(
                binding,
                RelayToolCodec.arguments("{}"),
                UsageAttribution.project(
                    "caller", "fixture", UsageAttribution.Operation.AGENT_CHAT),
                "call",
                () -> false));
    verify(repository, never()).submit(any());
  }

  @Test
  void unavailable_unknown_and_exhausted_ancestry_cannot_start_a_new_root() {
    for (var ancestry :
        List.of(
            Optional.<RelayCausation>empty(),
            Optional.of(RelayCausation.unknown("old")),
            Optional.of(new RelayCausation("root", "parent", 7)))) {
      when(repository.ancestry(42, owner)).thenReturn(ancestry);
      assertThrows(
          CallerFault.class,
          () ->
              service.invoke(binding, RelayToolCodec.arguments("{}"), owner, "call", () -> false));
    }
    verify(repository, never()).submit(any());
  }

  @Test
  void forged_publisher_wrong_project_and_reset_ancestry_do_not_complete() {
    var outcome = submit();
    complete(
        RelayPort.publisher("attacker"),
        "fixture",
        stored.get().intent().causation().next(outcome.id().toString(), 8));
    assertEquals(
        RelayToolCodec.State.UNKNOWN, service.read("fixture", "caller", outcome.id()).state());
    complete(
        RelayPort.publisher("provider"),
        "other",
        stored.get().intent().causation().next(outcome.id().toString(), 8));
    assertEquals(
        RelayToolCodec.State.UNKNOWN, service.read("fixture", "caller", outcome.id()).state());
    complete(
        RelayPort.publisher("provider"), "fixture", RelayCausation.root(outcome.id().toString()));
    assertEquals(
        RelayToolCodec.State.UNKNOWN, service.read("fixture", "caller", outcome.id()).state());
    verify(repository, never()).finish(any(), any());
  }

  @Test
  void owner_and_personal_space_fences_apply_to_retained_reads() {
    var outcome = submit();
    when(repository.find(42, "other", outcome.id())).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> service.read("fixture", "other", outcome.id()));
    when(projects.personalOwner("fixture")).thenReturn(Optional.of("other"));
    assertThrows(CallerFault.class, () -> service.read("fixture", "caller", outcome.id()));
    verify(repository, never()).finish(any(), any());
  }

  @Test
  void invalid_arguments_are_refused_before_repository_calls() {
    for (String input :
        List.of(
            "{\"target\":\"example\"}",
            "{\"nested\":{}}",
            "{\"a\":1,\"a\":2}",
            "{} {}",
            "[]",
            "{\"n\":null}")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.invoke(binding, RelayToolCodec.arguments(input), owner, "call", () -> false));
    }
    verify(repository, never()).submit(any());
  }

  @Test
  void nested_catalogue_schema_and_request_keep_the_offered_fingerprint_and_retained_identity()
      throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var rows =
        json.readTree(
                java.nio.file.Files.readString(
                    java.nio.file.Path.of("../test-support/contracts/tool-schemas.json")))
            .path("cases");
    var row =
        java.util.stream.StreamSupport.stream(rows.spliterator(), false)
            .filter(item -> "prefix-provider".equals(item.path("name").textValue()))
            .findFirst()
            .orElseThrow();
    var schema =
        io.aeyer.plowshare.protocol.SchemaCodec.decode(
            json.readValue(row.path("schemaSource").textValue(), java.util.Map.class));
    var declaration =
        new io.aeyer.plowshare.server.applications.ApplicationServerSettings.Declaration(
            "network_scope", "Read nested scope", List.of(), 1, schema);
    var catalogue =
        new io.aeyer.plowshare.server.applications.ApplicationServerSettings.Catalogue(
            "plowshare-tool-catalog/2", List.of(declaration));
    var read =
        io.aeyer.plowshare.server.applications.ApplicationServerSettings.catalogue(
            json.writeValueAsString(catalogue));
    var nested =
        read.tools()
            .getFirst()
            .bind(
                "fixture",
                new io.aeyer.plowshare.server.applications.ApplicationServerSettings.Provider(
                    "scanner", "provider", "network_", 30));
    assertEquals(schema, nested.schema().parameters());
    var input = RelayToolCodec.arguments(row.path("argumentsSource").textValue());
    var outcome = service.invoke(nested, input, owner, "call", () -> stored.get() != null);
    assertEquals(RelayToolCodec.State.UNKNOWN, outcome.state());
    var request = stored.get().intent().request();
    assertEquals("plowshare-tool/2", request.schema());
    assertEquals(row.path("fingerprint").textValue(), request.schemaFingerprint());
    assertEquals(input.values(), request.arguments());
    assertEquals(request, RelayToolCodec.request(RelayToolCodec.write(request)));
    assertEquals(nested, RelayToolCodec.definition(RelayToolCodec.write(nested)));
    var original = stored.get().intent();
    var legacyRequest =
        new RelayToolCodec.Request(
            request.schema(),
            request.invocationId(),
            request.project(),
            request.provider(),
            request.tool(),
            request.account(),
            request.run(),
            request.call(),
            request.deadline(),
            request.arguments(),
            row.path("legacyFingerprint").textValue());
    var retained =
        new RelayToolRepository.Intent(
            original.id(),
            original.projectId(),
            original.fingerprint(),
            original.binding(),
            legacyRequest,
            original.occurredAt(),
            original.causation(),
            original.agent(),
            original.hosted());
    stored.set(new RelayToolRepository.Stored(retained, null));
    var resubmission = new java.util.concurrent.atomic.AtomicBoolean(true);
    var recovered =
        service.invoke(nested, input, owner, "call", () -> !resubmission.getAndSet(false));
    assertEquals(RelayToolCodec.State.UNKNOWN, recovered.state());
    assertSame(retained, stored.get().intent());
    assertEquals(legacyRequest, stored.get().intent().request());
    assertEquals(
        RelayToolCodec.State.UNKNOWN, service.read("fixture", "caller", retained.id()).state());
    assertSame(retained, stored.get().intent());
    verify(repository, times(2)).submit(any());
    verify(relay, never()).publish(any(), any());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new io.aeyer.plowshare.server.applications.ApplicationServerSettings.Catalogue(
                "plowshare-tool-catalog/1", List.of(declaration)));
  }
}
