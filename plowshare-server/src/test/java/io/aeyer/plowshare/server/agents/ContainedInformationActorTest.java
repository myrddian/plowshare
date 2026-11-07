package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.access.ScopedPermissions;
import io.aeyer.plowshare.server.applications.ContainedPrincipals;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationActor;
import io.aeyer.plowshare.server.information.InformationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which identity a run presents to the information system, decided from its own log.
 *
 * <h2>Why this needs no database</h2>
 *
 * <p>Everything asserted here is a Java decision over two lookups. {@code
 * JobRuntime.informationActorFor} reads a {@link ConversationRecord} and a contained principal and
 * returns an {@link InformationActor}; {@code InformationAccess.forRun} turns that actor into an
 * {@link InformationContext}. Both lookups are interfaces, so an in-memory fake states the facts
 * more clearly than a schema would, and no assertion here is PostgreSQL's. The gates those two
 * values are then handed to <em>are</em> database facts, and they are proved in {@code
 * ContainedInformationReachDatabaseTest}.
 *
 * <h2>Characterization, measured before the wiring</h2>
 *
 * <p>Measured, not predicted. The production change was inverted to its pre-task behaviour -- the
 * binding site returning {@code InformationActor.of(callerHandle)} unconditionally, and {@code
 * InformationAccess.forRun} raising {@code IllegalStateException} for a contained actor -- and this
 * class was run as written: <strong>6 tests, 4 failed, 2 passed</strong>. The failures were three
 * distinct shapes rather than one.
 *
 * <ul>
 *   <li>{@code aContainedRunActsAsItsPrincipalAndIsAttributedToItsContainer} FAILED on {@code
 *       assertEquals(PRINCIPAL, actor.acting())}: the actor's acting axis held {@code "alice"}, the
 *       container's handle. <strong>A contained run resolved as its container</strong>, which is
 *       the defect this task exists to remove and a silent one -- the run would have worked, with
 *       the parent's identity inside the child's home.
 *   <li>{@code anUncontainedOriginWithAContainedProjectIsRefused} FAILED on the same fact seen from
 *       the dangerous side: {@code assertNull} got the container's actor for a run whose home is a
 *       child's. That is the escalation, and it is why the fail-closed clause exists rather than a
 *       fallback to the caller.
 *   <li>{@code aContainedRunsSelectionIsItsOwnProjectWithGlobalExcluded} FAILED with a {@code
 *       CallerFault} out of {@code forRun}: handed the container's actor, the uncontained branch
 *       resolved the child's project as an ordinary one and the refusing permissions fake stopped
 *       it. So the pre-task behaviour was "resolve as the parent, then be refused for the parent's
 *       lack of a role in the child's project" -- not a refusal of containment.
 *   <li>{@code aContainedActorStillNeedsAnExplicitHome} FAILED with the pre-task {@code
 *       IllegalStateException} where a {@code CallerFault} is expected, which is the seam the raise
 *       was put there to make visible.
 *   <li>{@code aContainedOriginWithAnUncontainedProjectIsRefused} <strong>PASSED before the
 *       change</strong>, because resolving as the caller is also the right answer for an
 *       uncontained home. It earns its place anyway: it is the half of the two-part test that no
 *       failure would have forced anyone to implement, and a rule keyed on the origin alone would
 *       pass every other test in this class.
 *   <li>{@code anUncontainedRunActsAndIsAttributedAsItsCaller} PASSED before the change, as the
 *       control must: this task's claim is that the ordinary path is untouched.
 * </ul>
 *
 * <h2>One thing that came out other than expected</h2>
 *
 * <p>{@link InformationActor}'s compact constructor rejects a blank acting principal but does
 * <strong>not</strong> UUID-validate it, so nothing in the carrier would catch a non-principal
 * string arriving on the acting axis. What makes that safe is the shape of the resolution rather
 * than validation: the only value that can reach a contained actor's acting axis is a {@code
 * contained_principals.id} read through the authority record, and the SQL arms that admit it
 * ({@code contained_knowledge_actor}, V229:30) cast the actor to {@code uuid} and match it against
 * that table. A string that is not a live principal selects no arm, so a mis-supplied actor fails
 * closed at the gate instead of being admitted on a name. This class therefore asserts the
 * resolution's shape -- that the acting axis is the repository's UUID and the attribution axis is
 * the caller's handle -- rather than asking the record to validate.
 */
