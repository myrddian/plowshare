package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;

/** Definition lookup in a retained conversation's owning project and account. */
public interface ConversationDefinitions {
  /** The recorded home; a missing conversation is refused by its owning store. */
  Home homeOfConversation(String conversation);

  /** Eligible session resources plus the recorded owner, never a request-selected project. */
  DefinitionResolver.Caller callerForConversation(String conversation, String session);

  /** Readable definitions include unexported agents; lookup does not execute work. */
  AgentDefinition readAgent(String name, DefinitionResolver.Caller caller);

  /** Exported work definitions, with the same alias and grant checks as initial submission. */
  AgentDefinition requireAgent(String name, DefinitionResolver.Caller caller);
}
