package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.requests.RequestedLimitsChange;
import org.springframework.stereotype.Service;

/**
 * Moving a running job's ceilings without restarting it: the whole decision, in the order it has to
 * be made in, above {@link JobStore} and {@link RunLimits} and below whatever surface was asked.
 *
 * <h2>Why this is a class and not a dozen lines in a controller</h2>
 *
 * <p>It was a dozen lines in {@code api.AgentController.limits}, and a WebSocket frame reaching the
 * same capability calls the service and never the controller — so every rule left in the handler is
 * a rule the second surface silently does not have. {@code Runs}' own javadoc makes the general
 * argument. What moved here is the <b>decision</b>: three refusals, the two mutations, and above
 * all the order between them.
 *
 * <h2>The ordering is the reason this had to move as a decision</h2>
 *
 * <p>The budget is moved <em>before</em> the cap is, and nothing throws for the order. A body
 * naming both, with the budget half refused, must not leave the cap half applied — the caller is
 * told nothing was changed, and a reversed implementation would have changed something. That is a
 * defect no refusal can point at and no status can show, so it is pinned directly: {@code
 * LimitsTest.a_refused_budget_leaves_the_turn_cap_where_it_was} sends one body naming both and
 * asserts the cap did not move.
 *
 * <p>The three refusals are ordered too, and each order is a choice about which correction a caller
 * can act on: the job is fetched first, so an id this process does not know is a miss rather than a
 * complaint about a body; the body's own shape next, through {@link RequestedLimitsChange}, so a
 * request that asks for nothing is told so whatever kind of job it named; then whether the job has
 * limits at all, then whether it is still running — the kind of job before its state, because "not
 * an agent run" is the fact that would still be true had it been running.
 *
 * <h2>What is deliberately still {@code requests}'</h2>
 *
 * <p>{@link RequestedLimitsChange#wanted} holds the at-least-one-field refusal and wraps {@code
 * RequestedTurnCap.in} so that a turn cap said twice is refused in its own words. That nesting is
 * load-bearing and is not restated here — see that class's own javadoc.
 */
@Service
public final class Limits {

  private final JobStore jobs;

  public Limits(JobStore jobs) {
    this.jobs = jobs;
  }

  /**
   * Move them, or refuse.
   *
   * <p>The job is returned rather than a view of it, so that each surface renders the state it has
   * just changed in its own shape.
   *
   * @param id the job whose ceilings are being moved
   * @param maxTurns how many turns this run may take from here, or {@code null} to leave the cap
   *     alone
   * @param noTurnCap whether the cap comes off altogether, or {@code null}
   * @param maxModelCalls what this run and everything it delegates to may spend, or {@code null} to
   *     leave the budget alone
   * @return the job, with whatever moved already moved
   * @throws NotFoundFault if this process holds no job under that id — {@link JobStore#get}'s own
   *     refusal, unchanged
   * @throws CallerFault if the body names no limit to move, names a turn cap twice, names a job
   *     that is not one agent's run, names one that has already finished, or asks for a budget
   *     {@code Budget} refuses
   */
  public Job move(String id, Integer maxTurns, Boolean noTurnCap, Integer maxModelCalls) {
    Job job = jobs.get(id);
    TurnCap wanted = RequestedLimitsChange.wanted(maxTurns, noTurnCap, maxModelCalls, "this run");
    RunLimits limits =
        job.limits()
            .orElseThrow(
                () ->
                    new CallerFault(
                        "job "
                            + id
                            + " is not an agent run and has no limits this server holds. A"
                            + " curator pass carries its own budget across every ruling in it, and"
                            + " it is not on this handle to move. Nothing was changed."));
    if (job.state() != Job.State.RUNNING) {
      throw new CallerFault(
          "job "
              + id
              + " has already finished, so there is"
              + " nothing left for a limit to bound. Nothing was changed.");
    }
    if (maxModelCalls != null) {
      limits.budget().changeToOrRefuse(id, maxModelCalls);
    }
    // The cap second, and only once the budget has either moved or been
    // refused: a body that named both and had one refused must not leave
    // half of it applied without saying so, and the budget is the half with
    // a durable consequence.
    if (wanted != null) {
      if (wanted.capped()) {
        limits.cap().changeTo(wanted.turns());
      } else {
        limits.cap().lift();
      }
    }
    return job;
  }

  public Job moveFor(
      String id, String account, Integer maxTurns, Boolean noTurnCap, Integer maxModelCalls) {
    jobs.getFor(id, account);
    return move(id, maxTurns, noTurnCap, maxModelCalls);
  }
}
