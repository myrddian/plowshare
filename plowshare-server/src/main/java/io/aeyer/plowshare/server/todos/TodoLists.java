package io.aeyer.plowshare.server.todos;

import java.util.List;
import java.util.Optional;

/** A conversation's todo list as the runtime and the tools see it. */
public interface TodoLists {

    /** A rendered notice, and what {@link #noticed} must be told once it is recorded. */
    record Notice(String text, TodoNotices.Seen seen) {}

    List<TodoItem> list(String conversation);

    /**
     * Applies every operation or none.
     *
     * @param sessionId the speaking session, or {@code null}; used only to tell clients
     * @return the list after the batch
     * @throws TodoRefused naming the first refused operation, having changed nothing
     */
    List<TodoItem> apply(String conversation, List<TodoOp> ops, String sessionId);

    /**
     * The notice for a non-empty list, or empty. Change-gated: produced when the visible list or
     * the compaction ordinal changed since it was last produced. For a non-empty list this is pure
     * -- it remembers nothing -- so the caller must call {@link #noticed} once the notice is
     * actually recorded, or it is produced again next time.
     */
    Optional<Notice> noticeFor(String conversation);

    /** Told once a notice {@link #noticeFor} produced has been recorded. Must not throw. */
    void noticed(String conversation, TodoNotices.Seen seen);

    /** The next {@link #noticeFor} on this conversation produces a notice again. */
    void forget(String conversation);

    /** Told after a batch commits. Must not throw. */
    @FunctionalInterface
    interface Changed {
        Changed NONE = (sessionId, conversation) -> { };

        void changed(String sessionId, String conversation);
    }

    /**
     * Told after a batch commits that moved the status of a stage item, or of any item under one:
     * an orchestration's conductor making progress on its stages, which is what resets its nudge
     * count (spec 2026-09-28, "stuck" tells the person why). A batch that moved no such status —
     * only added, renamed, re-summarised or reordered, or set a status to the one it had — is not
     * progress and tells nobody. Must not throw: the batch has already committed.
     */
    @FunctionalInterface
    interface Progressed {
        Progressed NONE = conversation -> { };

        void progressed(String conversation);
    }
}
