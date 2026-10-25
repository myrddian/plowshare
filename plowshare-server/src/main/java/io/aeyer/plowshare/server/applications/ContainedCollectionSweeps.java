package io.aeyer.plowshare.server.applications;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The production trigger for {@link ContainedSourceCollector}.
 *
 * <p><strong>Both, because they answer different crashes.</strong> Startup reconciliation answers
 * the crash that happened while this server was down: a creation interrupted between staging its
 * source and committing its row leaves a directory nothing accounts for, and no request will ever
 * arrive to clean it up. The repeating sweep answers the crash that happens while the server is up:
 * a destruction that committed and whose source removal did not finish leaves an unmarked
 * collection record, and that record is only acted on by a sweep. Neither alone closes the gap the
 * capability names as failure — "an interrupted create leaves no orphan revision directory".
 *
 * <p>This follows {@code ApprovalContinuationRecovery}'s shape deliberately rather than inventing a
 * mechanism: an {@code ApplicationReadyEvent} listener that also carries the {@code @Scheduled}
 * method, with a readiness flag so nothing sweeps while the context is still initialising. It runs
 * on the application-context scheduler {@code SchedulingConfig} owns.
 *
 * <p><strong>Why a timer is acceptable here when {@code Retention} refused one.</strong> That
 * record's objections were that a sweep deletes bytes with nobody watching, that a boot timer fires
 * in every Spring test, and that several servers on one database means several racing sweeps. The
 * first does not apply: the only bytes this can delete are directories under the operator's own
 * containment root that no committed record accounts for — already-unreachable garbage, not a
 * misconfigured export directory, and an unconfigured server has no root and sweeps nothing. The
 * second is answered structurally rather than by argument: this bean is conditional on {@code
 * ContainmentConfig}, which is itself conditional on {@code plowshare.containment.root}, so a test
 * context that has not configured nesting does not contain this object at all. The third is real
 * and is why every step is idempotent — removal is {@code deleteIfExists}, the mark is an
 * idempotent {@code UPDATE} and the collection insert is {@code ON CONFLICT DO NOTHING} — so
 * concurrent sweeps duplicate work but cannot corrupt it.
 *
 * <p>Overlap is guarded in process: a sweep already running is not started again, so a long sweep
 * cannot pile up behind the fixed delay.
 */
public final class ContainedCollectionSweeps implements ApplicationListener<ApplicationReadyEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(ContainedCollectionSweeps.class);

  private final ContainedSourceCollector collector;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile boolean ready;

  public ContainedCollectionSweeps(ContainedSourceCollector collector) {
    this.collector = Objects.requireNonNull(collector);
  }

  /** Startup reconciliation: the first sweep is the one that answers a crash while down. */
  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    this.ready = true;
    sweep();
  }

  @Scheduled(
      initialDelayString = "${plowshare.containment.sweep-initial-delay:PT5M}",
      fixedDelayString = "${plowshare.containment.sweep-interval:PT15M}")
  public void sweep() {
    if (!ready || !running.compareAndSet(false, true)) return;
    try {
      var swept = collector.sweep();
      // Counts, never paths: a containment root is deployment configuration and does not belong
      // in a log. A quiet server stays quiet -- but a *blind* one does not. A sweep that could not
      // read its root answers the same zeroes a clean root does, and staying silent about that
      // would leave an operator unable to tell a system with nothing to collect from one that has
      // stopped collecting. This component carries ADR 0042's observability claim; it cannot be
      // the thing that hides its own failure.
      // ABSENT is deliberately not among them. A configured root that does not exist yet is a
      // server that has not created a contained Application, which is the steady state of a fresh
      // installation: warning about it every interval would be this component crying wolf at
      // nothing, and an operator who learns to ignore the warning loses the blind-sweep signal it
      // exists to carry.
      if (swept.outcome() == ContainedSourceCollector.Outcome.PARTIAL)
        LOG.warn(
            "Contained source sweep did not enumerate its whole root; collected {}, retained {}."
                + " Counts are what this sweep managed, not what the root holds",
            swept.collected(),
            swept.retained());
      else if (swept.collected() > 0)
        LOG.info(
            "Collected {} contained source areas, retained {}",
            swept.collected(),
            swept.retained());
    } catch (RuntimeException failed) {
      // A sweep is housekeeping and must never take the scheduler down with it. The work it did
      // not finish is still recorded, so the next sweep retries exactly what is left.
      LOG.warn("Contained source sweep did not finish: {}", failed.toString());
    } finally {
      running.set(false);
    }
  }
}
