package io.aeyer.plowshare.server.board;

/**
 * The one harness line a woken seat is told — spec 2026-09-29 §6. It says why, and how much is
 * unread; the messages themselves are read through {@code board_read} (step 2b), fenced as data.
 *
 * <p><b>The topic's label and title are an author's words inside the harness's voice</b>, so
 * they are rendered as quoted data — the label in brackets, the title in double quotes — and not
 * as the line's own text (the final review's I-4). {@code Board.open} has already folded each
 * onto one line and capped it, and V74 bounds them again; here a backslash, and the one character
 * that would close each one's quotes, is escaped, so no title can end its quotes early and go on
 * in the harness's voice.
 */
final class WakeUtterance {

    private WakeUtterance() {}

    static String of(BoardTopic topic, WakeRules.Reason reason, String by, int unread) {
        return "Board · [" + escape(topic.label(), ']') + "] \"" + escape(topic.title(), '"')
                + "\" · woken because " + because(reason, by) + " · " + unread
                + " unread · use board_read.";
    }

    /** {@code text} with each backslash and each {@code closing} preceded by a backslash. */
    private static String escape(String text, char closing) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == closing) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String because(WakeRules.Reason reason, String by) {
        return switch (reason) {
            case OPENED -> "a new topic opened";
            case REPLY -> "@" + by + " replied to your message";
            case MENTION -> "@" + by + " mentioned you";
            case ALERT -> "@" + by + " sent an alert to everyone";
            case REQUEST -> "@" + by + " asked for a sub-topic";
            case QUIET -> "the topic is quiet: close it, mention someone, or ask";
            case EXHAUSTED -> "the budget is exhausted: close with what you have, or ask for more";
        };
    }
}
