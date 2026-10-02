package io.aeyer.plowshare.server.orchestrations;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of the orchestration record, as {@code V59__orchestration_record.sql} holds it.
 *
 * @param ordinal where in its tree's story this came; 1 is the first
 * @param run the run this event belongs to — the root, or a run under it
 * @param actor {@code conductor}, or the agent's name
 * @param detail the short extra — a tool line's outcome, a stage's summary, a phase's directory —
 *     or {@code null}
 * @param body the whole text behind {@code text}, newlines kept, when a person has to read it in
 *     full and the line is not all of it — a question, an answer, a stall, a run's or a phase's
 *     ending (V64) — or {@code null}
 */
public record RecordRow(int ordinal, Instant at, String run, String actor, RecordKind kind,
        String text, String detail, String body) {

    public RecordRow {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
    }
}
