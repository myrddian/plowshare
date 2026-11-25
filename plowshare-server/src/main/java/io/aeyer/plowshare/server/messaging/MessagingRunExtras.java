package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.events.InboxAlertReadTool;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.information.InformationJobs;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Messaging tools compose independently for account-owned project contexts and transport runs. */
public final class MessagingRunExtras implements RunExtras {
  private final MessageService messages;
  private final ConversationStore conversations;
  private final Supplier<InboxStore> inbox;
  private final Supplier<InformationJobs> information;

  public MessagingRunExtras(
      MessageService messages,
      ConversationStore conversations,
      Supplier<InboxStore> inbox,
      Supplier<InformationJobs> information) {
    this.messages = Objects.requireNonNull(messages, "messages");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.information = Objects.requireNonNull(information, "information");
  }

  @Override
  public Extras forRun(Context context) {
    String conversation = context.conversationId();
    if (conversation == null) return Extras.NONE;
    if (messages.isTransport(conversation)
        || (context.home() != null
            && !context.home().isGlobal()
            && conversations.ownerOf(conversation).isPresent())) {
      var tools = new ArrayList<AgentTool>();
      tools.add(messages.tool(context));
      messages
          .mailboxAlertFor(context)
          .ifPresent(
              alert -> {
                var results = inbox.get();
                var inputs = information.get();
                if (results != null && inputs != null)
                  tools.add(
                      new InboxAlertReadTool(
                          results,
                          inputs,
                          context.callerHandle(),
                          context.home(),
                          conversation,
                          alert.item(),
                          alert.firing()));
              });
      return new Extras(List.copyOf(tools), context.end(), false);
    }
    return Extras.NONE;
  }
}
