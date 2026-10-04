package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.Retention;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one door a retention sweep is run through.
 *
 * <h2>Why this is a verb an operator calls and not a timer this server owns</h2>
 *
 * <p>Plowshare has no scheduler. That is a fact about this server rather than an omission this
 * endpoint works around: {@code V17__conversation_origin.sql} refuses a {@code schedule} origin
 * because a value nothing can write does not belong in a constraint, and the retention design says
 * the same of itself — it must not read as though something is built. So the choice was a timer
 * started at boot or an explicit operation, and {@link Retention} carries the four reasons it is
 * this. The two that decide it: <b>a sweep deletes bytes</b>, and a timer deletes them with nobody
 * watching; and <b>a timer started from a bean fires in the test suite</b>, which stands up Spring
 * contexts against real databases in dozens of tests.
 *
 * <p><b>The operator already has a scheduler</b> — cron, a systemd timer, a CronJob, a person — and
 * a sweep is written to be safe to call twice, which is what makes handing them the verb the whole
 * of the answer rather than half of it.
 *
 * <h2>Behind the same door as everything else</h2>
 *
 * <p>Under {@code /v1}, so {@code AuthFilter} refuses it to a request carrying no access token
 * exactly as it refuses every other path. There is no separate operator credential here and there
 * should not be one: this server has one authentication story, and a second one invented for the
 * one endpoint that removes data is the shape that gets misconfigured.
 */
@RestController
public class RetentionController {

  private final Retention retention;

  public RetentionController(Retention retention) {
    this.retention = retention;
  }

  /**
   * {@code POST /v1/retention/sweep} — eject what was marked, then mark what policy selects, and
   * say what happened.
   *
   * <h2>200 and a report, not 202 and a job</h2>
   *
   * <p>Every other endpoint in this server that starts work answers 202 with a job to poll, because
   * that work calls a model and takes minutes. A sweep calls no model: it is a few queries, a file
   * write per payload and an UPDATE per row, all of it bounded by what is actually marked, and a
   * caller who has just asked for data to be removed wants to be told what was removed rather than
   * a handle to ask later.
   *
   * <p><b>The report is the point of the synchronous answer.</b> An operation that deletes file
   * bodies and answers "accepted" gives an operator nothing to check; {@code SweepReport} names how
   * many trees were marked, how many results were ejected and how many characters those held —
   * which is the number they were trying to bring down.
   *
   * <p><b>Safe to call twice</b>, which is what makes an explicit trigger workable at all: every
   * ejection is guarded by {@code ejected_at IS NULL} and every move by the transition table, so a
   * second call against the same state finds nothing to do and reports nothing done. An operator
   * who is not sure whether last night's run happened can simply run it.
   *
   * <p><b>Nothing is marked and ejected by the same call.</b> A sweep ejects what an earlier one
   * marked and then marks what is newly eligible, so a conversation the policy selects tonight
   * keeps every payload until the next sweep and can be cancelled back to active in between. That
   * window is the whole of what the staged path buys, and a sweep that closed it in one pass would
   * have staging in name only.
   */
  @PostMapping("/v1/retention/sweep")
  public Retention.SweepReport sweep() {
    return retention.sweep();
  }
}
