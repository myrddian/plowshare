package io.aeyer.plowshare.server.hooks;

import java.util.List;

/**
 * What {@code step.post} decided: notes for the model, sent after the step's tool
 * results and recorded as runtime notes, and the records of how it decided.
 */
public record StepPost(List<String> notes, List<HookRecord> records) {

    public static final StepPost NOTHING = new StepPost(List.of(), List.of());

    public StepPost {
        notes = List.copyOf(notes);
        records = List.copyOf(records);
    }
}
