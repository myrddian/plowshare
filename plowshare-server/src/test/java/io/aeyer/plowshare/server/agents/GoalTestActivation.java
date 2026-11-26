package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;

/** Drives the actual intrinsic tool and settlement seam with a scripted result. */
public final class GoalTestActivation {
  private GoalTestActivation() {}

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
