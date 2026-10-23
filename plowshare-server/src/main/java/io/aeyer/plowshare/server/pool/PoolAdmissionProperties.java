package io.aeyer.plowshare.server.pool;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the wait-only throttle in front of database connection acquisition.
 *
 * <p>The permit count and the delay threshold are deliberately not configurable. They are derived
 * from the pool size by {@link #permits(int)} and {@link #threshold(int)}, so they cannot be set to
 * values that silently disagree with the pool. Only the shape of the throttle is configured here.
 *
 * <p><b>Why a margin.</b> {@code permitMargin} connections are never handed out by the throttle
 * because some consumers take primary-pool connections without passing through it. There are three
 * classes of them: Flyway at boot; Spring Boot's {@code DataSourceHealthIndicator}, which borrows
 * one on every {@code /actuator/health} probe; and second connections taken on a thread that
 * already holds one, where Spring's {@code PROPAGATION_REQUIRES_NEW} suspends the outer connection
 * holder and borrows again on the same thread. Without the margin the first two would compete with
 * throttled work for the last connections.
 *
 * <p><b>What the margin does not cover.</b> It is a fixed reserve, not a bound on the third class.
 * The throttle takes a real permit for a nested borrow whenever one is immediately free, so those
 * borrows are normally accounted for; but at saturation a nested borrow is admitted permit-free
 * rather than deadlocking its own thread, and the margin cannot size that, because the number of
 * simultaneously nesting threads is a property of the workload rather than of this configuration.
 * The residual is published as {@code plowshare.pool.admission.overdraft}: a sustained non-zero
 * count means threads are holding more connections than there are permits, and the pool, not the
 * margin, is the next thing to raise. The margin is also not a guard against races inside the
 * throttle.
 *
 * <p><b>The cross-file invariant.</b> {@code ceiling} is the longest the throttle will ever delay
 * one acquisition. It must stay clearly below Hikari's {@code connection-timeout}, which lives in
 * {@code spring.datasource.hikari} and not here. If the ceiling reached the acquire timeout, a
 * request delayed by the throttle and a request that failed because the pool was exhausted would
 * look the same in the logs, and the attribution this throttle exists to provide would be lost. The
 * two numbers sit in different files and nothing else ties them together, so {@link
 * #validateAgainst(Duration)} enforces the relationship at wiring time and fails startup rather
 * than leaving it as a comment. It cannot run in the constructor because this record does not know
 * the acquire timeout.
 *
 * @param enabled whether acquisitions pass through the throttle at all
 * @param permitMargin connections withheld from the throttle for consumers that bypass it
 * @param thresholdFraction fraction of the permits at which delay begins, in the open interval (0,
 *     1)
 * @param baseQuantum the delay unit the curve scales, at occupancy just past the threshold
 * @param ceiling the longest delay applied to one acquisition
 * @param pressureWindow time over which observed acquire latency pressure decays by half; {@code
 *     PoolAdmissionConfiguration} passes it to {@link AdmissionThrottle}
 */
@ConfigurationProperties("plowshare.pool-admission")
public record PoolAdmissionProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("2") int permitMargin,
    @DefaultValue("0.5") double thresholdFraction,
    @DefaultValue("20ms") Duration baseQuantum,
    @DefaultValue("3s") Duration ceiling,
    @DefaultValue("10s") Duration pressureWindow) {

  /** Minimum distance between the ceiling and the pool acquire timeout. */
  static final Duration MINIMUM_GAP = Duration.ofSeconds(1);

  /** Permits that must lie between the threshold and the total for the delay curve to exist. */
  private static final int MINIMUM_CURVE_PERMITS = 2;

  /** Validates everything intrinsic to this record; the acquire timeout is checked separately. */
  public PoolAdmissionProperties {
    if (permitMargin < 0) {
      throw invalid("permitMargin must not be negative: " + permitMargin);
    }
    if (!(thresholdFraction > 0.0 && thresholdFraction < 1.0)) {
      throw invalid("thresholdFraction must be in (0, 1): " + thresholdFraction);
    }
    if (!isPositive(baseQuantum)) {
      throw invalid("baseQuantum must be positive: " + baseQuantum);
    }
    if (!isPositive(ceiling)) {
      throw invalid("ceiling must be positive: " + ceiling);
    }
    if (!isPositive(pressureWindow)) {
      throw invalid("pressureWindow must be positive: " + pressureWindow);
    }
    if (baseQuantum.compareTo(ceiling) >= 0) {
      throw invalid("baseQuantum (" + baseQuantum + ") must be below ceiling (" + ceiling + ")");
    }
  }

  /** Connections the throttle may have outstanding: the pool size less the bypass margin. */
  public int permits(int poolSize) {
    int permits = poolSize - permitMargin;
    if (permits < 1) {
      throw new IllegalArgumentException(
          "Pool size " + poolSize + " leaves no permits after a margin of " + permitMargin);
    }
    return permits;
  }

  /**
   * Occupancy at which delay begins: {@code thresholdFraction} of {@link #permits(int)}, floored,
   * and at least one.
   *
   * <p>{@link AdmissionThrottle} cannot define its delay curve unless at least two permits lie
   * between the threshold and the total. That bound depends on the pool size, the margin and the
   * fraction together, so it is checked here, where all three are named, rather than surfacing as
   * an anonymous failure in the throttle's constructor.
   *
   * @throws IllegalArgumentException when fewer than two permits remain above the threshold
   */
  public int threshold(int poolSize) {
    int permits = permits(poolSize);
    int threshold = Math.max(1, (int) Math.floor(permits * thresholdFraction));
    int gap = permits - threshold;
    if (gap < MINIMUM_CURVE_PERMITS) {
      throw new IllegalArgumentException(
          "pool size "
              + poolSize
              + " with permitMargin "
              + permitMargin
              + " and thresholdFraction "
              + thresholdFraction
              + " gives "
              + permits
              + " permits and threshold "
              + threshold
              + ", leaving gap "
              + gap
              + "; the throttle needs a gap of at least "
              + MINIMUM_CURVE_PERMITS
              + ". Increase the pool, or lower the margin or fraction.");
    }
    return threshold;
  }

  /**
   * Fails when the ceiling is not at least {@link #MINIMUM_GAP} below the pool acquire timeout.
   *
   * @param acquireTimeout Hikari's {@code connection-timeout}
   * @throws IllegalStateException when the two values are too close to tell apart in logs
   */
  public void validateAgainst(Duration acquireTimeout) {
    if (!isPositive(acquireTimeout)) {
      throw new IllegalArgumentException("The pool acquire timeout must be positive");
    }
    Duration gap = acquireTimeout.minus(ceiling);
    if (gap.compareTo(MINIMUM_GAP) < 0) {
      throw new IllegalStateException(
          "plowshare.pool-admission.ceiling ("
              + ceiling.toMillis()
              + "ms) must be at least "
              + MINIMUM_GAP.toMillis()
              + "ms below spring.datasource.hikari.connection-timeout ("
              + acquireTimeout.toMillis()
              + "ms), but the gap is "
              + gap.toMillis()
              + "ms. A throttle wait and a pool acquire timeout must be distinguishable in logs.");
    }
  }

  private static IllegalArgumentException invalid(String detail) {
    return new IllegalArgumentException(
        "Invalid plowshare.pool-admission configuration: " + detail);
  }

  private static boolean isPositive(Duration value) {
    return value != null && !value.isZero() && !value.isNegative();
  }
}
