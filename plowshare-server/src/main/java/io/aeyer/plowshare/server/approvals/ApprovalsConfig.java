package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.SpeakerHandles;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** The questions run's gate asks and the approvals they become — wired onto {@link JobRuntime}. */
@Configuration
public class ApprovalsConfig {

  @Bean
  public SuspendedRunCalls suspendedRunCalls(
      JdbcTemplate jdbc, JobRuntime runtime, ActiveRunApprovals active) {
    SuspendedRunCalls calls = new JdbcSuspendedRunCalls(jdbc);
    runtime.useSuspendedRunCalls(calls);
    runtime.useActiveRunApprovals(active);
    return calls;
  }

  @Bean
  public ActiveRunApprovals activeRunApprovals() {
    return new ActiveRunApprovals();
  }

  @Bean
  public RunApprovalContinuations runApprovalContinuations(
      SuspendedRunCalls calls,
      RunApprovalStore approvals,
      ActiveRunApprovals active,
      io.aeyer.plowshare.server.agents.Runs runs,
      io.aeyer.plowshare.server.agents.Pictures pictures,
      ApprovalDelivery delivery,
      ObjectProvider<ConductorContinuation> conductors,
      ObjectProvider<io.aeyer.plowshare.server.agents.Messaging> messaging,
      Inbox inbox,
      ObjectProvider<io.aeyer.plowshare.server.agents.LogStages> stages) {
    HarnessRunApprovalContinuations continuation =
        new HarnessRunApprovalContinuations(
            calls,
            approvals,
            active,
            (approval, call, audit) -> {
              var started =
                  approval.handle() == null
                      ? runs.start(
                          new io.aeyer.plowshare.server.agents.Runs.Ask(
                              approval.agent(),
                              audit,
                              null,
                              call.session(),
                              call.conversation(),
                              null,
                              null,
                              java.util.List.of(),
                              Speaker.approval(approval.id()),
                              call.owner()),
                          pictures)
                      : runs.continueApproved(
                          call.conversation(),
                          approval.agent(),
                          audit,
                          Speaker.approval(approval.id()),
                          outcome -> delivery.continuationEnded(approval, outcome));
              return started.id();
            },
            () -> conductors.getIfAvailable(() -> ConductorContinuation.NONE),
            messaging::getIfAvailable,
            problem -> {
              if (problem.owner() != null)
                inbox.notifyOnceFromLog(
                    problem.owner(),
                    "approval",
                    problem.note(),
                    problem.conversation(),
                    "approval-recovery:" + problem.approval());
            });
    continuation.whenDecisionRecovered(
        approval -> {
          try {
            stages
                .getIfAvailable(() -> io.aeyer.plowshare.server.agents.LogStages.NONE)
                .approvalAnswered(
                    approval.conversation(),
                    approval.id(),
                    RunApproval.DENIED.equals(approval.state())
                        ? io.aeyer.plowshare.server.hooks.ApprovalAnswer.DENY
                        : io.aeyer.plowshare.server.hooks.ApprovalAnswer.ALLOW,
                    approval.scope());
          } catch (RuntimeException failed) {
            org.slf4j.LoggerFactory.getLogger(ApprovalsConfig.class)
                .warn(
                    "Recovered approval {} stands; approval.post failed ({})",
                    approval.id(),
                    failed.getClass().getSimpleName());
          }
        });
    return continuation;
  }

  @Bean
  public ApprovalContinuationRecovery approvalContinuationRecovery(
      RunApprovalContinuations continuations) {
    return new ApprovalContinuationRecovery(continuations);
  }

  @Bean
  public RunApprovalStore runApprovalStore(
      JdbcTemplate jdbc, JobRuntime runtime, ConversationStore conversations) {
    RunApprovalStore store = new RunApprovalStore(jdbc, Instant::now);
    runtime.useApprovals(store);
    runtime.useApprovalRoots(conversations::rootOf);
    runtime.useLogOwners(conversations::ownerOf);
    return store;
  }

  @Bean
  public ApprovalDelivery approvalDelivery(
      RunApprovalStore store,
      ConversationStore conversations,
      Turn turn,
      Callers callers,
      Inbox inbox,
      JobRuntime runtime,
      io.aeyer.plowshare.server.agents.RunningConversations running,
      io.aeyer.plowshare.server.relay.RelayProperties relay,
      ObjectProvider<SpeakerHandles> speakers) {
    PersonDelivery people =
        new PersonDelivery(
            conversations,
            new PersonDelivery.Voice() {
              @Override
              public boolean isSpeaking(String conversation) {
                return turn.isSpeaking(conversation);
              }

              @Override
              public void speakForAccount(
                  String conversation, String agent, String text, Speaker speaker, String account) {
                callers.requireConversationProject(conversation, account);
                try {
                  callers.requireWork(turn.homeOf(conversation).project(), account);
                } catch (io.aeyer.plowshare.server.faults.CallerFault denied) {
                  throw new Turn.Refused("The recipient cannot execute this delivery");
                }
                AgentDefinition definition =
                    callers.requirePinnedAgent(
                        agent, callers.callerForConversation(conversation, null));
                turn.deliverForExecutor(
                    conversation, definition, text, speaker, outcome -> {}, account);
              }
            },
            new PersonDelivery.Inbox() {
              public void notify(String handle, String kind, String text, String about) {
                inbox.notify(handle, kind, text, about);
              }

              public void notifyFromLog(
                  String handle, String kind, String text, String about, String source) {
                inbox.notifyFromLog(handle, kind, text, source, about);
              }

              public void notifyOnceFromLog(
                  String handle, String kind, String text, String about, String source) {
                inbox.notifyOnceFromLog(handle, kind, text, source, about);
              }
            });
    ApprovalDelivery delivery = new ApprovalDelivery(store, people, inbox::settle);
    delivery.useRelayDelivery(relay.isInternalEnabled());
    delivery.whenNotificationReady(
        conversation ->
            runtime.waitingForApprovalIn(conversation)
                || (!turn.isSpeaking(conversation) && !running.hasRunningJob(conversation)));
    // V68: an answered, denied or withdrawn question's notice leaves the person's inbox.
    store.whenSettled(delivery::settled);
    runtime.useApprovalDelivery(delivery);
    runtime.useApprovalHandles(
        session -> speakers.getIfAvailable(() -> SpeakerHandles.NONE).handleOf(session));
    return delivery;
  }

  @Bean
  public ApplicationListener<ApplicationReadyEvent> approvalsAtBoot(ApprovalDelivery delivery) {
    return ready -> delivery.drainAll();
  }
}
