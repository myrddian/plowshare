package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalUtteranceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static RunApproval approval(List<String> command) {
    return new RunApproval(
        "apr_fixture",
        7L,
        "cnv_fixture",
        "cnv_fixture",
        "coder",
        "local",
        command,
        "/fixture folder",
        null,
        RunApproval.ALLOWED,
        RunApproval.ONCE,
        null,
        "person",
        Instant.now(),
        Instant.now());
  }

  @Test
  void continuation_keeps_argument_boundaries_instead_of_requiring_shell_reconstruction()
      throws Exception {
    List<String> command =
        List.of(
            "python3",
            "connect.py",
            "post",
            "Hello from Professor Farnsworth!",
            "--topic",
            "welcome",
            "",
            "literal \"quotes\"",
            "back\\slash",
            "line\nbreak");
    RunApproval approval = approval(command);
    for (String decision :
        List.of(RunApproval.ONCE, RunApproval.CONVERSATION, RunApproval.PROJECT)) {
      String utterance = ApprovalUtterance.forAnswer(approval, decision, List.of("python3"));
      var fields = JSON.readTree(utterance.substring(utterance.indexOf('{')));
      assertEquals(JSON.valueToTree(command), fields.get("command"));
      assertEquals("/fixture folder", fields.get("cwd").asText());
      assertTrue(utterance.contains("Preserve the original stdin and timeout"));
    }
  }

  @Test
  void denial_does_not_supply_retry_instructions() {
    String utterance = ApprovalUtterance.forAnswer(approval(List.of("python3")), "deny", null);
    assertTrue(utterance.contains("Do not run it"));
    assertFalse(utterance.contains("Use these exact"));
  }
}
