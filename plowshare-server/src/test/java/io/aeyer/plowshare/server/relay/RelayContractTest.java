package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayContractTest {
  private static final Instant NOW = Instant.parse("2026-10-05T01:00:00.123456789Z");

  @Test
  void scheduled_payload_names_preserve_existing_event_identity_bounds() {
    var payload = new RelayPayload.ScheduleDue("s".repeat(1024), "e".repeat(1024), NOW);
    assertEquals(
        payload, RelayPayloadCodec.read(payload.kind(), 1, RelayPayloadCodec.write(payload)));
    for (String invalid : List.of("s".repeat(1025), " padded", "control\n", "separator\u2028"))
      assertThrows(
          IllegalArgumentException.class,
          () -> new RelayPayload.ScheduleDue(invalid, "daily", NOW));
  }

  @Test
  void lifecycle_and_wake_payloads_are_explicit_validated_reference_dtos() {
    var change =
        new RelayPayload.Lifecycle("job.ended", "job_fixture", "CANCELLED", null, "cnv_fixture");
    var wake =
        new RelayPayload.WakeRequested(
            "fir_fixture", "conversation:cnv_fixture", RelayPayload.WakeKind.MESSAGE);
    for (RelayPayload payload : List.of(change, wake))
      assertEquals(
          payload, RelayPayloadCodec.read(payload.kind(), 1, RelayPayloadCodec.write(payload)));
    for (String bad : List.of("bad state\n", " padded", "x".repeat(1025)))
      assertThrows(
          IllegalArgumentException.class,
          () -> new RelayPayload.Lifecycle("job.ended", bad, "CANCELLED", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayPayload.WakeRequested(
                "fir_fixture", "conversation:", RelayPayload.WakeKind.BOARD));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RelayPayloadCodec.read(
                RelayPayload.Kind.LIFECYCLE,
                1,
                "{\"source\":\"job.ended\",\"subject\":\"job_fixture\",\"state\":true,\"context\":null,\"related\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RelayPayloadCodec.read(
                RelayPayload.Kind.WAKE_REQUESTED,
                1,
                "{\"firing\":\"fir_fixture\",\"target\":\"conversation:cnv_fixture\",\"type\":\"ANY\",\"grant\":\"admin\"}"));
  }

  @Test
  void system_policy_overrides_are_bounded_and_never_apply_to_project_topics() {
    var settings = new RelayProperties();
    var policy = new RelayProperties.SystemTopic("job.ended", Duration.ofDays(2), 100L);
    settings.setSystemTopics(List.of(policy));
    assertEquals(
        policy.policy(),
        settings
            .systemPolicy(new Relay.TopicKey(Relay.SystemScope.SERVER, "job.ended"))
            .orElseThrow());
    assertTrue(settings.systemPolicy(new Relay.TopicKey(1, "job.ended")).isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> settings.setSystemTopics(List.of(policy, policy)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayProperties.SystemTopic("../topic", Duration.ofDays(1), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayProperties.SystemTopic("job.ended", Duration.ZERO, null));
  }

  @Test
  void explicit_system_scope_cannot_be_coerced_to_a_project() {
    var system = new Relay.TopicKey(Relay.SystemScope.SERVER, "schedule.due");
    var project = new Relay.TopicKey(1, "schedule.due");
    assertNotEquals(system, project);
    assertThrows(IllegalArgumentException.class, system::projectId);
    for (var scope : List.of(Relay.SystemScope.SERVER, new Relay.ProjectScope(Long.MAX_VALUE))) {
      var key = new Relay.TopicKey(scope, "schedule.due");
      assertEquals(scope, RelayScopeCodec.read(RelayScopeCodec.write(key)));
    }
    for (String malformed :
        List.of(
            "project:0",
            "project:01",
            "project:-1",
            "project:9223372036854775808",
            "project:1 ",
            "unknown"))
      assertThrows(IllegalStateException.class, () -> RelayScopeCodec.read(malformed));
  }

  @Test
  void topic_names_and_subscription_names_cannot_escape_their_scope() {
    for (String name :
        List.of("../other", "Release", "a/b", " a", "a\n", "a..b", "a".repeat(161))) {
      assertThrows(IllegalArgumentException.class, () -> new Relay.TopicKey(1, name));
    }
    var key = new Relay.TopicKey(1, "release.observed");
    assertThrows(IllegalArgumentException.class, () -> new Relay.TopicKey(0, "release.observed"));
    assertThrows(IllegalArgumentException.class, () -> new Relay.SubscriptionKey(key, "../route"));
    assertEquals(
        "release-review.route-a",
        new Relay.SubscriptionKey(key, "release-review.route-a").subscriber());
  }

  @Test
  void policy_is_bounded_and_system_default_is_four_days() {
    assertEquals(Duration.ofDays(4), Relay.Policy.systemDefault().retention());
    for (Duration retention :
        List.of(
            Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(1), Duration.ofDays(3651))) {
      assertThrows(IllegalArgumentException.class, () -> new Relay.Policy(retention, null));
    }
    assertThrows(IllegalArgumentException.class, () -> new Relay.Policy(Duration.ofDays(4), 0L));
    assertEquals(1L, new Relay.Policy(Duration.ofSeconds(1), 1L).maxRecords());
  }

  @Test
  void schedule_topic_has_a_registered_family_and_database_precision() {
    var key = new Relay.TopicKey(1, "schedule.due");
    assertThrows(
        IllegalArgumentException.class,
        () -> new Relay.Topic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault(), 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Relay.Topic(
                new Relay.TopicKey(1, "other"),
                RelayPayload.Kind.SCHEDULE_DUE,
                Relay.Policy.systemDefault(),
                0,
                0));
    var draft =
        new Relay.Draft(
            "occurrence-1",
            "scheduler",
            NOW,
            null,
            null,
            new RelayPayload.ScheduleDue("morning", "daily", NOW));
    assertEquals(Instant.parse("2026-10-05T01:00:00.123456Z"), draft.occurredAt());
    assertEquals(draft.occurredAt(), ((RelayPayload.ScheduleDue) draft.payload()).fireAt());
  }

  @Test
  void codec_round_trips_only_explicit_payloads() {
    for (RelayPayload payload :
        List.of(
            new RelayPayload.Empty(),
            new RelayPayload.Text("Retained prose"),
            new RelayPayload.ScheduleDue("morning", "daily", NOW))) {
      assertEquals(
          payload, RelayPayloadCodec.read(payload.kind(), 1, RelayPayloadCodec.write(payload)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> RelayPayloadCodec.read(RelayPayload.Kind.EMPTY, 2, "{}"));
  }

  @Test
  void stored_conversion_rejects_unknown_fields_coercion_duplicates_and_trailing_values() {
    for (String source :
        List.of(
            "null",
            "[]",
            "{}",
            "{\"text\":null}",
            "{\"text\":1}",
            "{\"text\":true}",
            "{\"text\":\"\"}",
            "{\"text\":\"x\",\"extra\":1}",
            "{\"text\":\"x\",\"text\":\"y\"}",
            "{\"text\":\"x\"} {}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> RelayPayloadCodec.read(RelayPayload.Kind.TEXT, 1, source));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RelayPayloadCodec.read(
                RelayPayload.Kind.SCHEDULE_DUE,
                1,
                "{\"schedule\":\"s\",\"emits\":\"e\",\"fireAt\":10}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RelayPayloadCodec.read(RelayPayload.Kind.EMPTY, 1, "{\"anything\":1}"));
  }

  @Test
  void byte_limits_and_errors_do_not_disclose_payload_content() {
    // Raw bytes, not escaped JSON bytes, own the limit. The encoded form exceeds 30 MiB.
    var largest =
        new RelayPayload.Text(
            "\u0001".repeat(io.aeyer.plowshare.protocol.RelayPort.DEFAULT_TEXT_BYTES));
    String encoded = RelayPayloadCodec.write(largest);
    assertTrue(encoded.length() > 30 * 1024 * 1024);
    assertEquals(largest, RelayPayloadCodec.read(RelayPayload.Kind.TEXT, 1, encoded));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RelayPayloadCodec.read(
                RelayPayload.Kind.TEXT,
                1,
                "{\"text\":\""
                    + "x".repeat(io.aeyer.plowshare.protocol.RelayPort.MAX_TEXT_BYTES + 1)
                    + "\"}"));
    String source = "{\"text\":\"private-marker\" BROKEN}";
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> RelayPayloadCodec.read(RelayPayload.Kind.TEXT, 1, source));
    assertFalse(failure.getMessage().contains("private-marker"));
    assertNull(failure.getCause());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Relay.Draft("event\n", "publisher", NOW, null, null, new RelayPayload.Empty()));
  }
}
