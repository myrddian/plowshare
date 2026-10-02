package io.aeyer.plowshare.server.board;

import java.time.Instant;

/**
 * One topic on a project's board — spec 2026-09-29, the project board and the swarm, §4. A root
 * holds its tree's pot ({@code potTotal}, {@code potSpent}, {@code reserve}); a child holds none.
 */
public record BoardTopic(
        String id, String project, String parent, String root, int depth, String title,
        String label, String account, String openerKind, String opener,
        String originConversation, String state, String resolution, Integer potTotal,
        Integer potSpent, Integer reserve, Instant quietNotifiedAt, Instant openedAt,
        Instant closedAt) {

    public static final String OPEN = "open";
    public static final String EXHAUSTED = "exhausted";
    public static final String CLOSED = "closed";

    /**
     * The longest a title and a label may be, in characters (code points, as Postgres's
     * {@code char_length} counts them; V74 holds the same bounds). Each is an author's words that
     * every woken seat is told inside the harness's own wake line, so each is bounded — see
     * {@code Board.fold}.
     */
    public static final int TITLE_MAX = 120;
    public static final int LABEL_MAX = 60;

    public static final String BY_PERSON = "person";
    public static final String BY_BOT = "bot";
    public static final String BY_AGENT = "agent";
    public static final String BY_MEMBER = "member";

    public boolean isRoot() {
        return parent == null;
    }

    public boolean isClosed() {
        return CLOSED.equals(state);
    }
}
