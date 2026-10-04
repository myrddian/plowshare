package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real journal, real JobRuntime and actual ESM; command effects and hooks are controlled fixtures.
 */
@Tag("full-db")
@Testcontainers
class ScriptRuntimeTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static final ObjectMapper JSON = new ObjectMapper();
  LlmDispatcher dispatcher;
  ScriptStore store;
  JobRuntime runtime;
  Transcript transcript;
  AgentDefinition definition;
  String conversation;
  final AtomicInteger effects = new AtomicInteger(), questions = new AtomicInteger();
  static final String SOURCE =
      ScriptProgram.MARKER
          + """
        \nexport const manifest={name:'fixture',description:'A journal fixture',model:'fast',tools:['probe'],calls:[],scopes:[],
          'max-turns':10,'max-model-calls':10,stages:[{id:'one'}]};
        export function step(input) {
          return input.state?{state:{done:true},command:{tool:'orchestration_finish',arguments:{result:'complete'}}}
            :{state:{n:1},command:{tool:'probe',arguments:{value:'requested'}}};
        }
        """;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void initialise() {
    jdbc.execute("TRUNCATE conversations, admins CASCADE");
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('alice','hash')");
    conversation =
        new ConversationStore(jdbc)
            .log(Origin.ORCHESTRATION, Home.global(), "fixture", null, Budget.of(10), "alice")
            .id();
    store = new ScriptStore(jdbc);
    definition =
        OrchestrationRegistry.parsePinned(
                "fixture",
                "fixture.js",
                SOURCE,
                Set.of("probe"),
                OrchestrationDefinition.Tier.SHIPPED)
            .conductor();
    transcript =
        new Transcript() {
          public List<io.aeyer.plowshare.server.llm.dispatch.ChatMessage> before() {
            return List.of();
          }

          public void promptMeasured(int tokens) {}

          public String conversationId() {
            return conversation;
          }

          public Origin origin() {
            return Origin.ORCHESTRATION;
          }
        };
    AgentTool probe =
        new AgentTool() {
          public ToolSchema schema() {
            return new ToolSchema("probe", "fixture", Map.of("type", "object"));
          }

          public String run(String arguments, Home home) {
            effects.incrementAndGet();
            assertEquals(Home.global(), home);
            return "{\"effect\":true}";
          }
        };
    dispatcher = mock(LlmDispatcher.class);
    runtime = new JobRuntime(dispatcher, List.of(probe));
    runtime.useScripts(store);
    runtime.useRunExtras(
        context ->
            new RunExtras.Extras(
                List.of(
                    endingTool(ConductorTools.FINISH_NAME, Outcome.Ending.ANSWERED, context.end()),
                    endingTool(ConductorTools.ASK_NAME, Outcome.Ending.AWAITING, context.end())),
                context.end(),
                false));
  }

  AgentTool endingTool(String name, Outcome.Ending ending, TurnEnd end) {
    return new AgentTool() {
      public ToolSchema schema() {
        return new ToolSchema(name, "fixture", Map.of("type", "object"));
      }

      public String run(String arguments, Home home) {
        if (ending == Outcome.Ending.AWAITING) questions.incrementAndGet();
        end.request(ending, "complete");
        return "{\"ended\":true}";
      }
    };
  }

  Outcome run(int cap) {
    return runtime.run(
        definition,
        "begin",
        Home.global(),
        Budget.of(10),
        () -> false,
        null,
        JobWatch.UNWATCHED,
        transcript,
        TurnCap.of(cap),
        List.of(),
        "alice");
  }

  @Test
  void a_custom_script_completes_with_zero_conductor_model_calls() {
    var result = run(10);
    assertEquals(Outcome.Ending.ANSWERED, result.ending());
    assertEquals(0, result.modelCalls());
    assertTrue(
        mockingDetails(dispatcher).getInvocations().stream()
            .allMatch(call -> call.getMethod().getName().equals("wireModelFor")),
        "only hook-profile routing is allowed; no inference dispatch");
    assertEquals(1, effects.get());
  }

  @Test
  void command_cap_resume_uses_the_same_journal_without_repeating_effects() {
    assertEquals(Outcome.Ending.TURN_CAP, run(1).ending());
    assertEquals(1, effects.get());
    assertEquals(Outcome.Ending.ANSWERED, run(2).ending());
    assertEquals(1, effects.get());
    assertEquals(1, store.latest(conversation).orElseThrow().sequence());
  }

  @Test
  void native_question_receipt_and_saved_plan_resume_without_repeating_the_question() {
    var actions = mock(ConductorActions.class);
    when(actions.ask(anyString(), anyString(), anyString())).thenReturn(java.util.Optional.empty());
    when(actions.finish(anyString(), anyString())).thenReturn(java.util.Optional.empty());
    runtime.useRunExtras(
        context ->
            new RunExtras.Extras(
                ConductorTools.forRun(actions, "orc_fixture", context.end()),
                context.end(),
                false));
    definition =
        definition.withPrompt(
            ScriptProgram.MARKER
                + """
            \nexport const manifest={name:'fixture',description:'Objective question fixture',model:'fast',
              tools:['probe'],calls:[],scopes:[],'max-turns':10,'max-model-calls':10,stages:[{id:'one'}]};
            export function step(input) {
              if(!input.state) return {state:{phase:'review',plan:'retained objectives'},command:{
                tool:'orchestration_ask',arguments:{question:'Please review the retained objectives.',questions:[{
                  header:'Objectives',question:'Do these objectives capture your intent?',options:[
                    {label:'Approve objectives',description:'Begin research'},
                    {label:'Change objectives',description:'Revise before research'}]}]}}};
              if(input.state.phase==='review') {
                if(!input.result.startsWith('Asked.') || !input.message.includes('Approve objectives'))
                  throw new Error('missing delivered approval');
                return {state:{phase:'research',plan:input.state.plan},command:{tool:'probe',arguments:{}}};
              }
              return {state:input.state,command:{tool:'orchestration_finish',arguments:{result:input.state.plan}}};
            }
            """);
    assertEquals(Outcome.Ending.AWAITING, run(10).ending());
    assertEquals(0, effects.get());
    var paused = store.latest(conversation).orElseThrow();
    assertEquals("review", paused.state().path("phase").asText());
    assertTrue(paused.result().startsWith("Asked."));
    var resumed =
        runtime.run(
            definition,
            ScriptResearchTest.Fixture.deliveredAnswer("Approve objectives"),
            Home.global(),
            Budget.of(10),
            () -> false,
            null,
            JobWatch.UNWATCHED,
            transcript,
            TurnCap.of(10),
            List.of(),
            "alice");
    assertEquals(Outcome.Ending.ANSWERED, resumed.ending());
    assertEquals(1, effects.get());
    verify(actions, times(1)).ask(eq("orc_fixture"), contains("retained objectives"), anyString());
    verify(actions).finish("orc_fixture", "retained objectives");
    assertEquals(paused.hash(), store.latest(conversation).orElseThrow().hash());
  }

  @Test
  void script_validation_failure_reports_the_reason_and_counts_without_replaying_receipts() {
    definition =
        definition.withPrompt(
            SOURCE.replace(
                "return input.state?{state:{done:true}",
                "if(input.state) throw new Error('Invalid queries: expected 12–24 items, received 27'); return input.state?{state:{done:true}"));
    var budget = Budget.of(10);
    runtime.useHooks(
        new Hooks() {
          public ToolPost toolPost(HookContext c, String tool, String arguments, String result) {
            if (tool.equals("probe")) assertTrue(budget.trySpend());
            return ToolPost.untouched(result);
          }
        });
    var stopped =
        runtime.run(
            definition,
            "begin",
            Home.global(),
            budget,
            () -> false,
            null,
            JobWatch.UNWATCHED,
            transcript,
            TurnCap.of(10),
            List.of(),
            "alice");
    assertEquals(Outcome.Ending.UNAVAILABLE, stopped.ending());
    assertTrue(stopped.text().contains("Script stopped before command 1"));
    assertTrue(stopped.text().contains("expected 12–24 items, received 27"));
    assertEquals(1, stopped.steps());
    assertEquals(1, stopped.modelCalls());
    assertEquals("{\"effect\":true}", store.latest(conversation).orElseThrow().result());
    assertEquals(1, effects.get());
    assertEquals(Outcome.Ending.UNAVAILABLE, run(10).ending());
    assertEquals(1, effects.get(), "a retained command is never reissued after validation failure");
  }

  @Test
  void pre_hook_refusal_parks_before_execution_and_can_be_rejudged() {
    runtime.useHooks(
        new Hooks() {
          public ToolPre toolPre(HookContext c, String tool, String arguments) {
            return tool.equals("probe")
                ? new ToolPre(arguments, "fixture denial", List.of())
                : ToolPre.allowed(arguments);
          }
        });
    assertEquals(Outcome.Ending.AWAITING, run(10).ending());
    assertEquals(0, effects.get());
    assertEquals(1, questions.get());
    assertFalse(store.latest(conversation).orElseThrow().started());
    runtime.useHooks(Hooks.NONE);
    assertEquals(Outcome.Ending.ANSWERED, run(10).ending());
    assertEquals(1, effects.get());
  }

  @Test
  void failed_post_hook_retains_raw_result_and_resume_does_not_repeat_paid_work() {
    runtime.useHooks(
        new Hooks() {
          public ToolPost toolPost(HookContext c, String tool, String arguments, String result) {
            if (tool.equals("probe")) throw new IllegalStateException("fixture post gate failed");
            return ToolPost.untouched(result);
          }
        });
    assertEquals(Outcome.Ending.AWAITING, run(10).ending());
    assertEquals(1, effects.get());
    var pending = store.latest(conversation).orElseThrow();
    assertNotNull(pending.raw());
    assertNull(pending.result());
    runtime.useHooks(Hooks.NONE);
    assertEquals(Outcome.Ending.ANSWERED, run(10).ending());
    assertEquals(1, effects.get());
  }

  @Test
  void an_atomic_stage_refusal_is_rejudged_without_advancing_the_script() {
    var deny = new java.util.concurrent.atomic.AtomicBoolean(true);
    var applied = new AtomicInteger();
    io.aeyer.plowshare.server.todos.TodoLists lists =
        new io.aeyer.plowshare.server.todos.TodoLists() {
          public List<io.aeyer.plowshare.server.todos.TodoItem> list(String log) {
            return List.of();
          }

          public void forget(String log) {}

          public java.util.Optional<Notice> noticeFor(String log) {
            return java.util.Optional.empty();
          }

          public void noticed(String log, io.aeyer.plowshare.server.todos.TodoNotices.Seen seen) {}

          public List<io.aeyer.plowshare.server.todos.TodoItem> apply(
              String log, List<io.aeyer.plowshare.server.todos.TodoOp> ops, String session) {
            applied.incrementAndGet();
            return List.of();
          }
        };
    runtime.useTodos(lists);
    runtime.useRunExtras(
        context ->
            new RunExtras.Extras(
                List.of(
                    new TodoTools.Write(
                        lists,
                        context.transcript(),
                        null,
                        (log, ops, home) -> {
                          if (deny.get())
                            throw new io.aeyer.plowshare.server.todos.TodoRefused(
                                "stage.pre denied by the pinned chain");
                          return ops;
                        }),
                    endingTool(ConductorTools.FINISH_NAME, Outcome.Ending.ANSWERED, context.end()),
                    endingTool(ConductorTools.ASK_NAME, Outcome.Ending.AWAITING, context.end())),
                context.end(),
                false));
    definition =
        definition.withPrompt(
            SOURCE.replace(
                "tool:'probe',arguments:{value:'requested'}",
                "tool:'todo_write',arguments:{ops:[{op:'update',id:'stage',status:'in_progress'}]}"));
    assertEquals(Outcome.Ending.AWAITING, run(10).ending());
    assertEquals(0, applied.get());
    assertNull(store.latest(conversation).orElseThrow().raw());
    deny.set(false);
    assertEquals(Outcome.Ending.ANSWERED, run(10).ending());
    assertEquals(1, applied.get());
  }

  @Test
  void changed_pre_hook_arguments_cannot_reuse_a_cached_execution() {
    assertEquals(Outcome.Ending.TURN_CAP, run(1).ending());
    jdbc.update(
        "UPDATE orchestration_script_steps SET result=NULL,completed_at=NULL WHERE conversation_id=?",
        conversation);
    runtime.useHooks(
        new Hooks() {
          public ToolPre toolPre(HookContext c, String tool, String arguments) {
            return ToolPre.allowed("{\"value\":\"different\"}");
          }
        });
    assertTrue(
        assertThrows(IllegalStateException.class, () -> run(10))
            .getMessage()
            .contains("different request"));
    assertEquals(1, effects.get());
  }

  @Test
  void uncertain_side_effect_is_not_replayed() {
    var output = ScriptProgram.step(SOURCE, JSON.createObjectNode().put("requestId", "fixture"));
    store.prepare(
        conversation,
        new ScriptStore.Step(0, hash(), output.path("state"), output.path("command"), null, null));
    store.started(conversation, 0, "{\"value\":\"requested\"}");
    assertTrue(
        assertThrows(IllegalStateException.class, () -> run(10))
            .getMessage()
            .contains("automatic replay is refused"));
    assertEquals(0, effects.get());
  }

  @Test
  void an_interrupted_native_readiness_observer_can_resume_from_its_saved_references() {
    String source =
        SOURCE
            .replace("'probe'", "'information_read'")
            .replace(
                "arguments:{value:'requested'}",
                "arguments:{operation:'await',sources:[{revision:'00000000-0000-0000-0000-000000000001'}],waitMs:0}");
    var parsed =
        OrchestrationRegistry.parsePinned(
            "fixture",
            "fixture.js",
            source,
            Set.of(InformationTool.READ),
            OrchestrationDefinition.Tier.SHIPPED);
    definition = parsed.conductor();
    AgentTool observer =
        new AgentTool() {
          public ToolSchema schema() {
            return new ToolSchema(
                InformationTool.READ, "fixture readiness", Map.of("type", "object"));
          }

          public String run(String arguments, Home home) {
            effects.incrementAndGet();
            assertTrue(arguments.contains("\"operation\":\"await\""));
            return "{\"complete\":true}";
          }
        };
    runtime = new JobRuntime(dispatcher, List.of(observer));
    runtime.useScripts(store);
    runtime.useRunExtras(
        context ->
            new RunExtras.Extras(
                List.of(
                    endingTool(ConductorTools.FINISH_NAME, Outcome.Ending.ANSWERED, context.end())),
                context.end(),
                false));
    var output = ScriptProgram.step(source, JSON.createObjectNode().put("requestId", "fixture"));
    store.prepare(
        conversation,
        new ScriptStore.Step(
            0, parsed.hash(), output.path("state"), output.path("command"), null, null));
    store.started(conversation, 0, output.path("command").path("arguments").toString());
    assertEquals(Outcome.Ending.ANSWERED, run(10).ending());
    assertEquals(1, effects.get());
  }

  @Test
  void readiness_recovery_cannot_replay_other_commands_or_changed_effective_operations() {
    var command = JSON.createObjectNode().put("tool", InformationTool.READ);
    command.putObject("arguments").put("operation", "await");
    store.prepare(
        conversation,
        new ScriptStore.Step(0, hash(), JSON.createObjectNode(), command, null, null));
    store.started(conversation, 0, "{\"operation\":\"read\"}");
    assertThrows(IllegalStateException.class, () -> store.retryReadinessObserver(conversation, 0));
    assertTrue(store.latest(conversation).orElseThrow().started());
  }

  @Test
  void source_changes_and_ungranted_commands_fail_closed() {
    assertEquals(Outcome.Ending.TURN_CAP, run(1).ending());
    definition = definition.withPrompt(SOURCE + "\n// changed");
    assertTrue(
        assertThrows(IllegalStateException.class, () -> run(10))
            .getMessage()
            .contains("pinned journal"));
    jdbc.update("DELETE FROM orchestration_script_steps WHERE conversation_id=?", conversation);
    definition = definition.withPrompt(SOURCE.replace("tool:'probe'", "tool:'ungranted'"));
    assertTrue(
        assertThrows(IllegalStateException.class, () -> run(10))
            .getMessage()
            .contains("ungranted tool"));
    assertEquals(1, effects.get());
  }

  @Test
  void delegated_answer_survives_a_crash_before_the_parent_receipt() {
    var child =
        new ConversationStore(jdbc)
            .log(
                Origin.DELEGATION,
                Home.global(),
                "research_analyst",
                conversation,
                null,
                "alice",
                "script_7");
    jdbc.update(
        "INSERT INTO turns(conversation_id,ordinal,utterance,answer,ending) VALUES(?,1,'task','{\"result\":\"paid answer\"}','ANSWERED')",
        child.id());
    assertEquals(
        "{\"result\":\"paid answer\"}", store.delegateResult(conversation, 7).orElseThrow());
    assertTrue(store.delegateResult(conversation, 8).isEmpty());
  }

  String hash() {
    return OrchestrationRegistry.parsePinned(
            "fixture", "fixture.js", SOURCE, Set.of("probe"), OrchestrationDefinition.Tier.SHIPPED)
        .hash();
  }
}
