package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.time.Instant;
import java.util.Objects;

/**
 * One question put to a human about one memory: promote it, or not.
 *
 * <p>Immutable, like {@link io.aeyer.plowshare.protocol.Memory}, and for a
 * sharper reason than symmetry: {@link ProposalStore#resolve} settles a row with
 * a single conditional UPDATE, so a mutable proposal handed back to a caller
 * would be a second, unsynchronised copy of a state the database is the only
 * authority on.
 *
 * @param id stable identity, assigned once and never reused
 * @param memoryId the memory this proposal is about. Re-resolved at settlement
 *     time rather than trusted: a memory can be superseded or invalidated
 *     between the proposal and the answer, and {@code Archive.promote} refuses
 *     a memory the archive no longer stands behind by naming its state.
 * @param home the tier that memory lives in. Read back through the memory's own
 *     row rather than stored on the proposal — see {@code V2__proposals.sql} on
 *     why there is no {@code project} column — and typed as {@link Home} rather
 *     than as a bare project string so that the global tier stays the absence of
 *     a project rather than a name a project could take.
 * @param action what is proposed. {@link ProposalStore#PROMOTE} today; an open
 *     string so a second kind of question needs no migration.
 * @param reason the curator's account of why, required. A proposal a human
 *     cannot act on is queue depth and nothing else.
 * @param state waiting, or settled which way
 * @param createdAt when the curator filed it
 * @param proposedBy who raised the question. {@code null} only on a row filed
 *     before {@code V4__proposals_proposed_by.sql} added the column, where
 *     nobody recorded it — {@link ProposalStore#propose} requires a name, so
 *     nothing written since is anonymous. It is what lets a person reading a
 *     waiting row tell a curator's question ({@code Curator.BY}) from a
 *     person's, which the reason text alone could not: two callers can write
 *     the same sentence and mean different things by it.
 * @param resolvedAt when it was settled, or {@code null} while it waits
 * @param resolvedBy who settled it, or {@code null} while it waits. Required at
 *     settlement: this row is the record that a human agreed, and one that
 *     cannot say which human records only that somebody, once, thought it was
 *     fine.
 * @param resolution the settler's own account, or {@code null}. Optional where
 *     {@code reason} is not, and the asymmetry is deliberate: an unexplained
 *     rejection is still a rejection the curator must respect, while an
 *     unexplained proposal is a question with nothing in it.
 */
public record Proposal(
        String id,
        String memoryId,
        Home home,
        String action,
        String reason,
        ProposalState state,
        Instant createdAt,
        String proposedBy,
        Instant resolvedAt,
        String resolvedBy,
        String resolution) {

    /**
     * Rejects the nulls that have no meaning, and only those.
     *
     * <p>{@code resolvedAt}, {@code resolvedBy} and {@code resolution} are
     * legitimately absent on a proposal nobody has answered yet, and {@code
     * proposedBy} on one filed before the column existed. The rest are not: a
     * null {@code state} or {@code home} would reach the store as a NOT NULL
     * violation from a stack many layers from whoever dropped the field.
     */
    public Proposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
