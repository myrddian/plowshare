package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.documents.Derivation;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable catalogue. All mutations are short transactions; extraction, models and hooks run outside them. */
public final class InformationCatalogue {
    public static final List<String> STAGES = List.of("extract", "derive", "embed", "summarise", "summary_embed");
    private final JdbcTemplate jdbc;
    private final UnitOfWork transactions;
    private final InformationAccess access;
    private final Clock clock;
    private InformationLifecycle.Gates gates=new InformationLifecycle.Gates(){};
    public InformationCatalogue withGates(InformationLifecycle.Gates gates) { this.gates=gates;return this; }
    private java.util.function.IntSupplier allowance = () -> 1000;
    private java.util.function.Supplier<Map<String,String>> configuration=Map::of;
    public void useConfiguration(java.util.function.Supplier<Map<String,String>> configuration){this.configuration=configuration;}
    public String fingerprint(String stage){return configuration.get().get(stage);}
    public InformationCatalogue withAllowance(java.util.function.IntSupplier allowance) { this.allowance=allowance;return this; }

    public InformationCatalogue(JdbcTemplate jdbc, UnitOfWork transactions, InformationAccess access, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.access = access;
        this.clock = clock;
    }

    private InformationWriteGates writeGates;
    public void useWriteGates(InformationWriteGates writeGates) { this.writeGates=writeGates; }
    private <T> T prepared(InformationContext context,UUID request,String operation,Object identity,List<UUID> sources,String session,Class<T> type,java.util.function.Supplier<T> transition) {
        return writeGates==null?transition.get():writeGates.execute(context,request,operation,identity,sources,session,type,transition);
    }
    private InformationAcquisitions acquisitions;
    public void useAcquisitions(InformationAcquisitions acquisitions) { this.acquisitions=acquisitions; }
    public Map<String,Object> acquire(InformationContext context,UUID request,String url,String name,String session) {
        if(acquisitions==null) throw new CallerFault("durable acquisition is unavailable");
        return acquisitions.submit(context,request,url,name,session);
    }
    public Map<String,Object> acquisitionStatus(InformationContext context,UUID acquisition) {
        if(acquisitions==null) throw new CallerFault("durable acquisition is unavailable");
        return acquisitions.status(context,acquisition);
    }

    public Admission admit(InformationContext context, UUID request, String name, byte[] bytes,
            String mediaType, String sourceUri) {
        return admitForSession(context,request,name,bytes,mediaType,sourceUri,null);
    }

