package io.aeyer.plowshare.server.approvals;

import java.util.List;

/** Per-run operation review, followed by a fresh publication fence immediately before execution. */
public interface OperationApprovalReview {
  record Request(
      String project,
      String provider,
      List<String> argv,
      String cwd,
      String stdin,
      String call,
      ApprovalPolicy.Kind kind,
      List<String> paths) {
    public Request(
        String project, String provider, List<String> argv, String cwd, String stdin, String call) {
      this(project, provider, argv, cwd, stdin, call, ApprovalPolicy.Kind.COMMAND, List.of());
    }

    public Request {
      paths = List.copyOf(paths);
      argv = List.copyOf(argv);
    }
  }

  ApprovalReviews.Decision review(Request request, ApprovalReviews.Consent consent);

  /**
   * Only the retained human-consent path may use this method, after its exact grant is consumed.
   */
  void humanOverride(Request request);

  default void humanOverride(Request request, String approval) {
    humanOverride(request);
  }

  /**
   * Rejects expired policy generations and new deterministic prohibitions before provider effects.
   */
  void requireCurrent(Request request);
}
