package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.cache.ProjectionCacheProperties;
import io.aeyer.plowshare.server.cache.ProjectionCaches;
import io.aeyer.plowshare.server.cache.ProjectionItemVersions;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.Set;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** One lifecycle owner and one shared entry/byte budget for all log-visibility consumers. */
@Configuration
public class InformationVisibilityConfiguration {
  /**
   * Targeted checks from per-tree facts (design slice 2): one item-scoped, versioned projection
   * ({@code log-tree-facts-v1}) holds each tree under {@code conversation:<root>}; retained inputs
   * read the shared slice 1 facts. Collection reads keep the per-account deny set.
   */
  @Bean
  public InformationLogVisibility informationLogVisibility(
      JdbcTemplate jdbc,
      ProjectionCaches caches,
      ProjectionVersions versions,
      ProjectionItemVersions itemVersions,
      ProjectionCacheProperties properties,
      AuthoritySnapshots authority,
      KnowledgeFactSource facts,
      ReadAdmission admission) {
    var trees =
        new CachedLogReadability(
            caches.createItemScoped(
                "log-tree-facts-v1",
                Set.of(ProjectionVersions.Domain.READABILITY),
                ProjectionCaches.Freshness.VERSIONED),
            new JdbcLogTreeFactRepository(jdbc),
            facts,
            itemVersions,
            authority,
            admission,
            // A hint is one log id (at most 256 characters) and its roots: sixteen per tree entry.
            properties.entries() * 16);
    return new JdbcInformationLogVisibility(
        jdbc,
        caches.create(
            "information-log-visibility-v1",
            Set.of(ProjectionVersions.Domain.READABILITY, ProjectionVersions.Domain.AUTHORITY),
            // Collection reads only; each keeps its own per-read version fence.
            ProjectionCaches.Freshness.NOTIFIED),
        versions,
        admission,
        trees);
  }
}
