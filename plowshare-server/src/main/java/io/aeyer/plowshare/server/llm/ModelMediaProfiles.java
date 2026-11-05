package io.aeyer.plowshare.server.llm;

import java.util.Optional;

/** Immutable startup declarations for a concrete wire model; this lookup never performs I/O. */
@FunctionalInterface
public interface ModelMediaProfiles {
  /** Missing declarations are unknown, not an inferred permission to send native media. */
  Optional<ModelMediaCapabilities> mediaFor(String wireModel);

  ModelMediaProfiles NONE = ignored -> Optional.empty();
}
