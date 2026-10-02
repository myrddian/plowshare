package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.hooks.Summarised;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the log stages were told, one line each; a delivery note when {@link #note} is set. */
public final class RecordingLogStages implements LogStages {

    public final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    /** Every log opened, whole: its session and what it inherits are not in {@link #lines}. */
    public final List<LogOpened> opened = Collections.synchronizedList(new ArrayList<>());

    /** Appended by {@link #deliveryPre} after a blank line, as a hook's note would be. */
    public String note;

    @Override
    public void opened(LogOpened opened) {
        this.opened.add(opened);
        lines.add("opened " + opened.origin().wireName() + " " + opened.log()
                + (opened.parent() == null ? "" : " from " + opened.parent()));
    }

    @Override
    public void closed(String log, String ending) {
        lines.add("closed " + log + " " + ending);
    }

    @Override
    public void closed(String log, String ending, int atTurn) {
        lines.add("closed " + log + " " + ending + " at " + atTurn);
    }

    @Override
    public void moved(ConversationRecord moved) {
        lines.add("moved " + moved.id() + " " + moved.lifecycle().wireName());
    }

    @Override
    public String deliveryPre(String sourceLog, String destination, String text) {
        lines.add("pre " + sourceLog + " " + destination);
        return note == null ? text : text + "\n\n" + note;
    }

    @Override
    public void deliveryPost(String sourceLog, String destination, String text) {
        lines.add("post " + sourceLog + " " + destination + " " + text);
    }

    @Override
    public void approvalAnswered(String log, String approval, String decision, String scope) {
        lines.add("approval " + log + " " + approval + " " + decision + " " + scope);
    }

    /** What {@link #foldPost} keeps, as a hook's keep would be. */
    public String kept = "";

    /** Its held notices, sent: a {@code told <log>} line when the fold says so. */
    @Override
    public Held foldPost(String log, int atTurn, Summarised summarised) {
        lines.add("fold.post " + log + " at " + atTurn + " " + summarised);
        return new Held(kept, () -> lines.add("told " + log));
    }
}
