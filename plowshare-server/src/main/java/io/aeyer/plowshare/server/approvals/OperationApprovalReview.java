package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.files.FileProvider.PersonalWorkspace;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
      List<String> paths,
      Optional<PersonalWorkspace> personalWorkspace,
      boolean securityFileMutation) {
    public Request(
        String project,
        String provider,
        List<String> argv,
        String cwd,
        String stdin,
        String call,
        ApprovalPolicy.Kind kind,
        List<String> paths,
        Optional<PersonalWorkspace> personalWorkspace) {
      this(project, provider, argv, cwd, stdin, call, kind, paths, personalWorkspace, false);
    }

    public Request(
        String project, String provider, List<String> argv, String cwd, String stdin, String call) {
      this(
          project,
          provider,
          argv,
          cwd,
          stdin,
          call,
          ApprovalPolicy.Kind.COMMAND,
          List.of(),
          Optional.empty(),
          false);
    }

    public Request {
      paths = List.copyOf(paths);
      argv = List.copyOf(argv);
      Objects.requireNonNull(personalWorkspace);
      if (personalWorkspace.isPresent()
          && kind != ApprovalPolicy.Kind.FILE_READ
          && kind != ApprovalPolicy.Kind.FILE_WRITE)
        throw new IllegalArgumentException(
            "Personal workspace review evidence applies only to file reads and edits");
      if (securityFileMutation
          && (kind == ApprovalPolicy.Kind.COMMAND || kind == ApprovalPolicy.Kind.FILE_READ))
        throw new IllegalArgumentException("Security file mutation requires a direct file change");
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

  /** Accepts only the server-authored clearance retained with the exact file approval. */
  default void humanOverride(Request request, String approval, boolean securityEditVerified) {
    humanOverride(request, approval);
  }

  /**
   * Rejects expired policy generations and new deterministic prohibitions before provider effects.
   */
  void requireCurrent(Request request);
}
