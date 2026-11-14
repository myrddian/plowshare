package io.aeyer.plowshare.server.information;

import java.util.Optional;
import java.util.UUID;

/**
 * Answers {@link InformationCatalogueRepository#readable} without per-row permission SQL when it
 * can. Empty means "ask SQL": the caller must then run the original statement, which is the only
 * oracle. A present answer equals that statement's answer for the same committed state.
 */
public interface KnowledgeReadabilityCheck {
  Optional<Boolean> readable(InformationContext context, UUID revision);

  /** Every check goes to SQL. */
  static KnowledgeReadabilityCheck none() {
    return (context, revision) -> Optional.empty();
  }
}