    private Admission admit(InformationContext context, UUID request, String name, byte[] bytes,
            String mediaType, String sourceUri, String kind, List<UUID> inputs, List<UUID> citations, UUID feedback, String session, int capturedAllowance) {
        access.requireSelection(context);
        if (context.selection().scope() == InformationContext.Scope.SHARED)
            throw new CallerFault("create in a personal or project namespace, then explicitly share a revision");
        if (request == null || name == null || name.isBlank() || bytes == null || bytes.length == 0)
            throw new CallerFault("requestId, source name and nonempty source bytes are required");
        if (bytes.length > 32 * 1024 * 1024) throw new CallerFault("information sources may be at most 32 MiB");
        bytes = bytes.clone();
        final byte[] retained = bytes;
        int modelAllowance=capturedAllowance>0?capturedAllowance:allowance.getAsInt();
        if(modelAllowance<1) throw new CallerFault("processing allowance must be positive before accepting information");
        String hash = sha256(retained);
        String namespace = context.selection().scope() == InformationContext.Scope.PROJECT
                ? "project:" + context.selection().project() : "account:" + context.account();
        String fingerprint = sha256((canonical(java.util.Arrays.asList(namespace, name, hash, mediaType, sourceUri, kind, inputs, citations, feedback))).getBytes(StandardCharsets.UTF_8));
        return transactions.inTransaction(() -> {
            // Serialises this idempotency key and this namespace/name across processes, without model calls.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL", Boolean.class,
                    context.account() + ":" + request);
            List<Map<String,Object>> prior = jdbc.queryForList("SELECT fingerprint,revision_id FROM information_requests"
                    + " WHERE account=? AND request_id=?", context.account(), request);
            if (!prior.isEmpty()) {
                if (!fingerprint.equals(prior.getFirst().get("fingerprint")))
                    throw new CallerFault("requestId was already used for different information");
                UUID id = (UUID) prior.getFirst().get("revision_id");
                return new Admission(id, (UUID) row(id).get("resource_id"), false);
            }
            if(capturedAllowance==0 && jdbc.queryForObject("SELECT count(*) FROM information_acquisitions WHERE account=? AND request_id=?",Integer.class,context.account(),request)!=0)
                throw new CallerFault("requestId was already used for URL acquisition");
            for (UUID input : inputs) requireReadable(context, input);
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL", Boolean.class,
                    namespace + ":" + name);
            List<Map<String,Object>> resources = jdbc.queryForList("SELECT id,owner_handle,kind FROM information_resources"
                    + " WHERE namespace=? AND source_name=? FOR UPDATE", namespace, name);
            UUID resource;
            if (resources.isEmpty()) {
                resource = UUID.randomUUID();
                Long project = context.selection().project() == null ? null : jdbc.queryForObject(
                        "SELECT id FROM projects WHERE name=?", Long.class, context.selection().project());
                jdbc.update("INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind)"
                        + " VALUES(?,?,?,?,?,?)", resource, namespace, name, context.account(), project, kind);
            } else {
                if (!context.account().equals(resources.getFirst().get("owner_handle"))) throw absent();
                if (!kind.equals(resources.getFirst().get("kind"))) throw new CallerFault("this name already belongs to a different resource kind");
                resource = (UUID) resources.getFirst().get("id");
            }
            int ordinal = jdbc.queryForObject("SELECT coalesce(max(ordinal),0)+1 FROM information_revisions"
                    + " WHERE resource_id=?", Integer.class, resource);
            UUID revision = UUID.randomUUID();
            jdbc.update("INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,source_uri,"
                    + "content_hash,byte_size,source_bytes,allowance_total,caller_session) VALUES(?,?,?,?,?,?,?,?,?,?,?)", revision, resource,
                    ordinal, name, mediaType == null ? "application/octet-stream" : mediaType, sourceUri,
                    hash, retained.length, retained, modelAllowance, session);
            for (String stage : STAGES) jdbc.update("INSERT INTO information_steps(revision_id,generation,stage)"
                    + " VALUES(?,1,?)", revision, stage);
            for (UUID input : inputs) jdbc.update("INSERT INTO information_inputs VALUES(?,?)", revision, input);
            jdbc.update("INSERT INTO information_requests VALUES(?,?,?,?)", context.account(), request, fingerprint, revision);
            event(revision, 1, context.account(), "intake", "retained", "");
            return new Admission(revision, resource, true);
        });
    }

    public io.aeyer.plowshare.server.agents.Outcome awaitProcessing(InformationContext context, UUID revision,
            java.util.function.BooleanSupplier cancelled) {
        while (true) {
            if (cancelled.getAsBoolean()) {
                transactions.inTransaction(() -> {
                    requireOwner(context, revision);
                    long prior = generation(revision);
                    jdbc.update("UPDATE information_revisions SET generation=generation+1 WHERE id=?", revision);
                    for (String stage : STAGES) jdbc.update("INSERT INTO information_steps(revision_id,generation,stage,state)"
                            + " SELECT ?,?,?,coalesce((SELECT CASE WHEN state='ready' THEN 'ready' ELSE 'cancelled' END"
                            + " FROM information_steps WHERE revision_id=? AND generation=? AND stage=?),'cancelled')",
                            revision,prior+1,stage,revision,prior,stage);
                    jdbc.update("UPDATE information_steps SET state='cancelled',lease_token=NULL,lease_until=NULL"
                            + " WHERE revision_id=? AND state IN ('pending','running')", revision);
                    event(revision, generation(revision), context.account(), "processing", "cancelled", "");
                    return null;
                });
                return new io.aeyer.plowshare.server.agents.Outcome(io.aeyer.plowshare.server.agents.Outcome.Ending.CANCELLED,
                        "Processing cancelled; source bytes and checkpoints are retained.",0,0,"");
            }
            List<Map<String,Object>> steps=jdbc.queryForList("SELECT state,error FROM information_steps WHERE revision_id=? AND generation=?",
                    revision,generation(revision));
            if (steps.isEmpty()) return new io.aeyer.plowshare.server.agents.Outcome(io.aeyer.plowshare.server.agents.Outcome.Ending.CANCELLED,
                    "Processing was invalidated; inspect information.status.",0,0,"");
            var stopped=steps.stream().filter(step -> List.of("failed","blocked","cancelled").contains(step.get("state"))).findFirst();
            if(stopped.isPresent()) return new io.aeyer.plowshare.server.agents.Outcome(io.aeyer.plowshare.server.agents.Outcome.Ending.UNAVAILABLE,
                    "Revision " + revision + " retained with incomplete processing; inspect information.status and retry.",0,0,
                    String.valueOf(stopped.get().get("error")));
            if(steps.stream().allMatch(step -> List.of("ready","skipped").contains(step.get("state"))))
                return new io.aeyer.plowshare.server.agents.Outcome(io.aeyer.plowshare.server.agents.Outcome.Ending.ANSWERED,
                        "Revision " + revision + (steps.stream().anyMatch(step -> step.get("state").equals("skipped"))
                                ? " retains legacy projections; skipped capabilities remain unavailable." : " is fully processed."),0,0,"");
            try { Thread.sleep(200); }
            catch(InterruptedException stoppedThread) { Thread.currentThread().interrupt();return new io.aeyer.plowshare.server.agents.Outcome(
                    io.aeyer.plowshare.server.agents.Outcome.Ending.CANCELLED,"Processing observer interrupted; durable processing continues.",0,0,""); }
        }
    }

    public Admission admitForSession(InformationContext context, UUID request, String name, byte[] bytes,
            String mediaType, String sourceUri, String session) {
        if(bytes==null || bytes.length==0 || bytes.length>32*1024*1024 || name==null || name.isBlank())throw new CallerFault("nonempty source name and bytes within 32 MiB are required");
        byte[] retained=bytes.clone();
        return prepared(context,request,"intake",java.util.Arrays.asList(name,sha256(retained),mediaType,sourceUri),List.of(),session,Admission.class,
                () -> admit(context,request,name,retained,mediaType,sourceUri,"source",List.of(),List.of(),null,session,0));
    }

    Admission acquired(InformationContext context,UUID request,String name,byte[] bytes,String mediaType,String sourceUri,String session,int allowance) {
        return admit(context,request,name,bytes,mediaType,sourceUri,"source",List.of(),List.of(),null,session,allowance);
    }
    public String revisionName(InformationContext context, UUID revision) {
        Map<String,Object> resource=requireOwner(context,revision);
        String namespace=context.selection().scope()==InformationContext.Scope.PROJECT
                ? "project:"+context.selection().project():"account:"+context.account();
        if(!namespace.equals(resource.get("namespace"))) throw new CallerFault("select the resource's original namespace before revising it");
        if("deleted".equals(row(revision).get("availability"))) throw new CallerFault("a deleted resource cannot be revised");
        return (String)resource.get("source_name");
    }

    /** An indexed passage is only evidence when it can be matched back to retained source text. */
    public Map<String,Object> locateWindow(InformationContext context,UUID revision,String passage) {
        requireReadable(context,revision);
        String source=(String)row(revision).get("extracted_text");
        if(source==null) return Map.of("revision",revision,"matched",false,"reason","retained extraction is unavailable");
        if(passage==null || passage.isBlank() || passage.length()>32768)
            return Map.of("revision",revision,"matched",false,"reason","indexed passage is not a bounded source quote");
        int start=source.indexOf(passage);
        if(start<0) return Map.of("revision",revision,"matched",false,"reason","indexed passage differs from retained extraction");
        return Map.of("revision",revision,"matched",true,"start",start,"end",start+passage.length(),"text",source.substring(start,start+passage.length()));
    }

    public Map<String,Object> window(InformationContext context,UUID revision,int offset,int limit) {
        if(offset<0 || limit<1 || limit>32768) throw new CallerFault("read offset must be nonnegative and limit must be between 1 and 32768 characters");
        String value=text(context,revision);
        if(offset>value.length()) throw new CallerFault("read offset is beyond the retained text");
        int end=(int)Math.min((long)offset+limit,value.length());
        return Map.of("revision",revision,"start",offset,"end",end,"total",value.length(),"text",value.substring(offset,end));
    }

    private static String canonical(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }

    public void callerSession(Admission admission, String session) {
        if (admission.created() && session != null) jdbc.update("UPDATE information_revisions SET caller_session=? WHERE id=? AND caller_session IS NULL",
                session, admission.revision());
    }

    public List<Map<String,Object>> list(InformationContext context, int limit, int offset) {
        access.requireSelection(context);
        if (offset < 0) throw new CallerFault("offset must be nonnegative");
        List<Object> args = new ArrayList<>(selectionArgs(context));
        args.add(Math.max(1,Math.min(limit,100)));
        args.add(offset);
        return jdbc.queryForList(SELECT + " WHERE " + READABLE + " AND NOT r.excluded"
                + " AND NOT EXISTS(SELECT 1 FROM information_reports report WHERE report.revision_id=r.id AND report.status<>'final')"
                + " ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?", args.toArray());
    }

    public List<Map<String,Object>> inventory(InformationContext context,int limit,int offset) {
        access.requireSelection(context);
        if(limit<1 || limit>100 || offset<0) throw new CallerFault("inventory needs limit 1..100 and nonnegative offset");
        String project=context.selection().project();
        if(context.selection().scope()==InformationContext.Scope.SHARED) return List.of();
        return jdbc.queryForList("SELECT r.id,r.resource_id,r.ordinal,q.source_name,q.source_name AS title,q.kind,r.availability,r.excluded,r.generation,r.created_at,r.allowance_total,r.allowance_spent"
                + " FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE q.owner_handle=?"
                + " AND ((?::text IS NULL AND q.project_id IS NULL) OR q.project_id=(SELECT id FROM projects WHERE name=?)) ORDER BY r.created_at DESC,r.id OFFSET ? LIMIT ?",
                context.account(),project,project,offset,limit);
    }

    public Map<String,Object> status(InformationContext context, UUID revision) {
        boolean readable=true;
        try { requireReadable(context, revision); }
        catch (NotFoundFault hidden) { requireOwner(context, revision);readable=false; }
        Map<String,Object> result = new java.util.LinkedHashMap<>(jdbc.queryForMap(SELECT + " WHERE r.id=?", revision));
        if(!readable) {
            result.put("can_manage",true);
            result.put("title",result.get("source_name"));
            result.remove("source_uri");
            result.put("steps",jdbc.queryForList("SELECT stage,state,attempt,generation FROM information_steps WHERE revision_id=? ORDER BY generation,stage",revision));
            result.put("events",List.of());result.put("inputs",List.of());
            return result;
        }
        result.put("can_manage",context.account().equals(row(revision).get("owner_handle")));
        var steps=jdbc.queryForList("SELECT stage,state,attempt,error,generation,fingerprint,started_at,finished_at FROM information_steps WHERE revision_id=? ORDER BY generation,stage",revision);
        for(var step:steps){
            String expected=fingerprint((String)step.get("stage"));
            step.put("compatible",expected==null || expected.equals(step.get("fingerprint")));
            if(!context.account().equals(row(revision).get("owner_handle")))step.remove("error");
        }
        result.put("steps",steps);
        result.put("progress",Map.of("passage_vectors",jdbc.queryForMap("SELECT count(*) AS total,count(*) FILTER(WHERE c.embedding IS NOT NULL) AS completed FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=?",revision),
                "stored_summary_entities",jdbc.queryForMap("SELECT count(*) AS total,count(*) FILTER(WHERE summary IS NOT NULL) AS completed FROM (SELECT summary FROM paragraphs WHERE document_id=? UNION ALL SELECT summary FROM sections WHERE document_id=? UNION ALL SELECT summary FROM chapters WHERE document_id=? UNION ALL SELECT summary FROM documents WHERE id=?) summaries",revision,revision,revision,revision)));
        result.put("events",context.account().equals(row(revision).get("owner_handle"))?jdbc.queryForList("SELECT sequence,generation,stage,action,detail,recorded_at"
                + " FROM information_events WHERE revision_id=? ORDER BY sequence", revision):List.of());
        result.put("inputs", jdbc.queryForList("SELECT input_revision FROM information_inputs WHERE derived_revision=?", UUID.class, revision));
        if("report".equals(result.get("kind"))) {
            var reports=jdbc.queryForList("SELECT status,feedback_revision,finalised_at,details,produced_by,definition_hash FROM information_reports WHERE revision_id=?",revision);
            for(var report:reports)try{report.put("details",new com.fasterxml.jackson.databind.ObjectMapper().readTree(report.get("details").toString()));}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
            result.put("report",reports.isEmpty()?Map.of():reports.getFirst());
            result.put("citations",jdbc.queryForList("SELECT evidence_id FROM information_report_citations WHERE report_revision=?",UUID.class,revision));
        }
        return result;
    }

    public String text(InformationContext context, UUID revision) {
        requireReadable(context, revision);
        String value = jdbc.queryForObject("SELECT extracted_text FROM information_revisions WHERE id=?", String.class, revision);
        if (value == null) throw new CallerFault("this revision has not been extracted");
        return value;
    }

    public byte[] bytes(InformationContext context, UUID revision) {
        requireReadable(context, revision);
        byte[] bytes = jdbc.queryForObject("SELECT source_bytes FROM information_revisions WHERE id=?", byte[].class, revision);
        if (bytes == null) throw new CallerFault("this legacy revision has no retained source bytes");
        return bytes;
    }

    /** Unlink never deletes. A private link is visible only to its owner; project material needs a project revision. */
    public void link(InformationContext context,UUID revision,String project, boolean remove) {
        link(context,revision,project,remove,null);
    }

    public void link(InformationContext context,UUID revision,String project, boolean remove,UUID request) {
        managed(context,revision,remove ? "unlink" : "link",request,java.util.Arrays.asList(project,remove),() -> {
        access.resolve(context.account(), InformationContext.Selection.project(project));
        transactions.inTransaction(() -> {
            requireOwner(context, revision);
            if (remove) jdbc.update("DELETE FROM information_links WHERE revision_id=? AND project_id=(SELECT id FROM projects WHERE name=?)",
                    revision, project);
            else jdbc.update("INSERT INTO information_links(revision_id,project_id,actor_handle)"
                    + " SELECT ?,id,? FROM projects WHERE name=? ON CONFLICT DO NOTHING", revision, context.account(), project);
            event(revision, generation(revision), context.account(), "collection", remove ? "unlinked" : "linked", project);
            return null;
        });

        });
    }

    /** Sharing is per revision and never overrides restrictions inherited from any input. */
    public void share(InformationContext context,UUID revision,boolean shared) {
        share(context,revision,shared,null);
    }

    public void share(InformationContext context,UUID revision,boolean shared,UUID request) {
        managed(context,revision,shared ? "share" : "unshare",request,List.of(shared),() -> {
        transactions.inTransaction(() -> {
            Map<String,Object> resource = requireOwner(context, revision);
            if(shared) requireReadable(context,revision);
            if (shared && !jdbc.queryForObject("SELECT coalesce(bool_and(information_readable(input_revision,?,'shared',NULL,true)),true)"
                    + " FROM information_inputs WHERE derived_revision=?", Boolean.class, context.account(), revision))
                throw new CallerFault("every input must already be shared before a derived revision can be shared");
            String visibility = shared ? "shared" : resource.get("project_id") == null ? "personal" : "project";
            Long project = shared ? null : (Long) resource.get("project_id");
            if (jdbc.update("UPDATE information_document_policies SET visibility=?,project_id=? WHERE document_id=?",
                    visibility, project, revision) != 1) throw new CallerFault("derive this revision before sharing it");
            event(revision, generation(revision), context.account(), "sharing", shared ? "shared" : "unshared", "");
            return null;
        });

        });
    }

    /** Safety invalidation happens immediately. A hook cannot grant access to withdrawn or deleted content. */
    public void availability(InformationContext context,UUID revision,String state) {
        availability(context,revision,state,null);
    }

    public void availability(InformationContext context,UUID revision,String state,UUID request) {
        managed(context,revision,state,request,List.of(state),() -> {
        if (!List.of("active","excluded","included","withdrawn","deleted").contains(state)) throw new CallerFault("unknown availability");
        transactions.inTransaction(() -> {
            requireOwner(context, revision);
            if(state.equals("excluded") || state.equals("included")) {
                if(jdbc.update("UPDATE information_revisions SET excluded=? WHERE id=? AND availability<>'deleted'",state.equals("excluded"),revision)!=1)
                    throw new CallerFault("deleted revisions have no discovery state to change");
                event(revision,generation(revision),context.account(),"discovery",state,"");
                return null;
            }
            String old = jdbc.queryForObject("SELECT availability FROM information_revisions WHERE id=? FOR UPDATE", String.class, revision);
            if (old.equals(state)) return null;
            if (old.equals("deleted")) throw new CallerFault("deleted source bytes cannot be restored");
            long previous = generation(revision);
            jdbc.update("UPDATE information_revisions SET availability=?,generation=generation+1 WHERE id=?", state, revision);
            jdbc.update("UPDATE information_steps SET state='cancelled',lease_token=NULL,lease_until=NULL"
                    + " WHERE revision_id=? AND generation=? AND state IN ('pending','running','blocked','failed')", revision, previous);
            if (state.equals("deleted")) {
                jdbc.update("UPDATE information_revisions SET source_bytes=NULL,extracted_text=NULL WHERE id=?", revision);
                jdbc.update("UPDATE information_evidence SET quote='[deleted]' WHERE revision_id=?", revision);
                jdbc.update("DELETE FROM documents WHERE id=?", revision);
                jdbc.update("DELETE FROM information_model_steps WHERE revision_id=?",revision);
                jdbc.update("DELETE FROM orchestration_script_steps WHERE conversation_id IN (SELECT job_id FROM information_job_inputs WHERE revision_id=?)",revision);
                jdbc.update("UPDATE information_reports SET details=CAST('{\"deleted\":true}' AS jsonb) WHERE revision_id=?",revision);
                jdbc.update("UPDATE information_events SET detail='[deleted]' WHERE revision_id=?",revision);
            }
            if (state.equals("active")) {
                for (String stage : STAGES) jdbc.update("INSERT INTO information_steps(revision_id,generation,stage,state)"
                        + " SELECT ?,?,?,coalesce((SELECT CASE WHEN state IN ('ready','skipped') THEN state ELSE 'pending' END"
                        + " FROM information_steps WHERE revision_id=? AND generation<=? AND stage=? ORDER BY generation DESC LIMIT 1),'pending')",
                        revision, previous+1, stage, revision, previous, stage);
            }
            if(state.equals("active"))jdbc.update("UPDATE information_steps next SET fingerprint=prior.fingerprint FROM information_steps prior WHERE next.revision_id=? AND next.generation=? AND next.state IN ('ready','skipped') AND prior.revision_id=next.revision_id AND prior.generation=(SELECT max(last.generation) FROM information_steps last WHERE last.revision_id=next.revision_id AND last.stage=next.stage AND last.generation<?) AND prior.stage=next.stage",revision,previous+1,previous+1);
            event(revision, previous+1, context.account(), "availability", state, "");
            return null;
        });

        });
    }

    public void retry(InformationContext context,UUID revision) {
        retry(context,revision,null);
    }

    public void retry(InformationContext context,UUID revision,UUID request) {
        managed(context,revision,"retry",request,List.of(),() -> {
        transactions.inTransaction(() -> {
            requireOwner(context, revision);
            if (!"active".equals(row(revision).get("availability"))) throw new CallerFault("restore availability before retrying");
            jdbc.update("UPDATE information_steps SET state='pending',error=NULL WHERE revision_id=? AND generation=?"
                    + " AND state IN ('failed','blocked','cancelled')", revision, generation(revision));
            event(revision, generation(revision), context.account(), "processing", "retry", "");
            return null;
        });

        });
    }

    /** Rebuild derived checkpoints without changing source/evidence identity. */
    public void rebuild(InformationContext context,UUID revision,String from) {
        rebuild(context,revision,from,null);
    }

    public void rebuild(InformationContext context,UUID revision,String from,UUID request) {
        managed(context,revision,"rebuild",request,List.of(from),() -> {
        int first=STAGES.indexOf(from);
        if(first<2) throw new CallerFault("rebuild starts at embed, summarise or summary_embed; changed extraction or derivation requires a new immutable revision");
        transactions.inTransaction(() -> {
            requireOwner(context,revision);
            requireReadable(context,revision);
            long old=generation(revision);
            jdbc.queryForMap("SELECT id FROM information_revisions WHERE id=? FOR UPDATE",revision);
            jdbc.update("UPDATE information_revisions SET generation=generation+1 WHERE id=?",revision);
            jdbc.update("UPDATE information_steps SET state='cancelled',lease_token=NULL,lease_until=NULL WHERE revision_id=? AND generation=? AND state<>'ready'",revision,old);
            for(int i=0;i<STAGES.size();i++) {
                String stage=STAGES.get(i);
                boolean preserve=i<first || (first==2 && stage.equals("summarise"));
                String state=preserve?jdbc.queryForObject("SELECT state FROM information_steps WHERE revision_id=? AND generation=? AND stage=?",String.class,revision,old,stage):"pending";
                if(i<first && !List.of("ready","skipped").contains(state)) throw new CallerFault("complete earlier stages before rebuilding a downstream projection");
                jdbc.update("INSERT INTO information_steps(revision_id,generation,stage,state) VALUES(?,?,?,?)",revision,old+1,stage,state);
                if(preserve)jdbc.update("UPDATE information_steps next SET fingerprint=prior.fingerprint FROM information_steps prior WHERE next.revision_id=? AND next.generation=? AND next.stage=? AND prior.revision_id=next.revision_id AND prior.generation=? AND prior.stage=next.stage",revision,old+1,stage,old);
            }
            if(first==2) jdbc.update("UPDATE chunks SET embedding=NULL WHERE paragraph_id IN (SELECT id FROM paragraphs WHERE document_id=?)",revision);
            if(first==3) {
                jdbc.update("UPDATE paragraphs SET summary=NULL WHERE document_id=?",revision);
                jdbc.update("UPDATE sections SET summary=NULL WHERE document_id=?",revision);
                jdbc.update("UPDATE chapters SET summary=NULL WHERE document_id=?",revision);
                jdbc.update("UPDATE documents SET summary=NULL WHERE id=?",revision);
            }
            jdbc.update("UPDATE documents SET summary_embedding=NULL WHERE id=?",revision);
            event(revision,old+1,context.account(),"processing","rebuild",from);
            return null;
        });

        });
    }

    public void allowance(InformationContext context,UUID revision,int total) {
        allowance(context,revision,total,null);
    }

    public void allowance(InformationContext context,UUID revision,int total,UUID request) {
        managed(context,revision,"allowance",request,List.of(total),() -> {
        if(total<1) throw new CallerFault("processing allowance must be positive");
        transactions.inTransaction(() -> {
            requireOwner(context,revision);
            jdbc.queryForMap("SELECT id FROM information_revisions WHERE id=? FOR UPDATE",revision);
            if(total<((Number)row(revision).get("allowance_spent")).intValue()) throw new CallerFault("allowance cannot be lowered below actual spending");
            jdbc.update("UPDATE information_revisions SET allowance_total=? WHERE id=?",total,revision);
            jdbc.update("UPDATE conversations SET budget_total=? WHERE id=(SELECT processing_log FROM information_revisions WHERE id=?)",total,revision);
            event(revision,generation(revision),context.account(),"processing","allowance",Integer.toString(total));
            return null;
        });

        });
    }

    public Map<String,Object> events(InformationContext context,long after,int limit) {
        access.requireSelection(context);
        if(after<0 || limit<1 || limit>100) throw new CallerFault("event cursor must be nonnegative and limit between 1 and 100");
        List<Object> args=new ArrayList<>(selectionArgs(context));args.add(after);args.add(limit);
        List<Map<String,Object>> events=jdbc.queryForList("SELECT e.sequence,e.revision_id,e.generation,e.stage,e.action,e.detail,e.recorded_at"
                + " FROM information_events e JOIN information_revisions r ON r.id=e.revision_id JOIN information_resources q ON q.id=r.resource_id"
                + " WHERE "+READABLE+" AND e.sequence>? ORDER BY e.sequence LIMIT ?",args.toArray());
        long cursor=events.isEmpty()?after:((Number)events.getLast().get("sequence")).longValue();
        return Map.of("events",events,"cursor",cursor);
    }

    private record Command(UUID request,UUID token,boolean applied,boolean replay) {}
    private Command reserve(InformationContext context,UUID revision,UUID request,String operation,Object parameters) {
        if(request==null) return null; // Trusted in-process callers; socket mutations always supply a key.
        String fingerprint=sha256(canonical(java.util.Arrays.asList(operation,revision,context.selection(),parameters)).getBytes(StandardCharsets.UTF_8));
        return transactions.inTransaction(() -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",Boolean.class,context.account()+":command:"+request);
            var rows=jdbc.queryForList("SELECT * FROM information_commands WHERE account=? AND request_id=? FOR UPDATE",context.account(),request);
            UUID token=UUID.randomUUID();
            if(!rows.isEmpty()) {
                var prior=rows.getFirst();
                if(!fingerprint.equals(prior.get("fingerprint"))) throw new CallerFault("requestId was already used for a different information command");
                String state=(String)prior.get("state");
                if(state.equals("completed")) return new Command(request,(UUID)prior.get("token"),true,true);
                if(List.of("blocked","failed").contains(state)) throw new CallerFault((String)prior.get("error"));
                if(state.equals("applied")) {
                    if(((java.time.OffsetDateTime)prior.get("lease_until")).toInstant().isAfter(clock.instant())) throw new CallerFault("information command is still completing; retry the same requestId");
                    jdbc.update("UPDATE information_commands SET token=?,lease_until=? WHERE account=? AND request_id=?",token,clock.instant().plusSeconds(300).atOffset(java.time.ZoneOffset.UTC),context.account(),request);
                    return new Command(request,token,true,false);
                }
                if(((java.time.OffsetDateTime)prior.get("lease_until")).toInstant().isAfter(clock.instant())) throw new CallerFault("information command is still in progress; retry the same requestId");
                jdbc.update("UPDATE information_commands SET token=?,lease_until=? WHERE account=? AND request_id=?",token,clock.instant().plusSeconds(300).atOffset(java.time.ZoneOffset.UTC),context.account(),request);
            } else jdbc.update("INSERT INTO information_commands(account,request_id,revision_id,fingerprint,state,token,lease_until) VALUES(?,?,?,?,'reserved',?,?)",
                    context.account(),request,revision,fingerprint,token,clock.instant().plusSeconds(300).atOffset(java.time.ZoneOffset.UTC));
            return new Command(request,token,false,false);
        });
    }
    private void commandState(InformationContext context,Command command,String state,String error) {
        if(command!=null) jdbc.update("UPDATE information_commands SET state=?,error=? WHERE account=? AND request_id=? AND token=?",state,error,context.account(),command.request(),command.token());
    }
    private void apply(InformationContext context,Command command,Runnable work) {
        if(command!=null && command.applied()) return;
        transactions.inTransaction(() -> {
            if(command!=null && jdbc.queryForList("SELECT request_id FROM information_commands WHERE account=? AND request_id=? AND token=? AND state='reserved' AND lease_until>=? FOR UPDATE",
                    context.account(),command.request(),command.token(),clock.instant().atOffset(java.time.ZoneOffset.UTC)).isEmpty())
                throw new CallerFault("information command lease expired; retry the same requestId");
            work.run();
            commandState(context,command,"applied",null);
            return null;
        });
    }
    private void managed(InformationContext context,UUID revision,String operation,UUID request,Object parameters,Runnable work) {
        Map<String,Object> resource=requireOwner(context,revision);
        Command command=reserve(context,revision,request,operation,parameters);
        if(command!=null && command.replay()) return;
        boolean safety=List.of("withdrawn","excluded","deleted","unshare","unlink").contains(operation);
        // Publication commands have no expensive checkpoint: approve the prepared transition
        // before exposing its grant or final state. Safety reductions always commit first.
        boolean publication=List.of("share","finalise","link","active","included").contains(operation);
        var lease=new InformationLifecycle.Lease(revision,(UUID)resource.get("id"),generation(revision),operation,1,
                UUID.randomUUID(),context.account(),(Long)resource.get("project_id"));
        if(safety) apply(context,command,work);
        try {
            if(command==null || !command.applied()) {
                var pre=gates.before(lease);
                event(revision,generation(revision),context.account(),operation,"stage.pre",canonical(pre));
                if(pre.isDenied() && !safety) throw new CallerFault(pre.denied());
            }
            if(!safety && !publication) apply(context,command,work);
            var post=gates.after(lease);
            event(revision,generation(revision),context.account(),operation,"stage.post",canonical(post));
            if(post.isDenied() && !safety) throw new CallerFault((publication?"stage.post denied publication: ":"checkpoint retained; stage.post denied completion: ")+post.denied());
            if(publication) apply(context,command,work);
            commandState(context,command,"completed",null);
        } catch(RuntimeException failure) {
            if(!safety) {
                commandState(context,command,"blocked",failure.getMessage());
                throw failure;
            }
            event(revision,generation(revision),context.account(),operation,"hook.failed",failure.getClass().getSimpleName());
            commandState(context,command,"completed",null);
        } finally { gates.finished(lease); }
    }

    public UUID evidence(InformationContext context,UUID revision,int start,int end,String quote,String locator) {
        return evidence(context,revision,start,end,quote,locator,null);
    }
    public UUID evidence(InformationContext context,UUID revision,int start,int end,String quote,String locator,UUID request) {
        return evidenceForSession(context,revision,start,end,quote,locator,request,null);
    }
    public UUID evidenceForSession(InformationContext context,UUID revision,int start,int end,String quote,String locator,UUID request,String session) {
        String retained=text(context,revision);
        if(start<0 || end<=start || end>retained.length() || !retained.substring(start,end).equals(quote) || !"extracted-text:utf16".equals(locator))
            throw new CallerFault("evidence must quote retained UTF-16 text at valid offsets");
        if(request==null && writeGates==null)return evidenceUnchecked(context,revision,start,end,quote,locator,null);
        return prepared(context,request,"evidence.record",java.util.Arrays.asList(revision,start,end,quote,locator),List.of(revision),session,UUID.class,
                () -> evidenceUnchecked(context,revision,start,end,quote,locator,request));
    }
    private UUID evidenceUnchecked(InformationContext context,UUID revision,int start,int end,String quote,String locator,UUID request) {
        return transactions.inTransaction(() -> {
            String text = text(context, revision);
            if (start < 0 || end <= start || end > text.length() || !text.substring(start,end).equals(quote))
                throw new CallerFault("evidence must quote the retained extracted text at the supplied offsets");
            if(!"extracted-text:utf16".equals(locator)) throw new CallerFault("evidence locator must be extracted-text:utf16; offsets name the immutable retained extraction");
            if(request!=null) {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",Boolean.class,context.account()+":evidence:"+request);
                var prior=jdbc.queryForList("SELECT id,revision_id,start_offset,end_offset,quote,locator FROM information_evidence WHERE owner_handle=? AND request_id=?",context.account(),request);
                if(!prior.isEmpty()) {
                    var row=prior.getFirst();
                    if(!revision.equals(row.get("revision_id")) || start!=((Number)row.get("start_offset")).intValue() || end!=((Number)row.get("end_offset")).intValue() || !quote.equals(row.get("quote")) || !locator.equals(row.get("locator")))
                        throw new CallerFault("requestId was already used for different evidence");
                    return (UUID)row.get("id");
                }
            }
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO information_evidence(id,revision_id,start_offset,end_offset,quote,locator,owner_handle,request_id)"
                    + " VALUES(?,?,?,?,?,?,?,?)", id, revision, start, end, quote, locator,context.account(),request);
            return id;
        });
    }

    public Map<String,Object> evidence(InformationContext context, UUID id) {
        List<Map<String,Object>> found = jdbc.queryForList("SELECT id,revision_id,paragraph_id,start_offset,end_offset,quote,locator,created_at FROM information_evidence WHERE id=?", id);
        if (found.isEmpty()) throw absent();
        requireReadable(context,(UUID)found.getFirst().get("revision_id"));
        return found.getFirst();
    }

    /** All supplied inputs are dependencies, even those that the report did not cite. */
    public Admission report(InformationContext context, UUID request, String name, String text,
            List<UUID> inputs, List<UUID> evidence, UUID feedback) {
        return reportForSession(context,request,name,text,inputs,evidence,feedback,null);
    }
    public Admission reportForSession(InformationContext context,UUID request,String name,String text,List<UUID> inputs,List<UUID> evidence,UUID feedback,String session) {
        return reportDetailsForSession(context,request,name,text,inputs,evidence,feedback,session,InformationReportDetails.from(Map.of()),null);
    }
    public Admission reportDetailsForSession(InformationContext context,UUID request,String name,String text,List<UUID> inputs,List<UUID> evidence,UUID feedback,String session,InformationReportDetails details,String producer) {
        if(canonical(details).getBytes(StandardCharsets.UTF_8).length>512*1024)throw new CallerFault("report findings and review metadata must fit within 512 KiB");
        var citations=new java.util.LinkedHashSet<>(evidence);citations.addAll(details.evidence());
        var dependencies=new java.util.LinkedHashSet<>(inputs);
        if(feedback!=null)dependencies.add(feedback);
        for(UUID input:dependencies)requireReadable(context,input);
        if(feedback!=null) {
            var previous=status(context,feedback);
            if(!"report".equals(previous.get("kind")) || !java.util.Objects.equals(name,previous.get("source_name")))
                throw new CallerFault("feedback must name a retained revision of this report");
        }
        if(producer!=null)new InformationJobs(jdbc,access,this).requireLog(producer,context.account());
        if(text==null || text.isBlank() || name==null || name.isBlank() || dependencies.isEmpty())throw new CallerFault("a research report needs a name, text and input revisions");
        for(UUID id:citations)if(!dependencies.contains(evidence(context,id).get("revision_id")))throw new CallerFault("cited evidence must belong to a supplied input revision");
        return prepared(context,request,"record.report",java.util.Arrays.asList(name,sha256(text.getBytes(StandardCharsets.UTF_8)),List.copyOf(dependencies),List.copyOf(citations),feedback,details,producer),List.copyOf(dependencies),session,Admission.class,
                () -> reportUnchecked(context,request,name,text,List.copyOf(dependencies),List.copyOf(citations),feedback,session,details,producer));
    }
    private Admission reportUnchecked(InformationContext context,UUID request,String name,String text,List<UUID> inputs,List<UUID> evidence,UUID feedback,String session,InformationReportDetails details,String producer) {
        if (text == null || text.isBlank() || inputs.isEmpty()) throw new CallerFault("a research report needs text and input revisions");
        return transactions.inTransaction(() -> {
            List<UUID> dependencies = new ArrayList<>(inputs);
            if (feedback != null && !dependencies.contains(feedback)) dependencies.add(feedback);
            for (UUID id : evidence) {
                UUID revision = (UUID) evidence(context,id).get("revision_id");
                if (!dependencies.contains(revision)) throw new CallerFault("cited evidence must belong to a supplied input revision");
            }
            Admission result = admit(context, request, name, text.getBytes(StandardCharsets.UTF_8),
                    "text/markdown", null, "report", dependencies, evidence, feedback, session,0);
            if (result.created()) {
                String hash=producer==null?null:jdbc.queryForList("SELECT definition_hash FROM orchestrations WHERE conductor_conversation=?",String.class,producer).stream().findFirst().orElse(null);
                jdbc.update("INSERT INTO information_reports(revision_id,feedback_revision,details,produced_by,definition_hash) VALUES(?,?,CAST(? AS jsonb),?,?)",result.revision(),feedback,canonical(details),producer,hash);
                for (UUID id : evidence) jdbc.update("INSERT INTO information_report_citations VALUES(?,?)",result.revision(),id);
            }
            return result;
        });
    }

    public void finalise(InformationContext context,UUID revision) {
        finalise(context,revision,null);
    }

    public void finalise(InformationContext context,UUID revision,UUID request) {
        managed(context,revision,"finalise",request,List.of(),() -> {
        transactions.inTransaction(() -> {
            requireOwner(context, revision);
            requireReadable(context, revision);
            if (jdbc.queryForObject("SELECT count(*) FROM information_steps WHERE revision_id=? AND generation=? AND state='ready'",
                    Integer.class,revision,generation(revision)) != STAGES.size()) throw new CallerFault("a final report must complete processing first");
            for(var step:jdbc.queryForList("SELECT stage,fingerprint FROM information_steps WHERE revision_id=? AND generation=?",revision,generation(revision))) {
                String expected=fingerprint((String)step.get("stage"));
                if(expected!=null && !expected.equals(step.get("fingerprint")))
                    throw new CallerFault("a final report needs projections compatible with the configured pipeline; explicitly rebuild incompatible projections");
            }
            jdbc.update("UPDATE information_reports SET status='superseded' WHERE status='final' AND revision_id IN"
                    + " (SELECT older.id FROM information_revisions older JOIN information_revisions current ON older.resource_id=current.resource_id WHERE current.id=? AND older.id<>?)",revision,revision);
            if (jdbc.update("UPDATE information_reports SET status='final',finalised_at=? WHERE revision_id=? AND status='draft'",
                    clock.instant().atOffset(java.time.ZoneOffset.UTC),revision) != 1) throw new CallerFault("this revision is not a draft report");
            event(revision,generation(revision),context.account(),"report","finalised","");
            return null;
        });

        });
    }

    public void requireReadable(InformationContext context, UUID revision) {
        access.requireSelection(context);
        List<Object> args = new ArrayList<>(selectionArgs(context));
        args.add(revision);
        if (jdbc.queryForList(SELECT + " WHERE " + READABLE + " AND r.id=?", args.toArray()).isEmpty()) throw absent();
    }

    private Map<String,Object> requireOwner(InformationContext context, UUID revision) {
        access.requireSelection(context);
        List<Map<String,Object>> resources = jdbc.queryForList("SELECT q.* FROM information_resources q JOIN information_revisions r"
                + " ON r.resource_id=q.id WHERE r.id=? AND q.owner_handle=? FOR UPDATE OF q",revision,context.account());
        if (resources.isEmpty()) throw absent();
        Object project = resources.getFirst().get("project_id");
        if (project != null) {
            String name = jdbc.queryForObject("SELECT name FROM projects WHERE id=?",String.class,project);
            access.resolve(context.account(), InformationContext.Selection.project(name));
        }
        return resources.getFirst();
    }

    Map<String,Object> row(UUID revision) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT r.*,q.source_name,q.owner_handle,q.project_id FROM information_revisions r"
                + " JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",revision);
        if(rows.isEmpty()) throw absent();
        return rows.getFirst();
    }
    long generation(UUID revision) { return ((Number)row(revision).get("generation")).longValue(); }
    void event(UUID revision,long generation,String actor,String stage,String action,String detail) {
        transactions.inTransaction(() -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('information-event-order',0)) IS NULL",Boolean.class);
            Long sequence=jdbc.queryForObject("INSERT INTO information_events(revision_id,generation,actor_handle,stage,action,detail) VALUES(?,?,?,?,?,?) RETURNING sequence",Long.class,
                    revision,generation,actor,stage,action,detail == null ? "" : detail);
            jdbc.update("INSERT INTO information_event_recipients(sequence,account) SELECT ?,a.handle FROM admins a WHERE information_readable(?,a.handle,'personal',NULL,true)"
                    + " OR EXISTS(SELECT 1 FROM projects p JOIN project_members m ON m.project_id=p.id WHERE m.handle=a.handle AND information_readable(?,a.handle,'project',p.name,true)) ON CONFLICT DO NOTHING",
                    sequence,revision,revision);
            if(List.of("withdrawn","excluded","deleted","unshared","unlinked").contains(action))
                jdbc.update("INSERT INTO information_event_recipients(sequence,account) SELECT ?,x.account FROM information_event_recipients x JOIN information_events e ON e.sequence=x.sequence"
                        + " WHERE e.revision_id=? AND e.sequence<? ON CONFLICT DO NOTHING",sequence,revision,sequence);
            return null;
        });
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static NotFoundFault absent() { return new NotFoundFault("information is unavailable in this selection"); }
    private static List<Object> selectionArgs(InformationContext context) {
        return java.util.Arrays.asList(context.account(),context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
                context.selection().project(),context.selection().includeShared(),context.account(),
                context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
                context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),context.selection().project(),context.account(),
                context.account(),context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),context.selection().project(),context.selection().includeShared());
    }
    private static final String SELECT="SELECT r.id,r.resource_id,r.ordinal,r.title,r.media_type,r.source_uri,r.content_hash,r.text_hash,"
            + "r.byte_size,r.created_at,r.availability,r.excluded,r.generation,r.converter,r.allowance_total,r.allowance_spent,q.source_name,q.kind FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id";
    // Pending sources have no corpus row yet; ownership and project admission still apply. Dependencies always require live permission.
    private static final String READABLE="r.availability='active' AND (information_readable(r.id,?,?,?,?) OR ("
            + "NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=r.id) AND q.owner_handle=? AND q.namespace<>'legacy'"
            + " AND ((q.project_id IS NULL AND ?='personal') OR (q.project_id IS NOT NULL AND ?='project'"
            + " AND EXISTS(SELECT 1 FROM projects p JOIN project_members m ON m.project_id=p.id"
            + " WHERE p.id=q.project_id AND p.name=? AND m.handle=?)))"
            + " AND NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision=r.id"
            + " AND NOT information_readable(i.input_revision,?,?,?,?))))";
    public record Admission(UUID revision, UUID resource, boolean created) {}
}
