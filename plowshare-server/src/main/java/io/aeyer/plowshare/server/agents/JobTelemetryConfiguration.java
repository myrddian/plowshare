package io.aeyer.plowshare.server.agents;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Optional metrics composition; standalone runtimes do not require actuator. */
@Configuration(proxyBeanMethods = false)
public class JobTelemetryConfiguration {
  @Bean
  public JobTelemetry jobTelemetry(ObjectProvider<MeterRegistry> registries) {
    var registry = registries.getIfAvailable();
    return registry == null ? JobTelemetry.NONE : new MicrometerJobTelemetry(registry);
  }
}
