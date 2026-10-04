package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunActivity;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.todos.StatusMoves;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one thing that writes the orchestration record: it finds the tree an event belongs to, words
 * the event as one line, writes it, and tells the root's account {@code orchestration.recorded} —
 * spec 2026-09-28, the orchestration record §2–§3.
 *
 * <h2>It never throws</h2>
 *
 * <p>Every method runs inside {@link #quietly}: a record that cannot be written is logged and
 * forgotten, and the stage move, the tool call or the ending it was about goes on exactly as it
 * would have. A tool call started while the database is gone answers {@link RunActivity.Call#NONE}.
 *
 * <h2>Where a conversation works is asked once</h2>
 *
 * <p>The turn loop tells every tool call of every run. Which tree a conversation is in never
 * changes after it is opened — a conductor conversation is written in the same transaction as its
 * run, a delegation names its delegator when it is opened — so the answer, including "none", is
 * kept per conversation in a bounded map, and a bot's ordinary run costs one read and no more.
 */
public final class RecordKeeper implements OrchestrationRecorder {

  private static final Logger log = LoggerFactory.getLogger(RecordKeeper.class);

  /** The push's {@code kind}. */
  public static final String RECORDED = "orchestration.recorded";

  /** Who the engine's own events and a conductor's calls are recorded as. */
  static final String CONDUCTOR = "conductor";

  /** How much of a free text a line quotes. */
  static final int MOST_QUOTED = 160;

  private static final int PLACES_KEPT = 4096;

  /** Where a command's time is shown: the server's own zone, like its logs. */
  private final ZoneId zone = ZoneId.systemDefault();

  private final RecordStore records;
  private final OrchestrationStore runs;
  private final Supplier<AccountPushes> pushes;
  private final Map<String, Optional<RecordStore.Place>> places =
      Collections.synchronizedMap(
          new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                Map.Entry<String, Optional<RecordStore.Place>> eldest) {
              return size() > PLACES_KEPT;
            }
          });

  /** Each run's phase, as its phase_started row names it; bounded like {@link #places}. */
  private final Map<String, Optional<String>> phases =
      Collections.synchronizedMap(
          new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> eldest) {
              return size() > PLACES_KEPT;
            }
          });

  /**
   * @param pushes the account pushes, read when a row is written — the push side is built after the
   *     engine, on {@code OrchestrationsConfig.pushChanges}' reason
   */
  public RecordKeeper(
      RecordStore records, OrchestrationStore runs, Supplier<AccountPushes> pushes) {
    this.records = Objects.requireNonNull(records, "records");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.pushes = Objects.requireNonNull(pushes, "pushes");
  }

  // --- the engine ------------------------------------------------------------------------

  @Override
  public void runStarted(OrchestrationRecord run, String request, String phase) {
    quietly(
        "the start of " + run.id(),
        () -> {
          if (run.parent() == null) {
            writeFor(
                run,
                RecordKind.RUN_STARTED,
                run.definitionName() + " started: " + line(request),
                null);
          } else {
            writeFor(
                run,
                RecordKind.PHASE_STARTED,
                "phase "
                    + (phase == null ? run.definitionName() : phase)
                    + " started: "
                    + run.id()
                    + " ("
                    + run.definitionName()
                    + ")",
                phase);
          }
        });
  }

  @Override
  public void runEnded(OrchestrationRecord run) {
    quietly(
        "the end of " + run.id(),
        () -> {
          String ending = run.result() != null ? run.result() : run.failure();
          String said = line(ending);
          String state = run.state().wire();
          if (run.parent() == null) {
            writeFor(
                run,
                RecordKind.RUN_ENDED,
                run.definitionName() + " " + state + (said.isEmpty() ? "" : ": " + said),
                null,
                bodyOf(ending));
            return;
          }
          // A PHASE'S ENDING IS READ WHOLE AS A RUN'S IS: the detail stays its one line, and
          // the whole result or failure is the body when that line is not all of it (V64).
          records
              .treeOfRun(run.id())
              .ifPresent(
                  tree -> {
                    String phase =
                        records
                            .detailOf(tree.root(), run.id(), RecordKind.PHASE_STARTED)
                            .orElse(run.definitionName());
                    write(
                        tree,
                        run.id(),
                        CONDUCTOR,
                        RecordKind.PHASE_ENDED,
                        "phase " + phase + " ended " + state + ": " + run.id(),
                        said.isEmpty() ? null : said,
                        null,
                        bodyOf(ending));
                  });
        });
  }

  @Override
  public void questionAsked(OrchestrationRecord run, String question) {
    quietly(
        "a question of " + run.id(),
        () ->
            writeFor(
                run,
                RecordKind.QUESTION_ASKED,
                "asked: " + line(question),
                null,
                bodyOf(question)));
  }

  @Override
  public void questionAnswered(OrchestrationRecord run, String answer, String author) {
    quietly(
        "an answer to " + run.id(),
        () ->
            writeFor(
                run,
                RecordKind.QUESTION_ANSWERED,
                "answered by " + author + ": " + line(answer),
                null,
                bodyOf(answer)));
  }

  @Override
  public void installSettled(OrchestrationRecord run, String outcome) {
    quietly(
        "an install settled in " + run.id(),
        () ->
            writeFor(
                run,
                RecordKind.QUESTION_ANSWERED,
                "install settled: " + line(outcome),
                null,
                bodyOf(outcome)));
  }

  @Override
  public void stalled(OrchestrationRecord run, String notice) {
    quietly(
        "a stall of " + run.id(),
        () -> writeFor(run, RecordKind.STALLED, line(notice), null, bodyOf(notice)));
  }

  @Override
  public void checkRan(
      OrchestrationRecord run, List<String> argv, Integer exitCode, boolean timedOut) {
    checkRan(run, argv, exitCode, timedOut, null);
  }

  /**
   * The check's line — its command quoted at {@link #MOST_QUOTED}, so the ending after it is never
   * what the store's line length cuts — and the end of its output as the row's body.
   */
  @Override
  public void checkRan(
      OrchestrationRecord run,
      List<String> argv,
      Integer exitCode,
      boolean timedOut,
      String output) {
    quietly(
        "a check of " + run.id(),
        () ->
            writeFor(
                run,
                RecordKind.CHECK_RAN,
                "check `"
                    + line(String.join(" ", argv))
                    + "` "
                    + (timedOut
                        ? TIMED_OUT
                        : exitCode != null && exitCode == 0
                            ? PASSED
                            : "failed (exit " + exitCode + ")"),
                null,
                output));
  }

  /** A {@code check_ran} line's two fixed endings; a failure's is {@link #FAILED}. */
  private static final String PASSED = "passed";

  private static final String TIMED_OUT = "timed out";
  private static final Pattern FAILED = Pattern.compile("` failed \\(exit (-?\\d+|null)\\)$");

  /**
   * The newest {@code check_ran} row of {@code run} itself — never another run's in its tree — read
   * back by the ending {@link #checkRan(OrchestrationRecord, List, Integer, boolean, String)} wrote
   * at its line's end.
   */
  @Override
  public Optional<CheckFacts.Ran> latestCheck(OrchestrationRecord run) {
    try {
      return records
          .treeOfRun(run.id())
          .flatMap(tree -> records.latestOf(tree.root(), run.id(), RecordKind.CHECK_RAN))
          .map(RecordKeeper::ranOf);
    } catch (RuntimeException failed) {
      log.warn(
          "the latest check of {} could not be read; its delegate goes without it",
          run.id(),
          failed);
      return Optional.empty();
    }
  }

  private static CheckFacts.Ran ranOf(RecordRow row) {
    String text = row.text();
    if (text.endsWith("` " + PASSED)) {
      return new CheckFacts.Ran(row.at(), CheckFacts.Result.PASSED, 0, row.body());
    }
    if (text.endsWith("` " + TIMED_OUT)) {
      return new CheckFacts.Ran(row.at(), CheckFacts.Result.TIMED_OUT, null, row.body());
    }
    Matcher failed = FAILED.matcher(text);
    if (failed.find()) {
      String code = failed.group(1);
      return new CheckFacts.Ran(
          row.at(),
          CheckFacts.Result.FAILED,
          "null".equals(code) ? null : Integer.valueOf(code),
          row.body());
    }
    return new CheckFacts.Ran(row.at(), CheckFacts.Result.UNKNOWN, null, row.body());
  }

  @Override
  public void acceptanceRan(OrchestrationRecord run, String command, boolean passed, String why) {
    quietly(
        "an acceptance command of " + run.id(),
        () ->
            writeFor(
                run,
                RecordKind.ACCEPTANCE_RAN,
                "acceptance `" + command + "` " + (passed ? "passed" : "failed"),
                passed ? null : line(why)));
  }

  @Override
  public void concern(OrchestrationRecord run, String actor, String text, String body) {
    quietly(
        "a concern of " + run.id(),
        () ->
            records
                .treeOfRun(run.id())
                .ifPresent(
                    tree ->
                        write(
                            tree,
                            run.id(),
                            actor == null || actor.isBlank() ? CONDUCTOR : actor,
                            RecordKind.CONCERN,
                            line(text),
                            null,
                            null,
                            body == null ? bodyOf(text) : body)));
  }

  @Override
  public void capIncreased(OrchestrationRecord run, String kind, int n) {
    quietly(
        "a project-authorized cap increase in " + run.id(),
        () ->
            writeFor(
                run,
                RecordKind.CAP_CONTINUED,
                "cap increased automatically (" + n + ")",
                Orchestrations.CALL_BUDGET.equals(kind)
                    ? "the model-call budget"
                    : "the turn cap"));
  }

  @Override
  public void capContinued(OrchestrationRecord run, String kind, int n, int most) {
    quietly(
        "a cap continued in " + run.id(),
        () ->
            writeFor(
                run,
                RecordKind.CAP_CONTINUED,
                "cap continued (" + n + "/" + most + ")",
                Orchestrations.CALL_BUDGET.equals(kind)
                    ? "the model-call budget"
                    : Orchestrations.TIME_CAP.equals(kind) ? "the time cap" : "the turn cap"));
  }

  /** How far back {@link #latestMilestone} looks for a run's own line in its tree's record. */
  static final int MILESTONES_READ = 200;

  @Override
  public Optional<String> latestMilestone(OrchestrationRecord run) {
    try {
      return records
          .treeOfRun(run.id())
          .flatMap(
              tree ->
                  records
                      .pageBefore(
                          tree.root(),
                          RecordStore.FROM_THE_END,
                          RecordKind.MILESTONES,
                          MILESTONES_READ)
                      .rows()
                      .stream()
                      .filter(row -> run.id().equals(row.run()))
                      .findFirst()
                      .map(RecordRow::text));
    } catch (RuntimeException failed) {
      log.warn(
          "the latest milestone of {} could not be read; its question goes without",
          run.id(),
          failed);
      return Optional.empty();
    }
  }

  // --- the todo board ---------------------------------------------------------------------

  @Override
  public void moved(String conversation, List<StatusMoves.Move> moved, List<TodoItem> list) {
    quietly(
        "a stage move in " + conversation,
        () -> {
          OrchestrationRecord run = runs.byConductorConversation(conversation).orElse(null);
          if (run == null) {
            return;
          }
          RecordStore.Tree tree = records.treeOfRun(run.id()).orElse(null);
          if (tree == null) {
            return;
          }
          Map<String, String> stageOf =
              list.stream()
                  .filter(item -> item.stageId() != null)
                  .collect(Collectors.toMap(TodoItem::id, TodoItem::stageId, (a, b) -> a));
          for (StatusMoves.Move move : moved) {
            TodoItem after = move.after();
            String label =
                after.stageId() != null
                    ? after.stageId()
                    : after.parent() != null && stageOf.containsKey(after.parent())
                        ? stageOf.get(after.parent()) + " › " + line(after.text())
                        : null;
            if (label == null) {
              continue;
            }
            String summary = after.status() == TodoStatus.DONE ? line(after.summary()) : "";
            write(
                tree,
                run.id(),
                CONDUCTOR,
                RecordKind.STAGE_MOVED,
                label + ": " + move.before().status().wire() + " → " + after.status().wire(),
                summary.isEmpty() ? null : summary);
          }
        });
  }

  // --- approvals --------------------------------------------------------------------------

  @Override
  public void asked(RunApproval approval) {
    quietly(
        "approval " + approval.id(),
        () ->
            placeOf(approval.askedIn())
                .ifPresent(
                    place ->
                        write(
                            place,
                            actorOf(place, approval.agent()),
                            RecordKind.APPROVAL_ASKED,
                            "approval asked to run "
                                + what(approval)
                                + " in "
                                + approval.cwd()
                                + " ("
                                + approval.side()
                                + ")",
                            approval.id(),
                            null,
                            setBody(approval))));
  }

  /**
   * An answer. One the command judge gave (V67) says so, with the judge's words, so the person
   * reading the record sees what ran without them — and, for a set, every command it allowed.
   */
  @Override
  public void answered(RunApproval approval) {
    quietly(
        "approval " + approval.id(),
        () ->
            placeOf(approval.askedIn())
                .ifPresent(
                    place ->
                        write(
                            place,
                            actorOf(place, approval.agent()),
                            RecordKind.APPROVAL_ANSWERED,
                            RunApproval.JUDGE.equals(approval.answeredBy())
                                ? "approval "
                                    + approval.state()
                                    + " by the "
                                    + RunApproval.JUDGE
                                    + ": "
                                    + line(
                                        approval.judged() == null
                                            ? what(approval)
                                            : approval.judged())
                                : "approval "
                                    + approval.state()
                                    + (approval.scope() == null ? "" : " for " + approval.scope())
                                    + ": "
                                    + what(approval),
                            approval.answeredBy(),
                            null,
                            RunApproval.JUDGE.equals(approval.answeredBy())
                                ? commandsBody(approval)
                                : setBody(approval))));
  }

  /** The command an approval is for, quoted — or, for a set, how many. */
  private static String what(RunApproval approval) {
    return approval.isSet()
        ? approval.commands().size() + " acceptance commands"
        : "`" + String.join(" ", approval.argv()) + "`";
  }

  /** A set's commands, one per line, as the row's body; nothing for one command. */
  private static String setBody(RunApproval approval) {
    return approval.isSet() ? commandsBody(approval) : null;
  }

  /** Every command an approval covers, one per line. */
  private static String commandsBody(RunApproval approval) {
    return approval.covered().stream()
        .map(argv -> String.join(" ", argv))
        .collect(Collectors.joining("\n"));
  }

  @Override
  public void consentCovered(
      OrchestrationRecord run, List<String> argv, String approval, String how) {
    quietly(
        "the check of " + run.id(),
        () ->
            writeFor(
                run,
                RecordKind.APPROVAL_ANSWERED,
                "approval allowed: `"
                    + String.join(" ", argv)
                    + "` — covered by "
                    + approval
                    + " ("
                    + how
                    + ")",
                approval));
  }

  // --- the turn loop ----------------------------------------------------------------------

  @Override
  public RunActivity.Call called(
      String conversation, String agent, String tool, Supplier<String> salient) {
    try {
      RecordStore.Place place = placeOf(conversation).orElse(null);
      if (place == null) {
        return RunActivity.Call.NONE;
      }
      String actor = actorOf(place, agent);
      // Asked for only now, inside a tree: the arguments are parsed for the line, and a run
      // outside every tree has no line.
      String said = salient == null ? null : salient.get();
      // The conversation is this call's own — the delegate's, when this is one — so a tool
      // line always names precisely which delegation it belongs to (spec 2026-09-29 §1a).
      int ordinal =
          write(
              place,
              actor,
              RecordKind.TOOL_CALL,
              actor + " · " + tool + (said == null || said.isBlank() ? "" : " " + said),
              null,
              conversation);
      return new RunActivity.Call() {
        @Override
        public void returned(String outcome) {
          returned(outcome, null);
        }

        // The output's tail goes in the line's body, beside its word: what the delegation
        // facts footer reads back for a command that did not end ok.
        @Override
        public void returned(String outcome, String output) {
          quietly(
              "the outcome of " + tool,
              () ->
                  records
                      .settle(place.root(), ordinal, outcome, output)
                      .ifPresent(through -> push(place.root(), place.handle(), through, ordinal)));
        }
      };
    } catch (RuntimeException failed) {
      log.warn(
          "the orchestration record could not take a call to {}; the call goes on", tool, failed);
      return RunActivity.Call.NONE;
    }
  }

  @Override
  public void callFailure(String conversation, String agent, String tool, int warningsLeft) {
    quietly(
        "a call failure",
        () ->
            placeOf(conversation)
                .ifPresent(
                    place -> {
                      String actor = actorOf(place, agent);
                      write(
                          place,
                          actor,
                          RecordKind.CALL_FAILURE,
                          actor
                              + " wrote a call to "
                              + tool
                              + " as text — "
                              + warningsLeft
                              + (warningsLeft == 1 ? " warning" : " warnings")
                              + " left",
                          null);
                    }));
  }

  @Override
  public void callFailureEnded(
      String conversation, String agent, String tool, RunActivity.Unwarned why) {
    quietly(
        "a call failure that ended a turn",
        () ->
            placeOf(conversation)
                .ifPresent(
                    place -> {
                      String actor = actorOf(place, agent);
                      String how =
                          switch (why) {
                            case ALLOWANCE_SPENT -> " kept writing calls to " + tool + " as text";
                            case LAST_STEP ->
                                " wrote a call to " + tool + " as text on its last step";
                            case LAST_BUDGETED_CALL ->
                                " wrote a call to "
                                    + tool
                                    + " as text on its last budgeted model call";
                          };
                      write(
                          place,
                          actor,
                          RecordKind.CALL_FAILURE,
                          actor + how + " — the turn ended",
                          null);
                    }));
  }

  @Override
  public void delegated(
      String conversation, String agent, String callee, String task, String calleeConversation) {
    quietly(
        "a delegation",
        () ->
            placeOf(conversation)
                .ifPresent(
                    place -> {
                      String actor = actorOf(place, agent);
                      // calleeConversation, not conversation: the row's own conversation names the
                      // child
                      // this delegation opened, which is what delegationFacts reads back by (§1a).
                      write(
                          place,
                          actor,
                          RecordKind.DELEGATED,
                          actor + " → " + callee + ": " + line(task),
                          null,
                          calleeConversation);
                    }));
  }

  @Override
  public void delegateReturned(String conversation, String agent, String callee, Outcome outcome) {
    quietly(
        "a delegation's return",
        () ->
            placeOf(conversation)
                .ifPresent(
                    place -> {
                      String actor = actorOf(place, agent);
                      String said = line(outcome.text());
                      write(
                          place,
                          actor,
                          RecordKind.DELEGATE_RETURNED,
                          callee
                              + " → "
                              + actor
                              + ": "
                              + outcome.ending().name().toLowerCase(Locale.ROOT)
                              + (said.isEmpty() ? "" : ": " + said),
                          null);
                    }));
  }

  @Override
  public String delegationFacts(String conversation, String callee) {
    try {
      // conversation is the delegate's own — the very one delegated's calleeConversation
      // named — so the tool lines read back are this delegation's, never an overlapping
      // one to the same agent (spec 2026-09-29 §1a, fixed after review: a run whose first
      // agent_run to `callee` is still AWAITING an approval when a second is dispatched
      // has two live conversations for the same name, and only the conversation itself
      // tells them apart).
      RecordStore.Place place = placeOf(conversation).orElse(null);
      if (place == null) {
        return null;
      }
      return DelegationFacts.footer(
          callee,
          records.toolLinesOf(place.root(), place.run(), conversation),
          zone,
          phaseOf(place.root(), place.run()).orElse(null));
    } catch (RuntimeException failed) {
      log.warn(
          "the facts of {}'s delegation could not be read; the result goes without" + " them",
          callee,
          failed);
      return null;
    }
  }

  // --- shared -----------------------------------------------------------------------------

  private Optional<RecordStore.Place> placeOf(String conversation) {
    if (conversation == null) {
      return Optional.empty();
    }
    Optional<RecordStore.Place> known = places.get(conversation);
    if (known != null) {
      return known;
    }
    Optional<RecordStore.Place> found = records.placeOf(conversation);
    places.put(conversation, found);
    return found;
  }

  private static String actorOf(RecordStore.Place place, String agent) {
    return place.conductor() ? CONDUCTOR : agent;
  }

  private void writeFor(OrchestrationRecord run, RecordKind kind, String text, String detail) {
    writeFor(run, kind, text, detail, null);
  }

  private void writeFor(
      OrchestrationRecord run, RecordKind kind, String text, String detail, String body) {
    records
        .treeOfRun(run.id())
        .ifPresent(tree -> write(tree, run.id(), CONDUCTOR, kind, text, detail, null, body));
  }

  private int write(
      RecordStore.Place place, String actor, RecordKind kind, String text, String detail) {
    return write(place, actor, kind, text, detail, null);
  }

  private int write(
      RecordStore.Place place,
      String actor,
      RecordKind kind,
      String text,
      String detail,
      String conversation) {
    return write(place, actor, kind, text, detail, conversation, null);
  }

  private int write(
      RecordStore.Place place,
      String actor,
      RecordKind kind,
      String text,
      String detail,
      String conversation,
      String body) {
    return write(
        new RecordStore.Tree(place.root(), place.handle()),
        place.run(),
        actor,
        kind,
        text,
        detail,
        conversation,
        body);
  }

  private int write(
      RecordStore.Tree tree,
      String run,
      String actor,
      RecordKind kind,
      String text,
      String detail) {
    return write(tree, run, actor, kind, text, detail, null);
  }

  private int write(
      RecordStore.Tree tree,
      String run,
      String actor,
      RecordKind kind,
      String text,
      String detail,
      String conversation) {
    return write(tree, run, actor, kind, text, detail, conversation, null);
  }

  private int write(
      RecordStore.Tree tree,
      String run,
      String actor,
      RecordKind kind,
      String text,
      String detail,
      String conversation,
      String body) {
    int ordinal =
        records.append(
            tree.root(),
            run,
            actor,
            kind,
            labelled(tree.root(), run, kind, text),
            detail,
            conversation,
            body);
    push(tree.root(), tree.handle(), ordinal, null);
    return ordinal;
  }

  /** {@code text}, with a phase run's phase before it — spec 2026-09-29 §4. */
  private String labelled(String root, String run, RecordKind kind, String text) {
    if (run.equals(root) || kind == RecordKind.PHASE_STARTED || kind == RecordKind.PHASE_ENDED) {
      return text;
    }
    return phaseOf(root, run).map(named -> named + " · " + text).orElse(text);
  }

  /**
   * A run's phase label — its {@code phase_started} row's detail — or empty for a root, or a phase
   * the harness could not name. Read as {@link #placeOf} reads: looked up, then queried and put,
   * never queried inside the map's lock (final review) — {@code computeIfAbsent} on a synchronised
   * map holds it for the whole query, and every record write across every run waited on one run's
   * database round trip.
   */
  private Optional<String> phaseOf(String root, String run) {
    if (run.equals(root)) {
      return Optional.empty();
    }
    Optional<String> known = phases.get(run);
    if (known != null) {
      return known;
    }
    Optional<String> found = records.detailOf(root, run, RecordKind.PHASE_STARTED);
    phases.put(run, found);
    return found;
  }

  /**
   * Tell the root's account the record changed. {@code settled} names the tool line an outcome was
   * just written onto, for a settle, and is left out for a row appended: a reader follows the end
   * by {@code through}, but a line settled far above the end — a long delegation's {@code
   * agent_run}, hundreds of rows back — is one it would otherwise have to re-read the whole way
   * down to find, or never see settle at all.
   */
  private void push(String root, String handle, int through, Integer settled) {
    if (handle == null) {
      return;
    }
    try {
      pushes
          .get()
          .push(
              handle,
              settled == null
                  ? Map.of("kind", RECORDED, "root", root, "through", through)
                  : Map.of("kind", RECORDED, "root", root, "through", through, "settled", settled));
    } catch (RuntimeException failed) {
      log.debug(
          "orchestration {}: its account could not be told the record grew; the"
              + " next push or a read catches it up",
          root,
          failed);
    }
  }

  /**
   * The first non-blank line of {@code text}, stripped and cut at {@link #MOST_QUOTED}; empty for
   * {@code null}.
   */
  static String line(String text) {
    if (text == null) {
      return "";
    }
    String first =
        text.lines().map(String::strip).filter(each -> !each.isEmpty()).findFirst().orElse("");
    return first.length() <= MOST_QUOTED ? first : first.substring(0, MOST_QUOTED - 1) + "…";
  }

  /**
   * The whole of {@code text} as a row's body (V64) — stripped, its own lines kept — when {@link
   * #line} is not all of it: cut at {@link #MOST_QUOTED}, or more than one line. Null when the line
   * already says everything, so a short answer is not written twice.
   */
  static String bodyOf(String text) {
    if (text == null) {
      return null;
    }
    String whole = text.strip();
    return whole.isEmpty() || whole.equals(line(text)) ? null : whole;
  }

  private static void quietly(String what, Runnable step) {
    try {
      step.run();
    } catch (RuntimeException failed) {
      log.warn(
          "the orchestration record could not take {}; the work it describes goes on",
          what,
          failed);
    }
  }
}
