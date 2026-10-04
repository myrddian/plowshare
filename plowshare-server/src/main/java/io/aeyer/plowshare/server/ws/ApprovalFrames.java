package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.approvals.ApprovalDelivery;
import io.aeyer.plowshare.server.approvals.ConductorContinuation;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.ApprovalAnswer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The commands a run asked a person about: listing them, answering one — which continues the turn —
 * and revoking a standing project approval. Frames only; spec 2026-09-15, asking a person, §4.
 */
@Component
public class ApprovalFrames implements FrameArea {

  private static final Logger log = LoggerFactory.getLogger(ApprovalFrames.class);

  private static final Set<String> DECISIONS =
      Set.of(RunApproval.ONCE, RunApproval.CONVERSATION, RunApproval.PROJECT, "deny");

  private final RunApprovalStore store;
  private final ProjectStore projects;
  private final Callers callers;
  private final Runs runs;
  private final Pictures pictures;
  private final ApprovalDelivery delivery;
  private final ObjectProvider<ConductorContinuation> conductors;

  public ApprovalFrames(
      RunApprovalStore store,
      ProjectStore projects,
      Callers callers,
      Runs runs,
      Pictures pictures,
      ApprovalDelivery delivery,
      ObjectProvider<ConductorContinuation> conductors) {
    this.store = Objects.requireNonNull(store, "store");
    this.projects = Objects.requireNonNull(projects, "projects");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.pictures = Objects.requireNonNull(pictures, "pictures");
    this.delivery = Objects.requireNonNull(delivery, "delivery");
    this.conductors = Objects.requireNonNull(conductors, "conductors");
  }

  private java.util.function.Supplier<io.aeyer.plowshare.server.agents.Messaging> messageTransport =
      () -> null;

  @org.springframework.beans.factory.annotation.Autowired
  public void useMessageProvider(
      ObjectProvider<io.aeyer.plowshare.server.agents.Messaging> provider) {
    messageTransport = provider::getIfAvailable;
  }

  public void useMessaging(io.aeyer.plowshare.server.agents.Messaging transport) {
    messageTransport = () -> transport;
  }

  /**
   * Where an answer is told as {@code approval.post} — spec 2026-09-28-hooks-reach-the-log §3. Set
   * by {@code LogStagesConfig}, on slice 1's pattern for every door it reaches; {@link
   * LogStages#NONE} until then.
   */
  private volatile LogStages logStages = LogStages.NONE;

  private io.aeyer.plowshare.server.information.InformationJobs information;

