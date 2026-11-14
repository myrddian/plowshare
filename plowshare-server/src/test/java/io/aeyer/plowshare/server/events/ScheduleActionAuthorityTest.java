package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.SkillDefinition;
import io.aeyer.plowshare.server.agents.SkillResolver;
import io.aeyer.plowshare.server.messaging.MessageService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScheduleActionAuthorityTest {
  @Test
  void explicitHiddenSkillsUseCurrentWorkspaceGrantsAndDeclaredModes() {
    var source = new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "workspace");
    var files = mock(ScheduleFiles.class);
    var callers = mock(Callers.class);
    var skills = mock(SkillResolver.class);
    var agent = mock(AgentDefinition.class);
    var caller = mock(DefinitionResolver.Caller.class);
    when(files.executionSession(source)).thenReturn("live-session");
    when(callers.callerFor("project", "live-session", "owner")).thenReturn(caller);
    when(callers.requireAgent("worker", caller)).thenReturn(agent);
    when(agent.canUseSkill("review")).thenReturn(true);
    var skill =
        new SkillDefinition(
            "review",
            "Review",
            "Instructions",
            "worker",
            SkillDefinition.Mode.NEW,
            List.of(),
            Map.of(),
            "test",
            OrchestrationDefinition.Tier.PROJECT,
            "hash",
            "text",
            true,
            false,
            "Optional arguments; may be omitted");
    when(skills.forCaller(caller))
        .thenReturn(
            new SkillResolver.Catalog(
                Map.of("review", new SkillResolver.Resolved(skill, null)), Map.of()));
    var authority =
        new ScheduleActionAuthority(
            files, callers, skills, null, mock(MessageService.Routing.class), () -> null);
    authority.validate(source, definition("NEW"));
    verify(callers).requireWork("project", "owner");
    assertThrows(RuntimeException.class, () -> authority.validate(source, definition("DIRECT")));
    when(agent.canUseSkill("review")).thenReturn(false);
    assertThrows(RuntimeException.class, () -> authority.validate(source, definition("NEW")));
  }

  private ScheduledWork definition(String mode) {
    return new ScheduledWork(
        1,
        "0 0 9 * * *",
        "UTC",
        false,
        new ScheduledWork.Action("skill", "worker", "review", "exact arguments", mode),
        new ScheduledWork.Target("mailbox", null, null, null, null),
        null);
  }
}
