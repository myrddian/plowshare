package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persistent tree and exact source pointers. Browsing never increments memory uses. */
public final class DigestStore {
    private boolean informationProtected;
    public void protectInformation() { informationProtected=true; }
    private String safe(String alias) { return informationProtected?"information_digest_safe("+alias+"id) AND ":""; }
    private String sourceSafe(String alias) { return informationProtected?"information_log_readable("+alias+"conversation_id,NULL) AND ":""; }

    public record Node(String id, int depth, String summary, Instant staleAt, long revision) {}
    public record Span(String conversation, int since, int through) {}
    private String embeddingGeneration;
    public DigestStore embeddingGeneration(String generation) { this.embeddingGeneration=generation;return this; }
    private final JdbcTemplate jdbc;
    private final UnitOfWork transactions;
    public DigestStore(JdbcTemplate jdbc, UnitOfWork transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }
    private static final org.springframework.jdbc.core.RowMapper<Node> NODE = (r, n) ->
            new Node(r.getString("id"), r.getInt("depth"), r.getString("summary"),
                    r.getTimestamp("stale_at") == null ? null : r.getTimestamp("stale_at").toInstant(),
                    r.getLong("revision"));
    /** Completed, unfolded turns are source leaves even if no lesson was extracted. */
    public void captureUnfolded(Home home) {
        transactions.inTransaction(() -> {
            List<String> conversations=jdbc.queryForList("SELECT c.id FROM conversations c WHERE "
                    + (informationProtected?"information_log_readable(c.id,NULL) AND ":"") + "c.project_id IS NOT DISTINCT FROM CAST(? AS bigint) AND c.origin NOT IN ('curator','memory') "
                    + "ORDER BY c.id",String.class,ProjectIds.toRead(jdbc,home));
            for(String conversation:conversations) {
                List<Integer> turns=jdbc.queryForList("SELECT DISTINCT e.turn_ordinal FROM entries e "
                        + "JOIN turns t ON t.conversation_id=e.conversation_id AND t.ordinal=e.turn_ordinal "
                        + "WHERE e.conversation_id=? AND e.superseded_by IS NULL AND e.kind='utterance' "
                        + "AND NOT EXISTS(SELECT 1 FROM digest_spans s WHERE s.conversation_id=e.conversation_id "
                        + "AND e.turn_ordinal>s.since_turn AND e.turn_ordinal<=s.through_turn)",Integer.class,conversation);
                for(int turn:turns) {
                    String id="dig_t_"+UUID.nameUUIDFromBytes((conversation+":"+turn).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String excerpt=jdbc.queryForObject("SELECT string_agg(left(content,4000), E'\\n' ORDER BY ordinal) "
                            + "FROM entries WHERE conversation_id=? AND turn_ordinal=? AND kind IN ('utterance','answer') "
                            + "AND content IS NOT NULL",String.class,conversation,turn);
                    if(excerpt==null || excerpt.isBlank()) continue;
                    jdbc.update("INSERT INTO digests(id,project_id,depth,summary) VALUES(?,?,0,?) ON CONFLICT DO NOTHING",
                            id,ProjectIds.toRead(jdbc,home),"Unfolded turn excerpts (not a lesson):\n"+excerpt);
                    jdbc.update("INSERT INTO digest_spans VALUES(?,?,?,?,NULL) ON CONFLICT DO NOTHING",id,conversation,turn-1,turn);
                }
            }
            return null;
        });
    }
    public record Source(String id,String kind,String content,Instant ejectedAt,String export) {}
    public List<Source> source(Span span) {
        return jdbc.query("SELECT ordinal,kind,content,ejected_at,export FROM entries WHERE "+sourceSafe("")+"conversation_id=? "
                + "AND turn_ordinal>? AND turn_ordinal<=? AND role IS NOT NULL AND kind NOT IN ('summary','turn_summary') ORDER BY ordinal LIMIT 41",
                (r,n)->new Source(span.conversation()+":"+r.getInt(1),r.getString(2),r.getString(3),
                        r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant(),r.getString(5)),
                span.conversation(),span.since(),span.through());
    }
    /** Navigation rechecks the current source home after moves; provenance is not authority. */
    public List<Source> source(Home home,Span span) {
        boolean visible=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM conversations WHERE id=? AND project_id IS NOT DISTINCT FROM CAST(? AS bigint))",
                Boolean.class,span.conversation(),ProjectIds.toRead(jdbc,home)));
        if(!visible) return List.of();
        return jdbc.query("SELECT e.ordinal,e.kind,e.content,e.ejected_at,e.export FROM entries e JOIN conversations c ON c.id=e.conversation_id "
                + "WHERE "+sourceSafe("e.")+"e.conversation_id=? AND c.project_id IS NOT DISTINCT FROM CAST(? AS bigint) AND e.turn_ordinal>? AND e.turn_ordinal<=? "
                + "AND e.role IS NOT NULL AND e.kind NOT IN ('summary','turn_summary') ORDER BY e.ordinal LIMIT 41",
                (r,n)->new Source(span.conversation()+":"+r.getInt(1),r.getString(2),r.getString(3),
                        r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant(),r.getString(5)),
                span.conversation(),ProjectIds.toRead(jdbc,home),span.since(),span.through());
    }
    public ArchivedSpan archiveFor(Home home,Span span) {
        return jdbc.query("SELECT d.*,s.summary_ordinal FROM digest_spans s JOIN digests d ON d.id=s.digest_id "
                + "WHERE "+safe("d.")+"s.conversation_id=? AND s.since_turn<=? AND s.through_turn>=? "
                + "AND d.project_id IS NOT DISTINCT FROM CAST(? AS bigint) ORDER BY s.summary_ordinal IS NULL,s.through_turn-s.since_turn,d.id LIMIT 1",
                (r,n)->new ArchivedSpan(NODE.mapRow(r,n),r.getObject("summary_ordinal")!=null),
                span.conversation(),span.since(),span.through(),ProjectIds.toRead(jdbc,home)).stream().findFirst().orElse(null);
    }
    public List<Home> homes() {
        return jdbc.query("SELECT DISTINCT p.name FROM (SELECT project_id FROM digests UNION SELECT project_id FROM conversations WHERE origin NOT IN ('curator','memory')) d LEFT JOIN projects p ON p.id=d.project_id",
                (r,n) -> r.getString(1)==null ? Home.global() : Home.of(r.getString(1)));
    }
    public List<Node> roots(Home home) {
        return jdbc.query("SELECT d.* FROM digests d WHERE "+safe("d.")+"d.project_id IS NOT DISTINCT FROM CAST(? AS bigint) "
                + "AND NOT EXISTS(SELECT 1 FROM digest_children e WHERE e.child_id=d.id) ORDER BY d.id",
                NODE, ProjectIds.toRead(jdbc, home));
    }
    public Node get(Home home, String id) {
        return jdbc.query("SELECT * FROM digests WHERE "+safe("")+"id=? AND project_id IS NOT DISTINCT FROM CAST(? AS bigint)",
                NODE, id, ProjectIds.toRead(jdbc, home)).stream().findFirst().orElseThrow(() ->
                new ValidationException("No digest " + id + " in this home"));
    }
    public List<Node> children(Home home, String id) {
        get(home,id);
        return jdbc.query("SELECT d.* FROM digests d JOIN digest_children e ON d.id=e.child_id "
                + "WHERE "+safe("d.")+"e.parent_id=? ORDER BY d.id", NODE, id);
    }
    public List<Node> stale(Home home) {
        return jdbc.query("SELECT * FROM digests WHERE "+safe("")+"project_id IS NOT DISTINCT FROM CAST(? AS bigint) "
                + "AND stale_at IS NOT NULL ORDER BY depth,id", NODE, ProjectIds.toRead(jdbc,home));
    }
    public String memory(String id) {
        return jdbc.queryForList("SELECT memory_id FROM digest_memories WHERE "+safe("digest_")+"digest_id=?",String.class,id)
                .stream().findFirst().orElse(null);
    }
    public Span span(String id) {
        return jdbc.query("SELECT * FROM digest_spans WHERE "+safe("digest_")+"digest_id=?", (r,n) ->
                new Span(r.getString("conversation_id"),r.getInt("since_turn"),r.getInt("through_turn")),id)
                .stream().findFirst().orElse(null);
    }
    public record ArchivedSpan(Node node, boolean folded) {}
    /** Resolve the actual span archive, including when arriving through a retired lesson. */
    public ArchivedSpan archiveFor(Span span) {
        return jdbc.query("SELECT d.*,s.summary_ordinal FROM digest_spans s JOIN digests d ON d.id=s.digest_id "
                + "WHERE "+safe("d.")+"s.conversation_id=? AND s.since_turn<=? AND s.through_turn>=? "
                + "ORDER BY s.summary_ordinal IS NULL,s.through_turn-s.since_turn,d.id LIMIT 1",
                (r,n)->new ArchivedSpan(NODE.mapRow(r,n),r.getObject("summary_ordinal")!=null),
                span.conversation(),span.since(),span.through()).stream().findFirst().orElse(null);
    }
    public void provenance(String memory, String conversation, List<Integer> turns) {
        for (int turn : turns.stream().distinct().toList()) {
            jdbc.update("INSERT INTO memory_provenance VALUES(?,?,?) ON CONFLICT DO NOTHING",
                    memory,conversation,turn);
        }
    }
    public List<Span> provenance(String memory) {
        return jdbc.query("SELECT conversation_id,turn_ordinal FROM memory_provenance WHERE "+sourceSafe("")+"memory_id=? "
                + "ORDER BY conversation_id,turn_ordinal", (r,n) -> new Span(r.getString(1),r.getInt(2)-1,r.getInt(2)),memory);
    }
    public boolean covered(String conversation) {
        // Retention also visits short conversations that never needed a fold.
        // Copy their completed source turns before acknowledging coverage.
        jdbc.query("SELECT p.name FROM conversations c LEFT JOIN projects p ON p.id=c.project_id "
                + "WHERE c.id=? AND c.origin NOT IN ('curator','memory')",
                (r,n) -> r.getString(1)==null?Home.global():Home.of(r.getString(1)),conversation)
                .forEach(this::captureUnfolded);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM entries e "
                + "WHERE e.conversation_id=? AND e.kind='summary' AND NOT EXISTS(SELECT 1 FROM digest_spans s "
                + "WHERE s.conversation_id=e.conversation_id AND s.summary_ordinal=e.ordinal))",Boolean.class,conversation));
    }
    /** Membership is immutable; append parents, never overwrite a former root. */
    public Node group(Home home, List<Node> members, String summary, float[] embedding) {
        if (members.size()<2) throw new IllegalArgumentException("A singleton is carried, not summarised");
        return transactions.inTransaction(() -> {
            // Stable lock order also serialises competing passes in different server processes.
            for (Node member : members.stream().sorted(java.util.Comparator.comparing(Node::id)).toList()) {
                jdbc.queryForList("SELECT id FROM digests WHERE id=? FOR UPDATE",String.class,member.id());
                Node current=get(home,member.id());
                if (current.revision()!=member.revision() || current.staleAt()!=null)
                    throw new ValidationException("Digest changed during grouping; retry the pass");
            }
            String id="dig_"+UUID.randomUUID();
            int depth=members.stream().mapToInt(Node::depth).max().orElseThrow()+1;
            jdbc.update("INSERT INTO digests(id,project_id,depth,summary,embedding,embedding_generation) VALUES(?,?,?,?,CAST(? AS vector),?)",
                    id,ProjectIds.toWrite(jdbc,home),depth,summary,vector(embedding),embeddingGeneration);
            for(Node member:members) jdbc.update("INSERT INTO digest_children VALUES(?,?)",id,member.id());
            return get(home,id);
        });
    }
    public boolean refresh(Home home, Node expected, String summary, float[] embedding) {
        return transactions.inTransaction(() -> {
            jdbc.queryForList("SELECT id FROM digests WHERE id=? FOR UPDATE",String.class,expected.id());
            Node current=get(home,expected.id());
            if(current.revision()!=expected.revision()) return false;
            jdbc.update("INSERT INTO digest_revisions(digest_id,revision,summary) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                    current.id(),current.revision(),current.summary());
            jdbc.update("UPDATE digests SET summary=?,embedding=CAST(? AS vector),embedding_generation=?,stale_at=NULL WHERE id=?",
                    summary,vector(embedding),embeddingGeneration,current.id());
            return true;
        });
    }
    private static String vector(float[] v) {
        if(v==null) return null;
        return java.util.Arrays.toString(v);
    }
}
