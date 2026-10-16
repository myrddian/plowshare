package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.api.AgentController;
import io.aeyer.plowshare.server.api.AgentRows;
import io.aeyer.plowshare.server.api.AgentView;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.ws.AgentListHandler;
import io.aeyer.plowshare.server.ws.Asking;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exposure gates use the request actor without changing the server's shared registry. */
class AgentExposureTest {
  final String application = "@service/" + UUID.randomUUID();
  final DefinitionResolver resolver = mock(DefinitionResolver.class);
  final ProjectStore projects = mock(ProjectStore.class);
  final Callers callers =
      new Callers(resolver, projects, mock(Turn.class), mock(CallerAccess.class));
  final AgentDefinition diagnostic = systemDefinition("conversation_diagnostician", null);
  final AgentDefinition chat = definition("chat");
  final AgentRegistry registry =
      new AgentRegistry(Map.of("conversation_diagnostician", diagnostic, "chat", chat));

  static AgentDefinition definition(String name) {
    return new AgentDefinition(
        name, name, "fast", List.of(), List.of(), List.of(), 4, 8, "A fixture prompt", true, false);
  }

  DefinitionResolver.Caller caller(String account) {
    return new DefinitionResolver.Caller(null, null, account);
  }

  @BeforeEach
  void setup() {
    when(resolver.forCaller(any())).thenReturn(registry);
    when(resolver.refreshForCaller(any())).thenReturn(registry);
    when(resolver.defaultBot(any()))
        .thenReturn(
            Optional.of(
                new DefinitionResolver.DefaultBot("conversation_diagnostician", "fixture")));
  }

  @Test
  void direct_human_and_internal_resolution_keep_diagnostic_capabilities() {
    assertSame(diagnostic, callers.requireAgent("conversation_diagnostician", caller("alice")));
    assertSame(diagnostic, callers.readAgent("conversation_diagnostician", caller("alice")));
    assertSame(
        diagnostic, callers.requirePinnedAgent("conversation_diagnostician", caller("alice")));
    assertSame(diagnostic, AgentExposure.requireAvailable(diagnostic, null));
    assertSame(diagnostic, registry.get("conversation_diagnostician"));
    assertTrue(diagnostic.exported());
  }

  @Test
  void application_cannot_start_continue_read_or_delegate_to_the_diagnostic_agent() {
    for (Runnable request :
        List.<Runnable>of(
            () -> callers.requireAgent("conversation_diagnostician", caller(application)),
            () -> callers.requirePinnedAgent("conversation_diagnostician", caller(application)),
            () -> callers.readAgent("conversation_diagnostician", caller(application)),
            () -> callers.find(Home.global(), null, application, "conversation_diagnostician"))) {
      var refused = assertThrows(CallerFault.class, request::run);
      assertTrue(refused.getMessage().contains("SYSTEM agents"));
    }
    assertSame(chat, callers.requireAgent("chat", caller(application)));
    assertSame(
        diagnostic,
        callers.requireAgent("conversation_diagnostician", caller("alice")),
        "a refused integration must not mutate the shared registry");
  }

  @Test
  void acting_cannot_reach_conversation_diagnostician_even_if_its_other_capabilities_are_removed() {
    assertTrue(diagnostic.tools().isEmpty());
    assertThrows(CallerFault.class, () -> RetainedAgent.requireRoot(diagnostic));
    assertThrows(CallerFault.class, () -> RetainedAgent.require(diagnostic));
    RetainedAgent.requireRoot(chat);
  }

  @Test
  void missing_agent_refusals_do_not_offer_conversation_diagnostician_to_applications() {
    var refused =
        assertThrows(CallerFault.class, () -> callers.requireAgent("unknown", caller(application)));
    assertTrue(refused.getMessage().contains("chat"));
    assertFalse(refused.getMessage().contains("conversation_diagnostician"));
  }

  @Test
  void
      application_discovery_hides_conversation_diagnostician_including_its_default_marker_on_both_transports() {
    var controller =
        new AgentController(
            mock(JobStore.class),
            resolver,
            projects,
            callers,
            mock(Runs.class),
            mock(Pictures.class),
            null,
            null,
            null);
    assertEquals(
        List.of("chat"),
        controller.agents(null, application).getBody().stream().map(AgentView::name).toList());
    assertEquals(
        Set.of("chat", "conversation_diagnostician"),
        controller.agents(null, "alice").getBody().stream()
            .map(AgentView::name)
            .collect(java.util.stream.Collectors.toSet()));
    var handler = new AgentListHandler(resolver, projects, callers);
    var result = handler.handle(Map.of(), new Asking(null, application));
    assertEquals(
        AgentRows.of(registry, callers, resolver.defaultBot(caller(application)), application),
        result.payload());
    assertSame(registry, resolver.forCaller(caller("alice")));
  }

