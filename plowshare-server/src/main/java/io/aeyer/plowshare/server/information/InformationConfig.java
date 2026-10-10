package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.llm.*;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class InformationConfig {
  @Bean
  public DocumentPolicyAssignments documentPolicyAssignments(
      JdbcTemplate jdbc,
      UnitOfWork work,
      InformationAccess access,
      @org.springframework.beans.factory.annotation.Value(
              "${plowshare.information.migration-account:}")
          String operator) {
    return new DocumentPolicyAssignments(
        new JdbcDocumentPolicyRepository(jdbc),
        new InformationJobs(new JdbcJobInformationRepository(jdbc), access),
        work,
        access,
        Clock.systemUTC(),
        operator);
  }

  @Bean
  public InformationCatalogue informationCatalogue(
      JdbcTemplate jdbc,
      UnitOfWork work,
      InformationAccess access,
      InformationLogAccess logs,
      DocumentsProperties properties) {
    return new InformationCatalogue(
            new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC()), work, access, logs)
        .withAllowance(properties::ingestBudgetNow);
  }

  @Bean(destroyMethod = "close")
  public InformationEventPublisher informationEventPublisher(
      InformationEventRepository events,
      ObjectProvider<io.aeyer.plowshare.server.events.AccountPushes> pushes) {
    return new InformationEventPublisher(
        events,
        pushes.getIfAvailable(() -> io.aeyer.plowshare.server.events.AccountPushes.NONE),
        Clock.systemUTC());
  }

  @Bean(destroyMethod = "close")
  public InformationAcquisitions informationAcquisitions(
      JdbcTemplate jdbc,
      UnitOfWork work,
      InformationAccess access,
      InformationCatalogue catalogue,
      io.aeyer.plowshare.server.fetch.PageFetcher fetcher,
      DocumentsProperties properties,
      ConversationStore conversations,
      ObjectProvider<LogStages> logStages,
      InformationJobs inputs,
      ObjectProvider<Harness> harness,
      @Qualifier("projectHooks") ObjectProvider<Hooks> projectHooks,
      @Qualifier("localHooks") ObjectProvider<Hooks> localHooks) {
    var queue =
        new InformationAcquisitions(
            new io.aeyer.plowshare.server.information.JdbcAcquisitionRepository(
                jdbc, Clock.systemUTC()),
            work,
            access,
            catalogue,
            fetcher,
            properties::ingestBudgetNow,
            conversations,
            logStages.getIfAvailable(() -> LogStages.NONE),
            Hooks.chain(
                projectHooks.getIfAvailable(() -> Hooks.NONE),
                localHooks.getIfAvailable(() -> Hooks.NONE)),
            harness.getIfAvailable(() -> Harness.NONE),
            inputs);
    catalogue.useAcquisitions(queue);
    return queue;
  }

  @Bean
  public InformationWriteGates informationWriteGates(
      JdbcTemplate jdbc,
      UnitOfWork work,
      InformationAccess access,
      InformationJobs inputs,
      InformationCatalogue catalogue,
      DocumentPolicyAssignments migration,
      ConversationStore conversations,
      ObjectProvider<LogStages> logs,
      ObjectProvider<Harness> harness,
      @Qualifier("projectHooks") ObjectProvider<Hooks> projectHooks,
      @Qualifier("localHooks") ObjectProvider<Hooks> localHooks) {
    var gates =
        new InformationWriteGates(
            new io.aeyer.plowshare.server.information.JdbcInformationGateRepository(
                jdbc, Clock.systemUTC()),
            work,
            access,
            inputs,
            conversations,
            logs.getIfAvailable(() -> LogStages.NONE),
            Hooks.chain(
                projectHooks.getIfAvailable(() -> Hooks.NONE),
                localHooks.getIfAvailable(() -> Hooks.NONE)),
            harness.getIfAvailable(() -> Harness.NONE));
    catalogue.useWriteGates(gates);
    migration.useWriteGates(gates);
    return gates;
  }

  @Bean
  public InformationModelStages informationModelStages(
      JdbcTemplate jdbc,
      UnitOfWork work,
      InformationCatalogue catalogue,
      InformationJobs inputs,
      ObjectProvider<Harness> harness,
      @Qualifier("projectHooks") ObjectProvider<Hooks> projectHooks,
      @Qualifier("localHooks") ObjectProvider<Hooks> localHooks) {
    return new InformationModelStages(
        new io.aeyer.plowshare.server.information.JdbcInformationStageRepository(jdbc),
        work,
        catalogue,
        inputs,
        Hooks.chain(
            projectHooks.getIfAvailable(() -> Hooks.NONE),
            localHooks.getIfAvailable(() -> Hooks.NONE)),
        harness.getIfAvailable(() -> Harness.NONE));
  }

  @Bean
  public InformationJobs informationJobs(
      JdbcTemplate jdbc,
      InformationAccess access,
      ObjectProvider<InformationReadAccess> catalogue) {
    return new InformationJobs(
        new io.aeyer.plowshare.server.information.JdbcJobInformationRepository(jdbc),
        access,
        (context, revision) -> catalogue.getObject().requireReadable(context, revision));
  }

  @Bean
  public org.springframework.beans.factory.SmartInitializingSingleton informationJobBinding(
      InformationJobs policies,
      ObjectProvider<JobStore> jobs,
      ObjectProvider<JobRuntime> runtime,
      ObjectProvider<Deliberation> deliberation,
      ObjectProvider<LogStages> stages,
      ObjectProvider<io.aeyer.plowshare.server.events.InboxStore> inbox,
      ObjectProvider<io.aeyer.plowshare.server.orchestrations.RecordReads> records,
      ObjectProvider<io.aeyer.plowshare.server.orchestrations.OrchestrationStore>
          orchestrationStore,
      ObjectProvider<EntryStore> entries,
      ObjectProvider<MemoryStore> memory,
      ObjectProvider<DigestStore> digests,
      ObjectProvider<io.aeyer.plowshare.server.orchestrations.Delivery> delivery,
      ObjectProvider<io.aeyer.plowshare.server.agents.Citing> citing,
      ObjectProvider<io.aeyer.plowshare.server.approvals.ApprovalDelivery> approvalDelivery,
      ObjectProvider<io.aeyer.plowshare.server.ws.ApprovalFrames> approvalFrames,
      ObjectProvider<ConversationStore> conversations,
      InformationAccess access,
      InformationModelStages modelStages) {
    return () -> {
      jobs.ifAvailable(store -> store.useInformationJobs(policies));
      approvalDelivery.ifAvailable(approval -> approval.useInformationInputs(policies));
      approvalFrames.ifAvailable(frames -> frames.useInformationInputs(policies));
      runtime.ifAvailable(loop -> loop.useInformationInputs(policies));
      deliberation.ifAvailable(
          pass -> {
            pass.useInformationInputs(policies);
            pass.useStages(modelStages);
          });
      stages.ifAvailable(
          stage -> {
            if (stage instanceof HookedLogStages hooked) hooked.useInformationInputs(policies);
          });
      inbox.ifAvailable(store -> store.useInformationInputs(policies));
      records.ifAvailable(reads -> reads.useInformationInputs(policies));
      orchestrationStore.ifAvailable(
          io.aeyer.plowshare.server.orchestrations.OrchestrationStore::protectInformation);
      delivery.ifAvailable(output -> output.useInformationInputs(policies));
      entries.ifAvailable(EntryStore::protectInformationLearning);
      memory.ifAvailable(MemoryStore::protectInformation);
      digests.ifAvailable(DigestStore::protectInformation);
      citing.ifAvailable(
          writer -> {
            if (writer instanceof Citations citations)
              citations.useInformationInputs(access, policies, conversations.getObject());
          });
    };
  }

  @Bean(destroyMethod = "close")
  public InformationLifecycle informationLifecycle(
      JdbcTemplate jdbc,
      io.aeyer.plowshare.server.archive.ProjectMembers members,
      UnitOfWork work,
      InformationCatalogue catalogue,
      DocumentStore store,
      EmbeddingClient embeddings,
      Tokenizer tokenizer,
      LlmProperties llm,
      DocumentsProperties properties,
      ObjectProvider<Summariser> summariser,
      ConversationStore conversations,
      InformationJobs inputs,
      InformationModelStages modelStages,
      ObjectProvider<io.aeyer.plowshare.server.llm.accounting.UsageOwners> usageOwners,
      ObjectProvider<LogStages> logStages,
      ObjectProvider<Harness> harness,
      ObjectProvider<AgentRegistry> agents,
      ObjectProvider<GlobalAgentDefinitions> globals,
      @Qualifier("projectHooks") ObjectProvider<Hooks> projectHooks,
      @Qualifier("localHooks") ObjectProvider<Hooks> localHooks,
      ObjectProvider<io.aeyer.plowshare.server.embedding.DualEmbeddings> dualProvider,
      ObjectProvider<io.aeyer.plowshare.server.embedding.EmbeddingTokenizers> embeddingTokenizers) {
    var dual = dualProvider.getIfAvailable();
    var counters = embeddingTokenizers.getIfAvailable();
    var chunkTokenizer = counters == null ? tokenizer : counters.documents();
    int chunkLimit =
        counters == null
            ? llm.getEmbeddingMaxInputTokens()
            : counters.documentLimit(llm.getEmbeddingMaxInputTokens());
    catalogue.useConfiguration(
        () ->
            InformationConfiguration.fingerprints(
                llm,
                properties,
                GlobalAgentSnapshots.supply(globals, agents).get(),
                dual,
                counters));
    var processing = new JdbcInformationProcessingRepository(jdbc, Clock.systemUTC(), members);
    var processor =
        InformationLifecycle.processing(
            processing,
            work,
            catalogue,
            store,
            embeddings,
            new Chunking(chunkTokenizer, properties.getChunkTargetTokens(), chunkLimit),
            properties.getEmbedBatchSize(),
            llm.getEmbeddingDim(),
            summariser::getIfAvailable,
            properties,
            modelStages,
            usageOwners.getIfAvailable(
                () -> io.aeyer.plowshare.server.llm.accounting.UsageOwners.NONE),
            dual);
    var gates =
        new InformationStageHooks(
            new io.aeyer.plowshare.server.information.JdbcInformationStageRepository(jdbc),
            catalogue,
            conversations,
            logStages.getIfAvailable(() -> LogStages.NONE),
            inputs,
            Hooks.chain(
                projectHooks.getIfAvailable(() -> Hooks.NONE),
                localHooks.getIfAvailable(() -> Hooks.NONE)),
            harness.getIfAvailable(() -> Harness.NONE),
            GlobalAgentSnapshots.supply(globals, agents));
    gates.useUsageOwners(
        usageOwners.getIfAvailable(
            () -> io.aeyer.plowshare.server.llm.accounting.UsageOwners.NONE));
    catalogue.withGates(gates);
    return new InformationLifecycle(processing, work, catalogue, processor, gates);
  }

  @Bean
  public org.springframework.context.ApplicationListener<
          org.springframework.boot.context.event.ApplicationReadyEvent>
      informationQueueStart(
          InformationLifecycle lifecycle,
          InformationAcquisitions acquisitions,
          InformationEventPublisher events) {
    return ready -> {
      lifecycle.start();
      acquisitions.start();
      events.start();
    };
  }
}
