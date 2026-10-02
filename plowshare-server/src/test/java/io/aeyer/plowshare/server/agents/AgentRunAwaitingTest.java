package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.images.ImageStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRunAwaitingTest {

    @Test
    void a_child_waiting_for_approval_ends_its_parent_waiting_on_the_same_question() {
        AgentRegistry registry = mock(AgentRegistry.class);
        JobRuntime runtime = mock(JobRuntime.class);
        AgentDefinition caller = mock(AgentDefinition.class);
        AgentDefinition callee = mock(AgentDefinition.class);
        Transcript parent = mock(Transcript.class);
        Transcript child = mock(Transcript.class);
        Budget budget = Budget.of(10);
        TurnEnd end = new TurnEnd();
        Home home = Home.of("payments");
        Outcome awaiting = new Outcome(Outcome.Ending.AWAITING,
                "Approve running ./gradlew test [apr_1]", 1, 1, "");

        when(caller.name()).thenReturn("interlocutor");
        when(caller.calls()).thenReturn(List.of("coder"));
        when(callee.name()).thenReturn("coder");
        when(callee.description()).thenReturn("writes and tests code");
        when(callee.maxTurns()).thenReturn(10);
        when(registry.find("coder")).thenReturn(Optional.of(callee));
        when(parent.delegate(callee, home, null)).thenReturn(child);
        // A mock's activity() is null; the real runtime's never is (RunActivity.NONE until wired).
        ToldActivity activity = new ToldActivity();
        when(runtime.activity()).thenReturn(activity);
        when(runtime.run(same(callee), eq("build it"), eq(home), same(budget), any(),
                eq("tab-1"), same(JobWatch.UNWATCHED), same(child), any(TurnCap.class),
                eq(List.of()))).thenReturn(awaiting);

        AgentRunTool tool = new AgentRunTool(registry, runtime, caller, budget, () -> false,
                "tab-1", parent, List.of(), ImageStore.NONE, end);
        tool.run("{\"agent\":\"coder\",\"task\":\"build it\"}", home);

        assertEquals(Outcome.Ending.AWAITING, end.requested().orElseThrow().ending());
        assertEquals(awaiting.text(), end.requested().orElseThrow().text());
        verify(child).closed("build it", awaiting);
        // Gone out, and not come back: a waiting delegate's return is told when it is resumed and
        // ends (Orchestrations.delegateEnded), once. The call's own outcome is that it waits.
        assertEquals(List.of("delegated interlocutor coder build it"), activity.told());
        assertEquals(Outcome.Ending.AWAITING, tool.returned());
    }
}
