package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.archive.ConversationStore;
import java.util.List;
import java.util.Objects;

/** Messaging tools compose independently for account-owned project contexts and transport runs. */
public final class MessagingRunExtras implements RunExtras {
  private final MessageService messages;
  private final ConversationStore conversations;

  public MessagingRunExtras(MessageService messages, ConversationStore conversations) {
    this.messages = Objects.requireNonNull(messages, "messages");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
  }

  @Override
  public Extras forRun(Context context) {
    String conversation = context.conversationId();
    if (conversation == null) return Extras.NONE;
    if (messages.isTransport(conversation)
        || (context.home() != null
            && !context.home().isGlobal()
            && conversations.ownerOf(conversation).isPresent())) {
      return new Extras(List.of(messages.tool(context)), context.end(), false);
    }
    return Extras.NONE;
  }
}
