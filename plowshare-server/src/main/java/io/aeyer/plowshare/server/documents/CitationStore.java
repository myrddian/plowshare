package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.protocol.Home;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The rows of {@code citations}: what an answer said it took from the corpus,
 * and what one of those means now.
 *
 * <h2>The insert is the validation, and there is no second check</h2>
 *
 * <p>{@link #record} writes with {@code INSERT ... SELECT ... FROM paragraphs
 * JOIN documents WHERE p.id = ?}, so a paragraph id that names nothing writes
 * nothing and says so by returning false. <b>That is deliberately the whole of
 * the guard.</b> The alternative considered was to check the citation against
 * what the run had actually been shown — the log holds every {@code
 * document_search} result verbatim, so "which paragraphs this conversation was
 * handed" is a fact this server could reconstruct. It is not used as a gate for
 * one reason: <em>that</em> half of the evidence is the ejectable half. V19's
 * sweep nulls the {@code content} of a {@code tool_result} row, so a run late in
 * a long conversation, citing a passage from a turn whose payload has been
 * ejected, would fail a shown-check and record nothing — and a citation silently
 * dropped is precisely the invisible gap this slice exists to close. The corpus
 * is the thing that can answer "does this paragraph exist" for ever, so the
 * corpus is what is asked.
 *
 * <p>What that admits, said rather than hidden: a model that named a real
 * paragraph it was never shown gets a row. Nothing in this codebase can
 * distinguish that from a person pasting a paragraph id into their own question
 * and the agent citing it back, which is a legitimate citation, and a uuid is
 * not a thing a model invents by accident.
 *
 * <h2>The three states of a citation, and none of them is stored</h2>
 *
 * <p>{@link Standing} is derived from which of the two keys survived, and V25
 * argues why a column would have been a second answer to a question the foreign
 * keys already answer. A resolved citation carries the paragraph's current text;
 * a stale one carries none, because there is none — which is exactly V18's
 * "a dangling citation is a staleness signal and never a silent repoint at
 * different text".
 *
 * <h2>It holds no document text of its own</h2>
 *
 * <p>{@link Cited#paragraphText()} is read from {@code paragraphs} at resolution
 * time and is somebody's uploaded document. This class hands it back as it
 * stands and quotes nothing; the renderer that puts it in front of a reader owes
 * the rule {@code agents.DocumentTools} states, and {@code
 * api.CitationView.render} is where it is paid.
 *
 * <p>Immutable and holds only a {@link JdbcTemplate}. No transaction manager:
 * every statement here is one statement, unlike {@link DocumentStore#write}
 * whose whole point is that a derivation is atomic.
 */
public final class CitationStore {

    /** The most a listing returns, whatever was asked for. Twenty because a
     *  citation is one line of coordinates plus a paragraph of somebody's
     *  document, and {@code RetrievalService.MAX_HITS} is ten for the same
     *  arithmetic on a surface a model reads. This one is read by a person. */
    public static final int MOST_LISTED = 20;

    private final JdbcTemplate jdbc;
    private final InformationAccess access;
    private final InformationContext context;

    public CitationStore(JdbcTemplate jdbc) {
        this(jdbc, null, null);
    }

    private CitationStore(JdbcTemplate jdbc, InformationAccess access, InformationContext context) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.access = access;
        this.context = context;
    }

    UUID documentOf(UUID paragraph) {
        var rows=jdbc.queryForList("SELECT document_id FROM paragraphs WHERE id=?",UUID.class,paragraph);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public CitationStore scoped(InformationAccess policy, InformationContext caller) {
        policy.requireSelection(caller);
        return new CitationStore(jdbc, policy, caller);
    }

    private List<Cited> read(String sql, Object... arguments) {
        boolean hasWhere = sql.contains("/*information:and*/");
        String marker = hasWhere ? "/*information:and*/" : "/*information:where*/";
        int at = sql.indexOf(marker);
        if (at < 0) throw new IllegalStateException("citation read needs a policy seam");
        if (access == null) return jdbc.query(sql.replace(marker, ""), CITED, arguments);
        var filter = access.filter(context, "d");
        int before = (int) sql.substring(0, at).chars().filter(c -> c == '?').count();
        List<Object> bound = new ArrayList<>(java.util.Arrays.asList(arguments).subList(0, before));
        bound.addAll(filter.arguments());
        bound.add(context.account());
        bound.addAll(java.util.Arrays.asList(arguments).subList(before, arguments.length));
        String allowed = filter.sql() + " AND (c.conversation_id IS NULL OR EXISTS"
                + " (SELECT 1 FROM conversations log WHERE log.id = c.conversation_id"
                + " AND log.owner_handle = ? AND information_log_readable(log.id,log.owner_handle)))";
        return jdbc.query(sql.replace(marker, (hasWhere ? " AND " : " WHERE ") + allowed),
                CITED, bound.toArray());
    }

    /**
     * Record that an answer cited a paragraph.
     *
     * <p>The source name and the ordinal are read from the corpus here rather
     * than passed in, because the caller has a uuid and nothing else — and
     * because reading them in the same statement that writes the row is what
     * makes them true <em>at the moment of citation</em>, which is what V25 says
     * they are for.
     *
     * @param paragraphId the paragraph the answer named
     * @param conversationId the conversation the answer was spoken into, or null
     *     for a run started on its own behalf
     * @param turnOrdinal which turn of it, or null with the above
     * @param agent the name of the agent that answered
     * @param at when
     * @return whether a row was written. False means the corpus holds no such
     *     paragraph, which is not an error: it is what a citation to text that
     *     has been edited away, or to a uuid that was never one, is
     */
    public boolean record(
            UUID paragraphId, String conversationId, Integer turnOrdinal, String agent,
            Instant at) {
        Objects.requireNonNull(paragraphId, "paragraphId");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(at, "at");
        String sql = "INSERT INTO citations (id, paragraph_id, document_id, source_name,"
                + " paragraph_ordinal, conversation_id, turn_ordinal, agent, cited_at)"
                + " SELECT ?, p.id, d.id, d.source_name, p.ordinal, ?, ?, ?, ?"
                + " FROM paragraphs p JOIN documents d ON d.id = p.document_id WHERE p.id = ?";
        List<Object> bound = new ArrayList<>(java.util.Arrays.asList(UUID.randomUUID(),
                conversationId, turnOrdinal, agent, at.atOffset(ZoneOffset.UTC), paragraphId));
        if (access != null) {
            var filter = access.filter(context, "d");
            sql += " AND " + filter.sql();
            bound.addAll(filter.arguments());
            if (conversationId != null) {
                sql += " AND EXISTS (SELECT 1 FROM conversations log WHERE log.id = ? AND log.owner_handle = ?)";
                bound.add(conversationId);
                bound.add(context.account());
            }
        }
        return jdbc.update(sql, bound.toArray()) == 1;
    }

    /**
     * The most recent citations in the corpus, newest first.
     *
     * <p>Served by {@code citations_by_time}, whose second key is {@code id}
     * because two citations out of one answer are written from one instant and a
     * listing that varied between two calls would be a listing nobody could page
     * through.
     */
    public List<Cited> recent(int most) {
        if (most <= 0) {
            return List.of();
        }
        return read(SELECT + " /*information:where*/ ORDER BY c.cited_at DESC, c.id LIMIT ?",
                Math.min(most, MOST_LISTED));
    }

    /**
     * What one conversation cited, in the order it said so.
     *
     * <p>Ascending, unlike {@link #recent}: a conversation is read forwards, and
     * a person asking what an answer drew on is reading a transcript rather than
     * a feed.
     */
    public List<Cited> madeIn(String conversationId, int most) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (most <= 0) {
            return List.of();
        }
        return read(
                SELECT + " WHERE c.conversation_id = ? /*information:and*/ ORDER BY c.turn_ordinal, c.cited_at, c.id"
                        + " LIMIT ?",
                conversationId, Math.min(most, MOST_LISTED));
    }

    /**
     * What has cited one document, newest first.
     *
     * <p>The backlink, and the read {@code citations_of} was built for. It
     * answers over {@code document_id}, so a citation whose paragraph was edited
     * away is still counted against the paper it came from — which is the
     * question "has anybody used this document" rather than "is this citation
     * still good".
     */
    public List<Cited> of(UUID documentId, int most) {
        Objects.requireNonNull(documentId, "documentId");
        if (most <= 0) {
            return List.of();
        }
        return read(SELECT + " WHERE c.document_id = ? /*information:and*/ ORDER BY c.cited_at DESC, c.id"
                        + " LIMIT ?",
                documentId, Math.min(most, MOST_LISTED));
    }

    /** Model-facing citation read. Filter the conversation home before LIMIT, so
     * newer citations from another tier cannot hide this tier's results. Rows
     * without a conversation have no stored home and cannot be attributed. */
    public List<Cited> inHome(Home home, UUID document, int most) {
        Objects.requireNonNull(home, "home");
        if (most <= 0) return List.of();
        String scoped = SELECT + " JOIN conversations conversation ON conversation.id = c.conversation_id"
                + " LEFT JOIN projects project ON project.id = conversation.project_id"
                + " WHERE project.name IS NOT DISTINCT FROM CAST(? AS TEXT)";
        if (document != null) scoped += " AND c.document_id = ?";
        scoped += " ORDER BY c.cited_at DESC, c.id LIMIT ?";
        int limit = Math.min(most, MOST_LISTED);
        return document == null ? jdbc.query(scoped, CITED, home.project(), limit)
                : jdbc.query(scoped, CITED, home.project(), document, limit);
    }

    /** One citation by its own id, or empty. */
    public Optional<Cited> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return read(SELECT + " WHERE c.id = ? /*information:and*/", id).stream().findFirst();
    }

    /**
     * <b>Left joins on both sides, and that is the resolution.</b>
     *
     * <p>An inner join would return only the citations that still resolve, which
     * is the same silence {@code ON DELETE CASCADE} would have produced one layer
     * down — V25 declines it there and this query must not reintroduce it here.
     * The paragraph's text and the document's title come back null for a citation
     * whose keys were nulled, and {@link Cited#standing()} is what reads that.
     */
    private static final String SELECT =
            "SELECT c.id, c.paragraph_id, c.document_id, c.source_name, c.paragraph_ordinal,"
                    + " c.conversation_id, c.turn_ordinal, c.agent, c.cited_at,"
                    + " p.text AS paragraph_text, d.title"
                    + " FROM citations c"
                    + " LEFT JOIN paragraphs p ON p.id = c.paragraph_id"
                    + " LEFT JOIN documents d ON d.id = c.document_id";

    private static final RowMapper<Cited> CITED = (rs, row) -> new Cited(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("paragraph_id"),
            (UUID) rs.getObject("document_id"),
            rs.getString("source_name"),
            rs.getInt("paragraph_ordinal"),
            rs.getString("conversation_id"),
            (Integer) rs.getObject("turn_ordinal"),
            rs.getString("agent"),
            rs.getObject("cited_at", OffsetDateTime.class).toInstant(),
            rs.getString("paragraph_text"),
            rs.getString("title"));

    /**
     * One citation as it stands now.
     *
     * @param paragraphText the paragraph's text <b>as the corpus holds it
     *     today</b>, or null for a citation that no longer resolves. Somebody's
     *     uploaded document; see this class's javadoc
     */
    public record Cited(
            UUID id,
            UUID paragraphId,
            UUID documentId,
            String sourceName,
            int paragraphOrdinal,
            String conversationId,
            Integer turnOrdinal,
            String agent,
            Instant citedAt,
            String paragraphText,
            String title) {

        /** Which of the three states this citation is in. */
        public Standing standing() {
            if (paragraphId != null) {
                return Standing.RESOLVES;
            }
            return documentId != null ? Standing.PARAGRAPH_GONE : Standing.DOCUMENT_GONE;
        }
    }

    /**
     * What a citation resolves to, derived from which keys survived.
     *
     * <p><b>Three and not two</b>, because the two ways of going stale need
     * different things said about them: a paragraph edited away leaves a
     * document a reader can still go and look at, and a document that is gone
     * leaves nothing but the name it was filed under. Collapsing them would make
     * a citation into a live paper read the same as one into a paper nobody has
     * any more.
     */
    public enum Standing {

        /** The paragraph is in the corpus and its text is here. */
        RESOLVES,

        /** A later ingest of this document edited or removed the paragraph. The
         *  document is still in the corpus and the citation names where in it
         *  the words used to be. */
        PARAGRAPH_GONE,

        /** The document is no longer in the corpus at all. */
        DOCUMENT_GONE
    }
}
