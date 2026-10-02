package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.llm.accounting.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.fetch.PageFetcher;
import io.aeyer.plowshare.server.faults.*;
import io.aeyer.plowshare.server.harness.*;
import io.aeyer.plowshare.server.hooks.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable URL intake. A failed fetch is inspectable and retryable; a denied post gate retains its bytes. */
public final class InformationAcquisitions implements AutoCloseable, UsageAware {
    private UsageOwners usageOwners = UsageOwners.NONE;
    @Override public void useUsageOwners(UsageOwners source) { usageOwners = java.util.Objects.requireNonNull(source); }
    private final JdbcTemplate jdbc;
    private final UnitOfWork work;
    private final InformationAccess access;
    private final InformationCatalogue catalogue;
    private final InformationJobs inputs;
    private final PageFetcher fetcher;
    private final IntSupplier allowance;
    private final ConversationStore conversations;
    private final LogStages logStages;
    private final Hooks configured;
    private final Harness harness;
    private final Clock clock;
    private ScheduledExecutorService worker;
    public InformationAcquisitions(JdbcTemplate jdbc,UnitOfWork work,InformationAccess access,InformationCatalogue catalogue,
            PageFetcher fetcher,IntSupplier allowance,ConversationStore conversations,LogStages logStages,Hooks configured,Harness harness,Clock clock,InformationJobs inputs) {
        this.inputs=inputs;this.jdbc=jdbc;this.work=work;this.access=access;this.catalogue=catalogue;this.fetcher=fetcher;this.allowance=allowance;
        this.conversations=conversations;this.logStages=logStages;this.configured=configured;this.harness=harness;this.clock=clock;
    }
    public Map<String,Object> submit(InformationContext context,UUID request,String url,String name,String session) {
        access.requireSelection(context);
        if(context.selection().scope()==InformationContext.Scope.SHARED) throw new CallerFault("acquire into a personal or project namespace");
        if(request==null || url==null || url.isBlank() || name==null || name.isBlank()) throw new CallerFault("acquisition needs requestId, URL and source name");
        int total=allowance.getAsInt();if(total<1) throw new CallerFault("processing allowance must be positive");
        String fingerprint=InformationCatalogue.sha256(json(Arrays.asList(context.selection(),url,name)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID id=work.inTransaction(() -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",Boolean.class,context.account()+":acquire:"+request);
            var prior=jdbc.queryForList("SELECT id,fingerprint FROM information_acquisitions WHERE account=? AND request_id=?",context.account(),request);
            if(!prior.isEmpty()) {
                if(!fingerprint.equals(prior.getFirst().get("fingerprint"))) throw new CallerFault("requestId was already used for a different acquisition");
                return (UUID)prior.getFirst().get("id");
            }
            if(jdbc.queryForObject("SELECT count(*) FROM information_requests WHERE account=? AND request_id=?",Integer.class,context.account(),request)!=0) throw new CallerFault("requestId was already used for information intake");
            UUID ticket=UUID.randomUUID();
            jdbc.update("INSERT INTO information_acquisitions(id,account,request_id,scope,project_name,fingerprint,url,source_name,caller_session,allowance_total) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    ticket,context.account(),request,context.selection().scope().name().toLowerCase(Locale.ROOT),context.selection().project(),fingerprint,url,name,session,total);
            return ticket;
        });
        return status(context,id);
    }
    private Map<String,Object> owned(InformationContext context,UUID ticket) {
        access.requireSelection(context);
        var rows=jdbc.queryForList("SELECT * FROM information_acquisitions WHERE id=? AND account=?",ticket,context.account());
        if(rows.isEmpty()) throw new NotFoundFault("acquisition is unavailable to this account");
        var row=rows.getFirst();
        if(!Objects.equals(context.selection().project(),row.get("project_name")) || context.selection().scope()==InformationContext.Scope.SHARED)
            throw new NotFoundFault("select the acquisition's original namespace");
        return row;
    }
    public Map<String,Object> status(InformationContext context,UUID ticket) {
        var row=owned(context,ticket);
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","url","source_name","state","revision_id","attempt","error","allowance_total","created_at","pre_gate","post_gate")) {
            Object value=row.get(key);
            if(value!=null && (key.equals("pre_gate") || key.equals("post_gate"))) {
                try { value=new com.fasterxml.jackson.databind.ObjectMapper().readTree(value.toString()); }
                catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            }
            result.put(key,value);
        }
        return result;
    }
    public List<Map<String,Object>> list(InformationContext context,int limit,int offset) {
        access.requireSelection(context);
        if(context.selection().scope()==InformationContext.Scope.SHARED)return List.of();
        if(limit<1 || limit>100 || offset<0)throw new CallerFault("acquisition list needs limit 1..100 and nonnegative offset");
        return jdbc.queryForList("SELECT id,url,source_name,state,revision_id,attempt,error,allowance_total,created_at FROM information_acquisitions WHERE account=? AND project_name IS NOT DISTINCT FROM ? ORDER BY created_at DESC,id OFFSET ? LIMIT ?",
                context.account(),context.selection().project(),offset,limit);
    }
    public void retry(InformationContext context,UUID ticket) {
        owned(context,ticket);
        jdbc.update("UPDATE information_acquisitions SET state='queued',error=NULL WHERE id=? AND state IN ('failed','blocked')",ticket);
    }
    public synchronized void start() {
        if(worker!=null)return;
        worker=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("information-acquisition").factory());
        worker.scheduleWithFixedDelay(() -> {
            try { drainOne(); }
            catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(InformationAcquisitions.class).warn("information acquisition queue could not be serviced",failure); }
        },0,1,TimeUnit.SECONDS);
    }
    public boolean drainOne() {
        var row=work.inTransaction(() -> {
            var rows=jdbc.queryForList("SELECT * FROM information_acquisitions WHERE state='queued' OR (state='running' AND lease_until<?) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",now());
            if(rows.isEmpty())return null;
            var found=new LinkedHashMap<>(rows.getFirst());UUID token=UUID.randomUUID();
            found.put("token",token);found.put("attempt",((Number)found.get("attempt")).intValue()+1);
            jdbc.update("UPDATE information_acquisitions SET state='running',token=?,lease_until=?,attempt=? WHERE id=?",token,
                    clock.instant().plusSeconds(300).atOffset(ZoneOffset.UTC),found.get("attempt"),found.get("id"));
            return found;
        });
        if(row==null)return false;
        HarnessRun run=harness.begin();
        try {
            String account=(String)row.get("account"),project=(String)row.get("project_name");
            var context=access.resolve(account,project==null?InformationContext.Selection.personal():InformationContext.Selection.project(project));
            String log=(String)row.get("log_id");
            if(log==null) {
                Home home=project==null?Home.global():Home.of(project);
                log=conversations.log(Origin.SUBMISSION,home,"document_pipeline",null,Budget.of(((Number)row.get("allowance_total")).intValue()),account,null).id();
                String opened=log;
                work.inTransaction(() -> { fence(row);jdbc.update("UPDATE information_acquisitions SET log_id=? WHERE id=?",opened,row.get("id"));return null; });
                logStages.opened(new LogStages.LogOpened(log,Origin.SUBMISSION,home,"document_pipeline",false,null,(String)row.get("caller_session"),null));
            }
            var hookContext=HookContext.forLog("submission","document_pipeline",false,project,log)
                    .withUsage(usageOwners.conversation(log,0,UsageAttribution.Operation.HOOK_MODEL))
                    .about(new HookContext.Document("acquire",null,null,1,"acquire",((Number)row.get("attempt")).intValue(),(String)row.get("url")));
            Hooks hooks=Hooks.chain(run.forModel(null),configured);
            var shown=new StageShown("acquire","acquire",0,1);
            Gate pre=hooks.stagePre(hookContext,new StageStart(shown,null,null));
            work.inTransaction(() -> { fence(row);jdbc.update("UPDATE information_acquisitions SET pre_gate=CAST(? AS jsonb) WHERE id=?",json(pre),row.get("id"));return null; });
            if(pre.isDenied()) { finish(row,"blocked",pre.denied());return true; }
            UUID revision=(UUID)row.get("revision_id");
            String processingLog=log;
            if(revision==null) {
                var fetched=fetcher.fetch((String)row.get("url"));
                if(!fetched.isOk()) throw new CallerFault("acquisition failed: "+fetched.failure()+": "+fetched.message());
                if(fetched.sourceBytes()==null) throw new CallerFault("fetch provider returned no original response bytes");
                revision=work.inTransaction(() -> {
                    fence(row);access.requireSelection(context);
                    var admitted=catalogue.acquired(context,(UUID)row.get("request_id"),(String)row.get("source_name"),fetched.sourceBytes(),fetched.mediaType(),fetched.finalUrl(),(String)row.get("caller_session"),((Number)row.get("allowance_total")).intValue());
                    inputs.bind(processingLog,context,List.of(admitted.revision()));
                    jdbc.update("UPDATE information_acquisitions SET revision_id=? WHERE id=?",admitted.revision(),row.get("id"));
                    return admitted.revision();
                });
            }
            catalogue.requireReadable(context,revision);
            inputs.requireLog(log,account);
            var retained=catalogue.row(revision);
            Gate post=hooks.stagePost(hookContext.about(new HookContext.Document("acquire",retained.get("resource_id").toString(),revision.toString(),1,"acquire",((Number)row.get("attempt")).intValue(),(String)retained.get("source_uri"))),
                    new StageDone(shown,"original response bytes retained",null));
            work.inTransaction(() -> { fence(row);jdbc.update("UPDATE information_acquisitions SET post_gate=CAST(? AS jsonb) WHERE id=?",json(post),row.get("id"));return null; });
            finish(row,post.isDenied()?"blocked":"succeeded",post.denied());
        } catch(InformationLifecycle.StaleLease invalidated) {
            // A newer attempt owns the ticket. Its predecessor cannot admit bytes or publish success.
        } catch(RuntimeException failure) {
            finish(row,"failed",failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());
        } finally {
            var records=run.finish();
            jdbc.update("UPDATE information_acquisitions SET finish_records=CAST(? AS jsonb) WHERE id=? AND token=?",json(records),row.get("id"),row.get("token"));
        }
        return true;
    }
    private void fence(Map<String,Object> row) {
        if(jdbc.queryForList("SELECT id FROM information_acquisitions WHERE id=? AND token=? AND state='running' AND lease_until>=? FOR UPDATE",row.get("id"),row.get("token"),now()).isEmpty()) throw new InformationLifecycle.StaleLease();
    }
    private void finish(Map<String,Object> row,String state,String error) {
        jdbc.update("UPDATE information_acquisitions SET state=?,error=?,token=NULL,lease_until=NULL WHERE id=? AND token=? AND state='running'",state,error,row.get("id"),row.get("token"));
    }
    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }
    private static String json(Object value) { try {return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);} }
    @Override public synchronized void close() { if(worker!=null)worker.shutdownNow(); }
}