class ContainedInformationActorTest {
  private static final String CONTAINER = "alice";
  private static final String CHILD_PROJECT = "contained:" + UUID.randomUUID();
  private static final UUID PRINCIPAL = UUID.randomUUID();

  /**
   * The conversation records this installation holds, by id. Only {@code origin} and {@code home}
   * are read, and every other component is given the value a run's log legitimately has.
   */
  private static ConversationRecord log(String id, Origin origin, Home home) {
    return new ConversationRecord(
        id,
        home,
        origin,
        ConversationLifecycle.ACTIVE,
        null,
        "agent",
        Instant.EPOCH,
        null,
        null,
        null,
        null);
  }

  /**
   * {@code contained_principals}, as a map from the contained project's name to its principal.
   *
   * <p>It refuses a non-contained project name before the lookup, which is the production
   * repository's own first act, so a test that passed an ordinary project here could not pass by
   * the fake being more permissive than the row.
   */
  private static ContainedPrincipals principals(Map<String, UUID> rows) {
    return new ContainedPrincipals() {
      @Override
      public ContainedPrincipal issue(AuthorityScope.Contained scope) {
        throw new AssertionError("the run loop must not be able to issue a principal");
      }

      @Override
      public void revoke(AuthorityScope.Contained scope) {
        throw new AssertionError("the run loop must not be able to revoke a principal");
      }

      @Override
      public Optional<UUID> principalOfProject(String project) {
        if (!io.aeyer.plowshare.protocol.ContainedProjects.name(project)) return Optional.empty();
        return Optional.ofNullable(rows.get(project));
      }
    };
  }

  /**
   * A run loop over no model pool and no tools. Nothing here dispatches: the decision under test is
   * made before any tool is bound, and {@code offeredTo} is reached in production only.
   */
  private static JobRuntime bareRuntime() {
    return new JobRuntime(
        new io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher(
            List.of(), new io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger()),
        List.of());
  }

  private static JobRuntime runtime(ConversationRecord... records) {
    var runtime = bareRuntime();
    var byId = new java.util.HashMap<String, ConversationRecord>();
    for (var record : records) byId.put(record.id(), record);
    runtime.useContainedRuns(
        id -> Optional.ofNullable(byId.get(id)), principals(Map.of(CHILD_PROJECT, PRINCIPAL)));
    return runtime;
  }

  /**
   * The whole of this task, in one assertion pair: the gates are asked about the child and the rows
   * are accounted to the parent.
   *
   * <p>The caller handle is the container's and is unchanged by containment — a containment hop
   * keeps the parent's executor — so the attribution axis needs no lookup. What the lookup supplies
   * is the acting axis, and it is the principal UUID and nothing else: no handle is minted, and the
   * fake would fail the test if {@code issue} were called, because a run loop that could mint a
   * principal could mint one for a scope it does not own.
   */
  @Test
  void aContainedRunActsAsItsPrincipalAndIsAttributedToItsContainer() {
    var runtime = runtime(log("cnv_child", Origin.CONTAINED, Home.of(CHILD_PROJECT)));

    var actor = runtime.informationActorFor(CONTAINER, "cnv_child", Home.of(CHILD_PROJECT));

    assertNotNull(actor);
    assertEquals(PRINCIPAL.toString(), actor.acting(), "the gates are asked about the child");
    assertEquals(CONTAINER, actor.attribution(), "the rows are accounted to its container");
    assertFalse(actor.uncontained(), "which is what makes forRun take the contained branch");
    // NO CREDENTIAL MATERIAL: the acting axis is a bare UUID and is not the container's handle,
    // so nothing here could be presented to an authentication path as the child.
    assertEquals(PRINCIPAL, UUID.fromString(actor.acting()));
    assertNotEquals(actor.acting(), actor.attribution());
  }

