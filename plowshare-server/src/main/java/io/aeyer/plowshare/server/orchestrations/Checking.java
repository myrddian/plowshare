package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.ConductorTools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The harness's side of the acceptance checker (spec 2026-10-01, the acceptance checker §3): it
 * runs the checker at the plan pass and the end pass, puts its WHY questions to the conductor and
 * the conductor's answers back to it, keeps the list of concerns, and says what goes to the person.
 * The checker gives verdicts; this class decides what each verdict does, and records all of it —
 * every concern, every WHY and answer, every verdict — as {@code concern} lines of the run's
 * record, from the harness's own store.
 *
 * <p><b>The checker's words are data.</b> What it raised, asked and found reaches the conductor and
 * the person fenced and labelled as the checker's; the only instruction is the harness's own
 * sentence around it. A checker whose answer is not its JSON has nothing to say at the plan pass,
 * and at the end pass cannot check anything: everything it was to check goes to the person. The
 * person's acceptance is never skipped for want of a checker.
 */
public final class Checking {

  private static final Logger log = LoggerFactory.getLogger(Checking.class);

  /** The most concerns one pass may raise: a list longer than this is a checker not choosing. */
  static final int MOST_RAISED = 12;

  /** Who the conductor's answers are recorded as said by. */
  static final String CONDUCTOR = "conductor";

  /** Who the person's answers are recorded as said by. */
  static final String PERSON = "person";

  /** The answers that accept, lower-cased: for the whole of a concerns question or one line. */
  static final Set<String> ACCEPTS = Set.of("accept", "accepted", "y", "yes", "ok");

  private final Concerns concerns;
  private final AcceptanceChecker checker;
  private final OrchestrationRecorder recorder;

  public Checking(Concerns concerns, AcceptanceChecker checker, OrchestrationRecorder recorder) {
    this.concerns = Objects.requireNonNull(concerns, "concerns");
    this.checker = Objects.requireNonNull(checker, "checker");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
  }

  /** The concerns store, which the acceptance gate reads too. */
  public Concerns concerns() {
    return concerns;
  }

  // --- the plan pass --------------------------------------------------------------------------

  /**
   * Whether the plan pass has already raised something for this run: it runs once it has. A pass
   * that raised nothing runs again at the next plan move, since nothing is lost by asking again.
   */
  public boolean plannedAlready(String run) {
    return concerns.of(run).stream().anyMatch(each -> Concerns.AT_PLAN.equals(each.raised()));
  }

  /**
   * The plan pass: the checker reads the goal, spec.md with its acceptance and plan.md, and raises
   * its concerns; each WHY it asks is put to the conductor.
   *
   * @return what the conductor is told with its move: the WHY questions, or nothing
   */
  public List<String> planPass(OrchestrationRecord run, AcceptanceChecker.Brief brief) {
    List<AcceptanceChecker.Raised> raised;
    try {
      raised = checker.plan(brief);
    } catch (RuntimeException unreadable) {
      log.warn(
          "orchestration {}: the acceptance checker's plan pass could not be read; it"
              + " has nothing to say",
          run.id(),
          unreadable);
      recorder.concern(
          run,
          brief.checker(),
          "the checker's plan pass gave no answer that" + " could be read, so it raised nothing",
          reason(unreadable));
      return List.of();
    }
    if (raised.isEmpty()) {
      recorder.concern(run, brief.checker(), "the checker raised no concern at plan", null);
      return List.of();
    }
    List<Concerns.Concern> asked = new ArrayList<>();
    for (AcceptanceChecker.Raised each : raised.subList(0, Math.min(MOST_RAISED, raised.size()))) {
      Concerns.Concern concern =
          concerns.raise(run.id(), each.about(), each.why(), Concerns.AT_PLAN, each.question());
      recorder.concern(
          run,
          brief.checker(),
          "concern " + concern.id() + " raised: " + concern.about(),
          "Concern "
              + concern.id()
              + ": "
              + concern.about()
              + "\nWhy it is a concern: "
              + concern.why());
      if (concern.question() != null) {
        asked.add(concern);
        recorder.concern(
            run,
            brief.checker(),
            "the checker asks the conductor about " + concern.id() + ": " + concern.question(),
            null);
      }
    }
    return asked.isEmpty() ? List.of() : List.of(whyQuestions(asked));
  }

