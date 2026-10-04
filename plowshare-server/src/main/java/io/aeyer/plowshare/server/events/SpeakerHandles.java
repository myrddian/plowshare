package io.aeyer.plowshare.server.events;

import java.util.Optional;

/** Which account a session's listening socket is signed in as. */
@FunctionalInterface
public interface SpeakerHandles {

  SpeakerHandles NONE = session -> Optional.empty();

  Optional<String> handleOf(String session);
}
