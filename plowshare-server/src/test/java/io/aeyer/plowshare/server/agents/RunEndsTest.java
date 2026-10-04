package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RunEndsTest {

  @Test
  void every_run_that_ends_is_reported_with_its_id_and_name() throws Exception {
    JobStore jobs = new JobStore(new JobRuntime(mock(LlmDispatcher.class), List.of()));
    CountDownLatch ended = new CountDownLatch(1);
    AtomicReference<String> seen = new AtomicReference<>();
    jobs.onRunEnded(
        (id, agent, home) -> {
          seen.set(id + "/" + agent);
          ended.countDown();
        });

    String id = jobs.submit("ingest", cancelled -> new Outcome(Ending.ANSWERED, "ok", 0, 0, ""));

    assertTrue(ended.await(5, TimeUnit.SECONDS), "the run end was reported");
    assertEquals(id + "/ingest", seen.get());
  }

  @Test
  void a_listener_that_throws_does_not_break_the_store() throws Exception {
    JobStore jobs = new JobStore(new JobRuntime(mock(LlmDispatcher.class), List.of()));
    CountDownLatch second = new CountDownLatch(1);
    jobs.onRunEnded(
        (id, agent, home) -> {
          if (second.getCount() == 1 && agent.equals("first")) {
            throw new IllegalStateException("boom");
          }
          second.countDown();
        });
    jobs.submit("first", cancelled -> new Outcome(Ending.ANSWERED, "ok", 0, 0, ""));
    jobs.submit("second", cancelled -> new Outcome(Ending.ANSWERED, "ok", 0, 0, ""));
    assertTrue(second.await(5, TimeUnit.SECONDS));
  }
}
