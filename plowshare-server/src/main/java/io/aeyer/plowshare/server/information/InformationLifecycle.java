package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.llm.accounting.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database leases are the work queue. Expired running work resumes from committed checkpoints. */
public final class InformationLifecycle implements AutoCloseable {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private final JdbcTemplate jdbc;
    private final UnitOfWork transactions;
    private final InformationCatalogue catalogue;
    private final Clock clock;
    private final Processor processor;
    private final Gates gates;
    private ScheduledExecutorService worker;

    @FunctionalInterface public interface Processor {
        void process(Lease lease, BooleanSupplier cancelled, Runnable fence);
    }
    public interface Gates {
        default Gate before(Lease lease) { return Gate.NOTHING; }
        default Gate after(Lease lease) { return Gate.NOTHING; }
        default void finished(Lease lease) {}
    }
    public record Lease(UUID revision, UUID resource, long generation, String stage, int attempt,
            UUID token, String owner, Long project) {}

    public InformationLifecycle(JdbcTemplate jdbc, UnitOfWork transactions, InformationCatalogue catalogue,
            Clock clock, Processor processor, Gates gates) {
        this.jdbc=jdbc;
        this.transactions=transactions;
        this.catalogue=catalogue;
        this.clock=clock;
        this.processor=processor;
        this.gates=gates;
    }

