package io.aeyer.plowshare.server.agents.curator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.BoundSystemOperations;
import io.aeyer.plowshare.server.access.BuiltInIdentities;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationInference;
import io.aeyer.plowshare.server.access.ScopedAuthority;
import io.aeyer.plowshare.server.access.ScopedGrantRepository;
import io.aeyer.plowshare.server.access.StandardRole;
import io.aeyer.plowshare.server.access.SystemOperationRecords;
import io.aeyer.plowshare.server.access.SystemOperations;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.MemoryTools;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.ShippedDefinitions;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.archive.ProposalStore;
import io.aeyer.plowshare.server.archive.TocEntry;
import io.aeyer.plowshare.server.llm.dispatch.InferenceAdmissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which authority scope a curator ruling is admitted against — and, before that, whether a shipped
 * curator pass can be admitted at all.
 *
 * <h2>The headline, and it contradicts the shape the other SYSTEM callers have</h2>
 *
 * <p>{@code MEMORY_CURATION} is <b>not</b> a contract-bounded transformation like a conversation
 * fold or a write-path judge. {@code Curator} hands the admitted work to {@code
 * JobRuntime.forSystemWork} and runs a real turn loop in it, and the agent it runs — {@code
 * promotion_judge} — declares {@code tools: [memory_read]} in the shipped definition, deliberately:
 * its own file records that "one tool … is the whole reason a ruling is a job rather than a second
 * scribe". That is genuine latitude. But {@code BoundSystemOperations.admit} calls {@code
 * SystemModelTasks.requireModelOnly} on the definition before anything else, and that refuses a
 * definition with any tool. The two cannot both hold, and the test below executes which one wins.
 *
 * <h2>What is executed here, and why it is not an inference</h2>
 *
 * <p>The real {@link BoundSystemOperations} runs over mocked repositories, behind a mock of {@link
 * SystemOperations} that delegates to it so an {@link ArgumentCaptor} can see which overload {@code
 * Curator} reached. The shipped {@code promotion_judge} is read from {@link ShippedDefinitions}
 * rather than retyped, so the tool list under test is the one this server ships.
 *
 * <h2>Which homes a pass actually sees in production</h2>
 *
 * <p>Only a project home, and that is a type-level guarantee rather than a happy accident. {@code
 * Curator.pass} takes a {@code String project} and resolves it through {@code Home.of}, which
 * refuses null and blank; its own Javadoc says the {@code String} is deliberate, because "promotion
 * goes project to global and never the other way, and {@code Home} is exactly the type that can
 * spell the tier a pass must not be over". So the global branch of admission is unreachable from
 * this caller, and a control with a global home cannot be written without changing production code.
 * The project home test below is therefore the whole of question one for this caller.
 *
 * <h2>Stubbing</h2>
 *
 * <p>The proposal store is empty and the index holds one candidate, so exactly one ruling is
 * attempted; the global survey returns nothing, which is the ordinary "nothing close" path and does
 * not stop the pass. The SYSTEM grant is deliberately <b>not</b> narrowed by scope. Repositories
 * are mocked, per the database test policy.
 */
class CuratorSystemScopeTest {

  private static final String PROJECT = "payments";
  private static final Instant FORMED_AT = Instant.parse("2026-09-05T09:00:00Z");
  private static final String CANDIDATE = "mem_000001";

  /** The SYSTEM account's own live intrinsic grant; the only authority a ruling carries. */
  private static final UUID GRANT = UUID.randomUUID();

  private static final GrantRecipient SYSTEM_RECIPIENT =
      GrantRecipient.account(BuiltInIdentities.SYSTEM);

  /** The built-in account knowledge scope the shorter overload falls back to for a global home. */
  private static final AuthorityScope GLOBAL_KNOWLEDGE =
      new AuthorityScope.AccountKnowledge(
          BuiltInIdentities.GLOBAL_KNOWLEDGE, BuiltInIdentities.GLOBAL);

