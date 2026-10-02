package io.aeyer.plowshare.server.hooks;

import io.aeyer.plowshare.protocol.ToolCall;
import java.util.List;

/**
 * One step that asked for tools, as {@code step.post} is shown it: what the model
 * called, what came back, and what it streamed as thinking on the way.
 *
 * @param number which tool-asking step of this run this is, from 1
 * @param wireModel the model the step's call was served by, or null when the
 *     endpoint did not say
 * @param calls the tool calls, in the order the model asked for them
 * @param results each call's result, as the model was sent it, index for index
 * @param thinking what the model streamed as reasoning during the call, or null
 * @param outcomes each call's outcome in a word, index for index, as the run's record
 *     line tells it ({@code ok}, {@code refused}, {@code denied}, {@code exit 1}, …; see
 *     {@code ToolLines}), or empty when whoever built the step did not say. The runtime
 *     knows some outcomes structurally -- a hook's denial, a name no tool answers to, a
 *     delegate's ending -- that a result's text alone does not show, so it passes them
 *     rather than leaving a hook to guess them back. Not sent to script hooks.
 */
public record Step(int number, String wireModel, List<ToolCall> calls, List<String> results,
        String thinking, List<String> outcomes) {

    public Step {
        calls = List.copyOf(calls);
        results = List.copyOf(results);
        outcomes = List.copyOf(outcomes);
        if (calls.size() != results.size()) {
            throw new IllegalArgumentException("a step has " + calls.size() + " calls and "
                    + results.size() + " results; each call has exactly one");
        }
        if (!outcomes.isEmpty() && outcomes.size() != calls.size()) {
            throw new IllegalArgumentException("a step has " + calls.size() + " calls and "
                    + outcomes.size() + " outcomes; each call has exactly one, or none is told");
        }
    }

    /** A step whose outcomes are not told. */
    public Step(int number, String wireModel, List<ToolCall> calls, List<String> results,
            String thinking) {
        this(number, wireModel, calls, results, thinking, List.of());
    }
}
