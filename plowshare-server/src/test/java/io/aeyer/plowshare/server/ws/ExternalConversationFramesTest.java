package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.ExternalConversations;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.archive.ConversationAliasProperties;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import io.aeyer.plowshare.server.testing.LogicalSqlWorkBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ExternalConversationFramesTest {
  private final ExternalConversations owner = mock(ExternalConversations.class);
  private final FrameRouter router =
      new FrameRouter(
          new ExternalConversationFrames(owner).frames(),
          null,
          ReadAdmission.direct(),
          FailureReporter.NONE,
          new ScopedExternalConversationRequestWork(
              new LogicalSqlWorkBudget(),
              new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
              mock(ExternalConversationCompletions.class)));
  private final Asking caller = new Asking("socket", "alice");

  static Map<String, Object> locator() {
    return Map.of(
        "project", "house", "source", "ha.installation", "externalConversation", "opaque/CASE");
  }

  @Test
  void oneClientFrameReachesEachOwnerAndReturnsOrdinaryResultShapes() {
    var row =
        new ConversationRecord(
            "cnv_actual",
            Home.of("house"),
            Origin.TURN,
            ConversationLifecycle.ACTIVE,
            null,
            null,
            Instant.EPOCH,
            null,
            Budget.of(20),
            null,
            null);
    when(owner.open(any(), any(), any())).thenReturn(row);
    var open =
        router.route(
            new Envelope("1", FrameTypes.CONVERSATION_OPEN_EX, Envelope.CURRENT_VERSION, locator()),
            caller);
    assertEquals(Code.OK, open.code());
    assertEquals("cnv_actual", ((ConversationView) open.payload()).id());
    var run = new HashMap<>(locator());
    run.put("agent", "worker");
    run.put("task", "verbatim work");
    when(owner.run(any(), any(), any()))
        .thenReturn(new Runs.Started("job_actual", "worker", "cnv_actual"));
    var result =
        router.route(
            new Envelope("2", FrameTypes.AGENT_RUN_EX, Envelope.CURRENT_VERSION, run), caller);
    assertEquals(Code.ACCEPTED, result.code());
    verify(owner, times(1))
        .open(any(), any(), eq(new ExternalConversations.Caller("alice", "socket")));
    verify(owner, times(1))
        .run(any(), any(), eq(new ExternalConversations.Caller("alice", "socket")));
  }

  static Stream<Map<String, Object>> malformedOpen() {
    return Stream.of(
        Map.of("project", "house"),
        changed("conversation", "cnv_internal"),
        changed("owner", "other"),
        changed("source", "UPPER"),
        changed("source", "a".repeat(129)),
        changed("externalConversation", "has space"),
        changed("externalConversation", "é"),
        changed("maxModelCalls", 0),
        changed("maxModelCalls", 1.5),
        changed("noBudget", "false"),
        changed("maxTurns", "3"),
        changed("maxTurns", null),
        changed("project", null));
  }

  @ParameterizedTest
  @MethodSource("malformedOpen")
  void invalidOpenFieldsCannotReachBusinessLogic(Map<String, Object> payload) {
    assertEquals(
        Code.BAD_REQUEST,
        router
            .route(
                new Envelope(
                    "x", FrameTypes.CONVERSATION_OPEN_EX, Envelope.CURRENT_VERSION, payload),
                caller)
            .code());
    verifyNoInteractions(owner);
  }

  @Test
  void internalRoutingImagesAndNullOptionalValuesAreRejectedOnRun() {
    for (String key : List.of("conversation", "newConversation", "owner", "images", "noTurnCap")) {
      var payload = new HashMap<>(locator());
      payload.put("agent", "worker");
      payload.put("task", "work");
      payload.put(
          key,
          key.equals("images") ? List.of("img") : key.equals("newConversation") ? false : null);
      assertEquals(
          Code.BAD_REQUEST,
          router
              .route(
                  new Envelope("x", FrameTypes.AGENT_RUN_EX, Envelope.CURRENT_VERSION, payload),
                  caller)
              .code());
    }
    verifyNoInteractions(owner);
  }

  @Test
  void anonymousSocketCannotReachEitherAliasCapability() {
    assertEquals(
        Code.BAD_REQUEST,
        router
            .route(
                new Envelope(
                    "x", FrameTypes.CONVERSATION_OPEN_EX, Envelope.CURRENT_VERSION, locator()),
                new Asking("socket"))
            .code());
    verifyNoInteractions(owner);
  }

  @Test
  void exOperationsRemainMutationBarriers() {
    for (String type : List.of(FrameTypes.CONVERSATION_OPEN_EX, FrameTypes.AGENT_RUN_EX)) {
      assertFalse(DispatchPolicy.independentRead(type));
      assertEquals(
          DispatchPolicy.Kind.BARRIER,
          DispatchPolicy.decode(
                  new Envelope("x", type, Envelope.CURRENT_VERSION, locator()),
                  "alice",
                  "connection")
              .kind());
    }
  }

  private static Map<String, Object> changed(String key, Object value) {
    var fields = new HashMap<>(locator());
    fields.put(key, value);
    return fields;
  }
}
