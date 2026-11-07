package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Refuses an operation a confined Application has never been authorized to perform, before anything
 * is written.
 *
 * <p>Persistence-layer infrastructure: it is package-private, it collaborates only with the {@code
 * Jdbc*} repositories of this package, and it holds a {@link JdbcTemplate} for the same reason they
 * do — the predicate it asks is a SQL function. No service, controller, tool or adapter may reach
 * it, and it is deliberately not a bean; each repository constructs its own from the template it
 * already owns, so the guard cannot become a collaborator a service can be handed.
 *
 * <p><strong>Why a refusal and not the attribution axis.</strong> Nothing has decided that a
 * confined Application may link a document into another project, publish one, withdraw one, retry
 * or rebuild processing, set an allowance, cancel work, record evidence, finalise a report, change
 * a document's tags or tag groups, queue a URL for acquisition, authorise processing stages,
 * prepare a metadata revision, or import a portable bundle. V229 admits a contained principal into
 * {@code lockResource} and the readability predicates for its own home, so it can genuinely arrive
 * at every one of them.
 *
 * <p>{@code link} alone would fail on integrity rather than on authority: it writes {@code
 * information_links.actor_handle}, whose foreign key targets {@code admins} (V83:77) and which a
 * contained principal has no row in by design (V218), so unguarded it raises an FK error — a server
 * fault for something a caller did. <strong>Every other guarded operation in the catalogue
 * repository would have succeeded.</strong> The event-publisher ones hand the actor to Relay rather
 * than to a keyed column — the delivery table that once held that foreign key was dropped when
 * events moved onto Relay (V188:96), and it cannot be named here even in a comment about its
 * removal, because {@code InformationEventFenceRemovedTest} refuses any reference to it from server
 * source including inside the migration's own filename — and the tag mutators write no actor column
 * at all. That is the more dangerous half, and the reason this is an authority check rather than an
 * integrity patch: whatever made {@code link} work would have silently granted all the rest.
 *
 * <p>Extending {@code attribution()} across them would have granted each of those capabilities by
 * implementation accident and attributed the act to an account that did not ask — against this
 * capability's own rule that refusing a surface is an acceptable first slice while honouring one
 * nobody authorized is not. Several are pipeline-lifecycle operations over documents a contained
 * home's are already declined from. What a running child may reach is a decision that belongs to
 * the task that defines it, so this fails closed and keeps that decision open.
 *
 * <p>The child's own write path does not come through here. {@code admit} <em>carries the
 * attribution axis</em> for a confined Application, calls none of these, and writes its own
 * provenance columns from it. It is not the only operation a confined Application could otherwise
 * reach — every guarded site is one — so do not read it as the whole of what a child may do.
 *
 * <p><strong>What the guarded set is closed over, stated because it is narrower than "every
 * mutator".</strong> It is the mutators that take an {@code InformationContext} in {@code
 * JdbcInformationCatalogueRepository} and in the sibling repositories of this package: a
 * hand-maintained enumeration against a signature, not a structure. Three things that criterion
 * excludes, so a reader does not read "closed" as "complete":
 *
 * <ul>
 *   <li>{@code replaceTags(Resource, …)} takes no context, so the criterion does not reach it. It
 *       is guarded anyway, from the context its caller holds.
 *   <li>{@code JdbcInformationNavigationRepository} carries no call to this guard, because {@code
 *       lockIdentity} is its mutators' first statement and already refuses the acting principal on
 *       an {@code admins} row it cannot have, as a caller-visible {@code NotFoundFault}, before any
 *       write. That is an authority predicate about the acting axis, which cannot evaporate the way
 *       a provenance column's foreign key can; a second refusal in front of a sound one is a second
 *       thing to keep in step. {@code ContainedActorWriteReachDatabaseTest} records what every
 *       sibling repository actually did before any of them was guarded, and holds the assertions
 *       that keep that one refusal in place.
 *   <li>A mutator added without a guard is caught only by an enumeration gaining a row for it —
 *       {@code ContainedExecutionWriteDatabaseTest} for this package's catalogue mutators, {@code
 *       ContainedActorWriteReachDatabaseTest} for the sibling repositories — which is the same
 *       hand-maintained criterion as the guarded set itself and not an independent check. Neither
 *       test replaces the other: the first holds a row per catalogue mutator, the second one test
 *       per sibling repository.
 * </ul>
 *
 * <p>A structural alternative exists and is not built here: every mutator in the catalogue
 * repository reaches its write through {@code lockResource} or {@code requireDestination}, so the
 * check could live at those two chokepoints with an explicit per-operation allowance passed in —
 * which is a signature change across roughly a dozen call sites and a decision about what a
 * contained actor may do, not a refactor. Until then the enumeration is the contract, and what it
 * covers is the list above.
 *
 * <p><strong>One ordering this guard does not control, recorded rather than changed.</strong>
 * Reached through the service with a non-null request, the catalogue repository's {@code managed}
 * reserves the command before any guarded body runs, so {@code information_commands.account}'s
 * foreign key into {@code admins} would refuse a bare-UUID actor first and this guard would never
 * be asked. That is shared with every managed mutation there, predates the guard, and leaves the
 * refusal a server fault rather than the caller fault this raises — and no test covers it, because
 * the tests drive the repositories directly. Command-reservation ordering is not this guard's to
 * change.
 *
 * <p>Keyed on {@code contained_knowledge_actor} (V229:30), which is the same predicate V229's arms
 * are keyed on, so this cannot drift from the population it is about and is safe on a non-UUID
 * actor. Do not weaken this to a comparison of the two axes: attribution is a provenance decision
 * and this is an authority one.
 */
final class ContainedActorGuard {
  private final JdbcTemplate jdbc;

  ContainedActorGuard(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  /**
   * Raises {@link CallerFault} when the acting principal is a confined Application's.
   *
   * @param context the write's context; the <em>acting</em> axis is the one asked about
   * @param operation the refused operation, in the caller's voice, completing "A confined
   *     Application may not …"
   */
  void requireUncontained(InformationContext context, String operation) {
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT contained_knowledge_actor(?)", Boolean.class, context.acting())))
      throw new CallerFault("A confined Application may not " + operation);
  }
}
