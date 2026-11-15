package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.aeyer.plowshare.protocol.Home;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RequestBudgetToolTest {
  @Test
  void refuses_invalid_requests_before_any_question_or_grant() {
    AtomicInteger asked = new AtomicInteger();
    RequestBudgetTool tool =
        new RequestBudgetTool(
            request -> {
              asked.incrementAndGet();
              return Optional.of(new BudgetRequests.Decision("apr_one", true, "Approved"));
            });
    for (String invalid :
        new String[] {
          "null",
          "[]",
          "{}",
          "{\"additional_calls\":0,\"additional_steps\":0,\"reason\":\"finish\"}",
          "{\"additional_calls\":-1,\"additional_steps\":0,\"reason\":\"finish\"}",
          "{\"additional_calls\":1.5,\"additional_steps\":0,\"reason\":\"finish\"}",
          "{\"additional_calls\":\"2\",\"additional_steps\":0,\"reason\":\"finish\"}",
          "{\"additional_calls\":null,\"additional_steps\":0,\"reason\":\"finish\"}",
          "{\"additional_calls\":2147483648,\"additional_steps\":0,\"reason\":\"finish\"}",
          "{\"additional_calls\":2,\"additional_steps\":0,\"reason\":\" \"}",
          "{\"additional_calls\":2,\"additional_steps\":0,\"reason\":\"finish\",\"allow\":true}",
          "{\"additional_calls\":2,\"additional_calls\":3,\"additional_steps\":0,\"reason\":\"finish\"}",
          "{\"additional_calls\":2,\"additional_steps\":0,\"reason\":\"finish\"} {}"
        }) {
      tool.run(invalid, Home.of("project"));
      assertEquals(0, asked.get(), invalid);
      assertNull(tool.approval());
    }
    assertEquals(
        "Approved",
        tool.run(
            "{\"additional_calls\":2,\"additional_steps\":0,\"reason\":\"finish\"}",
            Home.of("project")));
    assertEquals(1, asked.get());
    assertEquals("apr_one", tool.approval());
  }
}