    public synchronized void start() {
        if (worker != null) return;
        worker=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("information-lifecycle").factory());
        worker.scheduleWithFixedDelay(() -> {
            try { drainOne(); }
            catch(RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(InformationLifecycle.class).warn("information queue could not be serviced",failure);
            }
        },0,1,TimeUnit.SECONDS);
    }

    public boolean drainOne() {
        Lease lease=claim();
        if(lease==null) return false;
        try {
            Gate pre=safeGate(lease,false);
            record(lease,"stage.pre",pre);
            if(pre.isDenied()) { finish(lease,"blocked",pre.denied()); return true; }
            processor.process(lease,() -> !renew(lease),() -> requireLease(lease));
            if(!renew(lease)) return true;
            Gate post=safeGate(lease,true);
            record(lease,"stage.post",post);
            finish(lease,post.isDenied()?"blocked":"ready",post.denied());
        } catch(StaleLease invalidated) {
            // Availability or generation changed. No obsolete checkpoint or completion may commit.
        } catch(RuntimeException failure) {
            finish(lease,failure instanceof io.aeyer.plowshare.server.documents.DocumentStages.Blocked?"blocked":"failed",failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());
        } finally {
            gates.finished(lease);
        }
        return true;
    }

    private Gate safeGate(Lease lease, boolean post) {
        try { return post?gates.after(lease):gates.before(lease); }
        catch(RuntimeException failure) { return new Gate("stage hook failed: " + failure.getClass().getSimpleName(),List.of(),List.of()); }
    }
    private void record(Lease lease,String stage,Gate gate) {
        transactions.inTransaction(() -> {
            requireLease(lease);
            catalogue.event(lease.revision(),lease.generation(),lease.owner(),stage,"gate",
                    json(gate));
            return null;
        });
    }

    public Lease claim() {
        return transactions.inTransaction(() -> {
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT s.*,r.resource_id,q.owner_handle,q.project_id FROM information_steps s"
                    + " JOIN information_revisions r ON r.id=s.revision_id JOIN information_resources q ON q.id=r.resource_id"
                    + " WHERE r.availability='active' AND r.generation=s.generation AND q.owner_handle IS NOT NULL"
                    + " AND NOT EXISTS(SELECT 1 FROM information_acquisitions a WHERE a.revision_id=r.id AND a.state<>'succeeded')"
                    + " AND (q.project_id IS NULL OR EXISTS(SELECT 1 FROM project_members m WHERE m.project_id=q.project_id AND m.handle=q.owner_handle))"
                    + " AND (s.state='pending' OR (s.state='running' AND s.lease_until<?))"
                    + " AND NOT EXISTS(SELECT 1 FROM information_steps prior WHERE prior.revision_id=s.revision_id AND prior.generation=s.generation"
                    + " AND array_position(ARRAY['extract','derive','embed','summarise','summary_embed'],prior.stage)"
                    + " < array_position(ARRAY['extract','derive','embed','summarise','summary_embed'],s.stage) AND prior.state NOT IN ('ready','skipped'))"
                    + " ORDER BY array_position(ARRAY['extract','derive','embed','summarise','summary_embed'],s.stage),r.created_at,r.id"
                    + " LIMIT 1 FOR UPDATE OF s SKIP LOCKED",now());
            if(rows.isEmpty()) return null;
            Map<String,Object> row=rows.getFirst();
            Lease lease=new Lease((UUID)row.get("revision_id"),(UUID)row.get("resource_id"),((Number)row.get("generation")).longValue(),
                    (String)row.get("stage"),((Number)row.get("attempt")).intValue()+1,UUID.randomUUID(),(String)row.get("owner_handle"),(Long)row.get("project_id"));
            String expected=catalogue.fingerprint(lease.stage());
            if(expected!=null && row.get("fingerprint")!=null && !expected.equals(row.get("fingerprint"))) {
                jdbc.update("UPDATE information_steps SET state='failed',error='configuration changed; explicitly rebuild this projection' WHERE revision_id=? AND generation=? AND stage=?",lease.revision(),lease.generation(),lease.stage());
                return null;
            }
            jdbc.update("UPDATE information_steps SET state='running',attempt=?,lease_token=?,lease_until=?,error=NULL,fingerprint=?,started_at=?,finished_at=NULL"
                    + " WHERE revision_id=? AND generation=? AND stage=?",lease.attempt(),lease.token(),expires(),expected,now(),lease.revision(),lease.generation(),lease.stage());
            catalogue.event(lease.revision(),lease.generation(),lease.owner(),lease.stage(),"started",Integer.toString(lease.attempt()));
            return lease;
        });
    }

    private boolean renew(Lease lease) {
        return jdbc.update("UPDATE information_steps s SET lease_until=? FROM information_revisions r"
                + " WHERE r.id=s.revision_id AND r.availability='active' AND r.generation=s.generation AND s.revision_id=?"
                + " AND s.generation=? AND s.stage=? AND s.lease_token=? AND s.state='running' AND s.lease_until>=?",
                expires(),lease.revision(),lease.generation(),lease.stage(),lease.token(),now())==1;
    }

    /** Called inside the same transaction that writes a checkpoint; row lock excludes invalidation. */
    void requireLease(Lease lease) {
        if (lease.project() != null && jdbc.queryForObject("SELECT count(*) FROM project_members WHERE project_id=? AND handle=?",
                Integer.class,lease.project(),lease.owner()) == 0) throw new StaleLease();
        String selectedProject = lease.project() == null ? null : jdbc.queryForObject("SELECT name FROM projects WHERE id=?",String.class,lease.project());
        if (jdbc.queryForObject("SELECT count(*) FROM information_inputs WHERE derived_revision=? AND NOT information_readable(input_revision,?,?,?,true)",
                Integer.class,lease.revision(),lease.owner(),selectedProject==null?"personal":"project",selectedProject) != 0) throw new StaleLease();
        List<Map<String,Object>> valid=jdbc.queryForList("SELECT r.id FROM information_revisions r JOIN information_steps s ON s.revision_id=r.id"
                + " WHERE r.id=? AND r.availability='active' AND r.generation=? AND s.generation=r.generation AND s.stage=?"
                + " AND s.lease_token=? AND s.state='running' AND s.lease_until>=? FOR UPDATE OF r,s",
                lease.revision(),lease.generation(),lease.stage(),lease.token(),now());
        if(valid.isEmpty()) throw new StaleLease();
    }
    private void finish(Lease lease,String state,String detail) {
        transactions.inTransaction(() -> {
            try { requireLease(lease); }
            catch(StaleLease gone) { return null; }
            jdbc.update("UPDATE information_steps SET state=?,error=?,finished_at=?,lease_token=NULL,lease_until=NULL WHERE revision_id=? AND generation=? AND stage=?",
                    state,detail,now(),lease.revision(),lease.generation(),lease.stage());
            catalogue.event(lease.revision(),lease.generation(),lease.owner(),lease.stage(),state,detail);
            return null;
        });
    }
    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }
    private OffsetDateTime expires() { return clock.instant().plus(LEASE).atOffset(ZoneOffset.UTC); }
    @Override public synchronized void close() { if(worker!=null) worker.shutdownNow(); }
    static final class StaleLease extends RuntimeException {}

    private static boolean validVector(float[] vector,int width) {
        if(vector==null || vector.length!=width)return false;
        boolean nonzero=false;
        for(float value:vector){if(!Float.isFinite(value))return false;if(value!=0)nonzero=true;}
        return nonzero;
    }

    /** Reuses Anchor's derivation and cascade, with separate, observable readiness for each stage. */
    public static Processor processing(JdbcTemplate jdbc,UnitOfWork transactions,InformationCatalogue catalogue,
            DocumentStore store,EmbeddingClient embeddings,Chunking chunking,int batch,int expectedDim,
            java.util.function.Supplier<Summariser> summariser,DocumentsProperties properties) {
        return processing(jdbc,transactions,catalogue,store,embeddings,chunking,batch,expectedDim,summariser,properties,null);
    }
    public static Processor processing(JdbcTemplate jdbc,UnitOfWork transactions,InformationCatalogue catalogue,
            DocumentStore store,EmbeddingClient embeddings,Chunking chunking,int batch,int expectedDim,
            java.util.function.Supplier<Summariser> summariser,DocumentsProperties properties,InformationModelStages stages) {
        return processing(jdbc,transactions,catalogue,store,embeddings,chunking,batch,expectedDim,summariser,properties,stages,UsageOwners.NONE);
    }
    public static Processor processing(JdbcTemplate jdbc,UnitOfWork transactions,InformationCatalogue catalogue,
            DocumentStore store,EmbeddingClient embeddings,Chunking chunking,int batch,int expectedDim,
            java.util.function.Supplier<Summariser> summariser,DocumentsProperties properties,InformationModelStages stages,UsageOwners owners) {
        return (lease,cancelled,fence) -> {
            Map<String,Object> row=catalogue.row(lease.revision());
            DocumentStore writer=store.fenced(fence);

            switch(lease.stage()) {
                case "extract" -> {
                    if(row.get("extracted_text")!=null) return;
                    byte[] bytes=(byte[])row.get("source_bytes");
                    if(bytes==null) throw new IllegalStateException("no retained source bytes; supply a new revision");
                    Extracted extracted=extract(row,bytes);
                    transactions.inTransaction(() -> {
                        fence.run();
                        jdbc.update("UPDATE information_revisions SET extracted_text=?,text_hash=?,title=?,converter=?,outline_top_level=CAST(? AS jsonb) WHERE id=?",
                                extracted.text(),InformationCatalogue.sha256(extracted.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)),extracted.title(),extracted.converter(),json(extracted.outlineTopLevel()),lease.revision());
                        return null;
                    });
                }
                case "derive" -> {
                    if(store.find(lease.revision()).isPresent()) return;
                    // Extraction's outline/converter provenance must survive into the derivation.
                    Extracted extracted=new Extracted((String)row.get("title"),(String)row.get("content_hash"),
                            (String)row.get("extracted_text"),outline(row.get("outline_top_level")),(String)row.get("converter"));
                    DerivedDocument derived=Derivation.derive(extracted,chunking);
                    if(derived.paragraphs().isEmpty()) throw new IllegalStateException("extraction derived no paragraphs");
                    transactions.inTransaction(() -> {
                        fence.run();
                        writer.writeRevision(lease.revision(),(String)row.get("source_name"),extracted,
                                ((Number)row.get("byte_size")).longValue(),lease.owner(),java.time.Instant.now(),derived);
                        jdbc.update("INSERT INTO information_document_policies(document_id,owner_handle,visibility,project_id,assigned_at)"
                                + " VALUES(?,?,?,?,now())",lease.revision(),lease.owner(),lease.project()==null?"personal":"project",lease.project());
                        return null;
                    });
                }
                case "embed" -> {
                    List<DocumentStore.UnembeddedChunk> waiting=store.unembedded(lease.revision());
                    for(int from=0;from<waiting.size();from+=batch) {
                        if(cancelled.getAsBoolean()) throw new StaleLease();
                        List<DocumentStore.UnembeddedChunk> part=waiting.subList(from,Math.min(from+batch,waiting.size()));
                        List<float[]> vectors=EmbeddingClient.owned(embeddings,part.stream().map(DocumentStore.UnembeddedChunk::text).toList(),processingOwner(owners, row));
                        if(vectors.size()!=part.size() || vectors.stream().anyMatch(v -> !validVector(v,expectedDim)))
                            throw new IllegalStateException("embedding batch shape differs from the configured corpus space");
                        for(int i=0;i<part.size();i++) writer.attach(part.get(i).id(),vectors.get(i));
                    }
                }
                case "summarise" -> {
                    Summariser cascade=summariser.get();
                    if(cascade==null) throw new IllegalStateException("no summariser cascade is configured");
                    String project=lease.project()==null?null:jdbc.queryForObject("SELECT name FROM projects WHERE id=?",String.class,lease.project());
                    Home home=project==null?Home.global():Home.of(project);
                    int remaining=((Number)row.get("allowance_total")).intValue()-((Number)row.get("allowance_spent")).intValue();
                    if(remaining<1) throw new IllegalStateException("retained processing allowance exhausted; increase it explicitly before retrying");
                    Budget budget=Budget.of(remaining,() -> transactions.inTransaction(() -> {
                        fence.run();
                        if(jdbc.update("UPDATE information_revisions SET allowance_spent=allowance_spent+1 WHERE id=? AND allowance_spent<allowance_total",
                                lease.revision())!=1) throw new IllegalStateException("processing allowance exhausted");
                        jdbc.update("UPDATE conversations c SET budget_total=r.allowance_total,budget_spent=r.allowance_spent"
                                + " FROM information_revisions r WHERE r.id=? AND c.id=r.processing_log",lease.revision());
                        return null;
                    }));
                    Summariser pass=cascade.forRevision(writer,home,lease.owner(),(String)row.get("processing_log"));
                    if(stages!=null) pass.withStages(stages.forLease(lease,fence));
                    Outcome outcome=pass.summarise(lease.revision(),(String)row.get("title"),
                            budget,cancelled);
                    if(outcome.detail().equals("document.stage.denied")) throw new io.aeyer.plowshare.server.documents.DocumentStages.Blocked(outcome.text());
                    if(outcome.ending()!=Outcome.Ending.ANSWERED || store.documentSummary(lease.revision())==null
                            || store.unsummarisedUnits(lease.revision())!=0)
                        throw new IllegalStateException(outcome.ending()+": "+outcome.text());
                }
                case "summary_embed" -> {
                    String summary=store.documentSummary(lease.revision());
                    if(summary==null || summary.isBlank()) throw new IllegalStateException("document summary is unavailable");
                    boolean waiting=store.summariesAwaitingAVector().stream().anyMatch(s -> s.documentId().equals(lease.revision()));
                    if(!waiting) return;
                    if(cancelled.getAsBoolean()) throw new StaleLease();
                    float[] vector=EmbeddingClient.owned(embeddings,summary,processingOwner(owners, row));
                    if(!io.aeyer.plowshare.server.documents.SummaryEmbeddings.valid(vector,expectedDim)) throw new IllegalStateException("summary vector is invalid for the configured corpus space");
                    writer.attachSummaryEmbedding(lease.revision(),vector);
                }
                default -> throw new IllegalStateException("unknown document stage");
            }
        };
    }
    private static UsageAttribution processingOwner(UsageOwners owners, Map<String, Object> revision) {
        if (owners == UsageOwners.NONE) return UsageAttribution.LEGACY;
        String log = java.util.Objects.requireNonNull((String) revision.get("processing_log"),
                "processing usage requires a durable log");
        return owners.conversation(log, 0, UsageAttribution.Operation.EMBEDDING_WRITE);
    }

    private static String json(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private static List<String> outline(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(value.toString(),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}); }
        catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }

    private static Extracted extract(Map<String,Object> row, byte[] bytes) {
        String type=(String)row.get("media_type");
        if(type.startsWith("text/html") || type.startsWith("application/xhtml+xml")) {
            java.nio.charset.Charset charset=java.nio.charset.StandardCharsets.UTF_8;
            okhttp3.MediaType media=okhttp3.MediaType.parse(type);
            if(media!=null) charset=media.charset(charset);
            var page=io.aeyer.plowshare.server.fetch.PageExtractor.extract(new String(bytes,charset),(String)row.get("source_uri"));
            String title=page.title().isBlank()?(String)row.get("source_name"):page.title();
            return new Extracted(title,(String)row.get("content_hash"),page.text(),List.of(),"jsoup-readable-html-v1");
        }
        return TextExtraction.extract((String)row.get("source_name"),bytes);
    }

}
