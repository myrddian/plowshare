package io.aeyer.plowshare.server.agents;

/**
 * What one completion was, judged by the harness and never by the model.
 *
 * <h2>Per call, beside {@link Outcome.Ending}, which is per run</h2>
 *
 * <p>An ending says how a <em>run</em> stopped. This says what a single
 * <em>completion</em> was, and a run is made of several. The two do not overlap,
 * and the concepts a request for refusal handling tends to list map onto what
 * this server already has rather than being invented twice:
 *
 * <ul>
 *   <li><b>success</b> is {@link #ANSWERED}, or {@link #CALLED_TOOLS} for a
 *       completion that is a step rather than an answer;
 *   <li><b>refusal</b> is {@link #REFUSED}, and is new: nothing distinguished a
 *       model declining a task from a model answering it;
 *   <li><b>context exhausted</b> is {@link #CUT_OFF} — {@code finish_reason:
 *       length}, which an OpenAI-compatible endpoint reports for a token limit
 *       and a context limit alike, so the constant names what is known;
 *   <li><b>model error</b> produces no completion at all. It is {@link
 *       Outcome.Ending#UNAVAILABLE}, and there is no row to judge;
 *   <li><b>tool error</b> is a tool result, which the model reads and may work
 *       around — {@code JobRuntime}'s "the tool 'x' failed" — or {@code
 *       UNAVAILABLE} when a dependency is gone. Neither is a completion.
 * </ul>
 *
 * <p>Spelled as V39's {@code entries_completion_is_known}. A constant added
 * here is a migration, for the reason {@link EntryKind} gives about its own.
 */
public enum CompletionOutcome {
    ANSWERED("answered"),
    REFUSED("refused"),
    CUT_OFF("cut_off"),
    CALLED_TOOLS("called_tools");

    private final String wireName;

    CompletionOutcome(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static CompletionOutcome of(String wireName) {
        for (CompletionOutcome outcome : values()) {
            if (outcome.wireName.equals(wireName)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("no completion outcome is spelled '" + wireName + "'");
    }
}
