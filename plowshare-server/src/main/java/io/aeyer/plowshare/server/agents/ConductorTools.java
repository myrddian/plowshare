package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code orchestration_ask} and {@code orchestration_finish}: a conductor's two
 * per-run tools, built fresh for the one run they end.
 *
 * <h2>Write first, trip last</h2>
 *
 * <p>Each tool calls its half of {@link ConductorActions} before it ever
 * touches {@link TurnEnd}. That call is the database write — recording the
 * question, or the result — and a refusal from it (this orchestration is not
 * running, a stage is not done) means nothing here happened, so nothing here
 * should end the run either: the tool returns the refusal's own sentence and
 * {@link TurnEnd#request} is never called. Only once the write has succeeded
 * does the tool ask {@link TurnEnd} to end the turn, which is why the "already
 * ending" case below can only be seen after the write already landed.
 *
 * <h2>The turn was already ending</h2>
 *
 * <p>{@link TurnEnd#request} keeps the first ending a batch asks for and tells
 * every later caller so by returning {@code false} — {@code orchestration_ask}
 * and {@code orchestration_finish} are the only two tools that can ask at all,
 * so the only way to see {@code false} is the other one of this pair having
 * already tripped it earlier in the same batch. The write has already
 * happened by then, and undoing it would be a second write racing whatever
 * reads the first one; so the result the model reads back says exactly that:
 * recorded, but not what will end this turn.
 */
public final class ConductorTools {

    public static final String ASK_NAME = "orchestration_ask";
    public static final String FINISH_NAME = "orchestration_finish";
    public static final String CHECK_NAME = "orchestration_check";
    /** The conductor's answer to its acceptance checker's WHY (spec 2026-10-01 §3). */
    public static final String CHECKER_ANSWER_NAME = "checker_answer";

    private ConductorTools() {
    }

    /** Both of a conductor's tools, bound to the one run's actions,
     *  orchestration id and {@link TurnEnd}. No {@code orchestration_check}: see the 4-arg
     *  overload for a run that can place a command. */
    public static List<AgentTool> forRun(
            ConductorActions actions, String orchestration, TurnEnd end) {
        return List.of(
                new Ask(actions, orchestration, end), new Finish(actions, orchestration, end));
    }

    /** As above, plus {@code orchestration_check} when {@code commands} is not null — a run with
     *  no way to place a command has no way to set one either. */
    public static List<AgentTool> forRun(
            ConductorActions actions, String orchestration, TurnEnd end, Commands.Port commands) {
        return forRun(actions, orchestration, end, commands, RunHooks.NONE, null);
    }

    /**
     * As above, the check's question put to the run's {@code approval.pre} first (spec
     * 2026-09-28-hooks-reach-the-log §3).
     *
     * @param hooks the conductor run's in-turn gates
     * @param about the orchestration, for the hook's context; null asks with none
     */
    public static List<AgentTool> forRun(ConductorActions actions, String orchestration,
            TurnEnd end, Commands.Port commands, RunHooks hooks, HookContext.Orchestration about) {
        List<AgentTool> tools = new ArrayList<>(forRun(actions, orchestration, end));
        if (commands != null) {
            tools.add(new Check(actions, orchestration, end, commands, hooks, about));
        }
        return List.copyOf(tools);
    }

    /**
     * {@code checker_answer}, for a run whose definition names an acceptance checker: built on the
     * run, as the other three are, and ending its turn through the same {@link TurnEnd} when the
     * person is asked.
     */
    public static AgentTool checkerAnswer(ConductorActions actions, String orchestration,
            TurnEnd end) {
        return new CheckerAnswer(actions, orchestration, end);
    }

    // --- orchestration_ask ---------------------------------------------------------

    private static final class Ask implements AgentTool {

        private static final String EXAMPLE = "{\"question\": \"Which database should I use?\"}";

        private static final ToolSchema SCHEMA = new ToolSchema(ASK_NAME, ASK_DESCRIPTION,
                ToolArguments.object(askFields(), List.of("question")));

        private static Map<String, Object> askFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("question", ToolArguments.string(
                    "What you cannot go on without the caller's decision on. With 'questions',"
                            + " the lead-in to them."));
            fields.put("questions", questionsSchema());
            return fields;
        }

        /** Spec 2026-09-29-orchestration-studio §2.1, in the shape {@link StructuredQuestions} reads. */
        private static Map<String, Object> questionsSchema() {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("label", ToolArguments.string(
                    "What the option is called, at most 60 characters. An answer names it."));
            option.put("description", ToolArguments.string(
                    "What choosing it means, at most 300 characters."));
            option.put("preview", ToolArguments.string("Optional monospace text shown beside the"
                    + " option on a screen: a draft, a table. At most 4096 characters."));
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("type", "array");
            options.put("description", "2 to 4 options.");
            options.put("items", ToolArguments.object(option, List.of("label", "description")));
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("header", ToolArguments.string(
                    "A short name for the question, at most 12 characters."));
            question.put("question", ToolArguments.string("The question, whole."));
            question.put("multi", ToolArguments.flag(
                    "Whether more than one option may be chosen. False when absent."));
            question.put("options", options);
            Map<String, Object> questions = new LinkedHashMap<>();
            questions.put("type", "array");
            questions.put("description", "Optional: 1 to 4 questions with options, answered"
                    + " together. Whoever answers may always answer in words instead.");
            questions.put("items",
                    ToolArguments.object(question, List.of("header", "question", "options")));
            return questions;
        }

        private final ConductorActions actions;
        private final String orchestration;
        private final TurnEnd end;

        Ask(ConductorActions actions, String orchestration, TurnEnd end) {
            this.actions = Objects.requireNonNull(actions, "actions");
            this.orchestration = Objects.requireNonNull(orchestration, "orchestration");
            this.end = Objects.requireNonNull(end, "end");
        }

        @Override
        public ToolSchema schema() {
            return SCHEMA;
        }

        @Override
        public String run(String argumentsJson, Home home) {
            Objects.requireNonNull(argumentsJson, "argumentsJson");
            Objects.requireNonNull(home, "home");
            try {
                return answer(argumentsJson);
            } catch (BadArguments unusable) {
                return unusable.getMessage();
            }
        }

        private String answer(String argumentsJson) {
            JsonNode args = ToolArguments.parse(argumentsJson, ASK_NAME, EXAMPLE);
            String question = ToolArguments.requireText(args, "question", ASK_NAME,
                    "what you cannot go on without the caller's decision on");
            JsonNode questions = args.get("questions");
            String text = question;
            String structure = null;
            if (questions != null && !questions.isNull()) {
                List<StructuredQuestions.Question> read;
                try {
                    read = StructuredQuestions.read(questions);
                } catch (StructuredQuestions.Refused refused) {
                    return refused.getMessage() + " Nothing was asked.";
                }
                text = StructuredQuestions.render(question, read);
                structure = StructuredQuestions.structure(
                        new StructuredQuestions.Asked(question, read));
            }
            Optional<String> refusal = structure == null ? actions.ask(orchestration, text)
                    : actions.ask(orchestration, text, structure);
            if (refusal.isPresent()) {
                return refusal.get();
            }
            if (!end.request(Outcome.Ending.AWAITING, text)) {
                return "Your question was recorded, but this turn is already ending for another"
                        + " reason; it will be delivered when that is settled.";
            }
            return "Asked. Your turn ends after this step; the answer arrives as your next"
                    + " message.";
        }
    }

    private static final String ASK_DESCRIPTION =
            "Ask whoever started this orchestration a question, and end your turn on it. Use"
                    + " this only when you cannot go on without the caller's decision — anything"
                    + " you can decide yourself is not this. Your turn ends after this call and"
                    + " the answer arrives as your next message."
                    + " To offer choices, add 'questions': each with a short header, the question"
                    + " and 2 to 4 options. The answer arrives saying which were chosen, or in the"
                    + " answerer's own words.";

    // --- orchestration_finish -------------------------------------------------------

    private static final class Finish implements AgentTool {

        private static final String EXAMPLE = "{\"result\": \"the summary of what was done\"}";

        private static final ToolSchema SCHEMA = new ToolSchema(FINISH_NAME, FINISH_DESCRIPTION,
                ToolArguments.object(
                        Map.of("result", ToolArguments.string(
                                "What this orchestration is finishing with.")),
                        List.of("result")));

        private final ConductorActions actions;
        private final String orchestration;
        private final TurnEnd end;

        Finish(ConductorActions actions, String orchestration, TurnEnd end) {
            this.actions = Objects.requireNonNull(actions, "actions");
            this.orchestration = Objects.requireNonNull(orchestration, "orchestration");
            this.end = Objects.requireNonNull(end, "end");
        }

        @Override
        public ToolSchema schema() {
            return SCHEMA;
        }

        @Override
        public String run(String argumentsJson, Home home) {
            Objects.requireNonNull(argumentsJson, "argumentsJson");
            Objects.requireNonNull(home, "home");
            try {
                return answer(argumentsJson);
            } catch (BadArguments unusable) {
                return unusable.getMessage();
            }
        }

        private String answer(String argumentsJson) {
            JsonNode args = ToolArguments.parse(argumentsJson, FINISH_NAME, EXAMPLE);
            String result = ToolArguments.requireText(args, "result", FINISH_NAME,
                    "what this orchestration is finishing with");
            Optional<String> refusal = actions.finish(orchestration, result);
            if (refusal.isPresent()) {
                return refusal.get();
            }
            if (!end.request(Outcome.Ending.ANSWERED, result)) {
                return "Your result was recorded, but this turn is already ending for another"
                        + " reason.";
            }
            return "Finished. The result goes to whoever started this orchestration.";
        }
    }

    private static final String FINISH_DESCRIPTION =
            "Finish this orchestration with a result, and end your turn on it. Refused until"
                    + " every stage is done. The result goes to whoever started this"
                    + " orchestration.";

    // --- orchestration_check -------------------------------------------------------

    private static final class Check implements AgentTool {

        /** The argument's shape, with no program named: the harness assumes no language. */
        private static final String EXAMPLE =
                "{\"command\": [\"<the program>\", \"<its first argument>\", \"<the next>\"]}";

        private static final ToolSchema SCHEMA = new ToolSchema(CHECK_NAME, CHECK_DESCRIPTION,
                ToolArguments.object(
                        Map.of("command", ToolArguments.strings(
                                "The program and its arguments, one item each.")),
                        List.of("command")));

        private final ConductorActions actions;
        private final String orchestration;
        private final TurnEnd end;
        private final Commands.Port commands;
        private final RunHooks hooks;
        private final HookContext.Orchestration about;

        Check(ConductorActions actions, String orchestration, TurnEnd end, Commands.Port commands,
                RunHooks hooks, HookContext.Orchestration about) {
            this.actions = Objects.requireNonNull(actions, "actions");
            this.orchestration = Objects.requireNonNull(orchestration, "orchestration");
            this.end = Objects.requireNonNull(end, "end");
            this.commands = Objects.requireNonNull(commands, "commands");
            this.hooks = Objects.requireNonNull(hooks, "hooks");
            this.about = about;
        }

        @Override
        public ToolSchema schema() {
            return SCHEMA;
        }

        @Override
        public String run(String argumentsJson, Home home) {
            Objects.requireNonNull(argumentsJson, "argumentsJson");
            Objects.requireNonNull(home, "home");
            try {
                return answer(argumentsJson, home);
            } catch (BadArguments | WorkspaceRefusedException unusable) {
                return unusable.getMessage();
            }
        }

        private String answer(String argumentsJson, Home home) {
            JsonNode args = ToolArguments.parse(argumentsJson, CHECK_NAME, EXAMPLE);
            List<String> argv = new ArrayList<>();
            for (JsonNode item : args.path("command")) {
                if (!item.isTextual()) {
                    throw new BadArguments(CHECK_NAME + " was given a 'command' item that is not a"
                            + " string: " + item + ". Every item is one argument, as a string.");
                }
                argv.add(item.asText());
            }
            if (argv.isEmpty()) {
                throw new BadArguments(CHECK_NAME + " needs 'command': the program and its"
                        + " arguments as a list of strings, like " + EXAMPLE + ".");
            }
            Commands.Placed placed = commands.place(home, null, argv);
            String refused = Commands.refusal(argv, placed.side(), placed.allowed(),
                    placed.offBecause());
            if (refused != null) {
                return refused;
            }
            String shown = "`" + String.join(" ", argv) + "`";
            // The run tool's own hooks, as for a run call with this command (final review F1): a
            // deny refuses the check outright, and an ask puts it to a person even on a side whose
            // mode is open — a hook that wants a person to see a command is not overruled by a
            // check being recorded as consented without one.
            Commands.Verdict verdict = commands.judge(placed);
            if (verdict.denied() != null) {
                return "The check " + shown + " was not set: a hook on run refused it: "
                        + verdict.denied();
            }
            String mode = verdict.asked() != null ? EnvironmentFile.ASK : placed.allowed().mode();
            String cwd = placed.cwd().toString();
            // approval.pre, if the engine gets as far as asking a person (spec
            // 2026-09-28-hooks-reach-the-log §3): where the command would run, whose run it is,
            // and the reason the person would be shown. Not asked when consent already given or
            // the command judge covers the check (V67). Its records are parked with the run's
            // gates, so they land after this call's result.
            ConductorActions.BeforeAsking before = reason -> hooks.recorded(hooks.approvalPre(
                    placed.environment(), about, new Approving(argv, cwd, reason, false,
                            RunApproval.SCOPES)));
            return switch (actions.setCheck(orchestration, argv, placed.side(), cwd, mode,
                    verdict.asked() != null, before)) {
                case ConductorActions.CheckSet.Refused r -> r.why();
                case ConductorActions.CheckSet.Set s -> "The check is " + shown + ". The harness"
                        + " runs it on the " + placed.side() + " side in " + placed.cwd()
                        + " whenever a checked stage is marked done, and the stage is done only"
                        + " if it passes." + (s.how() == null ? ""
                                : " Nobody was asked: " + s.how() + ".");
                case ConductorActions.CheckSet.Asking a -> end.request(Outcome.Ending.AWAITING,
                        a.question())
                        ? "Asked the person to allow " + shown + " as this run's check; your turn"
                                + " ends here and their answer arrives as your next message."
                        : "The check is recorded and a person is being asked to allow it; this"
                                + " turn is already ending for another reason.";
            };
        }
    }

    private static final String CHECK_DESCRIPTION =
            "Set this run's check: the command that shows the work is done, such as the test"
                    + " command in test-design.md's Done when. The harness runs it itself whenever"
                    + " a checked stage is marked done, and refuses the move if it fails. Set once;"
                    + " it does not change afterwards, unless a person denies it, in which case set"
                    + " a different one.";

    // --- checker_answer ------------------------------------------------------------

    private static final class CheckerAnswer implements AgentTool {

        private static final String EXAMPLE = "{\"concern\": \"c1\", \"reason\": \"the line"
                + " `run: <command> | runs-for: 5s` in spec.md's acceptance starts it, and"
                + " `check:` line 2 has the person look at the window\"}";

        private static final ToolSchema SCHEMA = new ToolSchema(CHECKER_ANSWER_NAME,
                CHECKER_ANSWER_DESCRIPTION, ToolArguments.object(checkerFields(),
                        List.of("concern", "reason")));

        private static Map<String, Object> checkerFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("concern", ToolArguments.string("The concern's id, as the checker's"
                    + " question named it: c1, c2, …"));
            fields.put("reason", ToolArguments.string("Why it is enough, naming what shows it —"
                    + " the file, the test, the acceptance line — or what you changed so that it"
                    + " is."));
            return fields;
        }

        private final ConductorActions actions;
        private final String orchestration;
        private final TurnEnd end;

        CheckerAnswer(ConductorActions actions, String orchestration, TurnEnd end) {
            this.actions = Objects.requireNonNull(actions, "actions");
            this.orchestration = Objects.requireNonNull(orchestration, "orchestration");
            this.end = Objects.requireNonNull(end, "end");
        }

        @Override
        public ToolSchema schema() {
            return SCHEMA;
        }

        @Override
        public String run(String argumentsJson, Home home) {
            Objects.requireNonNull(argumentsJson, "argumentsJson");
            Objects.requireNonNull(home, "home");
            try {
                JsonNode args = ToolArguments.parse(argumentsJson, CHECKER_ANSWER_NAME, EXAMPLE);
                String concern = ToolArguments.requireText(args, "concern", CHECKER_ANSWER_NAME,
                        "the concern's id, as the checker's question named it");
                String reason = ToolArguments.requireText(args, "reason", CHECKER_ANSWER_NAME,
                        "why it is enough, naming what shows it");
                ConductorActions.CheckerAnswered answered = actions.checkerAnswer(orchestration,
                        concern.strip(), reason.strip(), home);
                if (!answered.personAsked()) {
                    return answered.text();
                }
                return end.request(Outcome.Ending.AWAITING, answered.text())
                        ? answered.text() + " Your turn ends after this step; the person's answer"
                                + " arrives as your next message."
                        : answered.text() + " The person is asked; this turn is already ending"
                                + " for another reason.";
            } catch (BadArguments unusable) {
                return unusable.getMessage();
            }
        }
    }

    private static final String CHECKER_ANSWER_DESCRIPTION =
            "Answer one of the acceptance checker's questions. The checker is a harness agent that"
                    + " holds this run to its acceptance and asks you why something is enough;"
                    + " until each of its questions is answered no stage moves. It does not have"
                    + " to accept your reason: it may ask again, and one it still does not accept"
                    + " goes to the person.";
}