  /**
   * The control, and the claim that this task changes nothing for anybody else: an ordinary run's
   * two axes hold one handle, as they did before the carrier existed.
   *
   * <p>Asserted for a log that exists with an ordinary origin <em>and</em> for a run with no log at
   * all, because the second is the shape a job bound before its conversation is known has and is
   * the one a fail-closed clause could most easily have broken.
   */
  @Test
  void anUncontainedRunActsAndIsAttributedAsItsCaller() {
    var runtime = runtime(log("cnv_turn", Origin.TURN, Home.of("parent")));

    var actor = runtime.informationActorFor(CONTAINER, "cnv_turn", Home.of("parent"));
    assertEquals(InformationActor.of(CONTAINER), actor);
    assertTrue(actor.uncontained());

    assertEquals(
        InformationActor.of(CONTAINER),
        runtime.informationActorFor(CONTAINER, null, Home.of("parent")),
        "a run with no log is an ordinary run, not a refused one");
    assertEquals(
        InformationActor.of(CONTAINER),
        runtime.informationActorFor(CONTAINER, "cnv_unknown", Home.of("parent")),
        "and neither is a run whose log this installation does not hold");
    assertNull(
        runtime.informationActorFor(null, "cnv_turn", Home.of("parent")),
        "a handle-less run keeps carrying no actor, which is a pre-existing tolerated absence");
  }

  /**
   * Half of the two-part test: the origin says contained and the home does not.
   *
   * <p>Only {@code ConversationStore.openContained} writes {@code Origin.CONTAINED} and it only
   * ever writes it on a contained home, so this combination should not exist — which is the reason
   * to assert it rather than to rely on it. A rule that accepted the origin alone would be a rule
   * that a later door writing {@code CONTAINED} on an ordinary home would silently open, and it
   * would open it the worst way round: the run would act as a principal belonging to some other
   * project's child.
   *
   * <p>The run resolves as its caller here rather than as nothing, because an uncontained home is
   * an ordinary home and an ordinary run in it is exactly what this is.
   */
  @Test
  void aContainedOriginWithAnUncontainedProjectIsRefused() {
    var runtime = runtime(log("cnv_odd", Origin.CONTAINED, Home.of("parent")));

    var actor = runtime.informationActorFor(CONTAINER, "cnv_odd", Home.of("parent"));

    assertTrue(actor.uncontained(), "the origin alone does not make a run contained");
    assertEquals(CONTAINER, actor.acting());
    assertNotEquals(PRINCIPAL.toString(), actor.acting());
  }

  /**
   * The other half: the home says contained and the origin does not.
   *
   * <p>Here the answer is <strong>no actor at all</strong>, not the caller's, and the difference
   * matters more than the symmetry. The run's tool home is a child's, so resolving as the caller
   * would put the container's identity inside its child's home — the one escalation this wiring
   * must not produce. The home does not decide containment; it decides that the question had to be
   * answered, and an unanswered question fails closed.
   *
   * <p>The same clause is what protects a deployment whose lookups are unwired, asserted below
   * against a runtime that was never told anything: a server that cannot read the record must not
   * therefore run every child as its parent.
   */
  @Test
  void anUncontainedOriginWithAContainedProjectIsRefused() {
    var runtime = runtime(log("cnv_turn", Origin.TURN, Home.of(CHILD_PROJECT)));

    assertNull(
        runtime.informationActorFor(CONTAINER, "cnv_turn", Home.of(CHILD_PROJECT)),
        "a contained home with no contained origin resolves no actor rather than the parent's");

    var unwired = bareRuntime();
    assertNull(
        unwired.informationActorFor(CONTAINER, "cnv_child", Home.of(CHILD_PROJECT)),
        "and neither does a deployment that cannot read the record at all");
    assertEquals(
        InformationActor.of(CONTAINER),
        unwired.informationActorFor(CONTAINER, "cnv_child", Home.of("parent")),
        "while an ordinary home on the same unwired runtime is untouched");
  }

