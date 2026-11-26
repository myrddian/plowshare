package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationRecords;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.approvals.SuspendedRunCalls;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoalProperties.class)
class GoalConfiguration {
  @Bean
  GoalRepository goalRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      SqlWorkBudget sql,
      SqlWorkBudgetProperties budgets) {
    return new JdbcGoalRepository(jdbc, transactions, sql, budgets);
  }

  @Bean
  GoalExecution goalExecution(
      ConversationStore conversations,
      ConversationDefinitions definitions,
      OperationAuthority authority,
      OperationRecords operations,
      IdentityRepository identities,
      Turn turns,
      JobStore jobs,
      SuspendedRunCalls approvals) {
    return new GoalRuntime(
        conversations, definitions, authority, operations, identities, turns, jobs, approvals);
  }

  /** Goal admission can touch storage and definition files. Keep it off the socket scheduler. */
  @Bean
  ThreadPoolTaskScheduler conversationGoalScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("conversation-goal-");
    return scheduler;
  }

  @Bean
  GoalService goals(GoalRepository repository, GoalExecution runtime, GoalProperties properties) {
    return new GoalService(repository, runtime, properties);
  }
}