  @Test
  void resuming_a_humans_diagnostic_checks_the_application_actor_on_both_transports() {
    var rules = mock(io.aeyer.plowshare.server.archive.Conversations.class);
    var definitions = mock(ConversationDefinitions.class);
    var speaking = mock(Turn.class);
    var access = mock(CallerAccess.class);
    when(rules.whoToContinueAs("cnv_FIXTURE", null)).thenReturn("conversation_diagnostician");
    when(definitions.callerForConversation("cnv_FIXTURE", null)).thenReturn(caller("alice"));
    when(definitions.requireAgent("conversation_diagnostician", caller("alice")))
        .thenReturn(diagnostic);
    when(speaking.homeOf("cnv_FIXTURE")).thenReturn(Home.of("fixture"));
    var handler =
        new io.aeyer.plowshare.server.ws.ConversationResumeHandler(
            rules, definitions, speaking, access);
    assertThrows(
        CallerFault.class,
        () -> handler.handle(Map.of("conversation", "cnv_FIXTURE"), new Asking(null, application)));
    var controller =
        new io.aeyer.plowshare.server.api.ConversationController(
            null,
            null,
            null,
            null,
            null,
            speaking,
            definitions,
            null,
            null,
            null,
            rules,
            null,
            access);
    var request = new io.aeyer.plowshare.server.api.ResumeRunRequest(null, null, null, null, null);
    assertThrows(CallerFault.class, () -> controller.resume("cnv_FIXTURE", request, application));
    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }

  @Test
  void disabled_diagnostic_is_not_reintroduced_as_an_application_roster_entry() {
    var disabled =
        new AgentRegistry(
            new AgentRegistry.Loaded(
                    Map.of("chat", chat, "conversation_diagnostician", diagnostic),
                    Map.of(),
                    Map.of())
                .without("conversation_diagnostician", "fixture unavailable"));
    assertEquals(
        List.of("chat"),
        AgentRows.of(disabled, callers, Optional.empty(), application).stream()
            .map(AgentView::name)
            .toList());
  }

  @Test
  void user_authored_daedalus_name_and_alias_are_ordinary(@TempDir Path path) throws Exception {
    Files.writeString(
        path.resolve("diagnostic_variant.md"),
        """
        ---
        name: diagnostic_variant
        alias: daedalus
        description: Diagnostic fixture
        model: fast
        exported: true
        max-turns: 4
        max-model-calls: 8
        ---
        A diagnostic fixture.
        """);
    var aliases = AgentRegistry.of(path, Set.of());
    when(resolver.forCaller(any())).thenReturn(aliases);
    for (String name : List.of("daedalus", "diagnostic_variant")) {
      assertEquals("diagnostic_variant", callers.requireAgent(name, caller(application)).name());
      RetainedAgent.requireRoot(aliases.get(name));
      assertFalse(aliases.get(name).isSystem());
    }
    assertEquals(2, AgentRows.of(aliases, callers, Optional.empty(), application).size());
    var namesake = definition("daedalus");
    assertSame(namesake, AgentExposure.requireAvailable(namesake, application));
    RetainedAgent.requireRoot(namesake);
  }

  @Test
  void trusted_flag_protects_any_name_and_alias() {
    var protectedVariant = systemDefinition("worker_variant", "worker");
    var aliases = new AgentRegistry(Map.of(protectedVariant.name(), protectedVariant));
    when(resolver.forCaller(any())).thenReturn(aliases);
    for (String name : List.of("worker", "worker_variant")) {
      assertThrows(CallerFault.class, () -> callers.requireAgent(name, caller(application)));
      assertThrows(
          CallerFault.class, () -> callers.find(Home.of("fixture"), null, application, name));
      assertThrows(CallerFault.class, () -> RetainedAgent.requireRoot(aliases.get(name)));
    }
    assertTrue(AgentRows.of(aliases, callers, Optional.empty(), application).isEmpty());
    assertSame(protectedVariant, callers.requireAgent("worker", caller("alice")));
  }

  static AgentDefinition systemDefinition(String name, String alias) {
    var entry =
        new DefinitionSource.Definition(
            name,
            "fixture bundle",
            "---\nname: "
                + name
                + "\n"
                + (alias == null ? "" : "alias: " + alias + "\n")
                + "is-system: true\ndescription: Fixture\nmodel: fast\nexported: true\n"
                + "max-turns: 4\nmax-model-calls: 8\n---\nFixture prompt.",
            DefinitionSource.Provenance.BUNDLED);
    return AgentRegistry.read(
            new DefinitionSource() {
              public String describe() {
                return "fixture bundle";
              }

              public List<Definition> list() {
                return List.of(entry);
              }
            },
            Set.of(),
            Set.of(name))
        .enabled()
        .get(name);
  }
}
