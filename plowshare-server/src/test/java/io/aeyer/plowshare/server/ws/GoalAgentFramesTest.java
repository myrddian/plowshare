package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.AccessRequestDecoder;
import io.aeyer.plowshare.server.access.ResourceScopeRepository.Kind;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.goals.GoalAgents;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Selection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoalAgentFramesTest {
  private static final UUID GOAL = UUID.randomUUID();
  private static final UUID GENERATION = UUID.randomUUID();
  private static final String CONVERSATION = "cnv_Goal";
  private static final String TOPIC = "data.feed";

  private final GoalAgents agents = mock(GoalAgents.class);
  private final FrameHandler listen =
      new GoalAgentFrames(agents).frames().get(FrameTypes.MESSAGE_LISTEN);

  @Test
  void discovery_rejects_ambiguous_selectors_and_reads_the_immutable_launch_receipt() {
    var frames = new GoalAgentFrames(agents).frames();
    var caller = new Asking("session", "alice");
    var list = frames.get(FrameTypes.GOAL_LIST);
    var status = frames.get(FrameTypes.GOAL_STATUS);
    var receipt = frames.get(FrameTypes.GOAL_RECEIPT);
    var request = UUID.randomUUID();
    assertThrows(CallerFault.class, () -> list.handle(Map.of("project", "p", "limit", 51), caller));
    assertThrows(
        CallerFault.class, () -> list.handle(Map.of("project", "p", "limit", "20"), caller));
    assertThrows(
        CallerFault.class, () -> status.handle(Map.of("project", "p", "goal", "bad"), caller));
    assertThrows(
        CallerFault.class,
        () ->
            receipt.handle(
                Map.of("project", "p", "requestId", request.toString(), "agent", "x"), caller));
    verifyNoInteractions(agents);

    var original = new GoalAgents.Started(GOAL, CONVERSATION, "mai_Exact", 1, request);
    when(agents.receipt("alice", new GoalAgents.ReceiptRequest("p", request))).thenReturn(original);
    assertEquals(
        Outcome.ok(original),
        receipt.handle(Map.of("project", "p", "requestId", request.toString()), caller));
    var page = new GoalAgents.Page(List.of(), null);
    when(agents.list("alice", new GoalAgents.Listing("p", GOAL, 20))).thenReturn(page);
    assertEquals(
        Outcome.ok(page), list.handle(Map.of("project", "p", "after", GOAL.toString()), caller));
  }

  @Test
  void listens_in_the_routed_conversation_and_passes_the_exact_topic_generation() {
    var payload =
        Map.<String, Object>of(
            "goal",
            GOAL.toString(),
            "conversation",
            CONVERSATION,
            "topic",
            TOPIC,
            "generation",
            GENERATION.toString());
    var receipt = new GoalAgents.Listening(UUID.randomUUID(), GOAL, TOPIC, GENERATION);
    when(agents.listen(
            "alice", new GoalAgents.Listen(GOAL, CONVERSATION, new Selection(TOPIC, GENERATION))))
        .thenReturn(receipt);
    assertEquals(Outcome.ok(receipt), listen.handle(payload, new Asking("session", "alice")));
    assertEquals(
        List.of(new AccessRequest.Resource(Kind.CONVERSATION, CONVERSATION)),
        AccessRequestDecoder.decode(FrameTypes.MESSAGE_LISTEN, payload).resources());
  }

  @Test
  void invalid_generation_is_refused_before_the_goal_service_runs() {
    var payload =
        Map.<String, Object>of(
            "goal",
            GOAL.toString(),
            "conversation",
            CONVERSATION,
            "topic",
            TOPIC,
            "generation",
            "wrong");
    assertThrows(CallerFault.class, () -> listen.handle(payload, new Asking("session", "alice")));
    verifyNoInteractions(agents);
  }

  @Test
  void answers_the_exact_goal_question_under_the_authenticated_conversation() {
    UUID question = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    var payload =
        Map.<String, Object>of(
            "goal",
            GOAL.toString(),
            "conversation",
            CONVERSATION,
            "question",
            question.toString(),
            "requestId",
            request.toString(),
            "answer",
            " Release 17. ");
    var receipt = new GoalAgents.Answered(GOAL, CONVERSATION, question, request);
    when(agents.answer(
            "alice", new GoalAgents.Answer(GOAL, CONVERSATION, question, request, " Release 17. ")))
        .thenReturn(receipt);
    var answer = new GoalAgentFrames(agents).frames().get(FrameTypes.GOAL_ANSWER);
    assertThrows(CallerFault.class, () -> answer.handle(payload, new Asking("session")));
    assertEquals(Outcome.ok(receipt), answer.handle(payload, new Asking("session", "alice")));
    assertEquals(
        List.of(new AccessRequest.Resource(Kind.CONVERSATION, CONVERSATION)),
        AccessRequestDecoder.decode(FrameTypes.GOAL_ANSWER, payload).resources());
  }

  @Test
  void optional_batch_is_paired_strictly_and_disarm_passes_exact_owner_version() {
    var frames = new GoalAgentFrames(agents).frames();
    var asking = new Asking("session", "alice");
    var base = new HashMap<String, Object>();
    base.put("goal", GOAL.toString());
    base.put("conversation", CONVERSATION);
    base.put("topic", TOPIC);
    base.put("generation", GENERATION.toString());
    base.put("batchCount", 4);
    assertThrows(CallerFault.class, () -> listen.handle(base, asking));
    base.put("maxDelaySeconds", null);
    assertThrows(CallerFault.class, () -> listen.handle(base, asking));
    base.put("maxDelaySeconds", 5);
    var receipt = new GoalAgents.Listening(UUID.randomUUID(), GOAL, TOPIC, GENERATION);
    when(agents.listen(
            "alice",
            new GoalAgents.Listen(
                GOAL, CONVERSATION, new Selection(TOPIC, GENERATION), new GoalAgents.Batch(4, 5))))
        .thenReturn(receipt);
    assertEquals(Outcome.ok(receipt), listen.handle(base, asking));

    var unlisten = frames.get(FrameTypes.MESSAGE_UNLISTEN);
    UUID requestId = UUID.randomUUID();
    var payload =
        Map.<String, Object>of(
            "goal",
            GOAL.toString(),
            "conversation",
            CONVERSATION,
            "binding",
            receipt.binding().toString(),
            "version",
            7,
            "requestId",
            requestId.toString());
    var removed = new GoalAgents.Unlistened(GOAL, receipt.binding(), 8, requestId);
    when(agents.unlisten(
            "alice", new GoalAgents.Unlisten(GOAL, CONVERSATION, receipt.binding(), 7, requestId)))
        .thenReturn(removed);
    assertEquals(Outcome.ok(removed), unlisten.handle(payload, asking));
    assertEquals(
        List.of(new AccessRequest.Resource(Kind.CONVERSATION, CONVERSATION)),
        AccessRequestDecoder.decode(FrameTypes.MESSAGE_UNLISTEN, payload).resources());
  }
}
