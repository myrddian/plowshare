package io.aeyer.plowshare.server.reads;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.cache.ProjectionCaches;
import io.aeyer.plowshare.server.cache.ProjectionVersions.Domain;
import io.aeyer.plowshare.server.llm.accounting.CachedUsageReportRepository;
import io.aeyer.plowshare.server.llm.accounting.UsageReportRepository;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Repository decorators retain typed ownership and reuse the existing cache/version machinery. */
@Configuration
public class ReportReadConfiguration {
  @Bean
  @Primary
  UsageReportRepository cachedUsageReports(
      @org.springframework.beans.factory.annotation.Qualifier("jdbcUsageReportRepository")
          UsageReportRepository source,
      ProjectionCaches caches,
      ReadAdmission admission,
      ObjectMapper json) {
    return new CachedUsageReportRepository(
        source,
        caches.create(
            "usage-report-v1", Set.of(Domain.OBSERVATIONS, Domain.TRANSCRIPTS, Domain.AUTHORITY)),
        admission,
        json);
  }
}
