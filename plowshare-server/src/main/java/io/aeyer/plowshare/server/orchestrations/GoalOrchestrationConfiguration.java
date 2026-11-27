package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationRecords;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.information.InformationLogAccess;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class GoalOrchestrationConfiguration {
  @Bean
  GoalOrchestrations goalOrchestrations(
      ConversationDefinitions definitions,
      GrantedOrchestrations grants,
      OperationAuthority authority,
      OperationRecords operations,
      InformationLogAccess information,
      Orchestrations engine,
      OrchestrationCancel cancel,
      JdbcTemplate jdbc,
      UnitOfWork work,
      SqlWorkBudget sql,
      SqlWorkBudgetProperties limits) {
    var receipts = new JdbcGoalOrchestrationReceipts(jdbc, sql, limits);
    var startWork = sql.transactions(work, limits.queryCeiling());
    return new RetainedGoalOrchestrations(
        definitions,
        grants,
        authority,
        operations,
        information,
        receipts,
        (start, admitted, key, requireArmedWait) ->
            engine.startFromRun(
                start,
                admitted,
                key.invocation(),
                JdbcGoalOrchestrationReceipts.payload(key, start.request()),
                startWork,
                requireArmedWait),
        cancel::cancel);
  }
}
