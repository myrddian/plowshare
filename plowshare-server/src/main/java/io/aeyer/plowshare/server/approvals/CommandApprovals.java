package io.aeyer.plowshare.server.approvals;

import java.util.List;
import java.util.Optional;

/** Command consent repository used by the runtime; decisions remain distinct from execution. */
public interface CommandApprovals extends CommandConsentLookup {
  Optional<RunApproval> find(String id);

  Optional<RunApproval> consume(
      long project, String conversation, String side, List<String> argv, String cwd, String stdin);

  RunApproval ask(
      long project,
      String conversation,
      String askedIn,
      String agent,
      String side,
      List<String> argv,
      String cwd,
      String reason);

  RunApproval ask(
      long project,
      String conversation,
      String askedIn,
      String handle,
      String agent,
      String side,
      List<String> argv,
      String cwd,
      String reason);
}
