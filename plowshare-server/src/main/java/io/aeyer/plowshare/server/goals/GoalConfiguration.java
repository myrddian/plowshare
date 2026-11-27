package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationRecords;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.approvals.SuspendedRunCalls;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.GoalInbox;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.information.InformationLogVisibility;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import io.aeyer.plowshare.server.relay.GoalRelaySources;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayRetirementRepository;
import java.time.Instant;
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
      SuspendedRunCalls approvals,
      AllowanceRepository allowances,
      MessageService messages) {
    return new GoalRuntime(
        conversations,
        definitions,
        authority,
        operations,
        identities,
        turns,
        jobs,
        approvals,
        allowances,
        messages);
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
  GoalAgentStore goalAgentStore(
      JdbcTemplate jdbc, UnitOfWork work, SqlWorkBudget sql, SqlWorkBudgetProperties budgets) {
    return new JdbcGoalAgentStore(jdbc, work, sql, budgets);
  }

  @Bean
  GoalDiscoveryStore goalDiscoveryStore(
      JdbcTemplate jdbc,
      SqlWorkBudget sql,
      SqlWorkBudgetProperties budgets,
      InformationLogVisibility visibility) {
    return new JdbcGoalDiscoveryStore(jdbc, sql, budgets, visibility);
  }

  @Bean
  DedicatedGoalAgents dedicatedGoalAgents(
      GoalAgentStore inbox,
      GoalRepository goals,
      GoalExecution runtime,
      MessageService messages,
      IdentityRepository identities,
      GoalRelaySources sources,
      Relay relay,
      GoalProperties properties,
      RelayRetirementRepository retirement,
      GoalReturns returns,
      GoalDiscoveryStore discovery) {
    return new DedicatedGoalAgents(
        inbox,
        goals,
        runtime,
        messages,
        identities,
        sources,
        relay,
        properties,
        retirement,
        Instant::now,
        returns,
        discovery);
  }

  @Bean
  GoalWaitStore goalWaitStore(
      JdbcTemplate jdbc, SqlWorkBudget sql, SqlWorkBudgetProperties limits) {
    return new JdbcGoalWaitStore(jdbc, sql, limits);
  }

  @Bean
  GoalReturnStore goalReturnStore(
      JdbcTemplate jdbc, SqlWorkBudget sql, SqlWorkBudgetProperties limits) {
    return new JdbcGoalReturnStore(jdbc, sql, limits);
  }

  @Bean
  GoalReturns goalReturns(
      GoalReturnStore returns,
      GoalRepository goals,
      GoalAgentStore transactions,
      GoalWaitStore waits,
      GoalExecution runtime,
      GoalInbox inbox,
      MessageService messages,
      InformationJobs information) {
    return new GoalReturns(
        returns, goals, transactions, waits, runtime, inbox, messages, information);
  }

  @Bean
  GoalWaits goalWaits(
      GoalWaitStore waits,
      GoalRepository goals,
      GoalAgentStore inbox,
      SuspendedRunCalls calls,
      RunApprovalStore approvals,
      GoalOrchestrations orchestrations,
      InformationJobs information,
      GoalReturns returns) {
    return new GoalWaits(
        waits,
        goals,
        inbox,
        calls,
        id ->
            approvals.deny(
                id, RunApproval.RUN_ENDED, "The requesting Goal ended before continuation."),
        orchestrations,
        information,
        returns);
  }

  @Bean
  GoalService goals(
      GoalRepository repository,
      GoalExecution runtime,
      GoalProperties properties,
      DedicatedGoalAgents dedicated,
      GoalWaits waits) {
    return new GoalService(repository, runtime, properties, dedicated, waits);
  }
}
