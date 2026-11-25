package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.server.agents.EventRuns;
import io.aeyer.plowshare.server.agents.SkillResolver;
import io.aeyer.plowshare.server.agents.WorkCallers;
import io.aeyer.plowshare.server.archive.JobLog;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.board.BoardRecords;
import io.aeyer.plowshare.server.events.ApplicationInbox;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.events.ScheduleDefinitions;
import io.aeyer.plowshare.server.events.ScheduleStore;
import io.aeyer.plowshare.server.events.TriggerStore;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.information.InformationReadAccess;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayForwardingHistory;
import io.aeyer.plowshare.server.relay.RelayProperties;
import io.aeyer.plowshare.server.relay.RelayRetirementRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Shares the broker and event runner across Personal subscription sources. */
@Configuration
class PersonalSubscriptionConfiguration {
  @Bean
  PersonalSubscriptionStore personalSubscriptionStore(JdbcTemplate jdbc) {
    return new JdbcPersonalSubscriptionStore(jdbc);
  }

  @Bean
  PersonalNativeSources personalNativeSources(
      ApplicationInbox applications,
      InboxStore inbox,
      JobLog jobLog,
      OrchestrationStore orchestrations,
      InformationJobs information,
      BoardRecords board,
      InformationAccess informationAccess,
      InformationReadAccess readable) {
    return new PersonalNativeSources(
        applications,
        inbox,
        jobLog,
        orchestrations,
        information,
        board,
        informationAccess,
        readable);
  }

  @Bean
  PersonalSubscriptionRuntime personalSubscriptionRuntime(
      PersonalSpaces personal,
      PersonalSubscriptionStore store,
      Relay relay,
      RelayRetirementRepository retirement,
      RelayForwardingHistory history,
      RelayProperties relayProperties,
      ScheduleDefinitions definitions,
      ScheduleStore schedules,
      TriggerStore triggers,
      FiringStore firings,
      ProjectMembers members,
      ProjectWorkspaces projects,
      WorkCallers callers,
      SkillResolver skills,
      InboxStore inbox,
      PersonalNativeSources nativeSources,
      EventRuns jobs,
      ObjectProvider<MessageService> messages,
      UnitOfWork transactions,
      SqlWorkBudget sql,
      SqlWorkBudgetProperties sqlLimits) {
    return new PersonalSubscriptionRuntime(
        personal,
        store,
        relay,
        retirement,
        history,
        relayProperties.getMaxForwardingHops(),
        definitions,
        schedules,
        triggers,
        firings,
        members,
        projects,
        callers,
        skills,
        inbox,
        nativeSources,
        jobs,
        messages::getIfAvailable,
        transactions,
        sql,
        sqlLimits.queryCeiling());
  }

  @Bean
  PersonalScheduleAlertStore personalScheduleAlertStore(JdbcTemplate jdbc) {
    return new JdbcPersonalScheduleAlertStore(jdbc);
  }

  @Bean
  PersonalScheduleAlerts personalScheduleAlerts(
      PersonalScheduleAlertStore alerts,
      InboxStore inbox,
      ObjectProvider<MessageService> messages,
      UnitOfWork transactions,
      SqlWorkBudget sql,
      SqlWorkBudgetProperties sqlLimits,
      RelayProperties relay) {
    return new PersonalScheduleAlerts(
        alerts,
        inbox,
        messages::getIfAvailable,
        transactions,
        sql,
        sqlLimits.queryCeiling(),
        relay.getMaxForwardingHops());
  }

  @Bean
  ThreadPoolTaskScheduler personalSubscriptionTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("personal-subscriptions-");
    return scheduler;
  }

  @Bean
  SubscriptionWorker subscriptionWorker(
      PersonalSubscriptionRuntime subscriptions,
      PersonalScheduleAlerts alerts,
      RelayProperties relay) {
    return new SubscriptionWorker(subscriptions, alerts, relay);
  }

  static final class SubscriptionWorker {
    private final PersonalSubscriptionRuntime subscriptions;
    private final PersonalScheduleAlerts alerts;
    private final RelayProperties relay;
    private volatile boolean ready;

    SubscriptionWorker(
        PersonalSubscriptionRuntime subscriptions,
        PersonalScheduleAlerts alerts,
        RelayProperties relay) {
      this.subscriptions = subscriptions;
      this.alerts = alerts;
      this.relay = relay;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
      ready = true;
    }

    @Scheduled(
        scheduler = "personalSubscriptionTaskScheduler",
        fixedDelayString = "${plowshare.events.personal-mailbox-poll-delay:PT1S}")
    public void poll() {
      if (ready && relay.isInternalEnabled()) subscriptions.process();
    }

    @Scheduled(
        scheduler = "personalSubscriptionTaskScheduler",
        fixedDelayString = "${plowshare.events.personal-mailbox-poll-delay:PT1S}")
    public void pollScheduledAlerts() {
      if (ready && relay.isInternalEnabled()) alerts.process();
    }
  }
}
