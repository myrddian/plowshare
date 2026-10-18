package io.aeyer.plowshare.server.approvals;

import java.util.List;

/** Approval audit text for the trajectory. Approval never instructs a model to resubmit a tool. */
public final class ApprovalUtterance {
  private ApprovalUtterance() {}

  public static String forAnswer(RunApproval approval, String decision, List<String> prefix) {
    return "deny".equals(decision)
        ? "[approval] Command denied. The harness will return the decision to the requesting tool call."
        : "[approval] Command approved. The harness will resume the retained tool call.";
  }
}
