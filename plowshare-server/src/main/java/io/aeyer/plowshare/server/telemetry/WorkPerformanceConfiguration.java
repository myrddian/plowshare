package io.aeyer.plowshare.server.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One bounded, warning-only observer shared by workflow and request owners. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkPerformanceProperties.class)
public class WorkPerformanceConfiguration {
  @Bean(destroyMethod = "close")
  SlowWorkWarnings workPerformanceReporter(
      WorkPerformanceProperties properties, ObjectProvider<MeterRegistry> registries) {
    var reporter = new SlowWorkWarnings(properties);
    var registry = registries.getIfAvailable();
    if (registry != null) reporter.bind(registry);
    return reporter;
  }
}
