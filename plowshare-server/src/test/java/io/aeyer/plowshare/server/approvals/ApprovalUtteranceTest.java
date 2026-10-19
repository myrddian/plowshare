package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalUtteranceTest {
  @Test
  void trajectory_text_never_instructs_the_model_to_repeat_a_call() {
    RunApproval approval =
        new RunApproval(
            "apr_fixture",
            7,
            "cnv_fixture",
            "cnv_fixture",
            "coder",
            "local",
            List.of("python3", "connect.py", "Hello with spaces"),
            "/fixture folder",
            null,
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            Instant.now());
    for (String decision : List.of("once", "conversation", "project", "deny")) {
      String text = ApprovalUtterance.forAnswer(approval, decision, List.of("python3"));
      assertFalse(text.contains("Run it again"));
      assertFalse(text.contains("connect.py"));
      assertFalse(text.contains("Use these exact"));
      assertTrue(text.contains("harness"));
    }
  }
}
