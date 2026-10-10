package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.api.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transport-to-resolver regressions: real Application files, mocked persistence and execution. */
class ApplicationConversationScopeTest {
  @TempDir Path root;
  private Callers definitions;
  private DefinitionResolver resolver;
  private ProjectStore projects;
  private Turn speaking;
  private TurnStore turns;
  private CallerAccess access;
  private Conversations rules;
  private Compaction compaction;
  private JobRuntime runtime;
  private ConversationController rest;
  private ConversationResumeHandler resume;
  private ConversationProjectionHandler projection;
  private ConversationContextHandler context;
  private Path file;

  private void write(boolean exported, String prompt) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "---\nname: helper\ndescription: fixture\nmodel: m\nmax-turns: 2\nmax-model-calls: 4\nexported: "
            + exported
            + "\n---\n"
            + prompt
            + "\n");
  }

  @BeforeEach
  void configure() throws Exception {
    file = root.resolve("application/bots/helper.md");
    write(true, "application prompt");
    var global =
        new AgentDefinition(
            "helper",
            "global",
            "m",
            List.of(),
            List.of(),
            List.of(),
            2,
            4,
            "wrong global prompt",
            true,
            true);
    resolver =
        new DefinitionResolver(
            new AgentRegistry(Map.of("helper", global)),
            new DataLayout(null),
            id -> true,
            Set.of(),
            Set.of(),
            mock(SessionChannel.class),
            session -> false,
            DefinitionChecks.NONE);
    resolver.useApplicationResources(
        id -> id != null && id == 9L ? Optional.of(root.resolve("application")) : Optional.empty());
    projects = mock(ProjectStore.class);
    when(projects.id("application")).thenReturn(9L);
    speaking = mock(Turn.class);
    when(speaking.homeOf("conversation")).thenReturn(Home.of("application"));
    access = mock(CallerAccess.class);
    definitions = new Callers(resolver, projects, speaking, access);
    var conversations = mock(ConversationStore.class);
    when(conversations.ownerOf("conversation")).thenReturn(Optional.of("service"));
    var row = mock(ConversationRecord.class);
    when(row.home()).thenReturn(Home.of("application"));
    when(conversations.find("conversation")).thenReturn(Optional.of(row));
    definitions.useOwners(conversations, mock(SessionRegistry.class));
    turns = mock(TurnStore.class);
    rules = mock(Conversations.class);
    when(rules.whoToProjectAs(eq("conversation"), any(), any())).thenReturn("helper");
    when(rules.whoToContinueAs(eq("conversation"), any())).thenReturn("helper");
    runtime = mock(JobRuntime.class);
    when(runtime.withAgentRules(any(), any(), any(), any()))
        .thenAnswer(call -> call.getArgument(0));
    when(runtime.schemasOfferedTo(any(), any(), any(), any())).thenReturn(List.of());
    compaction = mock(Compaction.class);
    when(compaction.contextLengthOf(any())).thenReturn(OptionalInt.of(10000));
    when(compaction.projectionFor(eq("conversation"), any()))
        .thenAnswer(
            call -> List.of(ChatMessage.system(((AgentDefinition) call.getArgument(1)).prompt())));
    var tokenizer = new RatioTokenizer(RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN);
    rest =
        new ConversationController(
            conversations,
            mock(CompactionStore.class),
            turns,
            mock(EntryStore.class),
            runtime,
            speaking,
            definitions,
            new ConversationsProperties(),
            compaction,
            tokenizer,
            rules,
            LogStages.NONE,
            access);
    resume = new ConversationResumeHandler(rules, definitions, speaking, access);
    projection = new ConversationProjectionHandler(rules, turns, definitions, compaction, runtime);
    context =
        new ConversationContextHandler(rules, turns, runtime, definitions, tokenizer, compaction);
  }

  @Test
  void resume_uses_current_application_definition_and_payload_session_on_both_surfaces() {
    when(speaking.resume(eq("conversation"), any(), any(), any(), any())).thenReturn("job");
    resume.handle(
        Map.of("conversation", "conversation", "session", "target-session"),
        new Asking("socket-session", "manager"));
    rest.resume(
        "conversation", new ResumeRunRequest(null, "target-session", null, null, null), "manager");
    verify(speaking, times(2))
        .resume(
            eq("conversation"),
            argThat(d -> d.prompt().contains("application prompt")),
            eq("target-session"),
            any(),
            any());
    assertEquals("service", definitions.callerForConversation("conversation", null).handle());
    verify(access, times(2)).requireWork("application", "manager");
  }

  @Test
  void inspections_read_unexported_application_agents_without_starting_work() throws Exception {
    write(false, "private application prompt");
    var ws =
        (ProjectionView)
            projection
                .handle(Map.of("conversation", "conversation"), new Asking("session", "manager"))
                .payload();
    var http = rest.projection("conversation", null, null).getBody();
    assertEquals(http, ws);
    assertTrue(ws.messages().getFirst().content().contains("private application prompt"));
    var wsCost =
        (ContextView)
            context
                .handle(
                    Map.of("conversation", "conversation", "agent", "helper"),
                    new Asking("session", "manager"))
                .payload();
    var httpCost = rest.context("conversation", "helper").getBody();
    assertEquals(httpCost.prefix(), wsCost.prefix());
    assertEquals(
        definitions
            .readAgent("helper", definitions.callerForConversation("conversation", null))
            .prompt()
            .length(),
        wsCost.prefix().systemPromptCharacters());
    verify(speaking, never()).resume(any(), any(), any(), any(), any());
    assertThrows(
        CallerFault.class,
        () ->
            resume.handle(
                Map.of("conversation", "conversation"), new Asking("session", "manager")));
  }

  @Test
  void revoked_work_authority_or_invalid_replacement_prevents_resume() throws Exception {
    // Populate the resolver cache before replacing grants/source.
    definitions.readAgent("helper", definitions.callerForConversation("conversation", null));
    doThrow(new CallerFault("membership revoked"))
        .when(access)
        .requireWork("application", "manager");
    assertThrows(
        CallerFault.class,
        () ->
            resume.handle(
                Map.of("conversation", "conversation"), new Asking("session", "manager")));
    assertThrows(
        CallerFault.class,
        () ->
            rest.resume(
                "conversation", new ResumeRunRequest(null, null, null, null, null), "manager"));
    doNothing().when(access).requireWork("application", "manager");
    Files.writeString(file, "invalid definition");
    resolver.invalidate(9L);
    assertThrows(
        CallerFault.class,
        () ->
            resume.handle(
                Map.of("conversation", "conversation"), new Asking("session", "manager")));
    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }

  @Test
  void unavailable_conversation_project_never_falls_back_to_global() {
    when(projects.id("application")).thenReturn(null);
    assertThrows(
        CallerFault.class,
        () ->
            projection.handle(
                Map.of("conversation", "conversation"), new Asking("session", "manager")));
    assertThrows(CallerFault.class, () -> rest.context("conversation", "helper"));
  }

  @Test
  void historical_projection_preserves_the_recorded_system_block() {
    when(compaction.projectionAsOf(eq("conversation"), any(), eq(1)))
        .thenReturn(
            new Compaction.Shown(List.of(ChatMessage.system("recorded earlier prompt")), true));
    var ws =
        (ProjectionView)
            projection
                .handle(
                    Map.of("conversation", "conversation", "turn", 1),
                    new Asking("session", "manager"))
                .payload();
    var http = rest.projection("conversation", null, 1).getBody();
    assertEquals(http, ws);
    assertTrue(ws.systemBlockAsSent());
    assertEquals("recorded earlier prompt", ws.messages().getFirst().content());
    verify(runtime, never()).withAgentRules(any(), any(), any(), any());
    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }
}