  public void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs) {
    information = inputs;
  }

  private boolean readable(RunApproval approval, String handle) {
    return information == null || information.logAllowed(approval.askedIn(), handle);
  }

  public void useLogStages(LogStages logStages) {
    this.logStages = Objects.requireNonNull(logStages, "logStages");
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(FrameTypes.APPROVAL_LIST, this::list),
        Map.entry(FrameTypes.APPROVAL_ANSWER, this::answer),
        Map.entry(FrameTypes.APPROVAL_REVOKE, this::revoke));
  }

  /**
   * One approval as a front end shows it.
   *
   * @param command the command; empty for a set, which names its own in {@code commands}
   * @param commands an acceptance set's commands, each an argv, answered once for all (V67); null
   *     for one command
   * @param judged the command judge's one line about it, or null
   */
  public record View(
      String id,
      String conversation,
      String askedIn,
      String agent,
      String side,
      List<String> command,
      String cwd,
      String reason,
      String state,
      String scope,
      List<String> prefix,
      List<String> defaultPrefix,
      Instant createdAt,
      Instant answeredAt,
      List<List<String>> commands,
      String judged) {

    static View of(RunApproval a) {
      return new View(
          a.id(),
          a.conversation(),
          a.askedIn(),
          a.agent(),
          a.side(),
          a.argv(),
          a.cwd(),
          a.reason(),
          a.state(),
          a.scope(),
          a.prefix(),
          a.defaultPrefix(),
          a.createdAt(),
          a.answeredAt(),
          a.commands(),
          a.judged());
    }
  }

  public record Listed(List<View> approvals) {}

  /**
   * @param job the continuing turn's job, or null when the conversation was busy
   * @param busy whether the conversation had a turn in flight; the decision stands either way
   */
  public record Answered(String id, String state, String job, boolean busy, String note) {}

  public record Revoked(String id, boolean revoked) {}

  /**
   * @param mine every open question on the asking account, wherever it was raised
   */
  record ListBody(String conversation, String project, Boolean mine) {}

  record AnswerBody(String id, String decision, List<String> prefix) {}

  record RevokeBody(String id) {}

  Outcome list(Map<String, Object> payload, Asking asking) {
    ListBody body = Payloads.as(payload, ListBody.class, FrameTypes.APPROVAL_LIST);
    int named =
        (body.conversation() == null ? 0 : 1)
            + (body.project() == null ? 0 : 1)
            + (Boolean.TRUE.equals(body.mine()) ? 1 : 0);
    if (named != 1) {
      throw new CallerFault(
          FrameTypes.APPROVAL_LIST
              + " needs exactly one of 'conversation' (its"
              + " open questions), 'project' (its standing approvals) or 'mine': true (every"
              + " open question on this account).");
    }
    if (Boolean.TRUE.equals(body.mine())) {
      // THE ACCOUNT'S, WHEREVER THEY WERE RAISED. A question raised under an orchestration is
      // written against a conductor's conversation, which no person has open; listing by
      // conversation could never show it to them.
      String handle = asking.requireHandle(FrameTypes.APPROVAL_LIST);
      return Outcome.ok(
          new Listed(
              store.openFor(handle).stream()
                  .filter(a -> readable(a, handle))
                  .map(View::of)
                  .toList()));
    }
    if (body.conversation() != null) {
      asking.callerForConversation(callers, body.conversation());
      return Outcome.ok(
          new Listed(
              store.open(body.conversation()).stream()
                  .filter(a -> readable(a, asking.handle()))
                  .map(View::of)
                  .toList()));
    }
    // The check agent.run makes for a request naming a project, and no weaker one.
    asking.callerFor(callers, body.project());
    return Outcome.ok(
        new Listed(
            store.standing(projectId(body.project(), FrameTypes.APPROVAL_LIST)).stream()
                .filter(a -> readable(a, asking.handle()))
                .map(View::of)
                .toList()));
  }

  Outcome answer(Map<String, Object> payload, Asking asking) {
    AnswerBody body = Payloads.as(payload, AnswerBody.class, FrameTypes.APPROVAL_ANSWER);
    if (body.id() == null || body.decision() == null || !DECISIONS.contains(body.decision())) {
      throw new CallerFault(
          FrameTypes.APPROVAL_ANSWER
              + " needs 'id', the approval.list id, and"
              + " 'decision': once, conversation, project or deny. Nothing was answered.");
    }
    RunApproval approval = found(body.id(), FrameTypes.APPROVAL_ANSWER);
    if (information != null) information.requireLog(approval.askedIn(), asking.handle());
    if (approval.handle() == null) {
      asking.callerForConversation(callers, approval.conversation());
    } else if (!approval.handle().equals(asking.requireHandle(FrameTypes.APPROVAL_ANSWER))) {
      throw new CallerFault("approval " + approval.id() + " belongs to another account.");
    }
    callers.requireSession(asking.sessionId(), asking.handle());
    callers.requireConversationProject(approval.conversation(), asking.handle());
    String by = asking.handle() != null ? asking.handle() : asking.sessionId();
    boolean deny = "deny".equals(body.decision());
    boolean answered;
    if (deny) {
      answered = store.deny(approval.id(), by);
    } else {
      if (RunApproval.PROJECT.equals(body.decision()) && approval.isSet()) {
        // A set is many commands, and a standing approval covers one prefix: allowing the
        // set once or for the conversation is the answer it takes.
        throw new CallerFault(
            "approval "
                + approval.id()
                + " asks about "
                + approval.commands().size()
                + " commands at once, which no one project"
                + " prefix covers: answer it once, conversation or deny. Nothing was"
                + " answered.");
      }
      if (RunApproval.PROJECT.equals(body.decision())
          && !RunApprovalStore.covers(body.prefix(), approval.argv())) {
        throw new CallerFault(
            FrameTypes.APPROVAL_ANSWER
                + " for a project approval needs"
                + " 'prefix': the leading arguments of "
                + approval.argv()
                + " it covers, at"
                + " least the program. Nothing was answered.");
      }
      answered = store.allow(approval.id(), body.decision(), body.prefix(), by);
    }
    if (!answered) {
      RunApproval now = found(body.id(), FrameTypes.APPROVAL_ANSWER);
      if (RunApproval.DENIED.equals(now.state())
          && RunApproval.SUPERSEDED.equals(now.answeredBy())) {
        // Withdrawn by the harness, not denied by anyone: saying "it is denied" would
        // read as another person's answer to a question that simply no longer exists.
        throw new CallerFault(
            "approval "
                + body.id()
                + " was superseded by a changed"
                + " acceptance section; nothing was changed");
      }
      if (RunApproval.DENIED.equals(now.state())
          && RunApproval.RUN_ENDED.equals(now.answeredBy())) {
        throw new CallerFault(
            "approval "
                + body.id()
                + " was withdrawn: the run it was"
                + " asked for has ended; nothing was changed");
      }
      throw new CallerFault(
          "approval "
              + body.id()
              + " was already answered: it is "
              + now.state()
              + ". Nothing was changed.");
    }
    String utterance = utterance(approval, body.decision(), body.prefix());
    String state = deny ? RunApproval.DENIED : RunApproval.ALLOWED;
    // APPROVAL.POST (spec 2026-09-28-hooks-reach-the-log §3): the store has changed, and the
    // hooks are told before anything is continued. They can notify; they cannot undo it.
    toldApprovalPost(
        approval, deny ? ApprovalAnswer.DENY : ApprovalAnswer.ALLOW, deny ? null : body.decision());
    try {
      // The approval as this answer left it, not as it was read before: the engine speaks
      // an answer to a run's own check in words of its own, which differ by the decision.
      var messaging = messageTransport.get();
      if (messaging != null && messaging.continueApproved(answered(approval, state), utterance)) {
        return Outcome.ok(new Answered(approval.id(), state, null, false, null));
      }
      if (conductors
          .getIfAvailable(() -> ConductorContinuation.NONE)
          .continueApproved(answered(approval, state), utterance)) {
        return Outcome.ok(new Answered(approval.id(), state, null, false, null));
      }
      Runs.Started started =
          approval.handle() == null
              ? runs.start(
                  new Runs.Ask(
                      approval.agent(),
                      utterance,
                      null,
                      asking.sessionId(),
                      approval.conversation(),
                      null,
                      null,
                      List.of(),
                      Speaker.approval(approval.id()),
                      asking.handle()),
                  pictures)
              : runs.continueApproved(
                  approval.conversation(),
                  approval.agent(),
                  utterance,
                  Speaker.approval(approval.id()),
                  outcome -> delivery.continuationEnded(approval, outcome));
      return Outcome.ok(new Answered(approval.id(), state, started.id(), false, null));
    } catch (Turn.Refused busy) {
      // The decision stands: it is recorded, and the next turn in this conversation finds it.
      return Outcome.ok(new Answered(approval.id(), state, null, true, busy.getMessage()));
    }
  }

  Outcome revoke(Map<String, Object> payload, Asking asking) {
    RevokeBody body = Payloads.as(payload, RevokeBody.class, FrameTypes.APPROVAL_REVOKE);
    if (body.id() == null) {
      throw new CallerFault(FrameTypes.APPROVAL_REVOKE + " needs 'id', a standing approval's id.");
    }
    RunApproval approval = found(body.id(), FrameTypes.APPROVAL_REVOKE);
    // Whoever may speak in the conversation the approval was given in may take it back, which
    // is the check an answer makes, and no weaker one.
    if (approval.handle() == null) {
      asking.callerForConversation(callers, approval.conversation());
    } else if (!approval.handle().equals(asking.requireHandle(FrameTypes.APPROVAL_REVOKE))) {
      throw new CallerFault("approval " + approval.id() + " belongs to another account.");
    }
    boolean revoked = store.revoke(body.id());
    if (revoked) {
      toldApprovalPost(approval, ApprovalAnswer.REVOKE, approval.scope());
    }
    return Outcome.ok(new Revoked(body.id(), revoked));
  }

  /**
   * {@code approval.post}, guarded as {@code Compaction} guards its fold stages: the person's
   * answer is already stored, so a {@link LogStages} that throws despite its contract must not
   * leave it stored and the run never continued. It fails open (spec 2026-09-28-hooks-reach-the-log
   * §2.4), with a warning.
   */
  private void toldApprovalPost(RunApproval approval, String decision, String scope) {
    try {
      logStages.approvalAnswered(approval.conversation(), approval.id(), decision, scope);
    } catch (RuntimeException notTold) {
      log.warn(
          "approval {}: the person's {} stands, and approval.post could not be told."
              + " Reason: {}",
          approval.id(),
          decision,
          JobRuntime.describe(notTold));
    }
  }

  /** {@code approval} with the state an answer just gave it; everything else as it was asked. */
  private static RunApproval answered(RunApproval approval, String state) {
    return new RunApproval(
        approval.id(),
        approval.projectId(),
        approval.conversation(),
        approval.askedIn(),
        approval.handle(),
        approval.agent(),
        approval.side(),
        approval.argv(),
        approval.cwd(),
        approval.reason(),
        state,
        approval.scope(),
        approval.prefix(),
        approval.answeredBy(),
        approval.answeredAt(),
        approval.deliveredAt(),
        approval.createdAt(),
        approval.commands(),
        approval.judged());
  }

  /**
   * What the harness says to the model on the person's behalf, recorded as the turn's utterance.
   */
  static String utterance(RunApproval approval, String decision, List<String> prefix) {
    return io.aeyer.plowshare.server.approvals.ApprovalUtterance.forAnswer(
        approval, decision, prefix);
  }

  private RunApproval found(String id, String type) {
    return store
        .find(id)
        .orElseThrow(
            () ->
                new CallerFault(
                    type
                        + ": there is no approval "
                        + id
                        + "; approval.list names the ones that exist."));
  }

  private long projectId(String project, String type) {
    Long id;
    try {
      id = projects.id(project);
    } catch (RuntimeException unusable) {
      id = null;
    }
    if (id == null) {
      throw new CallerFault(type + ": there is no project '" + project + "'.");
    }
    return id;
  }
}
