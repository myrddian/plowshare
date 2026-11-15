package io.aeyer.plowshare.server.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Failure diagnostics remain available even when no metrics registry is installed. */
@Configuration(proxyBeanMethods = false)
public class FailureTelemetryConfiguration {
  @Bean
  public FailureReporter failureReporter(
      ObjectProvider<MeterRegistry> registries,
      @Value("${plowshare.telemetry.failures.log-interval:PT30S}") Duration logInterval) {
    var reporter = new AggregateFailureReporter(logInterval, System::nanoTime);
    var registry = registries.getIfAvailable();
    if (registry != null) reporter.bind(registry);
    return reporter;
  }
}
