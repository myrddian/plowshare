package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.Proposal;
import java.time.Instant;

/**
 * One proposal on the wire.
 *
 * <p>The tier is flattened to a {@code project} string rather than nesting {@code Home}'s
 * single-field record, so the wire shape matches every other endpoint's {@code project} parameter —
 * null means global there and here.
 */
public record ProposalView(
    String id,
    String memoryId,
    String project,
    String action,
    String reason,
    String state,
    Instant createdAt,
    String proposedBy,
    Instant resolvedAt,
    String resolvedBy,
    String resolution) {

  public static ProposalView of(Proposal proposal) {
    return new ProposalView(
        proposal.id(),
        proposal.memoryId(),
        proposal.home().project(),
        proposal.action(),
        proposal.reason(),
        proposal.state().wireName(),
        proposal.createdAt(),
        proposal.proposedBy(),
        proposal.resolvedAt(),
        proposal.resolvedBy(),
        proposal.resolution());
  }
}
