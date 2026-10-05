package io.aeyer.plowshare.protocol;

import java.util.List;
import java.util.Objects;

/**
 * What a write became.
 *
 * <p>{@code memoryId} is never {@code null}. Nothing is ever refused, so every verdict — new,
 * merge, supersession alike — ends in a record the caller can name, and a caller that stores the id
 * never has to handle its absence.
 *
 * <p>There is deliberately no {@code retiredReason} field. It existed in an earlier Excalibur
 * design only to explain a refusal; with nothing refused there is nothing to explain, and a key
 * that is always null is a key callers write branches against for no reason.
 *
 * @param kind the shape the write actually took
 * @param memoryId the memory this write became — the new record for {@link VerdictKind#NEW} and
 *     {@link VerdictKind#SUPERSEDES}, and the <em>target</em> for {@link VerdictKind#MERGED_INTO},
 *     which creates no new record
 * @param reason the verdict's reason, echoed back so a caller logging the result does not have to
 *     hold the verdict alongside it
 * @param targetId the memory the verdict named, or {@code null}
 * @param demoted ids demoted to {@code cold} by this write, lowest-scoring first. Surfaced rather
 *     than silent: a write that quietly pushed three other memories out of the index would make the
 *     index shrink for reasons no caller could see. Demotion is not deletion — every id here is
 *     still readable and still on the search path.
 */
public record WriteResult(
    VerdictKind kind, String memoryId, String reason, String targetId, List<String> demoted) {

  public WriteResult {
    Objects.requireNonNull(kind, "kind");
    memoryId = ContractValues.identity(memoryId, "memoryId", 1024);
    reason = ContractValues.text(reason, "reason", 32768, false);
    targetId = ContractValues.optionalIdentity(targetId, "targetId", 1024);
    // Copied, not stored by reference: the archive builds this list while
    // demoting and a caller that held the live list would watch it change.
    demoted =
        ContractValues.list(demoted, "demoted", 10000).stream()
            .map(id -> ContractValues.identity(id, "demoted id", 1024))
            .toList();
  }
}
