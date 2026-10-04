package io.aeyer.plowshare.server.harness;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.ModelJson;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.orchestrations.AcceptanceChecker;
import io.aeyer.plowshare.server.orchestrations.Concerns;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The acceptance checker as a model (spec 2026-10-01, the acceptance checker §2): the agent a
 * definition's {@code checker:} names — shipped as {@code acceptance_checker}, on {@code
 * system.checker}, which {@code plowshare.llm.system-overrides.checker} binds ({@code
 * SYSTEM_CHECKER_MODEL}; unset, the system model) — run by the harness as an agent of its own, with
 * its read-only file tools, and nothing spent from the run's budget.
 *
 * <p><b>Read-only whatever its file says.</b> The registry refuses a checker holding more than
 * {@link OrchestrationRegistry#CHECKER_TOOLS}; this keeps only those tools as well, so an operator
 * who edits the file after boot still cannot hand it {@code run} or a write.
 *
 * <p><b>Its answer is data the harness validates.</b> Each pass answers one JSON object; an answer
 * that is not that object, or breaks its shape, is {@link AcceptanceChecker.Unreadable}, which the
 * caller turns into "nothing to say" at plan time and "cannot check — ask the person" at the end.
 * What it is shown — spec.md, plan.md, the conductor's answers — is the run's text, so a tag that
 * would close its block early is neutralised before it is sent.
 */
public final class ModelAcceptanceChecker implements AcceptanceChecker, UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  /** The agent {@code implement_specification} names. */
  public static final String AGENT = "acceptance_checker";

  /** The most characters of one field the harness keeps from an answer. */
  static final int MOST_FIELD = 1500;

  /** What actually runs the agent, so the passes are tested without a model. */
  @FunctionalInterface
  public interface Runner {
    /**
     * Run {@code checker} on {@code task} to its answer.
     *
     * @param session the session whose machine the project's files are on, or null
     * @return its final answer
     * @throws RuntimeException when it ends without one
     */
    String run(AgentDefinition checker, String task, Home home, String session);

    default String run(
        AgentDefinition checker, String task, Home home, String session, UsageAttribution owner) {
      return run(checker, task, home, session);
    }
  }

  private final Function<String, Optional<AgentDefinition>> agents;
  private final Runner runner;

  /**
   * @param agents an agent by name, resolved per pass so an operator's edit reaches the next one
   * @param runner what runs it
   */
  public ModelAcceptanceChecker(Function<String, Optional<AgentDefinition>> agents, Runner runner) {
    this.agents = Objects.requireNonNull(agents, "agents");
    this.runner = Objects.requireNonNull(runner, "runner");
  }

  @Override
  public List<Raised> plan(Brief brief) {
    return parsePlan(run(brief, planTask(brief)));
  }

  @Override
  public Judged judge(Brief brief, Concerns.Concern concern, String reason) {
    return parseJudged(run(brief, judgeTask(brief, concern, reason)));
  }

  @Override
  public End end(Brief brief, List<Concerns.Concern> concerns) {
    return parseEnd(
        run(brief, endTask(brief, concerns)),
        concerns.stream().map(Concerns.Concern::id).collect(Collectors.toSet()));
  }

  private String run(Brief brief, String task) {
    AgentDefinition checker =
        agents
            .apply(brief.checker())
            .orElseThrow(
                () ->
                    new Unreadable(
                        "the agent '"
                            + brief.checker()
                            + "' does not resolve on this"
                            + " server"));
    AgentDefinition readOnly =
        checker.withTools(
            checker.tools().stream()
                .filter(OrchestrationRegistry.CHECKER_TOOLS::contains)
                .toList());
    try {
      var owner = usageOwners.orchestration(brief.run(), UsageAttribution.Operation.REVIEW);
      return owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
          ? runner.run(readOnly, task, brief.home(), brief.session())
          : runner.run(
              readOnly,
              task,
              brief.home(),
              brief.session(),
              owner.forOperation(UsageAttribution.Operation.REVIEW, readOnly.name()));
    } catch (Unreadable unreadable) {
      throw unreadable;
    } catch (RuntimeException failed) {
      throw new Unreadable("the checker gave no answer: " + failed.getMessage(), failed);
    }
  }

  // --- what it is told ------------------------------------------------------------------------

  /** The plan pass's task: where the run's files are, then spec.md and plan.md whole. */
  static String planTask(Brief brief) {
    return "PASS: plan\n\n"
        + where(brief)
        + "\n\nThe specification, with its acceptance section:\n<spec>\n"
        + neutral(text(brief.spec()))
        + "\n</spec>\n\nThe plan:\n<plan>\n"
        + neutral(text(brief.plan()))
        + "\n</plan>";
  }

  /** A judge's task: the concern, what was asked, and the conductor's answer. */
  static String judgeTask(Brief brief, Concerns.Concern concern, String reason) {
    return "PASS: answer\n\n"
        + where(brief)
        + "\n\nYour concern:\n<concern>\n"
        + neutral(describe(concern))
        + "\n</concern>\n\nThe conductor's answer:\n<answer>\n"
        + neutral(text(reason))
        + "\n</answer>";
  }

  /** The end pass's task: every concern with all it went through, and the files. */
  static String endTask(Brief brief, List<Concerns.Concern> concerns) {
    return "PASS: end\n\n"
        + where(brief)
        + "\n\nYour concerns:\n<concerns>\n"
        + neutral(
            concerns.isEmpty()
                ? "(none)"
                : concerns.stream()
                    .map(ModelAcceptanceChecker::describe)
                    .collect(Collectors.joining("\n\n")))
        + "\n</concerns>\n\nThe specification, with its acceptance section:\n<spec>\n"
        + neutral(text(brief.spec()))
        + "\n</spec>\n\nThe plan:\n<plan>\n"
        + neutral(text(brief.plan()))
        + "\n</plan>";
  }

  private static String where(Brief brief) {
    return "The run is "
        + brief.run()
        + ". Its artifacts directory, relative to the project's"
        + " first root, is "
        + (brief.artifactsDir() == null ? "(none)" : brief.artifactsDir())
        + ". The project is yours to read.";
  }

  private static String describe(Concerns.Concern concern) {
    StringBuilder text =
        new StringBuilder(
            concern.id()
                + ": "
                + concern.about()
                + "\nwhy: "
                + concern.why()
                + "\nstate: "
                + concern.state());
    if (concern.question() != null) {
      text.append("\nyou asked: ").append(concern.question());
    }
    if (concern.reason() != null) {
      text.append("\nthe conductor answered: ").append(concern.reason());
    }
    if (concern.objection() != null) {
      text.append("\nyou did not accept it because: ").append(concern.objection());
    }
    if (concern.personAnswer() != null) {
      text.append("\nthe person said: ").append(concern.personAnswer());
    }
    if (concern.verdict() != null) {
      text.append("\nlast verdict: ")
          .append(concern.verdict())
          .append(" — ")
          .append(concern.finding());
    }
    return text.toString();
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? "(not given)" : value.strip();
  }

  private static final Pattern FENCE_TAG =
      Pattern.compile(
          "<(/?)\\s*(spec|plan|concern|concerns|answer)\\s*>", Pattern.CASE_INSENSITIVE);

  /**
   * Every tag a brief's blocks use, with its angle brackets swapped for {@code ‹ ›}: still legible,
   * no longer a tag that closes a block early.
   */
  static String neutral(String text) {
    return FENCE_TAG
        .matcher(text)
        .replaceAll(found -> Matcher.quoteReplacement("‹" + found.group(1) + found.group(2) + "›"));
  }

  // --- what it answers ------------------------------------------------------------------------

  /** {@code {"concerns": [{"about", "why", "ask"?}]}}. */
  static List<Raised> parsePlan(String content) {
    JsonNode node = object(content);
    JsonNode list = node.path("concerns");
    if (!list.isArray()) {
      throw new Unreadable("its answer has no 'concerns' list");
    }
    List<Raised> raised = new ArrayList<>();
    for (JsonNode each : list) {
      raised.add(new Raised(required(each, "about"), required(each, "why"), optional(each, "ask")));
    }
    return List.copyOf(raised);
  }

  /** {@code {"resolved": true|false, "objection"?, "ask"?}}. */
  static Judged parseJudged(String content) {
    JsonNode node = object(content);
    JsonNode resolved = node.path("resolved");
    if (!resolved.isBoolean()) {
      throw new Unreadable("its answer has no true or false 'resolved'");
    }
    if (resolved.asBoolean()) {
      return new Judged(true, null, null);
    }
    return new Judged(false, required(node, "objection"), optional(node, "ask"));
  }

  private static final Set<String> VERDICTS =
      Set.of(Concerns.HOLDS, Concerns.DOES_NOT_HOLD, Concerns.CANNOT_CHECK);

  /**
   * {@code {"verdicts": [{"concern", "verdict", "finding", "person_check"?}], "found": [{"about",
   * "why", "verdict", "finding", "person_check"?}]}}. A verdict for a concern it was not shown is
   * dropped; one it left out is the person's ({@code Checking}).
   */
  static End parseEnd(String content, Set<String> known) {
    JsonNode node = object(content);
    JsonNode verdicts = node.path("verdicts");
    if (!verdicts.isArray()) {
      throw new Unreadable("its answer has no 'verdicts' list");
    }
    List<Verdict> read = new ArrayList<>();
    for (JsonNode each : verdicts) {
      String concern = required(each, "concern");
      if (known.contains(concern)) {
        read.add(new Verdict(concern, verdict(each), required(each, "finding"), personCheck(each)));
      }
    }
    List<Found> found = new ArrayList<>();
    JsonNode raised = node.path("found");
    if (!raised.isMissingNode() && !raised.isNull()) {
      if (!raised.isArray()) {
        throw new Unreadable("its 'found' is not a list");
      }
      for (JsonNode each : raised) {
        found.add(
            new Found(
                required(each, "about"),
                required(each, "why"),
                verdict(each),
                required(each, "finding"),
                personCheck(each)));
      }
    }
    return new End(read, found);
  }

  private static String verdict(JsonNode each) {
    String verdict = required(each, "verdict");
    if (!VERDICTS.contains(verdict)) {
      throw new Unreadable(
          "its verdict '" + verdict + "' is not holds, does_not_hold or" + " cannot_check");
    }
    return verdict;
  }

  /** What the person is to check: required with {@code cannot_check}, ignored otherwise. */
  private static String personCheck(JsonNode each) {
    if (!Concerns.CANNOT_CHECK.equals(each.path("verdict").asText())) {
      return null;
    }
    return required(each, "person_check");
  }

  private static JsonNode object(String content) {
    try {
      return ModelJson.object(content);
    } catch (ModelJson.Unreadable unreadable) {
      throw new Unreadable("its answer could not be read: " + unreadable.getMessage(), unreadable);
    }
  }

  private static String required(JsonNode node, String field) {
    String value = optional(node, field);
    if (value == null) {
      throw new Unreadable("its answer has no '" + field + "' text where one is needed");
    }
    return value;
  }

  private static String optional(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    String text = value.asText().strip();
    return text.length() <= MOST_FIELD ? text : text.substring(0, MOST_FIELD - 1) + "…";
  }
}
