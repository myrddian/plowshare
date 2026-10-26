package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.cache.*;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.Set;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** One lifecycle owner and one shared entry/byte budget for all log-visibility consumers. */
@Configuration
public class InformationVisibilityConfiguration {
  @Bean
  public InformationLogVisibility informationLogVisibility(
      JdbcTemplate jdbc,
      ProjectionCaches caches,
      ProjectionVersions versions,
      ReadAdmission admission) {
    return new JdbcInformationLogVisibility(
        jdbc,
        caches.create(
            "information-log-visibility-v1",
            Set.of(ProjectionVersions.Domain.READABILITY, ProjectionVersions.Domain.AUTHORITY)),
        versions,
        admission);
  }
}
