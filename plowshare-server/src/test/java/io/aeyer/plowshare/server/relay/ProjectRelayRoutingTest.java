package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectRelayRoutingTest {
  private static final RelayProjectFiles.Access ACCESS =
      new RelayProjectFiles.Access("operator", "project", 9);
  private final RelayProjectFiles files = mock(RelayProjectFiles.class);
  private final Relay relay = mock(Relay.class);
  private final RelayDeliveries deliveries = mock(RelayDeliveries.class);

  private void active() {
    when(files.read(ACCESS, "active.json"))
        .thenReturn(Optional.of("{\"version\":1,\"active\":[\"notices\"]}"));
    when(files.read(ACCESS, "notices/routes.js"))
        .thenReturn(
            Optional.of(
                RelayRouteProgramTest.DECLARATION
                    + "export function route(){return [{name:'notify',receiver:'agent-notices'},{name:'review',receiver:'local-handler',script:'review.js'}];}"));
  }

  @Test
  void only_active_folders_are_read_and_policy_is_separate() {
    active();
    when(files.read(ACCESS, "topics.json"))
        .thenReturn(
            Optional.of(
                "{\"version\":1,\"topics\":{\"schedule.due\":{\"retentionDays\":4},\"release.observed\":{\"retentionDays\":30,\"maxRecords\":100}}}"));
    try (var program = new GraalRelayRouteProgram()) {
      var config = new ProjectRelayRouting(files, program, relay, deliveries).load(ACCESS);
      assertEquals(1, config.relays().size());
      assertEquals(java.time.Duration.ofDays(30), config.policy("release.observed").retention());
      assertEquals(100L, config.policy("release.observed").maxRecords());
      assertEquals(Relay.Policy.systemDefault(), config.policy("unconfigured"));
      assertEquals(
          "relay.notices.due",
          config
              .relays()
              .getFirst()
              .key(9, config.relays().getFirst().manifest().subscriptions().getFirst())
              .subscriber());
      verify(files, never()).read(eq(ACCESS), contains("experiments"));
      verifyNoInteractions(relay, deliveries);
    }
  }

  @Test
  void absent_activation_is_empty_but_invalid_activation_does_not_fall_back() {
    var programs = mock(RelayRouteProgram.class);
    var routing = new ProjectRelayRouting(files, programs, relay, deliveries);
    assertEquals(List.of(), routing.load(ACCESS).relays());
    for (String bad :
        List.of(
            "{\"version\":1,\"active\":[\"../escape\"]}",
            "{\"version\":1,\"active\":[\"duplicate\",\"duplicate\"]}",
            "{\"version\":1,\"active\":[],\"active\":[]}",
            "{\"version\":\"1\",\"active\":[]}",
            "{\"version\":1,\"active\":[],\"routes\":[]}",
            "{\"version\":1,\"active\":[]} {}")) {
      when(files.read(ACCESS, "active.json")).thenReturn(Optional.of(bad));
      assertThrows(RuntimeException.class, () -> routing.load(ACCESS));
    }
    verifyNoInteractions(programs, relay, deliveries);
  }

  @Test
  void strict_topic_policies_reject_coercion_null_unknown_and_out_of_range() {
    for (String bad :
        List.of(
            "{\"retentionDays\":0}",
            "{\"retentionDays\":3651}",
            "{\"retentionDays\":1.5}",
            "{\"retentionDays\":\"4\"}",
            "{\"retentionDays\":4,\"maxRecords\":null}",
            "{\"retentionDays\":4,\"maxRecords\":-1}",
            "{\"retentionDays\":4,\"maxBytes\":100}",
            "{\"retentionDays\":4,\"retentionDays\":5}"))
      assertThrows(
          RuntimeException.class,
          () ->
              RelayRouteCodec.policies(
                  "{\"version\":1,\"topics\":{\"schedule.due\":" + bad + "}}"));
    assertThrows(
        RuntimeException.class,
        () ->
            RelayRouteCodec.policies(
                "{\"version\":1,\"topics\":{\"../topic\":{\"retentionDays\":4}}}"));
  }

  @Test
  void fanout_pins_selected_scripts_before_admission() {
    active();
    when(files.read(ACCESS, "notices/scripts/review.js"))
        .thenReturn(Optional.of("export function step(){return 'review';}"));
    try (var program = new GraalRelayRouteProgram()) {
      var routing = new ProjectRelayRouting(files, program, relay, deliveries);
      var pack = routing.load(ACCESS).relays().getFirst();
      var key = pack.key(9, pack.manifest().subscriptions().getFirst());
      var input = RelayRouteProgramTest.INPUT;
      when(relay.read(key, 1))
          .thenReturn(
              new Relay.Read(
                  new Relay.Subscription(key, 0, Instant.EPOCH), Optional.empty(), List.of(input)));
      when(deliveries.admit(any(), any()))
          .thenAnswer(
              invocation -> {
                RelayDeliveries.Decision decision = invocation.getArgument(1);
                assertEquals(2, decision.branches().size());
                assertNull(decision.branches().getFirst().handler());
                assertEquals(
                    "notices/scripts/review.js", decision.branches().get(1).handler().path());
                return new RelayDeliveries.Admission(
                    invocation.getArgument(0),
                    input,
                    decision,
                    Instant.EPOCH,
                    List.of(
                        new RelayDeliveries.DeliveryKey(key, java.util.UUID.randomUUID()),
                        new RelayDeliveries.DeliveryKey(key, java.util.UUID.randomUUID())));
              });
      assertEquals(2, routing.admit(ACCESS, key, input).deliveries().size());
      verify(deliveries).admit(eq(new RelayDeliveries.AdmissionKey(key, input.position())), any());
    }
  }

  @Test
  void missing_handler_gap_and_forged_input_never_admit() {
    active();
    try (var program = new GraalRelayRouteProgram()) {
      var routing = new ProjectRelayRouting(files, program, relay, deliveries);
      var pack = routing.load(ACCESS).relays().getFirst();
      var key = pack.key(9, pack.manifest().subscriptions().getFirst());
      var sub = new Relay.Subscription(key, 0, Instant.EPOCH);
      var input = RelayRouteProgramTest.INPUT;
      when(relay.read(key, 1)).thenReturn(new Relay.Read(sub, Optional.empty(), List.of(input)));
      assertThrows(CallerFault.class, () -> routing.admit(ACCESS, key, input));
      when(relay.read(key, 1))
          .thenReturn(new Relay.Read(sub, Optional.of(new Relay.Gap(0, 5)), List.of(input)));
      assertThrows(CallerFault.class, () -> routing.admit(ACCESS, key, input));
      when(relay.read(key, 1)).thenReturn(new Relay.Read(sub, Optional.empty(), List.of()));
      assertThrows(CallerFault.class, () -> routing.admit(ACCESS, key, input));
      verify(deliveries, never()).admit(any(), any());
    }
  }

  @Test
  void retries_use_original_admission_after_deactivation_but_recheck_authority_and_input() {
    var programs = mock(RelayRouteProgram.class);
    var routing = new ProjectRelayRouting(files, programs, relay, deliveries);
    var input = RelayRouteProgramTest.INPUT;
    var key = new Relay.SubscriptionKey(input.topic(), "relay.notices.due");
    var admissionKey = new RelayDeliveries.AdmissionKey(key, input.position());
    var admission =
        new RelayDeliveries.Admission(
            admissionKey,
            input,
            new RelayDeliveries.Decision(
                RelayDeliveries.SourcePin.of("notices/routes.js", "old source"), List.of()),
            Instant.EPOCH,
            List.of());
    when(deliveries.admission(admissionKey)).thenReturn(Optional.of(admission));
    assertEquals(admission, routing.admit(ACCESS, key, input));
    verify(files, never()).read(any(), any());
    verifyNoInteractions(programs, relay);
    var changed =
        new Relay.Publication(
            input.topic(),
            input.position(),
            Instant.EPOCH,
            new Relay.Draft(
                "other", "publisher", Instant.EPOCH, null, null, input.event().payload()));
    assertThrows(CallerFault.class, () -> routing.admit(ACCESS, key, changed));
    doThrow(new CallerFault("revoked")).when(files).requireAccess(ACCESS);
    assertThrows(CallerFault.class, () -> routing.admit(ACCESS, key, input));
    verify(deliveries, times(2)).admission(admissionKey);
  }
}
