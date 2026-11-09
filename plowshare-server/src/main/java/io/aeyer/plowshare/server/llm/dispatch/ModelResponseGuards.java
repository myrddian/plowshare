package io.aeyer.plowshare.server.llm.dispatch;

import java.util.Optional;

/** Selects a pure response filter after routing, using the wire model that actually answered. */
@FunctionalInterface
public interface ModelResponseGuards {
  ModelResponseGuards NONE = wireModel -> Optional.empty();

  Optional<Guard> forModel(String wireModel);

  /** Detection only. The filter cannot modify the response, infer, or execute a tool. */
  record Guard(String name, java.util.function.Predicate<String> rejects) {}
}
