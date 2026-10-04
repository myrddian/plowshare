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

  private List<InformationReadiness.Source> revisions() {
    return List.of(new InformationReadiness.Source(REVISION, null));
  }

  private Map<String, Object> status(String state) {
    return Map.of(
        "generation",
        1,
        "steps",
        List.of(
            Map.of("stage", "extract", "generation", 1, "state", state),
            Map.of("stage", "summarise", "generation", 1, "state", "pending")));
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
    when(catalogue.status(context, REVISION)).thenReturn(receipt);
    var fence = InformationReadiness.await(catalogue, context, revisions(), 0);
    assertEquals(false, fence.get("complete"));
    assertEquals(1, fence.get("pending"));
    assertEquals(0, fence.get("ready"));
    assertEquals("pending", first(fence).get("extraction_state"));
    assertEquals(0, first(fence).get("attempt"));
  }

  @Test
  void successful_fetch_receipts_are_pending_until_extraction_is_ready() {
    when(catalogue.acquisitionStatus(context, TICKET))
        .thenReturn(Map.of("state", "succeeded", "revision_id", REVISION));
    when(catalogue.status(context, REVISION))
        .thenReturn(status("pending"))
        .thenReturn(status("ready"));
    var sources = List.of(new InformationReadiness.Source(null, TICKET));
    assertEquals(false, InformationReadiness.await(catalogue, context, sources, 0).get("complete"));
    var fence = InformationReadiness.await(catalogue, context, sources, 0);
    assertEquals(true, fence.get("complete"));
    assertEquals(1, fence.get("ready"));
    assertEquals(REVISION, first(fence).get("revision"));
  }

  @Test
  void native_wait_observes_later_completion_without_waiting_for_summary_or_embedding() {
    when(catalogue.status(context, REVISION))
        .thenReturn(status("pending"))
        .thenReturn(status("ready"));
    var fence = InformationReadiness.await(catalogue, context, revisions(), 50);
    assertEquals(true, fence.get("complete"));
    verify(catalogue, times(2)).requireReadable(context, REVISION);
  }

  @Test
  void terminal_acquisition_outcomes_count_once_and_do_not_claim_readability() {
    for (String state : List.of("failed", "blocked", "cancelled")) {
      when(catalogue.acquisitionStatus(context, TICKET))
          .thenReturn(Map.of("state", state, "error", "fixture reason"));
      var sources =
          List.of(
              new InformationReadiness.Source(null, TICKET),
              new InformationReadiness.Source(null, TICKET));
      var fence = InformationReadiness.await(catalogue, context, sources, 0);
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
      var fence = InformationReadiness.await(catalogue, context, revisions(), 0);
      assertEquals(true, fence.get("complete"));
      assertEquals(state, first(fence).get("state"));
      assertEquals(0, fence.get("ready"));
    }
    doThrow(new NotFoundFault("hidden source details"))
        .when(catalogue)
        .requireReadable(context, REVISION);
    var fence = InformationReadiness.await(catalogue, context, revisions(), 0);
    assertEquals(true, fence.get("complete"));
    assertEquals("unavailable", first(fence).get("state"));
    assertFalse(fence.toString().contains("hidden source details"));
  }

  @Test
  void stale_generations_and_pending_acquisitions_do_not_count_as_completion() {
    when(catalogue.status(context, REVISION))
        .thenReturn(
            Map.of(
                "generation",
                2,
                "steps",
                List.of(
                    Map.of("stage", "extract", "generation", 1, "state", "ready"),
                    Map.of("stage", "extract", "generation", 2, "state", "pending"))));
    assertEquals(
        false, InformationReadiness.await(catalogue, context, revisions(), 0).get("complete"));
    when(catalogue.acquisitionStatus(context, TICKET)).thenReturn(Map.of("state", "running"));
    assertEquals(
        false,
        InformationReadiness.await(
                catalogue, context, List.of(new InformationReadiness.Source(null, TICKET)), 0)
            .get("complete"));
  }

  @Test
  void a_batch_waits_for_every_reference_including_unavailable_sources() {
    when(catalogue.status(context, REVISION)).thenReturn(status("ready"));
    when(catalogue.acquisitionStatus(context, TICKET))
        .thenReturn(Map.of("state", "queued"))
        .thenReturn(Map.of("state", "failed"));
    var sources =
        List.of(
            new InformationReadiness.Source(REVISION, null),
            new InformationReadiness.Source(null, TICKET));
    var waiting = InformationReadiness.await(catalogue, context, sources, 0);
    assertEquals(2, waiting.get("expected"));
    assertEquals(1, waiting.get("settled"));
    assertEquals(false, waiting.get("complete"));
    var complete = InformationReadiness.await(catalogue, context, sources, 0);
    assertEquals(2, complete.get("settled"));
    assertEquals(true, complete.get("complete"));
    assertEquals(1, complete.get("ready"));
  }

  @Test
  void interruption_ends_only_the_observer_and_pending_remains_pending() {
    when(catalogue.status(context, REVISION)).thenReturn(status("pending"));
    Thread.currentThread().interrupt();
    try {
      var fence = InformationReadiness.await(catalogue, context, revisions(), 100);
      assertEquals(true, fence.get("interrupted"));
      assertEquals(false, fence.get("complete"));
      assertEquals("pending", first(fence).get("state"));
    } finally {
      Thread.interrupted();
    }
    verify(catalogue, never()).retry(any(), any(), any());
  }
}
