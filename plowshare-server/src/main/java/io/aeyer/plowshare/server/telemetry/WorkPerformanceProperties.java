package io.aeyer.plowshare.server.telemetry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Warning-only thresholds and bounded observer storage; these are never execution deadlines. */
@ConfigurationProperties("plowshare.telemetry.performance")
public record WorkPerformanceProperties(
    @DefaultValue("PT2S") Duration warningThreshold,
    @DefaultValue("256") int eventQueueCapacity,
    @DefaultValue("1024") int maxActiveWatches) {
  public WorkPerformanceProperties {
    if (warningThreshold == null
        || warningThreshold.isZero()
        || warningThreshold.isNegative()
        || warningThreshold.compareTo(Duration.ofDays(1)) > 0
        || eventQueueCapacity < 1
        || eventQueueCapacity > 65536
        || maxActiveWatches < 1
        || maxActiveWatches > 65536)
      throw new IllegalArgumentException("Invalid work performance observation bounds");
  }

  public static WorkPerformanceProperties defaults() {
    return new WorkPerformanceProperties(Duration.ofSeconds(2), 256, 1024);
  }
}
