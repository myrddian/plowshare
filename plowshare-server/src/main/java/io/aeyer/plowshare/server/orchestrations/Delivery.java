package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import io.aeyer.plowshare.server.delivery.PersonDelivery.Result;
import io.aeyer.plowshare.server.hooks.Handover;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes an orchestration's question or ending to its caller — spoken into its parent conductor's
 * own conversation, into the caller's own TURN conversation, or left in its account's inbox —
 * Decisions 3 and 4.
 *
 * <h2>Where it goes</h2>
 *
 * <ol>
 *   <li>A question whose run's conductor is still in its turn: not yet — that turn's ending or a
 *       later drain delivers it.
 *   <li>A run with a live parent: spoken into that parent conductor's own conversation, which wakes
 *       a parent that was waiting on it. A parent still mid-turn — the ordinary case, because a
 *       wait is taken inside the turn that started the child — leaves the item undelivered for
 *       {@link #drainCaller} when that conversation frees, as a busy caller does. So does a refusal
 *       the parent survived: while it is alive it is still the place the report belongs, and no
 *       other route would reach the conductor that asked for the work — and a parent that took its
 *       turn between the check and the speak, and so refused this as in flight, arms the same
 *       one-shot retry, because that turn's free may already have been dropped by the in-flight
 *       guard and a {@code waiting} row is what neither recover nor a nudge picks up. Only a
 *       refusal that ended the parent falls through to the routes below, because nothing would ever
 *       free that conversation to drain the item again.
 *   <li>A caller conversation that exists and is {@link Origin#TURN}: if it is speaking, the item
 *       waits, undelivered, for {@link #drainCaller} when that turn frees; otherwise it is spoken
 *       into, by the caller's own agent.
 *   <li>Any other refusal to speak ({@link Turn.Refused} — a spent budget, an agent that no longer
 *       resolves — or {@link ArchiveException}), no conversation, or a conversation of another
 *       origin: the inbox, if the run names an account.
 *   <li>Nowhere at all: marked delivered with one WARN. Nothing can hear it, and it stays readable
 *       on its row.
 * </ol>
 *
 * <h2>Once</h2>
 *
 * <p>An in-process in-flight set, not a database claim (Decision 3): a delivery id is added before
 * anything is read and removed when the delivery completes or is left for a later drain, so a drain
 * that re-enters while the caller is being spoken to — {@code Turn}'s free fires inside a speak —
 * skips it. When an attempt found the caller — or the parent — busy, it checks once after releasing
 * the guard and retries once if it has become free; this closes the skipped-drain race without
 * spinning. Inside the guard the item's delivered mark is read again from the row, so an item asked
 * about twice is delivered once. The mark is written only after a speak or an inbox notice
 * succeeded, so a restart re-delivers anything that did not complete.
 *
 * <h2>It never throws</h2>
 *
 * <p>The engine calls it after its own state has committed, and {@code Turn}'s free calls the
 * drains. A throw from the voice, the inbox or the store is logged, and the item is left
 * undelivered for the next drain or the next boot.
 *
 * <h2>A question only the person may answer skips every model</h2>
 *
 * <p>Spec 2026-09-28: a run asking whether it should go on after three turns without progress
 * ({@code Orchestrations.STUCK}), or whether it goes on after its check kept failing ({@code
 * Orchestrations.CHECK_FAILURES}, V69), or to check its product or settle its acceptance checker's
 * concerns ({@code Orchestrations.PRODUCT_CHECK}, {@code Orchestrations.CONCERNS}, V77) — every
 * kind {@code Orchestrations.PERSON_ONLY} names — is asking the person, not its caller. It goes
 * straight to the account's inbox — never spoken into a caller conversation a bot would answer
 * from, nor up to a parent conductor — and is marked delivered there. A run with no account never
 * asks it.
 *
 * <h2>A cap question reaches both</h2>
 *
 * <p>Spec 2026-09-29 §2: a turn cap, a spent budget or a time cap (V69) is asked of the model as
 * above and, by {@link #toThePerson}, of the person at the same moment. The first answer settles
 * it; a model copy still undelivered then is withdrawn — marked delivered, never spoken.
 *
 * <h2>A question's notice settles with it</h2>
 *
 * <p>V68: a question that lands in the person's inbox names itself there ({@link #about}), and
 * {@link #questionsSettled} takes it out once it is no longer the question its run is asking —
 * answered by anyone, or its run stopped asking or ended. A question settled while its notice was
 * on its way is settled as it lands, since the settle its answer sent found nothing. An ending is
 * news, and never settles.
 *
 * <p>Only a question or an ending is ever delivered here. An answer is the conductor's to hear, and
 * {@code Orchestrations.speakAnswerIfPending} speaks it.
 */
public final class Delivery implements DeliveryPort {

  private static final Logger log = LoggerFactory.getLogger(Delivery.class);

  static final String INBOX_KIND = "orchestration";

  /** What an ending is delivered as; only an ending passes delivery.* (spec 2026-09-28 §3). */
  private static final String RESULT = "result";

  private final OrchestrationStore store;
  private final ConversationStore conversations;
  private final CallerVoice caller;
  private final Predicate<String> conductorSpeaking;
  private final ParentVoice parents;
  private final PersonDelivery people;
  private final InboxPort inbox;
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
  private volatile LogStages logStages = LogStages.NONE;

  /**
   * @param conductorSpeaking whether a conductor's conversation has a turn in flight — {@code
   *     Turn.isSpeaking}; a question is not delivered while its conductor's turn is still going
   * @param parents the door into a parent conductor's own conversation, for a nested run
   */
  public Delivery(
      OrchestrationStore store,
      ConversationStore conversations,
      CallerVoice caller,
      InboxPort inbox,
      Predicate<String> conductorSpeaking,
      ParentVoice parents) {
    this.store = Objects.requireNonNull(store, "store");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.caller = Objects.requireNonNull(caller, "caller");
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.conductorSpeaking = Objects.requireNonNull(conductorSpeaking, "conductorSpeaking");
    this.parents = Objects.requireNonNull(parents, "parents");
    this.people =
        new PersonDelivery(
            conversations,
            new PersonDelivery.Voice() {
              @Override
              public boolean isSpeaking(String conversation) {
                return caller.isSpeaking(conversation);
              }

              @Override
              public void speak(String conversation, String agent, String text, Speaker speaker) {
                caller.speak(conversation, agent, text, speaker);
              }
            },
            new PersonDelivery.Inbox() {
              @Override
              public void notify(String handle, String kind, String text, String about) {
                inbox.notify(handle, kind, text, about);
              }

              @Override
              public void notifyFromLog(
                  String handle, String kind, String text, String about, String source) {
                inbox.notifyFromLog(handle, kind, text, about, source);
              }
            });
  }

  private io.aeyer.plowshare.server.information.InformationJobs information;

  public void useInformationInputs(
      io.aeyer.plowshare.server.information.InformationJobs information) {
    this.information = information;
    people.useInformationInputs(information);
  }

  private void requireInformation(OrchestrationRecord run) {
    if (information != null)
      information.requireLog(run.conductorConversation(), run.callerHandle());
  }

  /**
   * The log stages a delivered ending passes: {@code delivery.pre} may add a note to it and {@code
   * delivery.post} is told once it is delivered; neither reroutes nor holds it (spec
   * 2026-09-28-hooks-reach-the-log §3). {@link LogStages#NONE} until wired; an implementation never
   * throws.
   */
  public void useLogStages(LogStages logStages) {
    this.logStages = Objects.requireNonNull(logStages, "logStages");
  }

  @Override
  public void questionAsked(OrchestrationRecord run, OrchestrationMessage question) {
    guarded("question " + question.id(), () -> deliverQuestion(run, question));
  }

  @Override
  public void runEnded(OrchestrationRecord run) {
    guarded("ending of " + run.id(), () -> deliverEnding(run));
  }

  /**
   * The person's copy of a cap question, into their inbox — spec 2026-09-29 §2. The stuck
   * question's route ({@link #deliverToThePerson}), but beside the model's copy rather than in
   * place of it: measured 2026-09-28 23:51, {@code orc_31893856D8F462A1}, a phase's cap question
   * reached only its parent conductor, which ended its turn in prose over it, and the tree waited
   * on itself for five hours with the person never told. Whichever answer comes first settles it
   * ({@code OrchestrationStore.answer}'s compare-and-set); the other copy is withdrawn — a model
   * copy still undelivered by {@link #deliverQuestion}, a late model answer by {@code
   * CallerOrchestrations}. The inbox notice itself cannot be unsent.
   *
   * <p>Nothing is sent for a run with no account, a question already settled, or a run whose model
   * copy has no model to reach — no live parent and no caller conversation a turn is spoken into —
   * because that copy falls to this same inbox, and a second notice would tell the person the same
   * thing twice.
   */
  @Override
  public void toThePerson(OrchestrationRecord run, OrchestrationMessage question) {
    guarded(
        "the person's copy of question " + question.id(),
        () -> {
          if (run.callerHandle() == null || !stillOpen(run, question) || !aModelHears(run)) {
            return;
          }
          people.sendFromLog(
              null,
              null,
              run.callerHandle(),
              INBOX_KIND,
              Utterances.capForThePerson(run, question),
              Speaker.orchestration(run.id()),
              about(question),
              run.conductorConversation());
          settleIfClosed(run, question);
        });
  }

  /** What a question's inbox notice asks about (V68): {@code question:<message id>}. */
  static String about(OrchestrationMessage question) {
    return "question:" + question.id();
  }

  /**
   * Every question of {@code run} but the one it is asking now is settled, and its notice leaves
   * the person's inbox. The open question is read fresh rather than from {@code run}: a question
   * asked since {@code run} was read is still open, and one answered since is not. Never throws —
   * the change it follows has committed.
   */
  @Override
  public void questionsSettled(OrchestrationRecord run) {
    try {
      String open = store.openQuestion(run.id()).map(OrchestrationMessage::id).orElse(null);
      store.messages(run.id()).stream()
          .filter(m -> m.kind() == OrchestrationMessage.Kind.QUESTION)
          .filter(m -> !m.id().equals(open))
          .forEach(m -> inbox.settle(about(m)));
    } catch (RuntimeException e) {
      log.warn(
          "orchestration {}: its settled questions could not be taken out of the" + " inbox",
          run.id(),
          e);
    }
  }

  /**
   * A notice that has just landed for a question no longer open — answered, or its run stopped
   * asking, between the check that sent it and now — is settled at once: the settle that change
   * sent found no notice, and nothing else would come back for it.
   */
  private void settleIfClosed(OrchestrationRecord run, OrchestrationMessage question) {
    try {
      if (!stillOpen(run, question)) {
        inbox.settle(about(question));
      }
    } catch (RuntimeException e) {
      log.warn(
          "orchestration {}: question {} was settled as its notice landed, and that"
              + " notice could not be taken out of the inbox",
          run.id(),
          question.id(),
          e);
    }
  }

  private boolean stillOpen(OrchestrationRecord run, OrchestrationMessage question) {
    return store.openQuestion(run.id()).filter(open -> open.id().equals(question.id())).isPresent();
  }

  /** Whether the model copy has a model to reach, as {@link #deliver} routes it. */
  private boolean aModelHears(OrchestrationRecord run) {
    if (run.parent() != null && parents.isLiveParent(run.parent())) {
      return true;
    }
    return run.callerConversation() != null
        && conversations
            .find(run.callerConversation())
            .map(conversation -> conversation.origin() == Origin.TURN)
            .orElse(false);
  }

  /** Every undelivered question or ending whose caller is this conversation. */
  public void drainCaller(String conversation) {
    drain("for " + conversation, run -> conversation.equals(run.callerConversation()));
  }

  /** Every undelivered question and ending, for boot. */
  public void drainAll() {
    drain("at boot", run -> true);
  }

  private void drain(String why, Predicate<OrchestrationRecord> mine) {
    List<OrchestrationMessage> questions;
    List<OrchestrationRecord> endings;
    try {
      questions =
          store.undeliveredMessages().stream()
              .filter(m -> m.kind() == OrchestrationMessage.Kind.QUESTION)
              .toList();
      endings = store.undeliveredEndings();
    } catch (RuntimeException e) {
      log.warn(
          "could not list undelivered orchestration questions and endings {}; the next"
              + " drain retries",
          why,
          e);
      return;
    }
    for (OrchestrationMessage question : questions) {
      guarded(
          "question " + question.id(),
          () ->
              store
                  .find(question.orchestration())
                  .filter(mine)
                  .ifPresent(run -> deliverQuestion(run, question)));
    }
    for (OrchestrationRecord run : endings) {
      if (mine.test(run)) {
        runEnded(run);
      }
    }
  }

  /**
   * A question whose conductor is still in its turn is not delivered yet: {@code orchestration_ask}
   * commits inside the tool, and a later call in the same batch can still end that turn some other
   * way. The turn's ending delivers it ({@code AWAITING}), or a later drain.
   */
  private void deliverQuestion(OrchestrationRecord run, OrchestrationMessage question) {
    if (conductorSpeaking.test(run.conductorConversation())) {
      log.debug(
          "orchestration {}: its conductor is still in its turn, so question {} waits",
          run.id(),
          question.id());
      return;
    }
    OrchestrationRecord now = store.find(run.id()).orElse(run);
    // A copy nobody is waiting on any more is withdrawn (spec 2026-09-29 §2): the person, or
    // the other model, answered first. Marked delivered, so no drain speaks it later — a
    // parent woken with a question already settled would answer it, be refused, and have
    // spent a turn on nothing.
    if (!stillOpen(now, question)) {
      store.messageDelivered(question.id());
      log.debug(
          "orchestration {}: question {} was settled before it was delivered, so it"
              + " is withdrawn",
          now.id(),
          question.id());
      return;
    }
    if (Orchestrations.personOnly(now.pendingCap())) {
      deliverToThePerson(now, question);
      return;
    }
    deliver(
        question.id(),
        "question",
        () ->
            store.messages(question.orchestration()).stream()
                .filter(m -> m.id().equals(question.id()))
                .filter(m -> m.deliveredAt() == null)
                .findFirst()
                .map(m -> run),
        fresh -> Utterances.questionForCaller(fresh, question),
        () -> store.messageDelivered(question.id()),
        question);
  }

  /**
   * A question only the person may answer, into their inbox: measured 2026-09-28, the bot a stuck
   * run's ending was spoken to invented a reason for it, and a parent conductor has no better view
   * of a phase that stopped moving. The in-flight guard and the delivered mark are {@link
   * #deliver}'s own, so a drain racing the ask tells the person once.
   */
  private void deliverToThePerson(OrchestrationRecord run, OrchestrationMessage question) {
    if (!inFlight.add(question.id())) {
      return;
    }
    try {
      boolean undelivered =
          store.messages(question.orchestration()).stream()
              .anyMatch(m -> m.id().equals(question.id()) && m.deliveredAt() == null);
      if (!undelivered) {
        return;
      }
      if (people
              .sendFromLog(
                  null,
                  null,
                  run.callerHandle(),
                  INBOX_KIND,
                  question.text(),
                  Speaker.orchestration(run.id()),
                  about(question),
                  run.conductorConversation())
              .result()
          == Result.NOWHERE) {
        log.warn(
            "orchestration {} asks what only a person may answer but has no account"
                + " to tell; its question stays on its row",
            run.id());
      }
      store.messageDelivered(question.id());
      settleIfClosed(run, question);
    } finally {
      inFlight.remove(question.id());
    }
  }

  private void deliverEnding(OrchestrationRecord run) {
    deliver(
        "ending:" + run.id(),
        RESULT,
        () -> store.find(run.id()).filter(r -> r.resultDeliveredAt() == null),
        Utterances::endingForCaller,
        () -> store.resultDelivered(run.id()),
        null);
  }

  /**
   * Routing step by step, for one item.
   *
   * @param undelivered the run as it now stands, re-read from the row, or empty if the item is
   *     already delivered (or gone)
   * @param question the question being delivered, which its inbox notice is {@link #about}, or null
   *     for an ending — news, which never settles
   */
  private void deliver(
      String id,
      String what,
      Supplier<Optional<OrchestrationRecord>> undelivered,
      Function<OrchestrationRecord, String> text,
      Runnable markDelivered,
      OrchestrationMessage question) {
    deliver(id, what, undelivered, text, markDelivered, question, true);
  }

  private void deliver(
      String id,
      String what,
      Supplier<Optional<OrchestrationRecord>> undelivered,
      Function<OrchestrationRecord, String> text,
      Runnable markDelivered,
      OrchestrationMessage question,
      boolean retryOnceFree) {
    if (!inFlight.add(id)) {
      return;
    }
    BooleanSupplier stillBusy = null;
    try {
      Optional<OrchestrationRecord> current = undelivered.get();
      if (current.isEmpty()) {
        return;
      }
      OrchestrationRecord fresh = current.get();
      String utterance = text.apply(fresh);
      // delivery.* is for a result, and records in the SOURCE run's log, never the
      // destination's (spec 2026-09-28-hooks-reach-the-log §3, decision 7).
      boolean anEnding = RESULT.equals(what);
      requireInformation(fresh);
      String source = fresh.conductorConversation();
      String parent = fresh.parent();
      if (parent != null && parents.isLiveParent(parent)) {
        if (parents.isSpeaking(parent)) {
          log.debug(
              "orchestration {}: its parent {} is mid-turn, so its {} waits",
              fresh.id(),
              parent,
              what);
          stillBusy = () -> parents.isSpeaking(parent);
          return;
        }
        String said =
            anEnding ? logStages.deliveryPre(source, Handover.PARENT, utterance) : utterance;
        if (spokenToParent(fresh, parent, said, what)) {
          markDelivered.run();
          if (anEnding) {
            logStages.deliveryPost(source, Handover.PARENT, said);
          }
          return;
        }
        if (parents.isLiveParent(parent)) {
          // The parent is still alive, so it is still where this belongs: left
          // undelivered for the drain when its conversation frees, or the next boot.
          // A parent that is speaking NOW took its turn between the check above and the
          // speak, and refused this as in flight: that turn's free may already have
          // fired and been dropped by the in-flight guard, so the one-shot retry is the
          // only thing that would come back for it — and nothing else would, because a
          // waiting row is what neither recover nor a nudge picks up. A parent that is
          // not speaking refused for a reason a second speak would only meet again (a
          // budget cap now out to its own caller), so nothing is armed.
          if (parents.isSpeaking(parent)) {
            stillBusy = () -> parents.isSpeaking(parent);
          }
          return;
        }
      }
      // Told where it will try first; a busy caller's retry is told again (plan choice 8).
      String said =
          anEnding
              ? logStages.deliveryPre(
                  source,
                  people.routeFor(fresh.callerConversation(), fresh.callerHandle()),
                  utterance)
              : utterance;
      PersonDelivery.Sent sent =
          people.sendFromLog(
              fresh.callerConversation(),
              fresh.callerAgent(),
              fresh.callerHandle(),
              INBOX_KIND,
              said,
              Speaker.orchestration(fresh.id()),
              question == null ? null : about(question),
              source);
      if (sent.result() == Result.BUSY) {
        String conversation = fresh.callerConversation();
        stillBusy = () -> caller.isSpeaking(conversation);
        return;
      }
      if (sent.result() == Result.DELIVERED) {
        markDelivered.run();
        if (anEnding) {
          logStages.deliveryPost(source, sent.destination(), said);
        }
        if (question != null && Handover.INBOX.equals(sent.destination())) {
          settleIfClosed(fresh, question);
        }
        return;
      }
      markDelivered.run();
      log.warn(
          "orchestration {} has no conversation or account to tell; its {} stays on its" + " row",
          fresh.id(),
          what);
    } finally {
      inFlight.remove(id);
      if (retryOnceFree && stillBusy != null && !stillBusy.getAsBoolean()) {
        deliver(id, what, undelivered, text, markDelivered, question, false);
      }
    }
  }

  /**
   * Speaks a child's item into its parent conductor's conversation, waking a parent that was
   * waiting.
   *
   * @return whether the parent heard it; false leaves the caller to decide from the parent's own
   *     state, because a refusal that settled the run has ended the only thing that could hear it
   */
  private boolean spokenToParent(
      OrchestrationRecord child, String parent, String utterance, String what) {
    try {
      parents.speak(parent, utterance);
      return true;
    } catch (Turn.Refused | ArchiveException refused) {
      log.info(
          "orchestration {}: its parent {} refused its {} ({})",
          child.id(),
          parent,
          what,
          refused.getMessage());
      return false;
    }
  }

  private static void guarded(String what, Runnable delivery) {
    try {
      delivery.run();
    } catch (RuntimeException e) {
      log.warn(
          "could not deliver orchestration {}; it stays undelivered and the next drain"
              + " or boot retries it",
          what,
          e);
    }
  }
}