  /** The concerns the conductor has yet to answer, in the order raised. */
  public List<Concerns.Concern> waiting(String run) {
    return concerns.of(run).stream().filter(each -> Concerns.ASKED.equals(each.state())).toList();
  }

  /**
   * What the conductor is told while it owes the checker answers: every open WHY, whole, and the
   * tool that answers it. Carried by the plan move's result, by a move refused while they are open,
   * and by a nudge — so a question lost with a refused batch is never lost to it.
   */
  public static String whyQuestions(List<Concerns.Concern> asked) {
    StringBuilder text =
        new StringBuilder(
            "The acceptance checker — a harness agent that holds"
                + " this run to its acceptance, assuming the product does not work until"
                + " something outside a model shows it does — asks you why. Answer each with "
                + ConductorTools.CHECKER_ANSWER_NAME
                + " {\"concern\": \"<id>\", \"reason\":"
                + " \"<why it is enough, naming the file, test or acceptance line that shows"
                + " it>\"}; no stage moves until every one is answered. It does not have to accept"
                + " a reason: one it still does not accept after "
                + Concerns.MOST_ROUNDS
                + " questions goes to the person. If the concern is right, say what you changed"
                + " — a `run:` line that observes it, or a `check:` line for the person — and"
                + " change it first.");
    for (Concerns.Concern concern : asked) {
      text.append("\n\n")
          .append(
              Utterances.fence(
                  concern.id()
                      + ", the checker's (round "
                      + concern.rounds()
                      + " of "
                      + Concerns.MOST_ROUNDS
                      + ")",
                  "About: "
                      + concern.about()
                      + "\nWhy it is a concern: "
                      + concern.why()
                      + (concern.objection() == null
                          ? ""
                          : "\nWhy your last answer was not enough: " + concern.objection())
                      + "\nIt asks: "
                      + concern.question()));
    }
    return text.toString();
  }

  // --- the conductor's answers ----------------------------------------------------------------

  /**
   * What answering a WHY came to.
   *
   * @param text what the conductor's tool returns, from the store as it now is
   * @param personNeeded whether every WHY is now answered and some concern is left for the person:
   *     the caller asks them, and the conductor's turn ends
   */
  public record Answered(String text, boolean personNeeded) {}

  /**
   * The conductor's answer to a WHY, shown to the checker, which does not have to accept it:
   * resolved, asked again (at most {@link Concerns#MOST_ROUNDS} questions in all), or — its rounds
   * spent — the person's.
   */
  public Answered answer(
      OrchestrationRecord run, AcceptanceChecker.Brief brief, String id, String reason) {
    Concerns.Concern concern = concerns.find(run.id(), id).orElse(null);
    if (concern == null || !Concerns.ASKED.equals(concern.state())) {
      List<Concerns.Concern> waiting = waiting(run.id());
      String why =
          concern == null
              ? "there is no concern " + id + " in this run"
              : "concern "
                  + id
                  + " is not waiting on an answer (it is "
                  + concern.state().replace('_', ' ')
                  + ")";
      return new Answered(
          "Nothing was recorded: "
              + why
              + ". "
              + (waiting.isEmpty()
                  ? "The checker is waiting on no answer."
                  : "It is waiting on " + ids(waiting) + "."),
          false);
    }
    concerns.answered(run.id(), id, reason);
    recorder.concern(run, CONDUCTOR, "the conductor answers " + id + ": " + reason, null);
    Concerns.Concern answered = concerns.find(run.id(), id).orElse(concern);
    AcceptanceChecker.Judged judged;
    try {
      judged = checker.judge(brief, answered, reason);
    } catch (RuntimeException unreadable) {
      log.warn(
          "orchestration {}: the acceptance checker gave no verdict on the answer to {};"
              + " it stands as answered",
          run.id(),
          id,
          unreadable);
      recorder.concern(
          run,
          brief.checker(),
          "the checker gave no verdict on the answer to "
              + id
              + "; it is checked against the finished project at the end",
          reason(unreadable));
      return settled(
          run,
          "Recorded. The checker gave no verdict on it, so "
              + id
              + " stands"
              + " as answered and is checked against the finished project before"
              + " acceptance.");
    }
    if (judged.resolved()) {
      concerns.resolved(run.id(), id);
      recorder.concern(
          run,
          brief.checker(),
          id + " resolved: the checker accepts the" + " conductor's reason",
          null);
      return settled(run, "The checker accepts your reason: " + id + " is resolved.");
    }
    String objection =
        judged.objection() == null || judged.objection().isBlank()
            ? "it gave no reason"
            : judged.objection().strip();
    if (answered.rounds() < Concerns.MOST_ROUNDS
        && judged.question() != null
        && !judged.question().isBlank()) {
      concerns.askedAgain(run.id(), id, objection, judged.question().strip());
      recorder.concern(
          run,
          brief.checker(),
          id + " not accepted: " + objection,
          "Why the checker does not accept the conductor's reason: "
              + objection
              + "\nIt asks again: "
              + judged.question().strip());
      Concerns.Concern again = concerns.find(run.id(), id).orElse(answered);
      return new Answered(
          "The checker does not accept it, and asks again.\n\n" + whyQuestions(List.of(again)),
          false);
    }
    concerns.forThePerson(run.id(), id, objection);
    recorder.concern(
        run,
        brief.checker(),
        id + " goes to the person: the checker does not" + " accept the conductor's reason",
        "Why the checker does not accept it: " + objection);
    return settled(
        run,
        "The checker does not accept it: "
            + objection
            + ". Its questions"
            + " about "
            + id
            + " are spent, so it goes to the person.");
  }

