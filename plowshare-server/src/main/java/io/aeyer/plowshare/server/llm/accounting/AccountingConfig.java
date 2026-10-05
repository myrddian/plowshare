package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.dispatch.InferenceAccounting;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Default-on durable token storage and production inference capture; pricing is optional. */
@Configuration
@ConditionalOnProperty(
    prefix = "plowshare.llm.accounting",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AccountingConfig {

  @Bean
  public static UsageOwnerBinding usageOwnerBinding(ObjectProvider<UsageOwners> source) {
    return new UsageOwnerBinding(source);
  }

  @Bean
  public UsageOwners usageOwners(
      UsageScopeRepository scopes, ObjectProvider<UsageExecutionStore> executions) {
    return new UsageOwners() {
      @Override
      public UsageAttribution in(
          io.aeyer.plowshare.protocol.Home home,
          String account,
          UsageAttribution.Operation operation) {
        String project = scopes.project(home);
        return account == null
            ? UsageAttribution.system(project, operation)
            : project == null
                ? UsageAttribution.global(account, operation)
                : UsageAttribution.project(account, project, operation);
      }

      @Override
      public UsageAttribution orchestration(String id, UsageAttribution.Operation operation) {
        String conversation = scopes.conductorConversation(id);
        return executions.getObject().source(conversation, 0, operation);
      }

      @Override
      public UsageAttribution conversation(
          String id, int turn, UsageAttribution.Operation operation) {
        return executions.getObject().source(id, turn, operation);
      }

      @Override
      public UsageAttribution processing(String id, UsageAttribution.Operation operation) {
        return executions.getObject().processing(id, operation);
      }
    };
  }

  @Bean(destroyMethod = "close")
  public AccountingJournal accountingJournal(
      DataLayout layout, LlmProperties properties, ObjectMapper mapper) {
    var settings = properties.getAccounting();
    settings.validate();
    return new AccountingJournal(
        layout.accounting(),
        settings.getMaxJournalBytes().toBytes(),
        settings.getSegmentBytes().toBytes(),
        settings.getIoTimeout(),
        mapper);
  }

  @Bean
  public AccountingStore accountingStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper mapper) {
    return new AccountingStore(jdbc, transactions, mapper, Clock.systemUTC());
  }

  @Bean
  public AccountingRecorder accountingRecorder(
      AccountingJournal journal, LlmProperties properties) {
    return new AccountingRecorder(
        journal, Clock.systemUTC(), properties.getAccounting().getRetryBufferEvents());
  }

  @Bean
  public InferenceAccounting inferenceAccounting(
      AccountingRecorder recorder, InferencePrices prices) {
    return new DurableInferenceAccounting(recorder, prices, Clock.systemUTC());
  }

  @Bean
  public UsageExecutionStore usageExecutionStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      ObjectMapper mapper,
      ObjectProvider<JobRuntime> runtimes,
      ObjectProvider<Compaction> logs) {
    var source = new UsageExecutionStore(jdbc, transactions, mapper);
    runtimes.ifAvailable(runtime -> runtime.useRunUsage(source));
    logs.ifAvailable(log -> log.useRunUsage(source));
    return source;
  }

  @Bean(destroyMethod = "close")
  public AccountingProjector accountingProjector(
      AccountingJournal journal,
      AccountingStore store,
      AccountingRecorder recorder,
      LlmProperties properties) {
    var projector = new AccountingProjector(journal, store, recorder, Clock.systemUTC());
    projector.start(properties.getAccounting().getProjectionInterval());
    return projector;
  }
}
