package io.aeyer.plowshare.server.swarm;

import io.aeyer.plowshare.server.config.Live;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The swarm's live keys — spec 2026-09-29 §5 and §9. The scheduler's two ({@code quantum}, {@code
 * wait-warning}) it asks at every wait; the board's two ({@code wake-cap}, {@code closing-reserve})
 * are asked as each wake starts and as each root topic is opened. Pool ceilings ({@code swarm:} on
 * a pool) are bound at boot with the pools and are not here. The plain getters are the bound
 * values; the {@code …Now()} accessors are the live reads.
 */
@ConfigurationProperties(prefix = "plowshare.swarm")
public class SwarmProperties {

  private static final Logger log = LoggerFactory.getLogger(SwarmProperties.class);

  static final String QUANTUM = "plowshare.swarm.quantum";
  static final String WAIT_WARNING = "plowshare.swarm.wait-warning";
  static final String WAKE_CAP = "plowshare.swarm.wake-cap";
  static final String CLOSING_RESERVE = "plowshare.swarm.closing-reserve";

  private boolean recoverAtBoot = true;
  private int quantum = 4;
  private Duration waitWarning = Duration.ofMinutes(10);
  private int wakeCap = 12;
  private int closingReserve = 10;
  private int maxDepth = 2;
  private RuntimeConfig live;

  public int getQuantum() {
    return quantum;
  }

  public void setQuantum(int quantum) {
    this.quantum = quantum;
  }

  public Duration getWaitWarning() {
    return waitWarning;
  }

  public void setWaitWarning(Duration waitWarning) {
    this.waitWarning = waitWarning;
  }

  public int getWakeCap() {
    return wakeCap;
  }

  public void setWakeCap(int wakeCap) {
    this.wakeCap = wakeCap;
  }

  public int getClosingReserve() {
    return closingReserve;
  }

  public void setClosingReserve(int closingReserve) {
    this.closingReserve = closingReserve;
  }

  @Autowired(required = false)
  public void setLive(RuntimeConfig live) {
    this.live = live;
  }

  /** Steps a run keeps its place for; a value below one in the map falls back to the bound one. */
  @Live(QUANTUM)
  public int quantumNow() {
    int value = live == null ? quantum : live.intOr(QUANTUM, quantum);
    return value < 1 ? quantum : value;
  }

  /** How long a wait runs before it is logged and reported overdue. ISO-8601 in the map. */
  @Live(WAIT_WARNING)
  public Duration waitWarningNow() {
    Optional<String> found = live == null ? Optional.empty() : live.get(WAIT_WARNING);
    if (found.isEmpty()) {
      return waitWarning;
    }
    try {
      Duration parsed = Duration.parse(found.get().trim());
      return parsed.isNegative() || parsed.isZero() ? waitWarning : parsed;
    } catch (DateTimeParseException notADuration) {
      log.warn(
          "{} holds '{}', which is not an ISO-8601 duration; using {}",
          WAIT_WARNING,
          found.get(),
          waitWarning);
      return waitWarning;
    }
  }

  /** Steps one wake may take before it must end (spec §5); below one falls back. */
  @Live(WAKE_CAP)
  public int wakeCapNow() {
    int value = live == null ? wakeCap : live.intOr(WAKE_CAP, wakeCap);
    return value < 1 ? wakeCap : value;
  }

  /**
   * Percent of a root topic's pot kept for its openers to close with (spec §9); 0–99. A hundred or
   * more would leave the members nothing to be woken on, so it falls back like any other value
   * outside the range.
   */
  @Live(CLOSING_RESERVE)
  public int closingReserveNow() {
    int value = live == null ? closingReserve : live.intOr(CLOSING_RESERVE, closingReserve);
    return value < 0 || value >= 100 ? closingReserve : value;
  }

  public int getMaxDepth() {
    return maxDepth;
  }

  public void setMaxDepth(int maxDepth) {
    this.maxDepth = maxDepth;
  }

  @Live("plowshare.swarm.max-depth")
  public int maxDepthNow() {
    int value = live == null ? maxDepth : live.intOr("plowshare.swarm.max-depth", maxDepth);
    return value < 0 ? maxDepth : value;
  }

  /** Bound at boot; the test classpath turns recovery off. */
  public boolean isRecoverAtBoot() {
    return recoverAtBoot;
  }

  public void setRecoverAtBoot(boolean recoverAtBoot) {
    this.recoverAtBoot = recoverAtBoot;
  }
}