  /**
   * What the resolved actor becomes: the child's own project, scope-bound, with Global excluded.
   *
   * <p>This is the second half of the task and it is {@code InformationAccess.forRun}'s contained
   * branch. The three assertions are the three things that branch decides and that nothing else
   * may: the selection names the child's own contained project; {@code includeGlobal} is false, so
   * no gate's global arm — each of which wants a credentialed general-read membership this
   * principal has none of — is ever asked for; and the two axes survive into the context.
   *
   * <p>{@link ScopedPermissions} is a fake that <strong>refuses everything</strong>, which is the
   * honest model of a contained project: it carries no {@code PROJECT} scope, so {@code
   * authority_project_role} returns null for the principal and for its container alike. The branch
   * resolving under that fake is the assertion that it asks no role question — and the last
   * assertion here is the other side of the same fact, that the service gates on this class still
   * refuse the context they were handed. That boundary is deliberate and is characterized in the
   * database test.
   */
  @Test
  void aContainedRunsSelectionIsItsOwnProjectWithGlobalExcluded() {
    var access =
        new InformationAccess((principal, target, action) -> false)
            .within(KnowledgeReadScope.homeOnly(Home.of(CHILD_PROJECT)));
    var runtime = runtime(log("cnv_child", Origin.CONTAINED, Home.of(CHILD_PROJECT)));
    var actor = runtime.informationActorFor(CONTAINER, "cnv_child", Home.of(CHILD_PROJECT));

    var context = access.forRun(actor, Home.of(CHILD_PROJECT));

    assertEquals(InformationContext.Scope.PROJECT, context.selection().scope());
    assertEquals(CHILD_PROJECT, context.selection().project(), "the child's own home, by name");
    assertFalse(context.selection().includeGlobal(), "Global is excluded, deliberately");
    assertEquals(PRINCIPAL.toString(), context.acting(), "the acting axis survives resolution");
    assertEquals(CONTAINER, context.attribution(), "and so does the accounting one");

    // THE BOUNDARY, stated here so it cannot be mistaken for an oversight: the service gates ask
    // ScopedPermissions for a live project role, a contained project grants none, and so they
    // refuse this context. Nothing in this task widens them.
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class, () -> access.requireSelection(context));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class, () -> access.requireWork(context));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class, () -> access.requireAudience(context));

    // THE CONTAINER CANNOT RESOLVE THE SAME HOME AS ITSELF, which is the other side of the split:
    // the uncontained branch runs PersonalSpaces.resolveAddress against a contained project name
    // and refuses it there, before any role question. The refusal's type is that helper's and is
    // not a CallerFault, so this asserts the refusal and not its wording -- a contained home is
    // not an address an ordinary run has, and the branch above is the only way into it.
    assertThrows(
        RuntimeException.class,
        () -> access.forRun(InformationActor.of(CONTAINER), Home.of(CHILD_PROJECT)));
  }

  /**
   * The Global home stays refused on the contained branch too, with the uncontained branch's own
   * wording, because a run with no project home has no contained home either.
   */
  @Test
  void aContainedActorStillNeedsAnExplicitHome() {
    var access = new InformationAccess((principal, target, action) -> false);
    var actor = InformationActor.contained(PRINCIPAL.toString(), CONTAINER);

    var refused =
        assertThrows(
            io.aeyer.plowshare.server.faults.CallerFault.class,
            () -> access.forRun(actor, Home.global()));
    assertTrue(refused.getMessage().contains("explicit Personal or project home"));

    // And a non-contained project home is refused by contained(...)'s own prefix test, so the
    // contained branch cannot be used to resolve an ordinary project under a principal.
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> access.forRun(actor, Home.of("parent")));
  }
}
