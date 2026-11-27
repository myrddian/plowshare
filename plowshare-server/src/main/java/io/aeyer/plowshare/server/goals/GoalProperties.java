package io.aeyer.plowshare.server.goals;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("plowshare.goals")
record GoalProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("8") int defaultActivations,
    @DefaultValue("40") int maxActivations,
    @DefaultValue("4") int concurrency,
    @DefaultValue("24") int scanSize,
    @DefaultValue("3") int maxNoProgress,
    @DefaultValue("8") int cycleModelCalls,
    @DefaultValue("PT1M") Duration automaticCycleInterval) {
  GoalProperties {
    if (defaultActivations < 1
        || maxActivations < defaultActivations
        || maxActivations > 1000
        || concurrency < 1
        || concurrency > 32
        || scanSize < concurrency
        || scanSize > 128
        || maxNoProgress < 1
        || maxNoProgress > maxActivations
        || cycleModelCalls < 1
        || cycleModelCalls > 1000
        || automaticCycleInterval.compareTo(Duration.ofMillis(1)) < 0
        || automaticCycleInterval.compareTo(Duration.ofDays(365)) > 0)
      throw new IllegalArgumentException("Invalid conversation goal limits");
  }
}
