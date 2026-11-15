package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.orchestrations.OrchestrationRecord;

/** Routes application-owned lifecycle output without changing execution or approval authority. */
public interface ApplicationNotices {
  boolean scheduled(
      TriggerRecord trigger, FiringRecord firing, String conversation, Outcome outcome);

  boolean orchestration(OrchestrationRecord run, String text, String about);

  boolean question(
      String conversation, String source, String author, String kind, String text, String about);
}
