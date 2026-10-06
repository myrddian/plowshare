package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayCausation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RelayCausationTest {
  @Test
  void typed_ancestry_survives_without_repository_reads_and_legacy_lifecycle_is_never_a_root() {
    var jdbc = mock(JdbcTemplate.class);
    var history = new JdbcRelayForwardingHistory(jdbc);
    var cause = new RelayCausation("root", "parent", 8);
    var payload = new RelayPayload.Lifecycle("job.ended", "job_fixture", "ANSWERED", null, null);
    var pub =
        new Relay.Publication(
            new Relay.TopicKey(9, "job.ended"),
            1,
            Instant.EPOCH,
            new Relay.Draft(
                "event", "system.source", Instant.EPOCH, null, "parent", payload, cause));
    assertEquals(cause, history.causation(pub, 8).orElseThrow());
    var legacy =
        new Relay.Publication(
            pub.topic(),
            1,
            Instant.EPOCH,
            new Relay.Draft("old", "system.source", Instant.EPOCH, null, null, payload));
    assertTrue(history.causation(legacy, 8).isEmpty());
    var forged =
        new Relay.Publication(
            pub.topic(),
            1,
            Instant.EPOCH,
            new Relay.Draft(
                "bad", "relay:relay.publish", Instant.EPOCH, null, null, new RelayPayload.Empty()));
    assertTrue(history.causation(forged, 8).isEmpty());
    verifyNoInteractions(jdbc);
  }

  @Test
  void persisted_causation_rejects_malformed_fields_types_and_invariants() {
    for (String source :
        List.of(
            "",
            "null",
            "{}",
            "{\"rootId\":\"root\",\"parentId\":null,\"depth\":1}",
            "{\"rootId\":\"root\",\"parentId\":\"parent\",\"depth\":33}",
            "{\"rootId\":\"root\",\"parentId\":null,\"depth\":\"0\"}",
            "{\"rootId\":\"root\",\"parentId\":null,\"depth\":0,\"extra\":true}"))
      assertThrows(IllegalArgumentException.class, () -> RelayCausationCodec.read(source));
    var cause = RelayCausation.root("root").next("root", 8);
    assertEquals(cause, RelayCausationCodec.read(RelayCausationCodec.write(cause)));
    assertThrows(
        IllegalStateException.class, () -> RelayCausation.unknown("legacy").next("parent", 8));
    assertThrows(
        IllegalStateException.class,
        () -> new RelayCausation("root", "parent", 8).next("parent", 8));
  }
}
