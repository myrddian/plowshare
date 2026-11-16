package io.aeyer.plowshare.server.approvals;

import java.util.List;
import java.util.Objects;

/** Exact file-operation display evidence; retained tool arguments alone drive execution. */
public record FileApproval(
    ApprovalPolicy.Kind kind,
    String provider,
    List<String> paths,
    String inputDigest,
    String inputPreview) {
  public FileApproval {
    Objects.requireNonNull(kind);
    if (kind == ApprovalPolicy.Kind.COMMAND)
      throw new IllegalArgumentException("A file operation is required");
    ApprovalPolicy.text(provider, "File provider", 1024);
    paths = ApprovalPolicy.strings(paths, "File approval paths", 2);
    if (paths.size() != (kind == ApprovalPolicy.Kind.FILE_MOVE ? 2 : 1))
      throw new IllegalArgumentException("Invalid file targets");
    for (String path : paths) ApprovalPolicy.text(path, "File approval path", 8192);
    ApprovalPolicy.digest(Objects.requireNonNull(inputDigest));
    if (inputPreview != null && inputPreview.length() > 16000)
      throw new IllegalArgumentException("File preview is too large");
  }
}
