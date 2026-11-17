package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.api.StartedJob;
import io.aeyer.plowshare.server.archive.ConversationAliasProperties;
import io.aeyer.plowshare.server.archive.ConversationAliasRepository;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnHistoryRepository;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.testing.LogicalSqlWorkBudget;
import io.aeyer.plowshare.server.ws.AgentRunExHandler;
import io.aeyer.plowshare.server.ws.Asking;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ResolvedConversationRunsTest {
  @Test
  void resolvedSubmissionUsesOrdinaryTurnClaimAndCancellationSettlement() {
    var callers = mock(Callers.class);
    var jobs = mock(JobStore.class);
    var conversations = mock(ConversationStore.class);
    var compaction = mock(Compaction.class);
    var definition = mock(AgentDefinition.class);
    when(definition.name()).thenReturn("worker");
    when(definition.maxTurns()).thenReturn(8);
    when(callers.requireAgent(eq("worker"), any())).thenReturn(definition);
    var row =
        new ConversationRecord(
            "cnv_existing",
            Home.of("house"),
            Origin.TURN,
            ConversationLifecycle.ACTIVE,
            null,
            null,
            Instant.EPOCH,
            null,
            Budget.of(20),
            null,
            null);
    when(conversations.find(row.id())).thenReturn(Optional.of(row));
    var transcript = mock(Compaction.TurnTranscript.class);
    when(compaction.transcriptFor(eq(row.id()), eq(definition), any(Speaker.class)))
        .thenReturn(transcript);
    var turn = new Turn(jobs, conversations, mock(TurnHistoryRepository.class), compaction);
    var ending = new AtomicReference<Consumer<Outcome>>();
    when(jobs.submit(
            eq(definition),
            eq("work"),
            eq(row.home()),
            isNull(),
            same(row.budget()),
            same(transcript),
            eq(Origin.TURN),
            any(),
            any(),
            eq(List.of()),
            eq("alice"),
            eq(true)))
        .thenAnswer(
            call -> {
              ending.set(call.getArgument(7));
              return "job_normal";
            });
    var runs = new Runs(callers, jobs, turn);
    var resolved = new ConversationRuns.Resolved(row, 42, "alice");
    var ask =
        new Runs.Ask(
            "worker",
            "work",
            null,
            null,
            row.id(),
            null,
            null,
            List.of(),
            Speaker.person("alice"),
            "alice",
            null,
            null,
            null);
    var aliases = mock(ConversationAliasRepository.class);
    when(aliases.find(any())).thenReturn(Optional.of(row));
    var identities = mock(IdentityRepository.class);
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                new AccountId(UUID.randomUUID()),
                "alice",
                IdentityRepository.AccountKind.USER,
                false));
    when(identities.project("house"))
        .thenReturn(new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 42));
    var service =
        new ExternalConversationService(
            aliases,
            identities,
            mock(WorkAccess.class),
            runs,
            mock(Runs.Shown.class),
            mock(LogStages.class),
            new ConversationsProperties(),
            new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
            new LogicalSqlWorkBudget());
    // The first admission crosses the actual Ex frame, real facade, Runs and Turn owners.
    var reply =
        new AgentRunExHandler(service)
            .handle(
                Map.of(
                    "project", "house",
                    "source", "ha.installation",
                    "externalConversation", "opaque-session",
                    "agent", "worker",
                    "task", "work"),
                new Asking("socket", "alice"));
    assertEquals("job_normal", ((StartedJob) reply.payload()).id());
    assertTrue(turn.isSpeaking(row.id()));
    assertThrows(
        Turn.Refused.class, () -> runs.startResolved(ask, mock(Runs.Shown.class), resolved));
    verify(callers, never()).homeOfConversation(anyString());
    verify(callers, never()).callerForConversation(anyString(), any());
    verify(callers, never()).requireProject(anyString(), anyString());
    verify(callers, times(1)).requireWork("house", "alice");
    // Cancellation settles through the ordinary retained completion callback, freeing exclusion.
    assertNotNull(ending.get());
    ending.get().accept(new Outcome(Outcome.Ending.CANCELLED, "Cancelled", 0, 0, ""));
    assertFalse(turn.isSpeaking(row.id()));
    verify(jobs, times(1))
        .submit(
            eq(definition),
            eq("work"),
            eq(row.home()),
            isNull(),
            same(row.budget()),
            same(transcript),
            eq(Origin.TURN),
            any(),
            any(),
            eq(List.of()),
            eq("alice"),
            eq(true));
  }

  @Test
  void resolvedMetadataNeverPermitsConflictingRouting() {
    var runs = new Runs(mock(Callers.class), mock(JobStore.class), mock(Turn.class));
    var row =
        new ConversationRecord(
            "cnv_existing",
            Home.of("house"),
            Origin.TURN,
            ConversationLifecycle.ACTIVE,
            null,
            null,
            Instant.EPOCH,
            null,
            Budget.of(20),
            null,
            null);
    var asked =
        new Runs.Ask(
            "worker",
            "work",
            "another",
            null,
            row.id(),
            null,
            null,
            List.of(),
            Speaker.person("alice"),
            "alice",
            null,
            null,
            null);
    assertThrows(
        CallerFault.class,
        () ->
            runs.startResolved(
                asked, mock(Runs.Shown.class), new ConversationRuns.Resolved(row, 42, "alice")));
  }
}
