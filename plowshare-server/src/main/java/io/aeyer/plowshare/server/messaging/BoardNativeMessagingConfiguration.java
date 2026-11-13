package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.RelayProperties;
import io.aeyer.plowshare.server.relay.RelayPublishingProperties;
import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit bridge wiring; the forum runtime does not construct or query native messaging. */
@Configuration
@EnableConfigurationProperties(BoardMessageExportProperties.class)
public class BoardNativeMessagingConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public BoardNativeExportRepository boardNativeExportRepository(JdbcTemplate jdbc) {
    return new JdbcBoardNativeExportRepository(jdbc);
  }

  @Bean
  public BoardNativeMessageRequests boardNativeRequests(
      BoardMessageExportProperties properties,
      MessageExportProperties external,
      BoardNativeExportRepository repository,
      NativeExecutionSources sources,
      @Lazy NativeMessageExports messages,
      RelayPublishingProperties grants,
      ProjectMembers members,
      ProjectWorkspaces projects,
      RelayProperties relay,
      RelayWorkerProperties workers,
      UnitOfWork work) {
    return new BoardNativeMessageRequests(
        properties,
        external,
        repository,
        sources,
        messages,
        grants,
        members,
        projects,
        relay.getMaxForwardingHops(),
        work,
        workers.getFailureBackoff(),
        Instant::now);
  }
}
