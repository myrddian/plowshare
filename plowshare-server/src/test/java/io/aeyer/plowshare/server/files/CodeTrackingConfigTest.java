package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class CodeTrackingConfigTest {
  @Test
  void production_configuration_binds_monitor_after_runtime_and_parses_duration_settings() {
    var runtime = mock(JobRuntime.class);
    new ApplicationContextRunner()
        .withUserConfiguration(CodeTrackingConfig.class)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(UnitOfWork.class, () -> UnitOfWork.NONE)
        .withBean(JobRuntime.class, () -> runtime)
        .withPropertyValues(
            "plowshare.code-tracking.interval=10s",
            "plowshare.code-tracking.capacity=2",
            "plowshare.code-tracking.enabled=false")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var monitor = context.getBean(CodeWorkspaceMonitor.class);
              verify(runtime).useCodeMonitor(monitor);
              assertEquals(
                  Duration.ofSeconds(10), context.getBean(CodeWorkspaceStore.class).interval());
              assertEquals(
                  "disabled",
                  monitor.observations("coder", "s", "alice").status(Home.global()).get("state"));
              assertFalse(monitor.pollOnce());
            });
  }

  @Test
  void every_registration_resolves_current_membership_tools_grants_and_global_session_owner() {
    var callers = mock(Callers.class);
    var files = mock(RunProviders.class);
    var store = mock(CodeWorkspaceStore.class);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("callers", callers);
    beans.registerSingleton("files", files);
    Home home = Home.of("source");
    var caller = new DefinitionResolver.Caller(1L, "s");
    when(callers.callerFor("source", "s")).thenReturn(caller);
    when(callers.callerFor(null, "s")).thenReturn(new DefinitionResolver.Caller(null, "s"));
    when(callers.readAgent(eq("coder"), any()))
        .thenReturn(
            definition(List.of(CodeMapTool.NAME), List.of(new Grant(Scope.WORKSPACE, Mode.READ))));
    when(store.begin(any(), eq("**")))
        .thenAnswer(
            call ->
                new CodeMapObservations.Ticket(
                    ((CodeWorkspaceStore.Scope) call.getArgument(0)).id(), 1));
    try (var monitor =
        new CodeTrackingConfig()
            .codeWorkspaceMonitor(
                store,
                beans.getBeanProvider(Callers.class),
                beans.getBeanProvider(RunProviders.class),
                true)) {
      var observe = monitor.observations("coder", "s", "alice");
      assertNotNull(observe.begin(home, "**"));
      verify(callers).requireProject("source", "alice");
      clearInvocations(store);
      when(callers.readAgent(eq("coder"), any()))
          .thenReturn(definition(List.of(CodeMapTool.NAME), List.of()));
      assertNull(observe.begin(home, "**"));
      verifyNoInteractions(store);
      when(callers.readAgent(eq("coder"), any()))
          .thenReturn(definition(List.of(), List.of(new Grant(Scope.WORKSPACE, Mode.READ))));
      assertNull(observe.begin(home, "**"));
      verifyNoInteractions(store);
      when(callers.readAgent(eq("coder"), any()))
          .thenReturn(
              definition(
                  List.of(CodeMapTool.NAME), List.of(new Grant(Scope.WORKSPACE, Mode.READ))));
      doThrow(new WorkspaceRefusedException("membership revoked"))
          .when(callers)
          .requireProject("source", "alice");
      assertNull(observe.begin(home, "**"));
      verifyNoInteractions(store);
      assertNotNull(observe.begin(Home.global(), "**"));
      verify(callers).requireSession("s", "alice");
      clearInvocations(store);
      doThrow(new WorkspaceRefusedException("wrong session owner"))
          .when(callers)
          .requireSession("s", "alice");
      assertNull(observe.begin(Home.global(), "**"));
      verifyNoInteractions(store);
      assertNull(monitor.observations("coder", null, "alice").begin(Home.global(), "**"));
    }
  }

  private static AgentDefinition definition(List<String> tools, List<Grant> grants) {
    return new AgentDefinition(
        "coder", "changes code", "fast", tools, List.of(), grants, 3, 3, "Inspect source");
  }
}
