package io.aeyer.plowshare.server.reads;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One shared budget across server entry points; metrics contain no principal/resource labels. */
@Configuration
@EnableConfigurationProperties({ReadCapacityProperties.class, ReadSchedulingProperties.class})
public class ReadCapacityConfiguration {
  @Bean
  ReadAdmission readAdmission(
      ReadCapacityProperties properties,
      ReadSchedulingProperties scheduling,
      ObjectProvider<MeterRegistry> registries) {
    var admission = new BoundedReadAdmission(properties, scheduling);
    var registry = registries.getIfAvailable();
    if (registry != null) {
      for (var work : ReadAdmission.Work.values())
        Gauge.builder("plowshare.read.active", admission, value -> value.active(work))
            .tag("work", work.name())
            .register(registry);
      Gauge.builder("plowshare.read.waiting", admission, BoundedReadAdmission::waitingReports)
          .register(registry);
      Gauge.builder("plowshare.read.rejected", admission, BoundedReadAdmission::rejected)
          .register(registry);
    }
    return admission;
  }
}
