package io.aeyer.plowshare.server.access;

import static io.aeyer.plowshare.server.access.ResourceScopeRepository.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AccessRequestDecoderTest {
  @Test
  void decodes_explicit_and_resource_scopes_before_policy_runs() {
    var request =
        AccessRequestDecoder.decode(
            "orchestration.status",
            Map.of(
                "project",
                " project ",
                "scope",
                Map.of("kind", "project", "project", "other"),
                "conversation",
                "cnv_1",
                "id",
                "orc_1",
                "conversations",
                List.of("cnv_2")));
    assertEquals(Set.of("project", "other"), request.projects());
    assertEquals(
        List.of(
            new AccessRequest.Resource(CONVERSATION, "cnv_1"),
            new AccessRequest.Resource(CONVERSATION, "cnv_2"),
            new AccessRequest.Resource(ORCHESTRATION, "orc_1")),
        request.resources());
    assertTrue(request.projectScope());
  }

  @Test
  void invalid_reference_types_and_coerced_controls_are_rejected() {
    for (var input :
        List.of(
            Map.of("project", 1),
            Map.of("conversation", List.of("cnv_1")),
            Map.of("conversations", List.of("cnv_1", 2)),
            Map.of("scope", "project"))) {
      assertThrows(
          CallerFault.class, () -> AccessRequestDecoder.decode("conversation.list", input));
    }
    assertThrows(
        CallerFault.class,
        () -> AccessRequestDecoder.decode("schedule.pause", Map.of("paused", "false")));
    assertThrows(
        CallerFault.class,
        () -> AccessRequestDecoder.decode("outgoing.status", Map.of("id", "not-a-uuid")));
  }

  @Test
  void business_fields_are_not_authorization_references() {
    assertTrue(
        AccessRequestDecoder.decode(
                "board.post", Map.of("project", "p", "body", "text", "title", "title"))
            .resources()
            .isEmpty());
    assertTrue(
        AccessRequestDecoder.decode(
                "orchestration.start", Map.of("project", "p", "request", "text"))
            .resources()
            .isEmpty());
  }
}