  /** {@code said}, then what is still owed — or, with nothing owed, whether the person is next. */
  private Answered settled(OrchestrationRecord run, String said) {
    List<Concerns.Concern> waiting = waiting(run.id());
    if (!waiting.isEmpty()) {
      return new Answered(said + " Still waiting on your answer to " + ids(waiting) + ".", false);
    }
    boolean person = concerns.of(run.id()).stream().anyMatch(Concerns.Concern::forThePersonAtPlan);
    return new Answered(said + (person ? "" : " Every question is answered; carry on."), person);
  }

  /** The concerns waiting on the person at plan time. */
  public List<Concerns.Concern> forThePerson(String run) {
    return concerns.of(run).stream().filter(Concerns.Concern::forThePersonAtPlan).toList();
  }

  /**
   * The person-only question for the concerns the checker could not resolve with the conductor:
   * each with what it is about, the conductor's reason and why the checker does not accept it.
   */
  public static String concernsQuestion(
      OrchestrationRecord run, List<Concerns.Concern> unresolved) {
    String id = run.id();
    StringBuilder text =
        new StringBuilder(
            "`"
                + id
                + "` (`"
                + run.definitionName()
                + "`)'s"
                + " acceptance checker could not resolve "
                + (unresolved.size() == 1
                    ? "this concern"
                    : "these " + unresolved.size() + " concerns")
                + " with the conductor:");
    for (Concerns.Concern concern : unresolved) {
      text.append("\n\n")
          .append(concern.id())
          .append(" — ")
          .append(concern.about())
          .append("\n  Why it is a concern: ")
          .append(concern.why())
          .append("\n  The conductor's reason: ")
          .append(concern.reason() == null ? "(none given)" : concern.reason())
          .append("\n  Why the checker does not accept it: ")
          .append(concern.objection() == null ? "(none given)" : concern.objection());
    }
    text.append("\n\n`/answer ")
        .append(id)
        .append(
            " accept` accepts the conductor's reason"
                + " for all of them. To answer each, one per line: `")
        .append(unresolved.get(0).id())
        .append(": accept`, or `")
        .append(unresolved.get(0).id())
        .append(
            ": <what to change>`. Any other answer is"
                + " your direction for all of them. A direction goes to the conductor, and the"
                + " checker checks the concern against the finished project before acceptance.");
    return text.toString();
  }

  private static final Pattern PER_CONCERN =
      Pattern.compile(
          "^\\s*[-*]?\\s*`?(c[1-9][0-9]*)`?\\s*[:\\-—]\\s*(.*)$", Pattern.CASE_INSENSITIVE);

