package io.aeyer.plowshare.server.approvals;

import java.util.List;
import java.util.Objects;

/**
 * Immutable operation evidence captured before review. The provider identity is the actual machine
 * and provider generation, not merely "local". User intent must come from an authenticated user
 * entry, never from a requesting model's justification. The input digest binds stdin or a file
 * edit.
 */
public record ApprovalIntent(
    String owner,
    String conversation,
    String call,
    String project,
    String provider,
    ApprovalPolicy.Kind kind,
    List<String> argv,
    List<String> paths,
    String cwd,
    String inputDigest,
    String userIntent,
    String justification,
    String inputPreview) {
  public ApprovalIntent(
      String owner,
      String conversation,
      String call,
      String project,
      String provider,
      ApprovalPolicy.Kind kind,
      List<String> argv,
      List<String> paths,
      String cwd,
      String inputDigest,
      String userIntent,
      String justification) {
    this(
        owner,
        conversation,
        call,
        project,
        provider,
        kind,
        argv,
        paths,
        cwd,
        inputDigest,
        userIntent,
        justification,
        null);
  }

  public ApprovalIntent {
    ApprovalPolicy.text(owner, "Approval owner", 512);
    ApprovalPolicy.text(conversation, "Approval conversation", 512);
    ApprovalPolicy.text(call, "Approval call", 512);
    ApprovalPolicy.text(project, "Approval project", 512);
    ApprovalPolicy.text(provider, "Approval provider", 1024);
    Objects.requireNonNull(kind, "Approval operation");
    argv = ApprovalPolicy.strings(argv, "Approval command", 256);
    paths = ApprovalPolicy.strings(paths, "Approval paths", 2);
    for (String path : paths) ApprovalPolicy.text(path, "Approval path", 8192);
    if (!argv.isEmpty()) ApprovalPolicy.text(argv.getFirst(), "Approval program", 8192);
    ApprovalPolicy.text(cwd, "Approval working directory", 8192);
    ApprovalPolicy.optional(inputDigest, "Approval input digest", 128);
    ApprovalPolicy.digest(inputDigest);
    ApprovalPolicy.optional(userIntent, "Authenticated user intent", 16000);
    ApprovalPolicy.optional(justification, "Agent justification", 4000);
    if (inputPreview != null && inputPreview.length() > 16000)
      throw new IllegalArgumentException("Approval input preview exceeds the review limit");
    if (kind == ApprovalPolicy.Kind.COMMAND
        ? argv.isEmpty() || !paths.isEmpty()
        : !argv.isEmpty() || paths.size() != (kind == ApprovalPolicy.Kind.FILE_MOVE ? 2 : 1))
      throw new IllegalArgumentException("Approval operation has an invalid target shape");
  }
}
