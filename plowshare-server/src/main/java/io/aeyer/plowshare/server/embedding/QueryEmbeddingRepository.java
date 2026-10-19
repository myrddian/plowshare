package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.information.InformationContext;
import java.util.UUID;

/** Owns query-source counters and the policy read fence, not a second inference ledger. */
public interface QueryEmbeddingRepository extends QuerySources {

  Pin pin(InformationContext context);

  void require(InformationContext context, Pin pin, UUID target);

  default Source open(
      InformationContext context, Pin pin, String fingerprint, String invocation, Integer limit) {
    return open(pin, fingerprint, invocation, limit);
  }

  void lockRead(InformationContext context, Pin pin, UUID target);
}
