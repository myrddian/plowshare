package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.TurnRelaySources;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.relay.RelayProperties;
import io.aeyer.plowshare.server.swarm.RunShareProvider;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Messaging composition owns its execution voice, routes, limits, recovery and wake dispatch. */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfiguration {
  @Bean
  public MessageService messageService(
      MessagingRepository repository,
      AllowanceRepository allowances,
      MessageInboxes inboxes,
      ConversationStore conversations,
      FiringStore firings,
      UnitOfWork work,
      Turn turn,
      Callers callers,
      Dispatcher dispatcher,
      ObjectProvider<InformationJobs> information,
      ObjectProvider<LogStages> logStages,
      RunApprovalStore approvals,
      JobStore jobs,
      MessagingProperties messagingProperties,
      ProjectMessageRouting routing,
      TurnRelaySources turnSources,
      RelayProperties relayProperties) {
    RelayMessaging messaging =
        new RelayMessaging(
            repository,
            allowances,
            conversations,
            firings,
            inboxes,
            work,
            new MessageService.Voice() {
              @Override
              public String speakFrom(
                  MessageService.Instance instance,
                  AgentDefinition definition,
                  String utterance,
                  Budget lease,
                  TurnCap cap,
                  Consumer<Outcome> ended,
                  Speaker source,
                  boolean command,
                  RelayCausation causation) {
                return turn.speakToMessage(
                    instance.conversation(),
                    definition,
                    utterance,
                    lease,
                    cap,
                    source,
                    ended,
                    command,
                    instance.account(),
                    causation);
              }

              @Override
              public String continueApproved(
                  MessageService.Instance instance,
                  AgentDefinition definition,
                  String utterance,
                  String approval,
                  Budget lease,
                  TurnCap cap,
                  Consumer<Outcome> ended,
                  RelayCausation causation) {
                return turn.speakToMessage(
                    instance.conversation(),
                    definition,
                    utterance,
                    lease,
                    cap,
                    Speaker.approval(approval),
                    ended,
                    false,
                    instance.account(),
                    causation);
              }

              @Override
              public String resumeDelegate(
                  MessageService.Instance instance,
                  String child,
                  AgentDefinition definition,
                  String utterance,
                  String approval,
                  Budget lease,
                  Consumer<Outcome> ended,
                  RelayCausation causation) {
                String parent = conversations.find(child).orElseThrow().parentId();
                return turn.speakToDelegate(
                    child,
                    parent,
                    definition,
                    utterance,
                    instance.account(),
                    lease,
                    Speaker.approval(approval),
                    ended,
                    causation);
              }

              @Override
              public String speakFrom(
                  MessageService.Instance instance,
                  AgentDefinition definition,
                  String utterance,
                  Budget lease,
                  TurnCap cap,
                  Consumer<Outcome> ended,
                  Speaker source,
                  boolean command) {
                return turn.speakToMessage(
                    instance.conversation(),
                    definition,
                    utterance,
                    lease,
                    cap,
                    source,
                    ended,
                    command,
                    instance.account());
              }

              public boolean busy(MessageService.Instance instance) {
                return turn.isMessageSpeaking(instance.conversation(), instance.agent());
              }

              public boolean busy(String conversation) {
                return turn.isSpeaking(conversation);
              }

              public String speak(
                  MessageService.Instance instance,
                  AgentDefinition definition,
                  String utterance,
                  Budget lease,
                  TurnCap cap,
                  Consumer<Outcome> ended) {
                return turn.speakToMessage(
                    instance.conversation(),
                    definition,
                    utterance,
                    lease,
                    cap,
                    Speaker.harness(),
                    ended,
                    false,
                    instance.account());
              }

              public String speakCommand(
                  MessageService.Instance instance,
                  AgentDefinition definition,
                  String utterance,
                  Budget lease,
                  TurnCap cap,
                  Consumer<Outcome> ended) {
                return turn.speakToMessage(
                    instance.conversation(),
                    definition,
                    utterance,
                    lease,
                    cap,
                    Speaker.harness(),
                    ended,
                    true,
                    instance.account());
              }

              public String continueApproved(
                  MessageService.Instance instance,
                  AgentDefinition definition,
                  String utterance,
                  String approval,
                  Budget lease,
                  TurnCap cap,
                  Consumer<Outcome> ended) {
                return turn.speakToMessage(
                    instance.conversation(),
                    definition,
                    utterance,
                    lease,
                    cap,
                    Speaker.approval(approval),
                    ended,
                    false,
                    instance.account());
              }

              public String resumeDelegate(
                  MessageService.Instance instance,
                  String child,
                  AgentDefinition definition,
                  String utterance,
                  String approval,
                  Budget lease,
                  Consumer<Outcome> ended) {
                String parent = conversations.find(child).orElseThrow().parentId();
                return turn.speakToDelegate(
                    child,
                    parent,
                    definition,
                    utterance,
                    instance.account(),
                    lease,
                    Speaker.approval(approval),
                    ended);
              }
            },
            (name, project) -> callers.readAgent(name, callers.callerFor(project, null)),
            dispatcher::drain,
            Instant::now,
            turnSources,
            relayProperties.getMaxForwardingHops());
    messaging.useApprovals(approvals);
    messaging.useLimits(messagingProperties);
    messaging.useRouting(routing);
    messaging.useCancellation(jobs::cancel);
    messaging.useLogVisibility(
        (conversation, account) -> {
          var inputs = information.getIfAvailable();
          return inputs == null || inputs.logAllowed(conversation, account);
        });
    LogStages stages = logStages.getIfAvailable();
    if (stages != null) messaging.useLogStages(stages);
    messaging.useLineage(
        (sender, recipient) -> {
          var inputs = information.getIfAvailable();
          if (inputs != null)
            inputs.inherit(sender.conversation(), recipient.conversation(), sender.account());
        });
    dispatcher.registerWakes("messaging", messaging::owns, messaging);
    dispatcher.registerWakeRecovery("messaging", messaging::recover);
    return messaging;
  }

  @Bean(destroyMethod = "close")
  public MessageMaintenance messageMaintenance(MessageService messaging) {
    return new MessageMaintenance(messaging);
  }

  @Bean
  public MessagingRunExtras messagingRunExtras(
      MessageService messages, ConversationStore conversations) {
    return new MessagingRunExtras(messages, conversations);
  }

  @Bean
  @Order(10)
  public RunShareProvider messagingShares(MessageService messages) {
    return context ->
        messages.share(context).map(share -> new RunShareProvider.Selection(share, true));
  }
}
