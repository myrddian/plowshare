package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.protocol.search.Verb;
import java.util.Objects;

/** Exact human-facing intent for one web request. It grants no shell or standing consent. */
public record WebApproval(
    Verb verb,
    String target,
    Integer page,
    Integer pageSize,
    Integer max,
    Integer offset,
    String inputDigest) {
  public WebApproval {
    Objects.requireNonNull(verb);
    ApprovalPolicy.text(target, "Web approval destination", 8192);
    ApprovalPolicy.digest(Objects.requireNonNull(inputDigest));
    if (verb == Verb.SEARCH) {
      if (page == null
          || pageSize == null
          || max == null
          || page < 1
          || pageSize < 1
          || max < 1
          || offset != null) throw new IllegalArgumentException("Invalid web search approval");
    } else if (offset == null || offset < 0 || page != null || pageSize != null || max != null) {
      throw new IllegalArgumentException("Invalid web fetch approval");
    }
  }
}
