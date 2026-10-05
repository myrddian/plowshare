package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemDocumentModelsTest {
  @Test
  void metadata_worker_carries_system_parent_but_preserves_admission_owner() {
    var definition = definition(List.of(), List.of());
    var registry = mock(AgentRegistry.class);
    when(registry.names()).thenReturn(java.util.Set.of("information_tag_grouper"));
    when(registry.get("information_tag_grouper")).thenReturn(definition);
    var logs = mock(Compaction.class);
    when(logs.accountingEnabled()).thenReturn(true);
    when(logs.logFor(
            eq(Origin.DELEGATION),
            any(),
            eq(definition),
            eq("pipeline"),
            isNull(),
            any(),
            eq("alice")))
        .thenReturn(Transcript.NONE);
    var system =
        UsageAttribution.system(null, UsageAttribution.Operation.DOCUMENT_SUMMARY)
            .withExecution(
                UsageLineage.root("pipeline"),
                UsageLineage.root("run:pipeline:1"),
                UsageLineage.NONE,
                "document_pipeline",
                1L,
                null);
    var owners = mock(UsageOwners.class);
    when(owners.processing("pipeline", UsageAttribution.Operation.DOCUMENT_SUMMARY))
        .thenReturn(system);
    when(logs.own(any(), any(), eq(definition), eq("alice")))
        .thenAnswer(
            call -> {
              Transcript transcript = call.getArgument(1);
              assertEquals(system, transcript.parentUsage());
              return new AttributedTranscript(transcript, system, system);
            });
    var runtime = mock(JobRuntime.class);
    when(runtime.run(eq(definition), anyString(), any(), any(), any(), isNull(), any(), any()))
        .thenReturn(new Outcome(Outcome.Ending.ANSWERED, "{\"databases\":[\"sql\"]}", 1, 1, ""));
    var summariser = new Summariser(mock(DocumentStore.class), runtime, () -> registry, 2, logs);
    summariser.useUsageOwners(owners);
    assertEquals(
        java.util.Map.of("databases", List.of("sql")),
        summariser
            .forRevision(mock(DocumentStore.class), Home.global(), "alice", "pipeline")
            .tagGroups("Existing tags: sql", List.of("sql"), Budget.of(2), () -> false));
    verify(runtime)
        .run(
            eq(definition),
            anyString(),
            any(),
            any(),
            any(),
            isNull(),
            any(),
            argThat(transcript -> transcript.usage().status() == UsageAttribution.Status.SYSTEM));
  }

  @Test
  void system_models_cannot_gain_tools_or_delegate_from_configuration() {
    SystemModelTasks.requireModelOnly(definition(List.of(), List.of()));
    assertThrows(
        IllegalStateException.class,
        () -> SystemModelTasks.requireModelOnly(definition(List.of("file_read"), List.of())));
    assertThrows(
        IllegalStateException.class,
        () -> SystemModelTasks.requireModelOnly(definition(List.of(), List.of("worker"))));
  }

  private static AgentDefinition definition(List<String> tools, List<String> calls) {
    return new AgentDefinition(
        "information_tag_grouper",
        "group tags",
        "fast",
        tools,
        calls,
        List.of(),
        1,
        1,
        "Group supplied tags.");
  }
}
