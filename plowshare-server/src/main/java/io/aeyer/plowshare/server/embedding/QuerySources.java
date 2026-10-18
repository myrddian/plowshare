package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.llm.dispatch.InferenceBinding;
import java.util.UUID;

/** Shared retained query usage; each consumer separately authorizes its pinned read selection. */
public interface QuerySources {
  record Pin(UUID account, UUID scope) {}

  /** Null is the existing unlimited ceiling, never absence of authority or usage evidence. */
  record Source(UUID id, UUID budget, Integer limit) {}

  Source open(Pin pin, String fingerprint, String invocation, Integer limit);

  Budget.Claim claim(Source source);

  void bind(Source source, InferenceBinding binding, String taskHash);

  void close(Source source);
}
