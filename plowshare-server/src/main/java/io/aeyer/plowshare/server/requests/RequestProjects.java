package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Optional;

/** Trusted omitted-target resolution. A resolved name still needs the operation's live checks. */
public interface RequestProjects {
  /** Empty means a human's Personal default; invalid/ambiguous machine contexts must throw. */
  Optional<String> implicit(String account);

  /** Isolated fixtures without account repositories cannot infer a machine's project. */
  RequestProjects EXPLICIT_ONLY =
      account -> {
        if (ServiceCredentials.principal(account))
          throw new CallerFault("Supply an explicit project");
        return Optional.empty();
      };
}
