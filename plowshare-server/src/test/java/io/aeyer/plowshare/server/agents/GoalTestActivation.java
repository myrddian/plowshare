package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import java.util.Map;

/** Drives the actual intrinsic tool and settlement seam with a scripted result. */
public final class GoalTestActivation {
  private GoalTestActivation() {}

  public static InputProvenance provenance(GoalTurn goal) {
    return goal.inputProvenance();
  }

  public static void retainApproval(
      GoalTurn goal, String approval, int turn, int answer, String call, Runnable retain) {
    goal.retainApproval(approval, turn, answer, call, retain);
  }

  public static String ask(GoalTurn goal, String question) {
    var end = new TurnEnd();
    var tool =
        goal.listenerTools().stream()
            .filter(candidate -> candidate.schema().name().equals("goal_ask"))
            .map(GoalTurn.WaitTool.class::cast)
            .findFirst()
            .orElseThrow();
    tool.calledAt(end, new Transcript.Spoken(goal.conversation(), 1), 1, "ask");
    try {
      assertEquals(
          question,
          tool.run(
              new ObjectMapper().writeValueAsString(Map.of("question", question)),
              Home.of("project")));
    } catch (JsonProcessingException invalid) {
      throw new AssertionError(invalid);
    }
    return end.requested().orElseThrow().text();
  }

  public static void dependencySettled(GoalTurn goal) {
    goal.dependencySettled();
  }

  public static void settle(
      GoalTurn goal,
      String job,
      int ordinal,
      Outcome outcome,
      GoalTurn.Decision decision,
      Runnable writeTurn) {
    if (decision != null) {
      var end = new TurnEnd();
      try {
        assertEquals(
            "Goal decision recorded for settlement.",
            goal.tool(end)
                .run(new ObjectMapper().writeValueAsString(decision), Home.of("project")));
      } catch (JsonProcessingException invalid) {
        throw new AssertionError(invalid);
      }
      assertEquals(decision.explanation(), end.requested().orElseThrow().text());
    }
    goal.commit(job, ordinal, outcome, writeTurn);
  }
}
