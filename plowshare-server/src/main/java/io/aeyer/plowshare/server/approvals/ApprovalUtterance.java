package io.aeyer.plowshare.server.approvals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Shared harness wording for an answered approval, including durable continuation recovery. */
public final class ApprovalUtterance {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ApprovalUtterance() {}

  public static String forAnswer(RunApproval approval, String decision, List<String> prefix) {
    String command =
        approval.isSet()
            ? approval.commands().size()
                + " commands ("
                + approval.commands().stream()
                    .map(argv -> "`" + String.join(" ", argv) + "`")
                    .collect(java.util.stream.Collectors.joining(", "))
                + ") in "
                + approval.cwd()
            : "`" + String.join(" ", approval.argv()) + "` in " + approval.cwd();
    String answer =
        switch (decision) {
          case RunApproval.ONCE ->
              "[approval] The person allowed " + command + ", this once. Run it again.";
          case RunApproval.CONVERSATION ->
              "[approval] The person allowed " + command + " for this conversation. Run it again.";
          case RunApproval.PROJECT ->
              "[approval] The person allowed any command starting `"
                  + String.join(" ", prefix)
                  + "` on the "
                  + approval.side()
                  + " side of this project,"
                  + " which covers "
                  + command
                  + ". Run it again.";
          default ->
              "[approval] The person denied "
                  + command
                  + ". Do not run it; carry on without it, or ask differently.";
        };
    if (!List.of(RunApproval.ONCE, RunApproval.CONVERSATION, RunApproval.PROJECT).contains(decision)
        || approval.isSet()) return answer;
    // The display sentence flattens argv and cannot be used to reconstruct a tool call:
    // spaces, quote characters and empty arguments are significant to the approval match.
    // Keep the retained argument boundaries in the continuation, without interpreting a shell.
    try {
      return answer
          + "\nPreserve the original stdin and timeout. Use these exact command and cwd"
          + " fields; each command array element is one argument, not shell syntax: "
          + JSON.writeValueAsString(new RunArguments(approval.argv(), approval.cwd()));
    } catch (JsonProcessingException failed) {
      throw new IllegalStateException("Could not encode the approved command", failed);
    }
  }

  private record RunArguments(List<String> command, String cwd) {}
}
