package io.aeyer.plowshare.protocol;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Executes an admitted command inside an OS boundary derived from the caller's current file policy.
 * Grants, approvals and project ownership remain the caller's responsibility. Implementations must
 * refuse unavailable isolation and must never retry the command outside the boundary.
 */
public interface CommandIsolation {
  /** An installation with no isolation backend configured. */
  CommandIsolation UNAVAILABLE =
      (command, reads, writes, host, cancelled) -> {
        throw new CommandRunner.Refused(
            "Linux bubblewrap isolation is not configured on this machine");
      };

  /**
   * Runs once, retaining the command's environment, input, output bounds, deadline and
   * cancellation. The read/write policies are local, authenticated values, never policies supplied
   * by the command.
   */
  CommandRunner.Outcome run(
      CommandRunner.Command command,
      FileAccess reads,
      FileAccess writes,
      Map<String, String> host,
      BooleanSupplier cancelled);
}
