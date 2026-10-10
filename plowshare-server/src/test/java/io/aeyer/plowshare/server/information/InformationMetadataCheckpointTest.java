package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.BoundTools;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.hooks.Hooks;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Paid output recovery exercises the stage contract with mocked repositories, never SQL. */
class InformationMetadataCheckpointTest {
  @Test
  void fenced_paid_metadata_can_be_reused_without_another_model_call_or_receipt() {
    UUID revision = UUID.randomUUID();
    var repository = mock(InformationStageRepository.class);
    var catalogue = mock(InformationCatalogue.class);
    var inputs = mock(InformationJobs.class);
    var stored = new AtomicReference<Outcome>();
    when(repository.resource(revision)).thenReturn(UUID.randomUUID());
    when(repository.paid(eq(revision), eq(1L), anyString(), eq("test-account")))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    doAnswer(
            invocation -> {
              stored.set(invocation.getArgument(5, Outcome.class));
              return null;
            })
        .when(repository)
        .remember(
            eq(revision),
            eq(1L),
            anyString(),
            eq("information_tagger"),
            eq("test-account"),
            any(Outcome.class),
            eq("test-log"));
    var transactions =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> work) {
            return work.get();
          }
        };
    var stage =
        new InformationModelStages(
                repository, transactions, catalogue, inputs, Hooks.NONE, Harness.NONE)
            .forLease(
                new InformationLifecycle.Lease(
                    revision,
                    UUID.randomUUID(),
                    1,
                    "autoTag",
                    1,
                    UUID.randomUUID(),
                    "test-account",
                    null),
                () -> {});
    var definition =
        AgentRegistry.of(Path.of("src/main/resources/agents"), BoundTools.boundByThisServer())
            .get("information_tagger");
    String response =
        "```json\n{\"autoTag\":[\"tcp probes\"],\"tagGroups\":{\"security\":[\"tcp probes\"]},\"documentAuthor\":null,\"documentOrganisation\":null}\n```";
    var calls = new AtomicInteger();
    java.util.function.Function<String, Outcome> model =
        task -> {
          calls.incrementAndGet();
          return new Outcome(Outcome.Ending.ANSWERED, response, 1, 1, "");
        };
    var first =
        stage.run(
            definition,
            "Retained network observations",
            "test-log",
            Home.of("test-project"),
            "test-account",
            model);
    assertEquals(1, first.modelCalls());
    assertEquals(
        List.of("tcp probes"),
        InformationMetadataCodec.read(first.text(), "Retained network observations").tags());
    var resumed =
        stage.run(
            definition,
            "Retained network observations",
            "test-log",
            Home.of("test-project"),
            "test-account",
            task -> {
              fail("A paid tagging answer must not dispatch a new model call");
              return null;
            });
    assertEquals(response, resumed.text());
    assertEquals(0, resumed.modelCalls());
    assertEquals(1, calls.get());
    assertEquals(
        List.of("tcp probes"),
        InformationMetadataCodec.read(resumed.text(), "Retained network observations").tags());
    verify(repository, times(1))
        .remember(
            eq(revision),
            eq(1L),
            anyString(),
            eq("information_tagger"),
            eq("test-account"),
            any(Outcome.class),
            eq("test-log"));
    verify(inputs, times(4)).requireLog("test-log", "test-account");
  }
}