  /**
   * The person's answer to the concerns question, recorded per concern: accepted resolves it; a
   * direction leaves it open, to be checked at the end, and is passed to the conductor.
   *
   * @return what the conductor is told
   */
  public String personAnswered(OrchestrationRecord run, String answer) {
    List<Concerns.Concern> unresolved = forThePerson(run.id());
    Map<String, String> each = perConcern(answer, unresolved);
    StringBuilder told =
        new StringBuilder("The person answered the acceptance checker's" + " concerns:");
    for (Concerns.Concern concern : unresolved) {
      String said = each.get(concern.id());
      boolean accepted = said != null && ACCEPTS.contains(plain(said));
      concerns.personAnswered(run.id(), concern.id(), said, accepted);
      recorder.concern(
          run,
          PERSON,
          concern.id()
              + (accepted
                  ? " resolved: the person accepts the conductor's reason"
                  : said == null
                      ? " left open: the person said nothing about it"
                      : " directed by the person: " + said),
          null);
      told.append("\n- ")
          .append(concern.id())
          .append(" (")
          .append(concern.about())
          .append("): ")
          .append(
              accepted
                  ? "they accept your reason."
                  : said == null
                      ? "they said nothing about it; it stays open."
                      : "their direction, which is yours to carry out:");
      if (!accepted && said != null) {
        told.append("\n").append(Utterances.fence("the person's direction", said));
      }
    }
    told.append(
        "\nThe checker checks every concern against the finished project before" + " acceptance.");
    return told.toString();
  }

  /** Each concern's answer: per line when the person answered per concern, else the whole. */
  static Map<String, String> perConcern(String answer, List<Concerns.Concern> unresolved) {
    String given = answer == null ? "" : answer.strip();
    Map<String, String> each = new LinkedHashMap<>();
    Set<String> known = unresolved.stream().map(Concerns.Concern::id).collect(Collectors.toSet());
    for (String line : given.lines().toList()) {
      Matcher matched = PER_CONCERN.matcher(line);
      if (matched.matches()) {
        String id = matched.group(1).toLowerCase(Locale.ROOT);
        if (known.contains(id) && !matched.group(2).isBlank()) {
          each.put(id, matched.group(2).strip());
        }
      }
    }
    if (each.isEmpty() && !given.isEmpty()) {
      for (Concerns.Concern concern : unresolved) {
        each.put(concern.id(), given);
      }
    }
    return each;
  }

  /** Nobody can be asked: the concerns stay open, and are checked at the end. */
  public void nobodyToAsk(OrchestrationRecord run, String checkerName) {
    for (Concerns.Concern concern : forThePerson(run.id())) {
      concerns.personAnswered(run.id(), concern.id(), null, false);
      recorder.concern(
          run,
          checkerName,
          concern.id()
              + " stays open: nobody can be asked,"
              + " so it is checked against the finished project at the end",
          null);
    }
  }

  // --- the end pass ---------------------------------------------------------------------------

  /**
   * The end pass: every concern, against the finished project, read-only and hostile. A concern
   * that holds is checked; one that does not is checked and returned, and refuses the run's
   * acceptance; one the checker cannot check is the person's, beside the {@code check:} lines. The
   * checker may raise concerns of its own here, each with its verdict.
   *
   * @return the concerns that do not hold, as the store now has them
   */
  public List<Concerns.Concern> endPass(OrchestrationRecord run, AcceptanceChecker.Brief brief) {
    List<Concerns.Concern> list = concerns.of(run.id());
    AcceptanceChecker.End end;
    try {
      end = checker.end(brief, list);
    } catch (RuntimeException unreadable) {
      log.warn(
          "orchestration {}: the acceptance checker's end pass could not be read; what"
              + " it was to check goes to the person",
          run.id(),
          unreadable);
      recorder.concern(
          run,
          brief.checker(),
          "the checker's end pass gave no answer that"
              + " could be read, so every concern goes to the person",
          reason(unreadable));
      for (Concerns.Concern concern : list) {
        toThePerson(run, brief.checker(), concern, "the checker could not check it", null);
      }
      return List.of();
    }
    Map<String, AcceptanceChecker.Verdict> byId = new LinkedHashMap<>();
    for (AcceptanceChecker.Verdict verdict : end.verdicts()) {
      byId.putIfAbsent(verdict.concern(), verdict);
    }
    for (Concerns.Concern concern : list) {
      AcceptanceChecker.Verdict verdict = byId.get(concern.id());
      if (verdict == null) {
        toThePerson(run, brief.checker(), concern, "the checker gave no verdict on it", null);
      } else {
        apply(
            run,
            brief.checker(),
            concern,
            verdict.verdict(),
            verdict.finding(),
            verdict.personCheck());
      }
    }
    for (AcceptanceChecker.Found found :
        end.found().subList(0, Math.min(MOST_RAISED, end.found().size()))) {
      Concerns.Concern concern =
          concerns.raise(run.id(), found.about(), found.why(), Concerns.AT_END, null);
      recorder.concern(
          run,
          brief.checker(),
          "concern " + concern.id() + " raised at the" + " end: " + concern.about(),
          "Concern "
              + concern.id()
              + ": "
              + concern.about()
              + "\nWhy it is a concern: "
              + concern.why());
      apply(run, brief.checker(), concern, found.verdict(), found.finding(), found.personCheck());
    }
    if (list.isEmpty() && end.found().isEmpty()) {
      recorder.concern(
          run, brief.checker(), "the checker had no concern to check at the" + " end", null);
    }
    return concerns.of(run.id()).stream().filter(Concerns.Concern::doesNotHold).toList();
  }

