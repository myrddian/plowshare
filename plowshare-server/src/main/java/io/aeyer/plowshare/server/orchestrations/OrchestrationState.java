package io.aeyer.plowshare.server.orchestrations;

/**
 * Where one orchestration run has got to. {@code orchestrations.state} stores the wire name, as
 * {@code archive.Origin} and {@code archive.ConversationLifecycle} store theirs.
 *
 * <p><b>Nothing holds this enum and {@code orchestrations_state_is_known} together at compile
 * time</b>, which is the situation every other wire-backed enum in this server is already in — a
 * constant added here without a migration is a row Postgres refuses the first time a real run
 * reaches it, not a compile error against the schema.
 */
public enum OrchestrationState {

    /** The conductor is working. The state a run starts in and returns to once an answer lands. */
    RUNNING("running"),

    /** The conductor asked a question and its turn ended to wait for the answer. */
    ASKING("asking"),

    /** Alive, not being asked anything, and with nothing to do until a child reports —
     *  {@code waiting_for} names the child this row started waiting on, though any live child's
     *  report wakes it, not only that one's. */
    WAITING("waiting"),

    /** Ended with a result. Terminal. */
    FINISHED("finished"),

    /** Ended by a failure. Terminal. */
    FAILED("failed"),

    /** Ended by hitting a limit — returns, restarts, or similar. Terminal. */
    CAPPED("capped"),

    /** Ended by cancellation. Terminal. */
    CANCELLED("cancelled");

    private final String wire;

    OrchestrationState(String wire) {
        this.wire = wire;
    }

    /** What the {@code state} column holds — lower case, as every other wire-backed enum here
     *  spells its own. */
    public String wire() {
        return wire;
    }

    /** Whether a run in this state has ended. {@code
     *  orchestrations_ended_iff_terminal} is the same predicate, in the database. */
    public boolean terminal() {
        return this == FINISHED || this == FAILED || this == CAPPED || this == CANCELLED;
    }

    /**
     * The constant a row's value spells, or a refusal naming what was read.
     *
     * @throws IllegalArgumentException if no state is spelled {@code wire}
     */
    public static OrchestrationState of(String wire) {
        for (OrchestrationState state : values()) {
            if (state.wire.equals(wire)) {
                return state;
            }
        }
        throw new IllegalArgumentException(
                "no orchestration state is spelled '" + wire + "'; this row was written by"
                        + " something that knows a state this build does not, and"
                        + " orchestrations_state_is_known should have refused it");
    }
}
