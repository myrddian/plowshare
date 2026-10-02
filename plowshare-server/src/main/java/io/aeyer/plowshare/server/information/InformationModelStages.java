package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.llm.accounting.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.documents.DocumentStages;
import io.aeyer.plowshare.server.harness.*;
import io.aeyer.plowshare.server.hooks.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/** Tier and deliberation gates preserve paid responses across denial/retry without widening evidence contexts. */
public final class InformationModelStages implements DocumentStages, UsageAware {
    private UsageOwners usageOwners = UsageOwners.NONE;
    @Override public void useUsageOwners(UsageOwners source) { usageOwners = java.util.Objects.requireNonNull(source); }
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final UnitOfWork transactions;
    private final InformationCatalogue catalogue;
    private final InformationJobs inputs;
    private final Hooks configured;
    private final Harness harness;
    public InformationModelStages(JdbcTemplate jdbc,UnitOfWork transactions,InformationCatalogue catalogue,InformationJobs inputs,Hooks configured,Harness harness) {
        this.jdbc=jdbc;this.transactions=transactions;this.catalogue=catalogue;this.inputs=inputs;this.configured=configured;this.harness=harness;
    }
    public DocumentStages forLease(InformationLifecycle.Lease lease,Runnable fence) {
        return (definition,task,log,home,owner,work) -> run(lease.revision(),lease.generation(),"processing",fence,definition,task,log,home,owner,work);
    }
    @Override public DocumentStages forDocument(UUID revision) {
        return (definition,task,log,home,owner,work) -> {
            if(log==null || owner==null) throw new IllegalStateException("document model stages need an owned durable log");
            inputs.requireLog(log,owner);
            return run(revision,catalogue.generation(revision),"ask:"+log,() -> inputs.requireLog(log,owner),definition,task,log,home,owner,work);
        };
    }
    @Override public Outcome run(AgentDefinition definition,String task,String log,Home home,String owner,Function<String,Outcome> work) {
        throw new IllegalStateException("document model stages need an explicit source revision");
    }
    private Outcome run(UUID revision,long generation,String purpose,Runnable fence,AgentDefinition definition,String task,String log,Home home,String owner,Function<String,Outcome> work) {
        HarnessRun run=harness.begin();
        try {
            if(log!=null) inputs.requireLog(log,owner);
            var row=catalogue.row(revision);
            var context=HookContext.forLog("delegation",definition.name(),false,home.project(),log)
                    .withUsage(usageOwners.conversation(log,0,UsageAttribution.Operation.HOOK_MODEL))
                    .about(new HookContext.Document(purpose.equals("processing")?"summarise":"ask",row.get("resource_id").toString(),revision.toString(),generation,definition.name(),1));
            var shown=new StageShown(definition.name(),definition.name(),0,1);
            Hooks hooks=Hooks.chain(run.forModel(definition.model()),configured);
            Gate pre=hooks.stagePre(context,new StageStart(shown,null,null));
            catalogue.event(revision,generation,owner,definition.name(),"stage.pre",json(pre));
            if(pre.isDenied()) throw new DocumentStages.Blocked(pre.denied());
            String asked=pre.applyTo(task);
            String key=InformationCatalogue.sha256(json(List.of(purpose,owner,home.toString(),definition.name(),String.valueOf(definition.model()),definition.intent().toString(),definition.sampling().toString(),definition.prompt(),asked)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var cached=jdbc.queryForList("SELECT response FROM information_model_steps WHERE revision_id=? AND generation=? AND stage_key=? AND owner_handle=?",revision,generation,key,owner);
            Outcome response;
            if(cached.isEmpty()) {
                response=work.apply(asked);
                if(response.ending()!=Outcome.Ending.ANSWERED || response.text().isBlank()) return response;
                Outcome paid=response;
                transactions.inTransaction(() -> {
                    fence.run();
                    jdbc.update("INSERT INTO information_model_steps(revision_id,generation,stage_key,stage,owner_handle,response,state,log_id) VALUES(?,?,?,?,?,CAST(? AS jsonb),'blocked',?) ON CONFLICT DO NOTHING",
                            revision,generation,key,definition.name(),owner,json(paid),log);
                    return null;
                });
            } else {
                Outcome paid=read(cached.getFirst().get("response").toString());
                response=new Outcome(paid.ending(),paid.text(),paid.steps(),0,paid.detail());
            }
            if(log!=null) inputs.requireLog(log,owner);
            Gate post=hooks.stagePost(context,new StageDone(shown,response.text(),null));
            catalogue.event(revision,generation,owner,definition.name(),"stage.post",json(post));
            if(post.isDenied()) throw new DocumentStages.Blocked(post.denied(),response.modelCalls());
            transactions.inTransaction(() -> {
                fence.run();
                jdbc.update("UPDATE information_model_steps SET state='ready' WHERE revision_id=? AND generation=? AND stage_key=?",revision,generation,key);
                return null;
            });
            return response;
        } finally {
            var records=run.finish();
            if(!records.isEmpty()) catalogue.event(revision,generation,owner,definition.name(),"hook.finish",json(records));
        }
    }
    private static Outcome stopped(String reason,int calls) { return new Outcome(Outcome.Ending.UNAVAILABLE,"Document stage blocked: "+reason,0,calls,reason); }
    private static String json(Object value) {
        try {return JSON.writeValueAsString(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
    }
    private static Outcome read(String value) {
        try {return JSON.readValue(value,Outcome.class);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
    }
}
