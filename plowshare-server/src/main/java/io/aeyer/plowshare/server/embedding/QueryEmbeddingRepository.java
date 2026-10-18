package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.llm.dispatch.InferenceBinding;
import java.util.UUID;

/** Owns query-source counters and the policy read fence, not a second inference ledger. */
public interface QueryEmbeddingRepository {
  record Pin(UUID account, UUID scope) {}

  /**
   * A null ceiling uses the existing unlimited Budget.Claim contract, with usage still retained.
   */
  record Source(UUID id, UUID budget, Integer limit) {}

  Pin pin(InformationContext context);

  void require(InformationContext context, Pin pin, UUID target);

  Source open(
      InformationContext context, Pin pin, String fingerprint, String invocation, Integer limit);

  Budget.Claim claim(Source source);

  void bind(Source source, InferenceBinding binding, String taskHash);

  void close(Source source);

  void lockRead(InformationContext context, Pin pin, UUID target);
}
