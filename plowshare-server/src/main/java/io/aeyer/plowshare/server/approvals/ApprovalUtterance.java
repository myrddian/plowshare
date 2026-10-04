package io.aeyer.plowshare.server.approvals;

import java.util.List;

/** Shared harness wording for an answered approval, including durable continuation recovery. */
public final class ApprovalUtterance {
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
    return switch (decision) {
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
  }
}
