package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable publication and read-back of information events (decision 0037).
 *
 * <p>Relay is the only durable delivery substrate, so this family is a wrapper over it and carries
 * no ordering fence, no position space of its own, no consumption cursor and no recipient
 * computation. One append per event, under Relay's own topic lock, to the topic the revision's home
 * scope maps to; who receives it is decided by the subscriptions on that topic.
 *
 * <p>What this replaced is worth recording, because it is the reason the contract looks like this.
 * The previous implementation took a {@code pg_advisory_xact_lock} on one key hashed from a fixed
 * name — one key for the whole installation — and then, still holding it, computed a recipient
 * fan-out by asking {@code information_readable} once per administrator and once per
 * administrator-project pair. Measured on the live installation with 13 administrators, that cost
 * <b>3,996 ms for a single event</b>, and because {@code pg_advisory_xact_lock} is held to the end
 * of the transaction, every information event serialized the whole installation for seconds while
 * the processing pipeline emitted them continuously.
 */
public interface InformationEvents {
  /**
   * Appends one event to its revision's audience, inside the caller's transaction.
   *
   * <p>The revision's allowance row is locked first and the home scope resolved once; the append
   * then takes only Relay's lock on that one topic row. Delivery hints to subscribed readers are
   * deferred until the caller's transaction commits, so a rolled-back mutation announces nothing.
   *
   * @throws InformationEventRecords.Unaddressable if the home scope has no Relay audience. This is
   *     not caught anywhere: a refused publication is a visible defect, while one delivered to the
   *     wrong audience, or silently dropped, is not.
   */
  void publish(Occurrence event);

  /**
   * One page of the calling account's own durable subscription to its selection's topic.
   *
   * <p>{@code after} is an acknowledgement of what the caller has already processed, not a seek:
   * Relay positions are monotonic, so it advances the subscription's durable position and cannot
   * rewind it. A caller that tracks nothing may pass {@code 0} and will be served whatever its
   * subscription has not yet seen, which is the point of a durable position.
   *
   * @throws io.aeyer.plowshare.server.faults.CallerFault if the selection has no Relay audience —
   *     Global READ and account knowledge are homed outside any project; see decision 0039 — or the
   *     account may not read the selected project
   */
  Information.Events read(InformationContext context, long after, int limit);

  /**
   * One event as the catalogue records it. {@code detail} may be empty and is read back only by the
   * home resource's owner, which is the rule the previous reader enforced in SQL.
   */
  record Occurrence(
      UUID revision, long generation, String actor, String stage, String action, String detail) {
    public Occurrence {
      Objects.requireNonNull(revision, "revision");
      if (generation < 1) throw new IllegalArgumentException("event generation must be positive");
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(action, "action");
    }
  }
}
