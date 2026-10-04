package io.aeyer.plowshare.server.archive;

/**
 * The archive holds the thing named and will not do this to it.
 *
 * <p>The other half of {@link ArchiveException}, which now means <em>absent</em> and nothing else:
 * an id nothing was ever written under, a proposal nobody filed, a project with no workspace. This
 * one is the opposite state — the row is there, and the operation is refused because of what the
 * row currently <em>is</em>. A promotion of a tombstone, a second settlement of a proposal a person
 * already answered, a verdict naming a target in another tier.
 *
 * <h2>A subclass, and why, given that the family's rule says otherwise</h2>
 *
 * <p>{@code WorkspaceRefusedException} owns this family's supertype policy and states the
 * criterion: <b>does collapsing the two change the run's <em>fate</em>?</b> Yes means no shared
 * supertype; no means a subclass is available and is a trade rather than a gift. That class also
 * <em>predicted</em> the answer for this split, in the first bullet — and has retracted the
 * prediction under its own heading, where a reader deciding a third case meets it. This is the
 * other end of that retraction, and the argument is here because this is where its subject lives.
 *
 * <p><b>The criterion is right and its prediction here was wrong, and the difference is a fact
 * about {@code JobRuntime} rather than about archives.</b> {@code JobRuntime.dependencyFailure}
 * lists {@code EmbeddingException}, {@code LlmException}, {@code ArchiveUnavailableException} and
 * {@code WorkspaceUnavailableException} — and deliberately not {@link ArchiveException}, whose
 * exclusion its own javadoc argues for by name: <em>"Everything else — including {@code
 * ArchiveException} … — is a tool result, because that is a mistake a model can correct."</em> So
 * an absent id and a refusal already end a run the same way, which is to say neither ends one.
 * Collapsing them cannot change a job's outcome; it costs a reader a status code. That is the
 * second bullet, not the first.
 *
 * <p>The prediction was made about a class that had not been read, and its premise is false on its
 * own terms besides: "proposal X was already settled by enzo" is exactly a thing a model stops
 * doing on its next turn. Both are "correctable" in the sense that matters to a run: the model does
 * something else next turn either way, and no {@code catch} anywhere branches on which it was. What
 * differs is what an operator's counter says, and 404 for a proposal that exists is the wart this
 * closes — <b>which cannot be the reason to reject a subclass, because the subclass is what
 * delivers it</b>.
 *
 * <h2>Which way the subclass points, and what it costs</h2>
 *
 * <p>{@link ArchiveException} stays the supertype and keeps the 404, and this narrows above it.
 * That is the fail-safe direction for the reason {@code SessionGoneException} gives one package
 * over: {@link io.aeyer.plowshare.server.api.ApiExceptionHandler} names the supertype in the list
 * that decides the status, so a site nobody has classified still answers as it did before this type
 * existed, and a handler that never learns about the narrowing behaves unchanged. Making
 * <em>absent</em> the subclass would have flipped the default the other way: an unclassified future
 * throw would newly report a conflict about a row that may not exist.
 *
 * <p><b>And the cost runs upward, which is not neutral.</b> A refusal is now caught by strictly
 * more handlers than a sibling type would have been. <b>Four {@code catch (ArchiveException)}
 * clauses take it, and they split three to one.</b>
 *
 * <ul>
 *   <li><b>Three cannot meet one</b>, and that is a fact about those three methods rather than a
 *       property of the type: {@code MemoryTools.Read.answer} and {@code MemoryTools.Read.missing},
 *       around {@link Archive#read} and {@link Archive#get}, and {@code
 *       ProposalStore.alreadySettledOrUnknown}, around {@link ProposalStore#get}. Every one of
 *       those calls throws only an absence.
 *   <li><b>The fourth is meant to</b>: {@code PromotionQueue.reconsider} wraps {@code
 *       ProposalStore.release}, whose three refusals — a row nobody claimed, a waiting place
 *       already taken, a claim somebody else gave back — are exactly what its {@code refused} list
 *       is made of. Catching them is the operation, not a hazard the subclass created.
 * </ul>
 *
 * <p><b>This paragraph said "three … none of them can meet one", and the fourth arrived two commits
 * later in the same task without it being touched</b> — while the sentence ended "so it is written
 * here where a fourth {@code catch} would be added", which is precisely what happened. It also
 * contradicted the classification list below, which already names {@code release}'s three refusals
 * as reachable. Recorded rather than quietly corrected, because the failure is the interesting
 * part: a count kept in prose is a count nothing fails when it goes stale, and the note asking to
 * be updated did not make anyone update it.
 *
 * <h2>Every site, classified</h2>
 *
 * <p>One list, here, because the classification is the whole of what the split is. <b>Refused</b>:
 * {@link Archive#promote} for a retired memory and for one already global; {@code
 * Archive.requireTarget} for a verdict with no target id and for one naming another tier; {@code
 * ProposalStore.note} for a waiting row and for one that went back to waiting; {@code
 * ProposalStore.release} for a row nobody claimed, for a waiting place already taken, and for a
 * claim somebody else gave back; {@code ProposalStore}'s already-settled and already-waiting
 * refusals; {@link PromotionQueue#approve} when a promotion that happened could not be written onto
 * its proposal; and {@code ProjectStore.rename} for a name another project already holds — the
 * first refusal that store has ever had, and one the database would otherwise report as {@code
 * projects_name_is_unique}.
 *
 * <p><b>Absent</b>, and so plain {@link ArchiveException}: {@link Archive#get}, {@code
 * Archive.requireTarget} for a target id that resolves to nothing, {@code ProposalStore.propose}'s
 * foreign key, {@link ProposalStore#get}, {@code ProjectStore.forget}, {@code
 * ProjectStore.effectiveExclusions} and {@code ProjectStore.rename} for a project no row is called.
 *
 * <h2>The relation is held by the compiler, at one site</h2>
 *
 * <p>{@code ProposalStore.alreadySettledOrUnknown} returns {@link ArchiveException}, because its
 * two answers are on opposite sides of this split — a settled row is a refusal and an unknown id is
 * an absence — and it returns an instance of this class down one branch. <b>Measured: making this a
 * sibling rather than a subclass fails to compile there</b>, which is stronger than a test, and it
 * is the same shape as {@code SessionGoneException}'s compile-time hold one package over. Its
 * sibling {@code alreadyWaiting} is declared narrow, because both of <em>its</em> answers are
 * refusals; the wide one is wide for a reason rather than by omission, which is what makes the hold
 * a property of the design rather than an accident of a signature nobody tightened.
 *
 * <p><b>Two residual imprecisions, stated rather than hidden.</b> A verdict with <em>no</em> target
 * id is malformed rather than conflicting, and 400 is what it means; 409 is nearer than the 404 it
 * used to get, and a third archive type for one throw site is the speculation this slice keeps
 * deleting. And {@code PromotionQueue.approve}'s wrap turns <em>anything</em> out of {@code note}
 * into this, including a database that could not be reached — which is a 503 in truth. Both predate
 * this split and neither is made worse by it.
 */
public class ArchiveRefusedException extends ArchiveException {

  public ArchiveRefusedException(String message) {
    super(message);
  }
}
