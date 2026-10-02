package io.aeyer.plowshare.server.search;

import io.aeyer.plowshare.protocol.search.Hit;
import java.util.List;

/**
 * What one call to {@link SearchLadder#run} produced: which rung answered and
 * what it returned, or — once the ladder was exhausted — why nothing did.
 *
 * <p>{@code providerKey} and {@code refusal} move together rather than being
 * independently nullable: a search that found an answer has no refusal to
 * report, and a search that found nothing has no provider to name. Nothing in
 * this record's constructor enforces that pairing — {@link SearchLadder} is
 * its only producer, and a canonical-constructor check here would duplicate a
 * rule that already lives in the one place that builds this record.
 *
 * @param providerKey the key of the registered provider that answered, or
 *     {@code null} if every rung was passed over or failed
 * @param hits what the answering provider returned, or an empty list when the
 *     ladder was exhausted
 * @param refusal prose naming every rung {@link SearchLadder} tried and what
 *     became of it — not registered, skipped for too many consecutive
 *     failures, or failed with its own message — written for a person or a
 *     model to act on, not a dumped list of statuses; {@code null} on success
 */
public record LadderResult(String providerKey, List<Hit> hits, String refusal) {

    public LadderResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
