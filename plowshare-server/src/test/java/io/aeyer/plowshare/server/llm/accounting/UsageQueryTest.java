package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.*;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.ws.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@Timeout(value=30,unit=TimeUnit.SECONDS,threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class UsageQueryTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    static DriverManagerDataSource data;
    @TempDir Path directory;
    JdbcTemplate jdbc; UsageQueryService queries; AccountingStore store; AccountingJournal journal;
    String project,root,leaf,foreign; UsageAttribution parent,child,other;
    @BeforeAll static void migrate() {
        data=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(data).load().migrate();
    }
    @BeforeEach void fresh() {
        jdbc=new JdbcTemplate(data);
        jdbc.execute("TRUNCATE conversations,projects,admins,inference_run_ownership,inference_accounting_events,inference_attempts,inference_calls,inference_pricing_versions,inference_accounting_instances,inference_accounting_journals CASCADE");
        jdbc.update("INSERT INTO admins(handle,password_hash) VALUES ('alice','fixture'),('bob','fixture')");
        var conversations=new ConversationStore(jdbc);
        root=conversations.open(Home.of("one"),Budget.of(10),TurnCap.none(),"alice").id();
        leaf=conversations.open(Home.of("one"),Budget.of(10),TurnCap.none(),"alice").id();
        foreign=conversations.open(Home.of("two"),Budget.of(10),TurnCap.none(),"bob").id();
        project=jdbc.queryForObject("SELECT id::text FROM projects WHERE name='one'",String.class);
        jdbc.update("INSERT INTO project_members(project_id,handle) SELECT id,CASE name WHEN 'one' THEN 'alice' ELSE 'bob' END FROM projects");
        parent=UsageAttribution.project("alice",project,UsageAttribution.Operation.AGENT_CHAT)
                .withExecution(UsageLineage.root(root),UsageLineage.root("run-root"),UsageLineage.NONE,"coordinator",1L,1L);
        child=parent.withExecution(parent.conversations().child(leaf),parent.runs().child("run-child"),UsageLineage.NONE,"researcher",1L,1L);
        other=UsageAttribution.project("bob",jdbc.queryForObject("SELECT id::text FROM projects WHERE name='two'",String.class),UsageAttribution.Operation.AGENT_CHAT)
                .withExecution(UsageLineage.root(foreign),UsageLineage.root("run-other"),UsageLineage.NONE,"researcher",1L,1L);
        var manager=new DataSourceTransactionManager(data);
        queries=new UsageQueryService(jdbc,manager,new ObjectMapper().findAndRegisterModules(),AccountingFixtures.CLOCK);
        store=new AccountingStore(jdbc,manager,AccountingFixtures.MAPPER,AccountingFixtures.CLOCK);
        journal=new AccountingJournal(directory,1024*1024,4096,Duration.ofSeconds(1),AccountingFixtures.MAPPER);
    }
    @AfterEach void close() {if(journal!=null)journal.close();}
    UsageQueryService.Filter filter(String conversation,String project,String run,String scope,List<String> groups,String cursor,Integer limit) {
        return new UsageQueryService.Filter(conversation,project,null,run,null,null,null,null,scope,
                AccountingFixtures.NOW.minusSeconds(1),AccountingFixtures.NOW.plusSeconds(1),groups,cursor,limit);
    }
    UsageQueryService.Report report(String type,UsageQueryService.Filter f) {return queries.report("alice",queries.resolve(type,f));}
    void call(UsageAttribution owner,RateCard price,long input,boolean unknownRetry) {
        var recorder=new AccountingRecorder(journal,AccountingFixtures.CLOCK,10);
        var call=recorder.begin(new AccountingEvent.CallCreated(owner,"model","pool","model",null,"local",Lane.CHAT,price));
        if(unknownRetry) {
            call.startAttempt();call.finishAttempt(CallLifecycle.FAILED,UsageObservation.UNKNOWN,null,503,
                    AccountingEvent.FinishReason.UNKNOWN,null,1);
        }
        call.startAttempt();
        call.finishAttempt(CallLifecycle.SUCCEEDED,UsageNormalizer.openAi(new ObjectMapper().valueToTree(Map.of("prompt_tokens",input,"completion_tokens",2,"total_tokens",input+2))),null,200,
                AccountingEvent.FinishReason.STOP,0L,1);
        call.finish(CallLifecycle.SUCCEEDED);
        store.project(journal.journalId(),journal.readBatch(100));
    }
    @Test void isolated_project_window_combines_analysis_and_independent_document_processing_without_double_counting() {
        call(child,AccountingFixtures.PRICE,120,false);
        var processing=UsageAttribution.project("alice",project,UsageAttribution.Operation.DOCUMENT_SUMMARY)
                .withExecution(UsageLineage.root(leaf),UsageLineage.NONE,UsageLineage.NONE,"document_pipeline",0L,null);
        call(processing,AccountingFixtures.PRICE,80,false);
        var report=report("usage.project",filter(null,"one",null,null,List.of("operation"),null,null));
        assertEquals("200",report.totals().get("input_tokens"));assertEquals("2",report.totals().get("calls"));
        assertEquals(2,report.groups().size());
        assertEquals("200",report.groups().stream().map(row->new java.math.BigInteger(row.get("input_tokens").toString()))
                .reduce(java.math.BigInteger.ZERO,java.math.BigInteger::add).toString());
        assertEquals(true,report.totals().get("usage_complete"));
    }
    @Test void direct_subtree_and_project_totals_sum_atomic_attempts_once() {
        call(parent,AccountingFixtures.PRICE,10,false);call(child,AccountingFixtures.PRICE,20,false);call(other,AccountingFixtures.PRICE,999,false);
        var direct=report("usage.conversation",filter(root,null,null,"direct",List.of(),null,null));
        var tree=report("usage.conversation",filter(root,null,null,"subtree",List.of("agent"),null,null));
        assertEquals("10",direct.totals().get("input_tokens"));assertEquals("30",tree.totals().get("input_tokens"));
        assertEquals("2",tree.totals().get("calls"));assertEquals(2,tree.groups().size());
        assertEquals(tree.totals().get("costs"),report("usage.project",filter(null,"one",null,null,List.of(),null,null)).totals().get("costs"));
        assertEquals("30",report("usage.run",filter(null,null,"run-root","subtree",List.of(),null,null)).totals().get("input_tokens"));
        assertEquals("1029",report("usage.pools",filter(null,null,null,null,List.of(),null,null)).totals().get("input_tokens"));
    }
    @Test void run_breakdown_retains_idle_ancestors_and_authorized_drilldown() throws Exception {
        jdbc.update("INSERT INTO inference_run_ownership(conversation_id,turn_ordinal,attribution) VALUES (?,1,?::jsonb)",root,AccountingFixtures.MAPPER.writeValueAsString(parent));
        var nested=child.withExecution(child.conversations(),child.runs().child("parallel-leaf"),UsageLineage.NONE,"worker",1L,1L);
        var peer=child.withExecution(child.conversations().child("parallel-conversation"),child.runs().child("parallel-peer"),UsageLineage.NONE,"second-worker",1L,1L);
        call(nested,AccountingFixtures.PRICE,20,false);call(peer,AccountingFixtures.PRICE,30,false);call(other,AccountingFixtures.PRICE,999,false);
        var tree=report("usage.conversation",filter(root,null,null,"subtree",List.of("run","agent"),null,null));
        assertEquals(2,tree.groups().size());assertEquals("parallel-leaf",tree.groups().getFirst().get("run"));
        assertEquals(List.of("run-child","run-root"),tree.groups().getFirst().get("ancestor_runs"));
        assertEquals("50",report("usage.run",filter(null,null,"run-root","subtree",List.of(),null,null)).totals().get("input_tokens"));
        assertEquals("0",report("usage.run",filter(null,null,"run-root","direct",List.of(),null,null)).totals().get("input_tokens"));
    }
    @Test void unknown_retry_preserves_known_subtotal_and_explains_incomplete_costs() {
        call(parent,AccountingFixtures.PRICE,10,true);
        var r=report("usage.project",filter(null,"one",null,null,List.of(),null,null));
        assertEquals("10",r.totals().get("input_tokens"));assertEquals("2",r.totals().get("attempts"));
        assertEquals("1",r.totals().get("incomplete_attempts"));assertEquals("1",r.totals().get("unknown_cost_attempts"));
        assertEquals(false,r.totals().get("complete"));assertEquals(Map.of("USD","0.000018"),r.totals().get("costs"));
    }
    @Test void currencies_and_large_token_totals_remain_decimal_strings() {
        call(parent,AccountingFixtures.PRICE,9007199254740993L,false);
        var euro=new RateCard("euro","local","model",RateCard.Mode.TOKEN,"EUR",null,null,
                new RateCard.Rates(new BigDecimal("0.0001"),BigDecimal.ZERO,null,null),List.of(),null,"operator");
        call(child,euro,1,false);
        var r=report("usage.project",filter(null,"one",null,null,List.of(),null,null));
        assertEquals("9007199254740994",r.totals().get("input_tokens"));
        var costs=(Map<?,?>)r.totals().get("costs");assertEquals(2,costs.size());assertEquals("0.0000000001",costs.get("EUR"));
    }
    @Test void inaccessible_descendants_are_filtered_before_grouping_and_target_ids_do_not_grant_access() {
        var alien=other.withExecution(parent.conversations().child(foreign),parent.runs().child("run-alien"),UsageLineage.NONE,"researcher",1L,1L);
        call(parent,AccountingFixtures.PRICE,10,false);call(alien,AccountingFixtures.PRICE,999,false);
        var r=report("usage.conversation",filter(root,null,null,"subtree",List.of("agent"),null,null));
        assertEquals("10",r.totals().get("input_tokens"));assertEquals(1,r.groups().size());
        assertThrows(CallerFault.class,()->report("usage.conversation",filter(foreign,null,null,null,List.of(),null,null)));
        assertThrows(CallerFault.class,()->queries.report("missing",queries.resolve("usage.pools",filter(null,null,null,null,List.of(),null,null))));
    }
    @Test void grouped_pages_keep_whole_header_and_signed_cursor_is_bound_to_account_filters() {
        call(parent,AccountingFixtures.PRICE,10,false);call(child,AccountingFixtures.PRICE,20,false);
        var f=filter(null,"one",null,null,List.of("agent"),null,1);var first=report("usage.project",f);
        assertEquals(1,first.groups().size());assertNotNull(first.cursor());assertEquals("30",first.totals().get("input_tokens"));
        var next=report("usage.project",filter(null,"one",null,null,List.of("agent"),first.cursor(),1));
        assertEquals(1,next.groups().size());assertNull(next.cursor());assertNotEquals(first.groups(),next.groups());
        assertThrows(CallerFault.class,()->report("usage.project",filter(null,"one",null,null,List.of("operation"),first.cursor(),1)));
    }
    @Test void call_audit_pagination_is_atomic_and_never_carries_prompt_or_credentials() throws Exception {
        call(parent,AccountingFixtures.PRICE,10,true);call(child,null,20,false);
        var f=filter(null,"one",null,null,List.of(),null,1);
        var first=queries.calls("alice",queries.resolve("usage.calls",f));assertEquals(1,first.calls().size());assertNotNull(first.cursor());
        var next=queries.calls("alice",queries.resolve("usage.calls",filter(null,"one",null,null,List.of(),first.cursor(),1)));
        assertEquals(1,next.calls().size());assertNull(next.cursor());assertNotEquals(first.calls().getFirst().get("call_id"),next.calls().getFirst().get("call_id"));
        String wire=new ObjectMapper().findAndRegisterModules().writeValueAsString(first);
        assertFalse(wire.contains("messages"));assertFalse(wire.contains("apiKey"));assertFalse(wire.contains("fixture"));
    }
    @Test void subscriptions_replace_snapshots_stay_quiet_and_close_on_membership_revocation() {
        var updates=new ArrayList<Envelope>();var subscriptions=new UsageSubscriptions(queries);
        subscriptions.connect("socket","alice",(id,e)->{if(e!=null)updates.add(e);});
        var asking=new Asking("session","alice","socket");
        var query=queries.resolve("usage.project",filter(null,"one",null,null,List.of(),null,null));
        var initial=subscriptions.subscribe(asking,query);assertEquals(0,initial.revision());
        call(parent,AccountingFixtures.PRICE,10,false);subscriptions.publish();assertTrue(updates.isEmpty());
        subscriptions.ready("socket",initial.subscription());subscriptions.publish();assertEquals(1,updates.size());
        assertNull(updates.getFirst().id());assertEquals("usage.updated",updates.getFirst().type());
        assertEquals(1L,updates.getFirst().payload().get("revision"));subscriptions.publish();assertEquals(1,updates.size());
        jdbc.update("DELETE FROM project_members WHERE project_id::text=?",project);
        subscriptions.publish();assertEquals("usage.closed",updates.getLast().type());subscriptions.publish();assertEquals(2,updates.size());
        subscriptions.unsubscribe(asking,initial.subscription());subscriptions.disconnect("socket");
        assertThrows(CallerFault.class,()->subscriptions.subscribe(asking,query));subscriptions.close();
    }
    @Test void subscription_limit_and_socket_ownership_are_enforced() {
        var subscriptions=new UsageSubscriptions(queries);var messages=new ArrayList<Envelope>();
        subscriptions.connect("one","alice",(id,e)->{if(e!=null)messages.add(e);});subscriptions.connect("two","alice",(id,e)->{});
        var one=new Asking("session","alice","one");var two=new Asking("session","alice","two");
        var q=queries.resolve("usage.models",filter(null,null,null,null,List.of(),null,null));
        var initial=subscriptions.subscribe(one,q);
        var ids=new ArrayList<String>(); ids.add(initial.subscription());
        for(int i=0;i<7;i++)ids.add(subscriptions.subscribe(one,q).subscription());
        assertThrows(CallerFault.class,()->subscriptions.subscribe(one,q));
        subscriptions.unsubscribe(two,initial.subscription());ids.forEach(id -> subscriptions.ready("one",id));call(parent,AccountingFixtures.PRICE,10,false);subscriptions.publish();
        assertEquals(8,messages.size());subscriptions.unsubscribe(one,initial.subscription());subscriptions.unsubscribe(one,initial.subscription());subscriptions.close();
    }
    @Test void websocket_envelope_echoes_correlation_and_ignores_claimed_account() throws Exception {
        call(parent,AccountingFixtures.PRICE,10,false);call(other,AccountingFixtures.PRICE,999,false);
        var subscriptions=new UsageSubscriptions(queries);var frames=new UsageFrames(queries,subscriptions);
        var router=new FrameRouter(frames.frames());
        String frame=new ObjectMapper().findAndRegisterModules().writeValueAsString(new Envelope("request-id","usage.models",Envelope.CURRENT_VERSION,
                Map.of("account","bob","admin",true,"from",AccountingFixtures.NOW.minusSeconds(1).toString(),"to",AccountingFixtures.NOW.plusSeconds(1).toString())));
        var outcome=router.route(frame,new Asking("session","alice"));assertEquals(Code.OK,outcome.code());
        var r=(UsageQueryService.Report)outcome.payload();assertEquals("10",r.totals().get("input_tokens"));
        var malformed=router.route(new Envelope("bad","usage.models",Envelope.CURRENT_VERSION,Map.of("group_by",List.of("unknown"))),new Asking("session","alice"));
        assertEquals(Code.BAD_REQUEST,malformed.code());
    }
    @Test void retained_accounting_survives_source_deletion_with_owner_access_only() {
        call(parent,AccountingFixtures.PRICE,10,false);
        jdbc.update("DELETE FROM conversations WHERE id=?",root);jdbc.update("DELETE FROM conversations WHERE id=?",leaf);jdbc.update("DELETE FROM projects WHERE id::text=?",project);
        assertEquals("10",report("usage.conversation",filter(root,null,null,null,List.of(),null,null)).totals().get("input_tokens"));
        assertThrows(CallerFault.class,()->queries.report("bob",queries.resolve("usage.conversation",filter(root,null,null,null,List.of(),null,null))));
    }
    @Test void deep_attempt_paging_is_lossless_and_rechecks_access_and_cursor_identity() {
        var recorder = new AccountingRecorder(journal,AccountingFixtures.CLOCK,10);
        var call = recorder.begin(new AccountingEvent.CallCreated(parent,"model","pool","model",null,"local",Lane.CHAT,AccountingFixtures.PRICE));
        for(int i=0;i<55;i++) {
            call.startAttempt();
            call.finishAttempt(CallLifecycle.FAILED,UsageObservation.UNKNOWN,null,503,
                    AccountingEvent.FinishReason.UNKNOWN,null,1);
        }
        call.finish(CallLifecycle.FAILED);
        store.project(journal.journalId(),journal.readBatch(200));
        var resolved = queries.resolve("usage.calls",filter(null,"one",null,null,List.of(),null,10));
        var audit = queries.calls("alice",resolved).calls().getFirst();
        assertEquals(true,audit.get("attempts_truncated"));
        assertEquals(50,((List<?>)audit.get("attempts")).size());
        String id = audit.get("call_id").toString();
        var tail = queries.attempts("alice",resolved,id,audit.get("attempt_cursor").toString());
        assertEquals(5,tail.attempts().size()); assertNull(tail.cursor());
        assertEquals(51,tail.attempts().getFirst().get("attempt_number"));
        var first = queries.attempts("alice",resolved,id,null);
        assertEquals(10,first.attempts().size()); assertNotNull(first.cursor());
        var second = queries.attempts("alice",resolved,id,first.cursor());
        assertEquals(11,second.attempts().getFirst().get("attempt_number"));
        var different = queries.resolve("usage.calls",filter(null,"one",null,null,List.of(),null,11));
        assertThrows(CallerFault.class,()->queries.attempts("alice",different,id,first.cursor()));
        assertThrows(CallerFault.class,()->queries.attempts("bob",resolved,id,null));
        jdbc.update("DELETE FROM project_members WHERE project_id::text=?",project);
        assertThrows(CallerFault.class,()->queries.attempts("alice",resolved,id,first.cursor()));
    }
    @Test void database_outage_is_unavailable_without_leaking_driver_text() {
        var manager = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("private-driver-address-and-credential"));
        var offline = new UsageQueryService(jdbc,manager,new ObjectMapper(),AccountingFixtures.CLOCK);
        var frames = new UsageFrames(offline,new UsageSubscriptions(offline));
        var result = new FrameRouter(frames.frames()).route(new Envelope("read","usage.models",Envelope.CURRENT_VERSION,Map.of()),new Asking("session","alice"));
        assertEquals(Code.ARCHIVE_UNAVAILABLE,result.code());
        assertFalse(result.said().contains("private-driver"));
    }
    @Test void replacement_socket_requires_fresh_subscription_and_never_inherits_pending_revisions() {
        var messages = new ArrayList<Envelope>(); var subscriptions = new UsageSubscriptions(queries);
        var asking = new Asking("session","alice","physical");
        var resolved = queries.resolve("usage.models",filter(null,null,null,null,List.of(),null,null));
        subscriptions.connect("physical","alice",(id,e)->{if(e!=null)messages.add(e);});
        var old = subscriptions.subscribe(asking,resolved);subscriptions.ready("physical",old.subscription());
        subscriptions.disconnect("physical");
        subscriptions.connect("physical","alice",(id,e)->{if(e!=null)messages.add(e);});
        call(parent,AccountingFixtures.PRICE,10,false);subscriptions.ready("physical",old.subscription());subscriptions.publish();
        assertTrue(messages.isEmpty());
        var fresh = subscriptions.subscribe(asking,resolved);
        assertNotEquals(old.subscription(),fresh.subscription());assertEquals(0,fresh.revision());
        subscriptions.ready("physical",fresh.subscription());subscriptions.publish();assertTrue(messages.isEmpty());subscriptions.close();
    }
    @Test void acknowledging_one_initial_reply_does_not_enable_another_subscription_early() {
        var messages = new ArrayList<Envelope>(); var subscriptions = new UsageSubscriptions(queries);
        subscriptions.connect("socket","alice",(id,e)->{if(e!=null)messages.add(e);});
        var asking = new Asking("session","alice","socket");
        var resolved = queries.resolve("usage.models",filter(null,null,null,null,List.of(),null,null));
        var first = subscriptions.subscribe(asking,resolved);var second = subscriptions.subscribe(asking,resolved);
        subscriptions.ready("socket",first.subscription());call(parent,AccountingFixtures.PRICE,10,false);subscriptions.publish();
        assertEquals(1,messages.size());assertEquals(first.subscription(),messages.getFirst().payload().get("subscription"));
        subscriptions.ready("socket",second.subscription());subscriptions.publish();
        assertEquals(2,messages.size());assertEquals(second.subscription(),messages.getLast().payload().get("subscription"));subscriptions.close();
    }
}
