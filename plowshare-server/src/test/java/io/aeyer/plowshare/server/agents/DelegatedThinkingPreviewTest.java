package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.JobDelta;
import io.aeyer.plowshare.protocol.JobEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DelegatedThinkingPreviewTest {
  @Test
  void delegated_thinking_uses_root_preview_without_replacing_its_lifecycle_or_answer() {
    List<JobEvent> events = new ArrayList<>();
    List<JobDelta> deltas = new ArrayList<>();
    List<String> sessions = new ArrayList<>();
    JobEvents publisher =
        new JobEvents() {
          @Override
          public void publish(String session, JobEvent event) {
            events.add(event);
          }

          @Override
          public boolean streaming(String session) {
            return "session".equals(session);
          }

          @Override
          public void stream(String session, JobDelta delta) {
            sessions.add(session);
            deltas.add(delta);
          }
        };
    JobWatch parent = new JobWatch("job-root", "session", publisher);
    parent.modelCall("parent", 2, 3);
    JobWatch child = parent.forDelegation();
    assertTrue(child.streaming());
    child.started("reviewer");
    child.modelCall("reviewer", 8, 9);
    child.toolCalled("reviewer", "file_read");
    child.thinking("Checking the collision order");
    child.answering("Child answer is not the root answer");
    child.ended("reviewer", Outcome.Ending.UNAVAILABLE, 8, 9);
    child.forDelegation().thinking("Nested reasoning");
    parent.alive("parent");
    parent.answering("Root answer");

    assertEquals(2, events.size());
    assertEquals(List.of("session", "session", "session"), sessions);
    assertEquals(
        List.of(
            JobDelta.thinking("job-root", "Checking the collision order"),
            JobDelta.thinking("job-root", "Nested reasoning"),
            JobDelta.answering("job-root", "Root answer")),
        deltas);
    assertEquals(2, events.getLast().steps());
    assertEquals(3, events.getLast().modelCalls());
  }

  @Test
  void unwatched_delegation_stays_unwatched() {
    assertSame(JobWatch.UNWATCHED, JobWatch.UNWATCHED.forDelegation());
  }
}