  private void apply(
      OrchestrationRecord run,
      String checkerName,
      Concerns.Concern concern,
      String verdict,
      String finding,
      String personCheck) {
    String found = finding == null || finding.isBlank() ? "(no finding given)" : finding.strip();
    if (Concerns.HOLDS.equals(verdict) || Concerns.DOES_NOT_HOLD.equals(verdict)) {
      concerns.checked(run.id(), concern.id(), verdict, found, null);
      recorder.concern(
          run,
          checkerName,
          concern.id() + (Concerns.HOLDS.equals(verdict) ? " holds: " : " does not hold: ") + found,
          null);
    } else {
      toThePerson(run, checkerName, concern, found, personCheck);
    }
  }

  private void toThePerson(
      OrchestrationRecord run,
      String checkerName,
      Concerns.Concern concern,
      String finding,
      String personCheck) {
    String check =
        personCheck == null || personCheck.isBlank()
            ? "check that this is settled: " + concern.about() + " (" + concern.why() + ")"
            : personCheck.strip();
    concerns.checked(run.id(), concern.id(), Concerns.CANNOT_CHECK, finding, check);
    recorder.concern(
        run, checkerName, concern.id() + " goes to the person at acceptance: " + check, null);
  }

  /** The concerns the end pass left for the person, to ask beside the {@code check:} lines. */
  public List<Concerns.Concern> forThePersonAtAcceptance(String run) {
    return concerns.of(run).stream().filter(Concerns.Concern::forThePersonAtAcceptance).toList();
  }

  /**
   * What the conductor is told with its move into acceptance when concerns do not hold: each
   * finding, fenced as the checker's, and the way back.
   *
   * @param back the stage acceptance may return to, or null for none
   * @param returnsLeft the returns the run has left
   */
  public static String findings(List<Concerns.Concern> failing, String back, int returnsLeft) {
    StringBuilder text =
        new StringBuilder(
            "The acceptance checker checked its concerns"
                + " against the finished project, and "
                + (failing.size() == 1 ? "one does" : failing.size() + " do")
                + " not hold, so the acceptance commands were"
                + " not run and `acceptance` cannot be marked done.");
    for (Concerns.Concern concern : failing) {
      text.append("\n\n")
          .append(
              Utterances.fence(
                  concern.id() + ", the checker's finding",
                  "About: "
                      + concern.about()
                      + "\nWhy it is a concern: "
                      + concern.why()
                      + "\nFound: "
                      + concern.finding()));
    }
    text.append("\n\n")
        .append(
            back == null || returnsLeft <= 0
                ? "This run has no return left to have them fixed: orchestration_ask your"
                    + " caller with these findings."
                : "Return to `"
                    + back
                    + "` (move it from done to in_progress) and have them"
                    + " fixed; the checker checks again when `acceptance` is started again.");
    return text.toString();
  }

  private static String ids(List<Concerns.Concern> list) {
    return list.stream().map(Concerns.Concern::id).collect(Collectors.joining(", "));
  }

  private static String plain(String said) {
    return said.strip().toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "");
  }

  private static String reason(RuntimeException failed) {
    return failed.getMessage() == null ? failed.toString() : failed.getMessage();
  }
}
