package io.aeyer.plowshare.server.information;

/**
 * The two axes a run presents to information and memory, carried as one value.
 *
 * <p>{@link InformationContext} already separates <em>who is acting</em> from <em>who the work is
 * accounted to</em>; what the run bindings carried was a single {@code String callerHandle}, which
 * can only ever mean both at once. This record is the carrier that lets a caller name them
 * separately, so that a run inside a contained Application can one day act as the child's own
 * principal while staying attributed to its container (ADR 0042).
 *
 * <p><strong>Production builds both.</strong> {@code JobRuntime} presents {@link #of(String)} for
 * every ordinary run, where both axes hold the same handle, so no gate, provenance column or log
 * sees a value it did not see before; it presents {@link #contained(String, String)} for a run
 * whose own durable conversation record says it is a contained Application's, and {@link
 * InformationAccess#forRun(InformationActor, io.aeyer.plowshare.protocol.Home)} resolves that one
 * through its contained branch. The containment decision is the conversation record's, never the
 * caller's.
 *
 * <p>Both components are required. The "same as acting" fallback belongs to {@link
 * InformationContext#attribution()} and nowhere else: a second place that quietly means the same
 * thing is a second place the two axes can be made to disagree, so {@code attribution} must be
 * stated even when it repeats {@code acting}.
 *
 * @param acting the principal every authority gate is asked about
 * @param attribution the account the resulting rows are accounted to
 */
public record InformationActor(String acting, String attribution) {
  public InformationActor {
    if (acting == null || acting.isBlank())
      throw new IllegalArgumentException("an information actor needs an acting principal");
    if (attribution == null || attribution.isBlank())
      throw new IllegalArgumentException("an information actor needs an accounting account");
  }

  /** The ordinary run: one authenticated handle acting and accountable for its own work. */
  public static InformationActor of(String handle) {
    return new InformationActor(handle, handle);
  }

  /**
   * The contained run: the child's own ephemeral principal acting, its container accountable.
   *
   * <p>The contained principal holds no credential material — no {@code admins}, {@code
   * authority_accounts} or {@code service_tokens} row and no handle — which is exactly why the
   * second axis has to be carried: the provenance columns' foreign keys require the container's
   * account, while the gates require the child's principal.
   *
   * @param principal the contained principal, acting
   * @param container the container's account, accountable
   */
  public static InformationActor contained(String principal, String container) {
    return new InformationActor(principal, container);
  }

  /** Whether both axes name the same account, which is true of every production caller today. */
  public boolean uncontained() {
    return acting.equals(attribution);
  }
}
