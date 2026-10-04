package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import java.util.Optional;

/**
 * What the harness notices about an incoming utterance's words. Asked only for an incoming
 * utterance, by a definition that grants orchestrations.
 *
 * <p><b>Incoming, on {@link JobRuntime#run}'s own terms.</b> A harness delivery ({@code
 * Turn.deliver}), a conductor turn, an approved-run continuation, a resume or a delegated task
 * never reaches this seam — spec §6 names the doors this may be asked through and {@code incoming}
 * is how {@code converse} tells them apart. That is not how {@link Whereabouts} and the todo list
 * notice are gated: those answer every run, whoever or whatever it is speaking for; this one is
 * narrower, asked only for an incoming utterance — a person's own words, or an event's, arriving
 * fresh — from a definition that grants at least one orchestration.
 *
 * <p>A notice, on {@link Noticing}'s terms: logged after the utterance, never hidden, so it only
 * extends the prefix.
 */
public interface TriggerNoticing {

  TriggerNoticing NONE = (definition, utterance, home, sessionId, conversation) -> Optional.empty();

  /** The notice for this utterance, or empty when nothing was noticed. */
  Optional<String> noticeFor(
      AgentDefinition definition,
      String utterance,
      Home home,
      String sessionId,
      String conversation);
}
