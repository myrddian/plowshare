package io.aeyer.plowshare.server.events;

import java.time.Instant;
import java.util.List;

/**
 * Existing firing inbox as a transactional subscriber for fired events, independent of jobs.
 *
 * <p>The sibling of {@link ScheduledArrival}, and deliberately a separate contract: the two
 * families' record steps differ in exactly the way their policies differ. A scheduled occurrence
 * locks every target queue and applies supersession before its publication is acknowledged, because
 * a tick is claimed once and no job may start from {@code record}. A fired event does neither —
 * {@code Dispatcher.dispatch} applies the trigger's queue cap when the firing is handed over — and
 * writing one interface over both would have made that difference invisible.
 *
 * <p>{@link ScheduledArrival.Arrival} is reused as the value rather than duplicated: it is a firing
 * paired with the trigger it arrived for, and its invariant — that the two agree, or that both name
 * no trigger — is the same invariant here.
 *
 * <p>The split between recording and dispatching is the same as the scheduled family's and exists
 * for the same reason: recording is database work that belongs inside the publisher's transaction,
 * and dispatching starts jobs and must not run inside one. A lost dispatch hint therefore costs
 * recovery latency and never the firing.
 */
public interface FiredArrival {

  /**
   * Copies a firing for every listening trigger and performs no external work.
   *
   * <p>Joins the caller's transaction, so the firings and the publication they were copied from
   * commit together or not at all. An event no trigger listens for still records one {@code
   * unmatched} firing naming no trigger, which is what keeps a fired event accounted for whether or
   * not anything was waiting to act on it.
   */
  List<ScheduledArrival.Arrival> recordFired(String event, EventPayload data, Instant now);

  /**
   * Post-commit hint to start recorded work; lost hints leave durable firings for recovery.
   *
   * <p>Declared with the signature {@link ScheduledArrival#dispatch} already has, so one
   * implementation satisfies both. That is intended rather than incidental: handing a recorded
   * firing to its target is the same operation whichever family recorded it, and it is only the
   * recording that differs.
   */
  void dispatch(List<ScheduledArrival.Arrival> arrivals);
}
