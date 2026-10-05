package io.aeyer.plowshare.server.orchestrations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A run's acceptance commands (spec 2026-09-29 §1b), as registered from its spec.md: {@link
 * OrchestrationChecks}' shape, one row per command, each with the consent it runs under. Unlike the
 * check, the set is not fixed once written — spec.md is the conductor's to edit until the
 * acceptance stage, and a section that changed is registered again, with new approvals, before it
 * runs: what runs is always what the person was asked about.
 */
public interface OrchestrationAcceptance {
  /**
   * One registered command, with the consent it runs under.
   *
   * @param orchestration the run
   * @param position its place in the section, from 0
   * @param line the line as written in spec.md
   * @param argv the program and its arguments
   * @param stdin its input, or null
   * @param exit the exit code it must end with
   * @param expect text its output must contain, or null
   * @param side the side it was placed on, and so the side its consent is for
   * @param cwd the directory it runs in
   * @param consent {@link OrchestrationChecks#OPEN} or {@link OrchestrationChecks#APPROVAL}
   * @param approval the approval's id when {@code consent} is {@link OrchestrationChecks#APPROVAL},
   *     else null
   * @param setAt when it was registered
   * @param runsFor how many seconds it must still be running after (spec 2026-10-01 §1), or null
   *     for a command held to {@code exit}
   */
  public record Registered(
      String orchestration,
      int position,
      String line,
      List<String> argv,
      String stdin,
      int exit,
      String expect,
      String side,
      String cwd,
      String consent,
      String approval,
      Instant setAt,
      Integer runsFor) {
    /** Copies {@code argv}, so a registered command is the command that runs. */
    public Registered {
      argv = List.copyOf(argv);
    }

    /** A command held to its exit code — every row before {@code runs-for:} existed. */
    public Registered(
        String orchestration,
        int position,
        String line,
        List<String> argv,
        String stdin,
        int exit,
        String expect,
        String side,
        String cwd,
        String consent,
        String approval,
        Instant setAt) {
      this(
          orchestration,
          position,
          line,
          argv,
          stdin,
          exit,
          expect,
          side,
          cwd,
          consent,
          approval,
          setAt,
          null);
    }
  }

  /** Atomically replaces requirements and all commands; readers never see half of a set. */
  void replace(String orchestration, String requirements, List<Registered> commands);

  List<Registered> find(String orchestration);

  Optional<String> requirements(String orchestration);

  Optional<Registered> byApproval(String approval);
}
