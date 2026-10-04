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
              public void speak(String conversation, String agent, String text, Speaker speaker) {
                AgentDefinition definition =
                    callers.requireAgent(agent, callers.callerForConversation(conversation, null));
                turn.deliver(conversation, definition, text, speaker, outcome -> {});
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
            });
    ApprovalDelivery delivery = new ApprovalDelivery(store, people, inbox::settle);
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
