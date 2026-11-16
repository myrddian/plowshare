package io.aeyer.plowshare.server.approvals;

import java.util.List;
import java.util.Optional;

/** Consent inspection does not spend a once grant while SECURITY and a bot are still reviewing. */
public interface CommandConsentLookup {
  Optional<RunApproval> inspect(
      long project, String conversation, String side, List<String> argv, String cwd, String stdin);

  /** Confirms the inspected immutable grant is still allowed, atomically consuming once consent. */
  boolean claim(RunApproval inspected);
}
