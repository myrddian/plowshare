package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationDeployment.*;
import java.util.UUID;

/** Server-owned source deployment. Caller-owned UUIDs reconcile uncertain submission. */
public interface ApplicationDeployments {
  /** Validate and install a new revision; compare-and-set activation commits with its receipt. */
  Receipt deploy(String account, Deploy request);

  /** Roll forward/back to a retained revision without replacing project identity or work. */
  Receipt activate(String account, Activate request);

  Status status(String account, String project);

  Receipt receipt(String account, String project, UUID requestId);
}
