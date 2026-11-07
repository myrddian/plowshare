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
   * <p><strong>The principal is canonicalised to one spelling, because the gates do not agree on
   * case.</strong> {@code contained_knowledge_actor} (V229:30) matches case-INsensitively — a
   * {@code ~*} regex, then {@code $1::uuid}, which accepts an uppercase UUID — and {@code
   * contained_own_home} (V229:248) likewise casts {@code $1::uuid}, so every WRITE arm and V232's
   * memory arm admit a mixed-case spelling. {@code contained_scope_readable}'s self arm compares
   * {@code principal.id::text=$1} case-SENSITIVELY (V223:158-160) against a column Postgres always
   * renders lowercase, so every READ arm — V231's new reference disjunct included — refuses the
   * same string. The net effect is fail-closed rather than an escalation, but it is a child that
   * can write what it then cannot read, and it is reachable only because this carrier used to pass
   * the caller's string through untouched. Lower-casing it here means one spelling reaches all of
   * them. {@code MemoryStore}'s {@code lower(?)} projections stay as defence in depth; this does
   * not replace them.
   *
   * <p>The test is {@link #UUID_SHAPE}, which is {@code contained_knowledge_actor}'s own regex
   * character for character, and the rewrite is {@code toLowerCase(ROOT)}. Both halves of that
   * matter. Because the shape is the gate's, a string the gate would reject is never rewritten into
   * one it would accept — so this cannot widen admission, only make it consistent. {@link
   * java.util.UUID#fromString} is deliberately NOT used: it accepts abbreviated forms such as
   * {@code 1-1-1-1-1} and expands them to a full UUID, which Postgres' {@code ::uuid} cast would
   * have refused, and that would be exactly the widening this must not do.
   *
   * <p>A string that does not match is left exactly as given, neither lower-cased nor rejected:
   * {@code contained_knowledge_actor} is false for it, so it is admitted by no contained arm either
   * way, and this record is a carrier that must not acquire a refusal of its own — {@code
   * JobRuntime.informationActorFor} decides containment from the durable conversation record and is
   * the only production caller.
   *
   * @param principal the contained principal, acting
   * @param container the container's account, accountable
   */
  public static InformationActor contained(String principal, String container) {
    return new InformationActor(canonical(principal), container);
  }

  /**
   * {@code contained_knowledge_actor}'s guard (V229:31): the same pattern text, and {@code
   * CASE_INSENSITIVE} for the same reason SQL spells it {@code ~*} rather than {@code ~}. Kept
   * identical on purpose — {@link #canonical} is safe only because a string this does not match is
   * left alone, which holds only while this accepts no more than the gate does.
   */
  private static final java.util.regex.Pattern UUID_SHAPE =
      java.util.regex.Pattern.compile(
          "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
          java.util.regex.Pattern.CASE_INSENSITIVE);

  /** One spelling of a UUID-shaped principal; anything else unchanged. See {@link #contained}. */
  private static String canonical(String principal) {
    return principal != null && UUID_SHAPE.matcher(principal).matches()
        ? principal.toLowerCase(java.util.Locale.ROOT)
        : principal;
  }

  /** Whether both axes name the same account, which is true of every production caller today. */
  public boolean uncontained() {
    return acting.equals(attribution);
  }
}
