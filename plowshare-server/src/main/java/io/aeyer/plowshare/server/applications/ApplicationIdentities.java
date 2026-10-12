package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationIdentity.*;
import java.util.UUID;

/** Server-owned reservation and explicit credential issuance, independent of deployment clients. */
public interface ApplicationIdentities {
  /** Called after destination authorization; reserves a dormant identity, never a bearer secret. */
  /** Acquires the administrative lifecycle lock before deployment locks its project row. */
  void lockActivation(String actor, String project);

  Binding reserve(String actor, String project, Declaration declaration);

  Status status(String actor, String project);

  Credential issue(String actor, Issue request);

  Receipt receipt(String actor, String project, UUID requestId);

  /** Joins source activation's transaction. A null declaration cannot remove an owned identity. */
  String activate(String actor, String project, Declaration declaration, String principal);
}
