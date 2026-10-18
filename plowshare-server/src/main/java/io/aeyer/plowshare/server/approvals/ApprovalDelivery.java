package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import io.aeyer.plowshare.server.delivery.PersonDelivery.Result;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Delivers durable approval questions through the shared person-delivery last mile. */
public final class ApprovalDelivery implements ApprovalNotifications {

  public static final String INBOX_KIND = "approval";
  private static final Logger log = LoggerFactory.getLogger(ApprovalDelivery.class);

  private final ApprovalNotificationStore store;
  private final PersonDelivery people;
  private final Consumer<String> settle;

  public void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs) {
    people.useInformationInputs(inputs);
  }

  public ApprovalDelivery(ApprovalNotificationStore store, PersonDelivery people) {
    this(store, people, about -> {});
  }

  /**
   * @param settle takes a settled question's notices out of the inbox, by what they are {@link
   *     #about} — {@code events.Inbox.settle} (V68)
   */
  public ApprovalDelivery(
      ApprovalNotificationStore store, PersonDelivery people, Consumer<String> settle) {
    this.store = Objects.requireNonNull(store, "store");
    this.people = Objects.requireNonNull(people, "people");
    this.settle = Objects.requireNonNull(settle, "settle");
  }

  /** What an approval's inbox notice asks about (V68): {@code approval:<id>}. */
  public static String about(String approval) {
    return "approval:" + approval;
  }

  /**
   * An approval was answered — allowed, denied, or withdrawn — so the person's notice asking it
   * leaves their inbox. {@code RunApprovalStore.whenSettled}; never throws, so the answer it
   * follows stands whatever the inbox does.
   */
  public void settled(String approval) {
    try {
      settle.accept(about(approval));
    } catch (RuntimeException failed) {
      log.warn(
          "approval {} was answered, but its inbox notice could not be settled", approval, failed);
    }
  }

  /** Questions raised anywhere under the root run that has just been durably closed. */
  public void drain(String conversation) {
    if (relayDelivery || conversation == null) {
      return;
    }
    guarded(() -> store.undelivered(conversation).forEach(this::deliver));
  }

  /** Every question a restart found before its inbox delivery completed. */
  public void drainAll() {
    if (relayDelivery) return;
    guarded(() -> store.undelivered().forEach(this::deliver));
  }

  private boolean relayDelivery;
  private java.util.function.Predicate<String> notificationReady = ignored -> true;

  /** Keep answers from racing the requesting root's transcript close and speaking claim. */
  public void whenNotificationReady(java.util.function.Predicate<String> ready) {
    notificationReady = Objects.requireNonNull(ready);
  }

  /** Relay owns request notifications in production; direct drains are a configured fallback. */
  public void useRelayDelivery(boolean enabled) {
    relayDelivery = enabled;
  }

  @Override
  public boolean requested(String id, long projectId) {
    var found = store.find(id);
    if (found.isEmpty()) return true;
    RunApproval approval = found.get();
    if (approval.projectId() != projectId)
      throw new IllegalArgumentException("Approval notification belongs to another project");
    if (!RunApproval.ASKED.equals(approval.state())) return true;
    if (!notificationReady.test(approval.conversation())) return false;
    return deliver(approval);
  }

  @Override
  public void resolved(String id, long projectId) {
    var found = store.find(id);
    if (found.isEmpty()) return;
    var approval = found.get();
    if (approval.projectId() != projectId)
      throw new IllegalArgumentException("Approval notification belongs to another project");
    if (!RunApproval.ASKED.equals(approval.state())) settled(id);
  }

  /**
   * The question as a person is told it: the command — or, for a set (V67), how many commands, each
   * of them in the reason — where it runs, and why.
   */
  public static String question(RunApproval approval) {
    String what =
        approval.isSet()
            ? approval.commands().size() + " commands"
            : String.join(" ", approval.argv());
    return "Approve running "
        + what
        + " in "
        + approval.cwd()
        + " on the "
        + approval.side()
        + " side?"
        + (approval.reason() == null ? "" : " " + approval.reason())
        + " ["
        + approval.id()
        + "]";
  }

  /** The result of the machine-owned continuation goes back to the same pinned account. */
  public void continuationEnded(RunApproval approval, Outcome outcome) {
    if (outcome.ending() == Outcome.Ending.AWAITING) {
      return;
    }
    guarded(
        () ->
            people.sendFromLog(
                approval.conversation(),
                approval.agent(),
                approval.handle(),
                INBOX_KIND,
                "Approval "
                    + approval.id()
                    + " continued the run, which ended "
                    + outcome.ending()
                    + ": "
                    + outcome.text(),
                Speaker.approval(approval.id()),
                null,
                approval.askedIn()));
  }

  private boolean deliver(RunApproval approval) {
    Result result =
        people
            .sendQuestionFromLog(
                approval.conversation(),
                approval.handle(),
                INBOX_KIND,
                question(approval),
                about(approval.id()),
                approval.askedIn())
            .result();
    if (result == Result.DELIVERED) {
      store.delivered(approval.id());
      // Answered while this was on its way — read as asked, then answered before the
      // notice landed: the settle that answer sent found nothing, and none will follow.
      if (store
          .find(approval.id())
          .filter(now -> !RunApproval.ASKED.equals(now.state()))
          .isPresent()) {
        settled(approval.id());
      }
    } else if (result == Result.NOWHERE) {
      log.warn("approval {} has no conversation or account to tell", approval.id());
    }
    return result == Result.DELIVERED;
  }

  private static void guarded(Runnable delivery) {
    try {
      delivery.run();
    } catch (RuntimeException failed) {
      log.warn("approval delivery failed; it stays undelivered for the next drain or boot", failed);
    }
  }
}
