package io.aeyer.plowshare.server.archive;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a proposal is: waiting for a human, or settled by one.
 *
 * <p>Two settled states rather than one, because the queue has to be able to
 * say which. {@code REJECTED} is the tombstone that stops a curator asking
 * again; {@code ACCEPTED} is the record that the memory it named has since been
 * superseded by the global one it became. A single {@code SETTLED} would make
 * those indistinguishable to the human reading the queue a year later, which is
 * the same failure {@link io.aeyer.plowshare.protocol.MemoryState} keeps
 * {@code COLD} and {@code SUPERSEDED} apart to avoid.
 */
public enum ProposalState {
    PENDING("pending"),
    ACCEPTED("accepted"),
    REJECTED("rejected");

    private final String wireName;

    ProposalState(String wireName) {
        this.wireName = wireName;
    }

    /**
     * The spelling that goes into a stored row and onto the wire.
     *
     * <p>Written out rather than derived from {@link #name()}, exactly as {@link
     * io.aeyer.plowshare.protocol.MemoryState#wireName()} is and for the same
     * reason: these three words are a contract with {@code V2__proposals.sql}'s
     * {@code proposals_state_known} check and with every row already written,
     * while the enum constant is a Java identifier a refactor may rename.
     * {@code name().toLowerCase()} would let that rename orphan the rows with no
     * compile error anywhere.
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** The inverse, for rehydrating a stored row. */
    @JsonCreator
    public static ProposalState fromWireName(String wireName) {
        for (ProposalState state : values()) {
            if (state.wireName.equals(wireName)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown proposal state: " + wireName);
    }
}
