package io.aeyer.plowshare.server.session;

import java.util.Optional;

/** Live project-to-workspace binding, without granting access to a session's files. */
public interface ProjectPresences {
  Optional<Presence> serving(String project);
}
