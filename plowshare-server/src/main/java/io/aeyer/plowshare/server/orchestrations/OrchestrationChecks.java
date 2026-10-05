package io.aeyer.plowshare.server.orchestrations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A run's check: the command the harness runs whenever one of its checked stages is marked done.
 * Set once — the primary key is the run — so the bar cannot be moved once it is written. The one
 * way back is {@link #clear}, for a check a person refused to allow, before any checked stage has
 * passed it.
 */
public interface OrchestrationChecks {
  public static final String OPEN = "open";
  public static final String APPROVAL = "approval";

  /**
   * @param consent {@link #OPEN} when the side's mode let commands run unasked when it was set,
   *     {@link #APPROVAL} when a person was asked
   * @param approval the approval's id when {@code consent} is {@link #APPROVAL}, else null
   */
  public record Check(
      String orchestration,
      List<String> argv,
      String side,
      String cwd,
      String consent,
      String approval,
      Instant setAt) {
    public Check {
      argv = List.copyOf(argv);
    }
  }

  /** Stores a command once; an existing command and its consent cannot be replaced. */
  boolean set(Check check);

  /** Clears only the command carrying this approval, before the engine admits another. */
  boolean clear(String orchestration, String approval);

  Optional<Check> find(String orchestration);
}
