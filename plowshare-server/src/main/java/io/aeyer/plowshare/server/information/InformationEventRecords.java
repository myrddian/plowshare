package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.relay.Relay;
import java.util.UUID;

/**
 * The information side's own persistence for events (decision 0037): where an event is published,
 * and what the revision's history keeps afterwards.
 *
 * <p>Neither half is a delivery mechanism. Delivery is Relay's, and nothing here allocates a
 * position, orders records, holds a lock beyond one revision's row, computes a recipient set or is
 * read by cursor.
 *
 * <h2>The audience</h2>
 *
 * <p>Resolved from the revision's <b>home scope</b>.
 *
 * <p>The home scope is the unit the information read predicates already resolve, so a scope's
 * subscribers are exactly the accounts that may read it and no new authority concept is introduced.
 * {@code authority_scopes.kind} maps as:
 *
 * <ul>
 *   <li>{@code PROJECT} and {@code PERSONAL} — the scope's own project; a personal space is a
 *       project.
 *   <li>{@code ACCOUNT_KNOWLEDGE} — the owning account's personal project, via {@code
 *       projects.personal_owner}. Sound because of V185: an account's knowledge scope and its
 *       personal project are the same place.
 *   <li>everything else — <b>no Relay audience exists</b>, and this contract throws. {@code
 *       GLOBAL_KNOWLEDGE} has no project and {@code SystemScope.SERVER} is the internal worker
 *       scope, so global-read content has no expressible audience until decision 0039 lands; {@code
 *       ADMINISTRATION} and {@code RESTRICTED} likewise. A refused publication is a visible defect,
 *       one delivered to the wrong audience is not, so there is no fallback topic.
 * </ul>
 */
public interface InformationEventRecords {
  /**
   * Locks the revision against a concurrent allowance writer and resolves its audience in one read.
   *
   * <p>The lock is {@code SELECT … FOR KEY SHARE} on {@code information_revision_allowances}, and
   * it is not incidental: a worker can already hold {@code FOR UPDATE} on that row while publishing
   * its own event, and the event used to need a foreign key against it. Transactions publishing
   * several revisions must resolve all of them before doing anything that waits on another
   * transaction.
   *
   * @throws Unaddressable if the revision's home scope has no Relay audience
   * @throws IllegalStateException if the revision does not exist
   */
  Audience resolve(UUID revision);

  /**
   * Records one published event in its revision's own history, inside the publishing transaction.
   *
   * <p>This is kept for a functional reason and not for the shape of anything removed. {@code
   * information.status} shows a revision's own event history to the resource's owner, and that
   * history has to outlive delivery: a Relay topic retains publications for its policy window —
   * four days by default — while a document's audit trail is expected to be there for as long as
   * the document is. Relay is also indexed by topic position, not by revision, so "what happened to
   * this document" is not a question its log can answer.
   *
   * <p>It is therefore a history keyed by revision, with no sequence of its own: the position it
   * stores is Relay's, so there is no second position space and nothing reads this by cursor. It
   * carries no recipients, because who received a publication is a property of the subscriptions on
   * its topic.
   *
   * @throws IllegalStateException if the revision does not exist
   */
  void append(Entry entry);

  /** One published event as its revision's history keeps it, at the position Relay gave it. */
  record Entry(
      UUID revision,
      Relay.Scope scope,
      long position,
      long generation,
      String actor,
      String stage,
      String action,
      String detail,
      java.time.Instant recordedAt) {
    public Entry {
      java.util.Objects.requireNonNull(revision, "revision");
      java.util.Objects.requireNonNull(scope, "scope");
      if (position < 1) throw new IllegalArgumentException("Relay position must be positive");
      if (generation < 1) throw new IllegalArgumentException("generation must be positive");
      java.util.Objects.requireNonNull(actor, "actor");
      java.util.Objects.requireNonNull(stage, "stage");
      java.util.Objects.requireNonNull(action, "action");
      java.util.Objects.requireNonNull(recordedAt, "recordedAt");
    }
  }

  /**
   * One revision's audience and the projection its subscribers read back.
   *
   * <p>{@code documentType}, {@code documentSubtype} and {@code owner} come from the same row as
   * the scope. They are carried into the publication so that reading a page needs no second query
   * per event: the old reader joined them, and {@code owner} is what decides whether a reader sees
   * an event's detail.
   */
  record Audience(Relay.Scope scope, String documentType, String documentSubtype, String owner) {
    public Audience {
      java.util.Objects.requireNonNull(scope, "scope");
      java.util.Objects.requireNonNull(documentType, "documentType");
      java.util.Objects.requireNonNull(owner, "owner");
    }
  }

  /**
   * A home scope with no Relay audience. Deliberately not a {@code CallerFault}: nothing the caller
   * supplied is wrong, and the publication must fail visibly rather than be dropped or redirected.
   */
  final class Unaddressable extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public Unaddressable(String message) {
      super(message);
    }
  }
}
