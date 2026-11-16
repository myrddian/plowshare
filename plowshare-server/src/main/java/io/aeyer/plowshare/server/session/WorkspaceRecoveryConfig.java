package io.aeyer.plowshare.server.session;

import io.aeyer.plowshare.server.agents.WorkspaceRunProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(WorkspaceRunProperties.class)
public class WorkspaceRecoveryConfig {
  @Bean
  public WorkspaceBindingRepository workspaceBindingRepository(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      WorkspaceRunProperties properties) {
    return new JdbcWorkspaceBindingRepository(
        jdbc, new TransactionTemplate(transactions), properties.getSqlTimeout());
  }

  @Bean
  public WorkspaceRecovery workspaceRecovery(
      WorkspaceBindingRepository bindings, SessionRegistry sessions) {
    return new RecoveredWorkspaces(bindings, sessions);
  }
}