  /** The project scope a pass over that project must reach. */
  private static final AuthorityScope PAYMENTS_SCOPE =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 11);

  /** Stubbed only so a negative assertion names something real. */
  private static final AuthorityScope SOMEONE_PERSONAL =
      new AuthorityScope.Personal(
          new ScopeId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), 7);

  /**
   * CONFIRMED DEFECT, pinned as it stands, and a different one from the other SYSTEM callers'.
   *
   * <p>A curator pass on a server wired as production wires it cannot take a single ruling. {@code
   * AgentsConfig.curator} supplies a {@link SystemOperations}, so every candidate reaches {@code
   * system.admit(MEMORY_CURATION, ..)}; {@code BoundSystemOperations.admit} calls {@code
   * SystemModelTasks.requireModelOnly(definition)} first, and the shipped {@code promotion_judge}
   * declares {@code memory_read}. The admission therefore raises {@code IllegalStateException:
   * SYSTEM document workers cannot use tools, delegate, or acquire capabilities}, and nothing in
   * {@code Curator.pass} catches it — the surrounding {@code try} covers the neighbour survey only
   * — so it leaves {@code pass} as a raised exception rather than as an {@link Outcome}. No ruling
   * is made, no proposal is filed and no envelope is retained.
   *
   * <p>So the answer to "which scope is it admitted against" is <b>none</b>: on the shipped
   * definition it is never admitted. That is why this caller's severity is not "wrong ceiling,
   * unusable" — it is a SYSTEM purpose whose work genuinely needs latitude the model-only ceiling
   * is written to forbid.
   *
   * <p>The existing {@code CuratorTest} does not see this: every fixture there uses the
   * five-argument constructor, which leaves {@code system} null and skips admission entirely.
   *
   * <p><b>When this is fixed, invert the assertions.</b> Which way it is fixed is a design decision
   * and not this test's to make: either {@code MEMORY_CURATION} stops being a model-only SYSTEM
   * purpose and is admitted with an explicit scope and its tool in the sandbox, or the ruling stops
   * being a tool-using job. Until then this test states that the shipped pair is contradictory.
   */
  @Test
  void a_shipped_curator_pass_cannot_be_admitted_at_all() {
    Fixture fixture = new Fixture(shippedJudge());

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> fixture.curator.pass(PROJECT, Budget.of(4)),
            "the shipped judge declares a tool, which model-only admission refuses");
    assertEquals(
        "SYSTEM document workers cannot use tools, delegate, or acquire capabilities",
        refused.getMessage());

    // The latitude that collides with the ceiling, read off the shipped file rather than retyped.
    assertEquals(
        List.of(MemoryTools.READ_NAME),
        shippedJudge().tools(),
        "the shipped promotion_judge really does carry a tool");
    assertTrue(shippedJudge().maxTurns() > 1, "and a turn loop to call it in");

    // Nothing was admitted, so there is no scope to read and nothing was filed.
    verify(fixture.evidence, never()).retain(any());
    verify(fixture.proposals, never()).propose(anyString(), anyString(), anyString(), anyString());
  }

  /**
   * What the scope would be if the ruling were tool-less — question one, answered through the only
   * home this caller can reach.
   *
   * <p>The judge here is {@code promotion_judge} with its tool list emptied and nothing else
   * changed. That is not the shipped agent, and the test above is what says so; this one exists
   * because "which scope" still has an answer worth recording for the day the contradiction above
   * is resolved in favour of a tool-less ruling.
   *
   * <p>The answer is the correct one, and for the ordinary reason rather than by design: {@code
   * Curator.pass} resolves its {@code String project} through {@code Home.of}, so the home is
   * always a project, and the five-argument {@code admit} resolves a project home to {@code
   * identities.project(home.project())} — the project's own scope. {@code GLOBAL_KNOWLEDGE} is
   * unreachable here. So the scope-less overload does not over-grant on this path; it is a
   * conformance gap only, in that an admitted unit of work is supposed to name its scope rather
   * than have it inferred.
   */
  @Test
  void a_toolless_ruling_would_be_admitted_against_that_project_scope() {
    Fixture fixture = new Fixture(toollessJudge());

    Outcome outcome = fixture.curator.pass(PROJECT, Budget.of(4));
    assertNotNull(outcome, "the pass must have reached a ruling to have admitted anything");

    // The omission itself: the overload that cannot be told its scope is the one called.
    Home home = fixture.capturedHome();
    assertEquals(Home.of(PROJECT), home);
    assertFalse(home.isGlobal(), "Home.of refuses null and blank, so a pass is never global");

    // Why there is no global control for this caller, executed rather than claimed:
    // pass() resolves its project through Home.of, and that is the refusal.
    assertThrows(IllegalArgumentException.class, () -> Home.of(null));
    assertThrows(IllegalArgumentException.class, () -> Home.of(" "));
    assertThrows(IllegalArgumentException.class, () -> fixture.curator.pass(null, Budget.of(4)));
    verify(fixture.system, never())
        .admit(any(), any(), any(ScopeId.class), any(), anyString(), any(Runnable.class));

    assertEquals(
        PAYMENTS_SCOPE.id(),
        fixture.capturedScope(),
        "a curator ruling is admitted against the curated project's own scope");
    assertNotEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE, fixture.capturedScope());
    assertNotEquals(SOMEONE_PERSONAL.id(), fixture.capturedScope());
  }

  // --- the two definitions ---------------------------------------------------

  /** The judge exactly as this server ships it, read rather than retyped. */
  private static AgentDefinition shippedJudge() {
    return ShippedDefinitions.registry(
            io.aeyer.plowshare.server.agents.BoundTools.boundByThisServer())
        .get(Curator.AGENT);
  }

  /** The same definition with its tool list emptied, and nothing else changed. */
  private static AgentDefinition toollessJudge() {
    AgentDefinition shipped = shippedJudge();
    return new AgentDefinition(
        shipped.name(),
        shipped.description(),
        shipped.model(),
        List.of(),
        List.of(),
        List.of(),
        shipped.maxTurns(),
        shipped.maxModelCalls(),
        shipped.prompt());
  }

  // --- the fixture ----------------------------------------------------------

  private static final class Fixture {
    final SystemOperationRecords evidence = mock(SystemOperationRecords.class);
    final Archive archive = mock(Archive.class);
    final ProposalStore proposals = mock(ProposalStore.class);
    final JobRuntime runtime = mock(JobRuntime.class);
    final JobRuntime lane = mock(JobRuntime.class);
    final Compaction logs = mock(Compaction.class);
    final SystemOperations system;
    final Curator curator;

    Fixture(AgentDefinition judge) {
      IdentityRepository identities = mock(IdentityRepository.class);
      ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
      ScopedAuthority authority = mock(ScopedAuthority.class);

      when(identities.scope(BuiltInIdentities.GLOBAL_KNOWLEDGE)).thenReturn(GLOBAL_KNOWLEDGE);
      when(identities.scope(PAYMENTS_SCOPE.id())).thenReturn(PAYMENTS_SCOPE);
      when(identities.scope(SOMEONE_PERSONAL.id())).thenReturn(SOMEONE_PERSONAL);
      when(identities.project(PROJECT)).thenReturn(PAYMENTS_SCOPE);

      ScopedGrantRepository.Binding root = mock(ScopedGrantRepository.Binding.class);
      when(root.id()).thenReturn(GRANT);
      when(root.role()).thenReturn(StandardRole.SYSTEM);
      when(grants.live(SYSTEM_RECIPIENT)).thenReturn(List.of(root));
      when(authority.allowsUsing(
              eq(SYSTEM_RECIPIENT), any(), any(AuthorityAction.class), eq(GRANT)))
          .thenReturn(true);

      BoundSystemOperations bound =
          new BoundSystemOperations(
              identities,
              grants,
              authority,
              evidence,
              new OperationInference(
                  mock(OperationAuthority.class), mock(InferenceAdmissions.class)));
      system = mock(SystemOperations.class, withSettings().defaultAnswer(delegatesTo(bound)));

      // One candidate nobody has ruled on, and a global tier holding nothing close
      // to it: the ordinary path to one ruling.
      when(proposals.ruledOn(any())).thenReturn(Set.of());
      when(proposals.pending(any())).thenReturn(List.of());
      when(archive.index(Home.of(PROJECT))).thenReturn(List.of(candidate()));
      when(archive.survey(anyString(), eq(Home.global()), anyInt()))
          .thenReturn(new Archive.Survey(List.of(), 0));
      when(archive.get(CANDIDATE)).thenReturn(held());
      when(logs.logFor(any(), any(), any(), isNull(), isNull())).thenReturn(Transcript.NONE);

      // The SYSTEM lane the admitted work is handed to. One turn cap ends the ruling
      // undecided, which leaves the pass with nothing to file.
      when(runtime.forSystemWork(any())).thenReturn(lane);
      when(lane.run(any(), anyString(), any(), any(), any(), isNull(), any(), any()))
          .thenReturn(new Outcome(Ending.TURN_CAP, "stopped at its cap", 0, 1, ""));

      curator =
          new Curator(
              archive,
              proposals,
              new PromotionQueue(archive, proposals),
              runtime,
              () -> new AgentRegistry(Map.of(judge.name(), judge)),
              logs,
              system);
    }

    /** The home the five-argument {@code admit} was actually handed. */
    Home capturedHome() {
      ArgumentCaptor<Home> home = ArgumentCaptor.forClass(Home.class);
      verify(system)
          .admit(
              eq(SystemOperations.Purpose.MEMORY_CURATION),
              home.capture(),
              any(AgentDefinition.class),
              anyString(),
              any(Runnable.class));
      return home.getValue();
    }

    /** The scope identifier the real admission retained for that SYSTEM operation. */
    ScopeId capturedScope() {
      ArgumentCaptor<SystemOperationRecords.Envelope> envelope =
          ArgumentCaptor.forClass(SystemOperationRecords.Envelope.class);
      verify(evidence).retain(envelope.capture());
      assertEquals(SystemOperations.Purpose.MEMORY_CURATION, envelope.getValue().purpose());
      return envelope.getValue().scope();
    }

    private static TocEntry candidate() {
      return new TocEntry(CANDIDATE, "The retry budget is 4 attempts", "Calling the API", false);
    }

    /** The same claim as the index entry, because admission re-reads it before judging. */
    private static Memory held() {
      return new Memory(
          CANDIDATE,
          "The retry budget is 4 attempts",
          "Calling the API",
          new Provenance(FORMED_AT, "claude", "an earlier session"),
          MemoryState.ACTIVE,
          false,
          0,
          null,
          "The body of " + CANDIDATE + ".",
          null,
          null,
          null,
          Home.of(PROJECT));
    }
  }
}
