package io.aeyer.plowshare.server.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.relay.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelayMessageReviewTest {
  private final Relay relay = mock(Relay.class);
  private final RelayLogRepository logs = mock(RelayLogRepository.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final FilteringProperties properties = new FilteringProperties();
  private final RelayPortProperties ports = new RelayPortProperties();
  private final AtomicReference<FilterReview.Request> request = new AtomicReference<>();
  private final Relay.TopicKey responseTopic = new Relay.TopicKey(1, "checks.responses");
  private final UsageAttribution owner =
      UsageAttribution.project("subject", "fixture", UsageAttribution.Operation.AGENT_CHAT);
  private RelayMessageReview review;

  @BeforeEach
  void setup() throws Exception {
    properties.setExternal(
        List.of(
            new FilteringProperties.External(
                "fixture", "subject", "checks.requests", "checks.responses", "reviewer", 1)));
    ports.setBindings(
        List.of(
            new RelayPortProperties.Binding(
                "fixture",
                "checks.requests",
                "reviewer",
                RelayPortProperties.Direction.EGRESS,
                List.of("detectors")),
            new RelayPortProperties.Binding(
                "fixture",
                "checks.responses",
                "reviewer",
                RelayPortProperties.Direction.INGRESS,
                List.of())));
    when(projects.id("fixture")).thenReturn(1L);
    when(projects.personalOwner("fixture")).thenReturn(Optional.empty());
    when(members.mayWork(eq("fixture"), anyString())).thenReturn(true);
    when(relay.topic(responseTopic)).thenReturn(topic(0));
    when(relay.publish(any(), any()))
        .thenAnswer(
            call -> {
              var draft = call.getArgument(1, Relay.Draft.class);
              request.set(
                  JsonMapper.builder()
                      .build()
                      .readValue(
                          ((RelayPayload.Text) draft.payload()).text(),
                          FilterReview.Request.class));
              return new Relay.Publication(call.getArgument(0), 1, Instant.now(), draft);
            });
    review = new RelayMessageReview(properties, relay, logs, members, projects, ports);
  }

  private Relay.Topic topic(long through) {
    return new Relay.Topic(
        responseTopic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault(), through, 0);
  }

  private void verdict(UnaryOperator<String> change, String publisher, boolean parent) {
    when(logs.read(eq(responseTopic), anyLong(), eq(100), eq("subject")))
        .thenAnswer(
            call -> {
              var r = request.get();
              var json =
                  JsonMapper.builder()
                      .build()
                      .writeValueAsString(
                          new FilterReview.Response(
                              1,
                              r.requestId(),
                              r.sourceHash(),
                              true,
                              "complete approved message",
                              "accepted"));
              var cause =
                  parent
                      ? new RelayCausation(
                          io.aeyer.plowshare.server.relay.RelayReviewCausation.root(r.requestId())
                              .rootId(),
                          r.requestId(),
                          1)
                      : new RelayCausation(r.requestId(), null, 0);
              var publication =
                  new Relay.Publication(
                      responseTopic,
                      1,
                      Instant.now(),
                      new Relay.Draft(
                          "verdict",
                          publisher,
                          Instant.now(),
                          r.requestId(),
                          parent ? r.requestId() : null,
                          new RelayPayload.Text(change.apply(json)),
                          cause));
              return new RelayLogRepository.Snapshot(
                  topic(1), call.getArgument(1), List.of(publication), List.of(), List.of());
            });
  }

  @Test
  void accepted_verdict_returns_the_entire_message_and_is_bound_to_the_held_source() {
    verdict(UnaryOperator.identity(), RelayPort.publisher("reviewer"), true);
    assertEquals(
        "complete approved message", review.review(owner, "user", "original", () -> false));
    assertEquals(RelayPort.hash("user\0original"), request.get().sourceHash());
    verify(relay, times(1)).publish(any(), any());
  }

  @Test
  void malformed_wrong_hash_and_wrong_ancestry_fail_closed() {
    for (var change :
        List.<UnaryOperator<String>>of(
            s -> s.replace(request.get().sourceHash(), "0".repeat(64)),
            s -> s.replace("\"accepted\":true", "\"accepted\":\"true\""),
            s -> s.replace("\"version\":1", "\"version\":1,\"version\":1"),
            s -> s.replace("\"message\":\"complete approved message\"", "\"message\":42"))) {
      verdict(change, RelayPort.publisher("reviewer"), true);
      assertThrows(LlmException.class, () -> review.review(owner, "user", "original", () -> false));
    }
    verdict(UnaryOperator.identity(), RelayPort.publisher("reviewer"), false);
    assertThrows(LlmException.class, () -> review.review(owner, "user", "original", () -> false));
  }

  @Test
  void foreign_publisher_cannot_approve_and_timeout_never_republishes() {
    verdict(UnaryOperator.identity(), RelayPort.publisher("intruder"), true);
    assertThrows(LlmException.class, () -> review.review(owner, "user", "original", () -> false));
    verify(relay, times(1)).publish(any(), any());
  }

  @Test
  void cancellation_and_revoked_membership_prevent_publication() {
    assertThrows(
        CallerAbandonedException.class, () -> review.review(owner, "user", "original", () -> true));
    when(members.mayWork("fixture", "reviewer")).thenReturn(false);
    assertThrows(LlmException.class, () -> review.review(owner, "user", "original", () -> false));
    verify(relay, never()).publish(any(), any());
  }
}
