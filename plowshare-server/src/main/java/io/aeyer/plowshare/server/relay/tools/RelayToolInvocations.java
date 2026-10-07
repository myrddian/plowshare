package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Scoped external invocation capability; callers have already checked the agent's tool grant. */
public interface RelayToolInvocations {
  Outcome invoke(
      RelayToolDefinition binding,
      RelayToolDefinition.Arguments arguments,
      UsageAttribution owner,
      String call,
      BooleanSupplier cancelled);

  /** Owner-scoped, non-submitting reconciliation; safe after timeout or broker retention expiry. */
  Outcome read(String project, String account, UUID invocation);

  record Outcome(UUID id, RelayToolCodec.State state, String text) {}
}
