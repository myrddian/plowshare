package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.protocol.Home;

/** Durable identifiers used for inference attribution; never grants authorization. */
public interface UsageScopeRepository {
  /** Global returns null; first project usage registers its durable identity atomically. */
  String project(Home home);

  String conductorConversation(String orchestration);
}
