package io.aeyer.plowshare.server.swarm;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.Scheduling;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SwarmSchedulingOwnershipTest {
  @Test
  void selectionResolvesTheShareAndFallbackPolicyOncePerRun() throws Exception {
    var context = new RunExtras.Context(null, "cnv_Message", null, null);
    var messaging = mock(RunShareProvider.class);
    var board = mock(RunShareProvider.class);
    var scheduler = mock(SwarmScheduler.class);
    var pools = mock(SwarmScheduler.Pools.class);
    var share = new SwarmScheduler.Share("alice", "ins_Recipient", "ins_Recipient");
    when(messaging.select(context))
        .thenReturn(Optional.of(new RunShareProvider.Selection(share, true)));
    when(pools.serving("ordinary")).thenReturn(List.of());
    var scheduling = new SwarmScheduling(scheduler, List.of(messaging, board), pools);
    assertSame(Scheduling.Slot.ANY, scheduling.forRun(context).await("ordinary", () -> false));
    verify(messaging, times(1)).select(context);
    verifyNoInteractions(board);
    verify(scheduler).enter(share);
  }

  @Test
  void unclaimedContextsStayOrdinaryAndDoNotEnterTheSharedScheduler() {
    var context = new RunExtras.Context(null, "cnv_Person", null, null);
    var provider = mock(RunShareProvider.class);
    var scheduler = mock(SwarmScheduler.class);
    when(provider.select(context)).thenReturn(Optional.empty());
    var scheduling = new SwarmScheduling(scheduler, List.of(provider), null);
    assertSame(Scheduling.Turn.ALWAYS, scheduling.forRun(context));
    verifyNoInteractions(scheduler);
  }
}
