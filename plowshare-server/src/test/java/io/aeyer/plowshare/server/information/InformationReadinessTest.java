package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class InformationReadinessTest {
  private final InformationCatalogue catalogue = mock(InformationCatalogue.class);
  private final InformationContext context =
      new InformationContext("alice", InformationContext.Selection.personal());
  private static final UUID REVISION = UUID.fromString("bb956c1f-5d8b-5db3-bf57-eed60b547544");
  private static final UUID TICKET = UUID.fromString("f50c21c4-a3be-5b3e-9162-5138c4e5c30f");

  private AcquisitionRepository.Status acquisition(String state, UUID revision, String error) {
    return new AcquisitionRepository.Status(
        TICKET,
        "https://example.test/source",
        "fixture",
        "documents",
        state,
        revision,
        0,
        error,
        12,
        java.time.Instant.EPOCH,
        null,
        null);
  }

  private List<InformationReadiness.Source> revisions() {
    return List.of(new InformationReadiness.Source(REVISION, null));
  }

  private io.aeyer.plowshare.protocol.Information.Revision status(String state) {
    return snapshot(
        1,
        List.of(
            new io.aeyer.plowshare.protocol.Information.Step(
                "extract", state, 0, null, 1, null, null, null, null),
            new io.aeyer.plowshare.protocol.Information.Step(
                "summarise", "pending", 0, null, 1, null, null, null, null)));
  }

  private io.aeyer.plowshare.protocol.Information.Revision snapshot(
      long generation, List<io.aeyer.plowshare.protocol.Information.Step> steps) {
    return new io.aeyer.plowshare.protocol.Information.Revision(
        REVISION,
        REVISION,
        1,
        "fixture",
        "text/plain",
        "document",
        "text",
        null,
        null,
        null,
        10L,
        java.time.Instant.EPOCH,
        "active",
        false,
        generation,
        null,
        12,
        0,
        "fixture",
        "source",
        "alice",
        List.of(),
        List.of(),
        false,
        null,
        null,
        Map.of(),
        "automatic",
        null,
        true,
        steps,
        null,
        List.of(),
        List.of(),
        null,
        null);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> first(Map<String, Object> fence) {
    return ((List<Map<String, Object>>) fence.get("outcomes")).getFirst();
  }

  @Test
  void the_production_fetched_but_pending_revision_does_not_release_the_fence() throws Exception {
    var receipt =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(
                Files.readString(Path.of("src/test/resources/scripted/extraction-pending.json")),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    when(catalogue.status(context, REVISION))
        .thenReturn(
            snapshot(
                ((Number) receipt.get("generation")).longValue(),
                ((List<?>) receipt.get("steps"))
                    .stream()
                        .map(
                            row ->
                                json.convertValue(
                                    row, io.aeyer.plowshare.protocol.Information.Step.class))
                        .toList()));
    var fence =
        InformationFixtures.view(InformationReadiness.await(catalogue, context, revisions(), 0));
    assertEquals(false, fence.get("complete"));
    assertEquals(1, fence.get("pending"));
    assertEquals(0, fence.get("ready"));
    assertEquals("pending", first(fence).get("extraction_state"));
    assertEquals(0, first(fence).get("attempt"));
  }

  @Test
  void successful_fetch_receipts_are_pending_until_extraction_is_ready() {
    when(catalogue.acquisitionStatus(context, TICKET))
        .thenReturn(acquisition("succeeded", REVISION, null));
    when(catalogue.status(context, REVISION))
        .thenReturn(status("pending"))
        .thenReturn(status("ready"));
    var sources = List.of(new InformationReadiness.Source(null, TICKET));
    assertEquals(
        false,
        InformationFixtures.view(InformationReadiness.await(catalogue, context, sources, 0))
            .get("complete"));
    var fence =
        InformationFixtures.view(InformationReadiness.await(catalogue, context, sources, 0));
    assertEquals(true, fence.get("complete"));
    assertEquals(1, fence.get("ready"));
    assertEquals(REVISION, first(fence).get("revision"));
  }

  @Test
  void native_wait_observes_later_completion_without_waiting_for_summary_or_embedding() {
    when(catalogue.status(context, REVISION))
        .thenReturn(status("pending"))
        .thenReturn(status("ready"));
    var fence =
        InformationFixtures.view(InformationReadiness.await(catalogue, context, revisions(), 50));
    assertEquals(true, fence.get("complete"));
    verify(catalogue, times(2)).requireReadable(context, REVISION);
  }

  @Test
  void terminal_acquisition_outcomes_count_once_and_do_not_claim_readability() {
    for (String state : List.of("failed", "blocked")) {
      when(catalogue.acquisitionStatus(context, TICKET))
          .thenReturn(acquisition(state, null, "fixture reason"));
      var sources =
          List.of(
              new InformationReadiness.Source(null, TICKET),
              new InformationReadiness.Source(null, TICKET));
      var fence =
          InformationFixtures.view(InformationReadiness.await(catalogue, context, sources, 0));
      assertEquals(1, fence.get("expected"));
      assertEquals(1, fence.get("settled"));
      assertEquals(0, fence.get("ready"));
      assertEquals(true, fence.get("complete"));
      assertEquals(state, first(fence).get("state"));
    }
    verify(catalogue, never()).status(any(), any());
  }

  @Test
  void extraction_failures_and_unavailable_revisions_are_terminal_outcomes() {
    for (String state : List.of("failed", "blocked", "cancelled", "skipped")) {
      when(catalogue.status(context, REVISION)).thenReturn(status(state));
      var fence =
          InformationFixtures.view(InformationReadiness.await(catalogue, context, revisions(), 0));
      assertEquals(true, fence.get("complete"));
      assertEquals(state, first(fence).get("state"));
      assertEquals(0, fence.get("ready"));
    }
    doThrow(new NotFoundFault("hidden source details"))
        .when(catalogue)
        .requireReadable(context, REVISION);
    var fence =
        InformationFixtures.view(InformationReadiness.await(catalogue, context, revisions(), 0));
    assertEquals(true, fence.get("complete"));
    assertEquals("unavailable", first(fence).get("state"));
    assertFalse(fence.toString().contains("hidden source details"));
  }

  @Test
  void stale_generations_and_pending_acquisitions_do_not_count_as_completion() {
    when(catalogue.status(context, REVISION))
        .thenReturn(
            snapshot(
                2,
                List.of(
                    new io.aeyer.plowshare.protocol.Information.Step(
                        "extract", "ready", 0, null, 1, null, null, null, null),
                    new io.aeyer.plowshare.protocol.Information.Step(
                        "extract", "pending", 0, null, 2, null, null, null, null))));
    assertEquals(
        false,
        InformationFixtures.view(InformationReadiness.await(catalogue, context, revisions(), 0))
            .get("complete"));
    when(catalogue.acquisitionStatus(context, TICKET))
        .thenReturn(acquisition("running", null, null));
    assertEquals(
        false,
        InformationFixtures.view(
                InformationReadiness.await(
                    catalogue, context, List.of(new InformationReadiness.Source(null, TICKET)), 0))
            .get("complete"));
  }

  @Test
  void a_batch_waits_for_every_reference_including_unavailable_sources() {
    when(catalogue.status(context, REVISION)).thenReturn(status("ready"));
    when(catalogue.acquisitionStatus(context, TICKET))
        .thenReturn(acquisition("queued", null, null))
        .thenReturn(acquisition("failed", null, null));
    var sources =
        List.of(
            new InformationReadiness.Source(REVISION, null),
            new InformationReadiness.Source(null, TICKET));
    var waiting =
        InformationFixtures.view(InformationReadiness.await(catalogue, context, sources, 0));
    assertEquals(2, waiting.get("expected"));
    assertEquals(1, waiting.get("settled"));
    assertEquals(false, waiting.get("complete"));
    var complete =
        InformationFixtures.view(InformationReadiness.await(catalogue, context, sources, 0));
    assertEquals(2, complete.get("settled"));
    assertEquals(true, complete.get("complete"));
    assertEquals(1, complete.get("ready"));
  }

  @Test
  void interruption_ends_only_the_observer_and_pending_remains_pending() {
    when(catalogue.status(context, REVISION)).thenReturn(status("pending"));
    Thread.currentThread().interrupt();
    try {
      var fence =
          InformationFixtures.view(
              InformationReadiness.await(catalogue, context, revisions(), 100));
      assertEquals(true, fence.get("interrupted"));
      assertEquals(false, fence.get("complete"));
      assertEquals("pending", first(fence).get("state"));
    } finally {
      Thread.interrupted();
    }
    verify(catalogue, never()).retry(any(), any(), any());
  }
}
