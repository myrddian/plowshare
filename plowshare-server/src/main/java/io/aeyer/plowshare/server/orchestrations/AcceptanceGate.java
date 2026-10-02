package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.llm.accounting.*;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Commands;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunHooks;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acceptance (spec 2026-09-29 §1b), in front of the board. The stage marked {@code acceptance:
 * written} is done only once spec.md's {@code ## Acceptance} parses and its commands are
 * registered — asked of the person unless the side is open. The stage marked {@code acceptance:
 * required} is done only once every registered command, run by the harness as the check runs,
 * passes; a failure refuses the move with each failing command's output and names the stage to
 * return to.
 *
 * <h2>What no command can observe is the person's (spec 2026-10-01, the acceptance checker §1)</h2>
 *
 * <p>Measured 2026-09-30, orc_3190A00C6035D3B5: 51 tests green, nine of nine commands passed, and
 * the game's main.py was still a no-op. A {@code run:} line with {@code runs-for:} passes only when
 * the program is still running that long in, so a no-op entry point fails it on its early exit.
 * And once every {@code run:} line has passed, the section's {@code check:} lines — with whatever
 * the run's acceptance checker could not check itself — are put to the person in one question
 * only the person may answer ({@link Person#askProduct}): accept, and the stage is done; anything
 * else is their notes, and the run goes back. A section with no {@code check:} line and nothing
 * the checker left for the person is done without asking. A concern the checker found not to hold
 * keeps the stage from being done at all.
 *
 * <p>The sceptical verifier that once judged the written stage (V65's {@code uncovered}) is
 * retired: where a definition names one, the acceptance checker takes its place.
 *
 * <h2>Why the harness runs them, and not the conductor</h2>
 *
 * <p>Measured, orc_31893856D8F462A1: a run finished with 74 tests green and every review clean
 * while {@code python -m rpg.main} did nothing — main.py had no {@code __main__} guard, so the
 * module was imported, defined {@code main()} and exited 0 having printed nothing. Every signal
 * the run had was a model's report of its own work. A command written into the spec before any
 * code, run here on the done move exactly as a person would type it, is the one signal no model
 * in the run can talk past.
 *
 * <h2>The check's machinery, not a second copy of it</h2>
 *
 * <p>Which op is the done move is {@link DoneMoves}, shared with {@link StageChecks}; a command is
 * placed, refused by the environment, judged by the run tool's hooks and run through the same
 * {@link Commands.Port}; its consent is a {@link RunApproval} asked through the same {@link
 * CheckConsent}, recorded {@link OrchestrationChecks#OPEN} on an open side as {@code setCheck}
 * records a check. Each rule the check learned in review — consent is for the side it was given
 * on (F3), a hook's deny binds every run (F1) — binds these commands by the same code path.
 */
public final class AcceptanceGate implements TodoTools.BeforeApply {
    private UsageAttribution usage = UsageAttribution.LEGACY;
    /** Frozen by the runtime when this per-run gate is constructed. */
    public AcceptanceGate withUsage(UsageAttribution owner) {
        java.util.Objects.requireNonNull(owner);
        if (usage.status() != UsageAttribution.Status.LEGACY_UNATTRIBUTED && !usage.equals(owner)) {
            throw new IllegalStateException("Acceptance gate cannot change its owner");
        }
        usage = owner;
        return this;
    }


    private static final Logger log = LoggerFactory.getLogger(AcceptanceGate.class);
    private static final int TAIL_LINES = 40;

    /** How much of a failure the person's failed-checks question shows (V69). */
    static final int FAILURE_LINES = 22;

    /** What the person is told an acceptance command's approval is for, before what it is
     *  given and must show ({@link #why}). */
    static final String WHY = "one of this run's acceptance commands: the harness runs it when"
            + " the acceptance stage is marked done";
    /**
     * What the person is told a whole set's one approval is for (V67), before each command and
     * what it is given and must show, one per line ({@link #setWhy}). Measured 2026-09-29,
     * orc_318DFD3782228160: fifteen approvals, one per command, and the person's "Why do I get
     * approval bombed?"
     */
    static final String SET_WHY = "this run's acceptance commands: the harness runs them when the"
            + " acceptance stage is marked done";
    /** Who withdrew an approval a changed section no longer uses. */
    static final String SUPERSEDED = RunApproval.SUPERSEDED;
    /** Who withdrew an approval asked for a run that ended while the gate asked it. */
    static final String RUN_ENDED = RunApproval.RUN_ENDED;
    /** How much of the approved requirements a refusal quotes back before it says it cut them. */
    private static final int REQUIREMENTS_SHOWN = 4096;
    /** How much of a command's input its approval quotes before counting the rest. */
    private static final int INPUT_SHOWN = 500;

    /** Replaces a run's registered set — {@link OrchestrationAcceptance#replace}. */
    @FunctionalInterface
    public interface Register {
        /**
         * @param run the run's id
         * @param requirements spec.md outside its acceptance section, which the set is held to
         * @param set its commands, in order
         */
        void replace(String run, String requirements, List<OrchestrationAcceptance.Registered> set);
    }

    /**
     * The person, whose acceptance stage this partly is: a run whose acceptance commands keep
     * failing asks them whether it goes on (V69), and a run whose section has {@code check:} lines
     * — or concerns its checker could not check — asks them to check the product (spec 2026-10-01
     * §1). Every method answers "nobody to ask" by default.
     */
    public interface Person {
        /** Nobody to ask. */
        Person NONE = new Person() {
        };

        /**
         * One more failure of the run's acceptance commands, counted with its check's (V69,
         * {@link CheckFailures}): at the project's {@code failed-checks} the person is asked
         * whether the run goes on. Nobody to ask by default.
         *
         * @return whether the person was asked, in which case the conductor's turn ends
         */
        default boolean checkFailed(String run, String what, String output) {
            return false;
        }

        /** @return the digest of the product check the person accepted, or empty */
        default Optional<String> productAccepted(String run) {
            return Optional.empty();
        }

        /**
         * Put the product check to the person: a question only they may answer.
         *
         * @param digest {@link #productDigest} of what the question shows; their {@code accept}
         *     accepts exactly that
         * @return whether they were asked — false when nobody can be
         */
        default boolean askProduct(String run, String digest, String question) {
            return false;
        }
    }

    private final Function<String, Optional<OrchestrationRecord>> runByConversation;
    private final Function<String, Optional<String>> artifactsDir;
    private final TodoLists todos;
    private final Commands.Port commands;
    private final Function<String, List<OrchestrationAcceptance.Registered>> registered;
    private final Function<String, Optional<String>> requirements;
    private final Register register;
    private final Function<String, Optional<RunApproval>> approvals;
    private final CheckConsent consent;
    private final Function<String, List<Concerns.Concern>> concerns;
    private final OrchestrationRecorder recorder;
    private final TurnEnd end;
    private final BiConsumer<String, String> withdraw;
    private final Person person;
    private final CommandJudge judge;
    private final RunHooks hooks;

    /**
     * Every collaborator as a function, so the gate is tested without a database.
     *
     * @param runByConversation the live run a conductor conversation belongs to
     * @param artifactsDir a run's artifacts directory, where its spec.md is
     * @param todos the conductor's list, as it stood before the batch
     * @param commands the run's own port: place, judge, run and read
     * @param registered a run's registered commands
     * @param requirements the requirements a run's commands were registered against ({@link
     *     Acceptance#requirements}), or empty when none were
     * @param register replaces a run's registered commands, with the requirements they are held to
     * @param approvals an approval by id
     * @param consent asks a person to allow one command
     * @param concerns a run's acceptance-checker concerns ({@link Concerns#of}); none for a run
     *     whose definition names no checker
     * @param recorder told of every command run, passed or not
     * @param end the run's turn, ended while a person is still asked
     * @param withdraw withdraws a still-asked approval by id, saying why ({@link #SUPERSEDED} or
     *     {@link #RUN_ENDED}) — one a changed section no longer uses, or one asked for a run that
     *     ended meanwhile, so the person's waiting list holds only questions whose answer still
     *     matters
     * @param person who is asked whether the run goes on, and to check the product
     * @param judge shown a set before the person is asked about it: clearly safe, it is allowed
     *     unasked ({@link CommandJudge#NONE} asks the person every time)
     * @param hooks the conductor run's in-turn gates: {@code approval.pre} is asked for every
     *     command a person is about to be asked about (spec 2026-09-28-hooks-reach-the-log §3) —
     *     not for one the judge allowed or one keeping an approval; their records are parked with
     *     the run ({@link RunHooks#NONE} asks no hook)
     */
    public AcceptanceGate(Function<String, Optional<OrchestrationRecord>> runByConversation,
            Function<String, Optional<String>> artifactsDir, TodoLists todos,
            Commands.Port commands,
            Function<String, List<OrchestrationAcceptance.Registered>> registered,
            Function<String, Optional<String>> requirements, Register register,
            Function<String, Optional<RunApproval>> approvals, CheckConsent consent,
            Function<String, List<Concerns.Concern>> concerns, OrchestrationRecorder recorder,
            TurnEnd end,
            BiConsumer<String, String> withdraw, Person person, CommandJudge judge,
            RunHooks hooks) {
        this.runByConversation = Objects.requireNonNull(runByConversation, "runByConversation");
        this.artifactsDir = Objects.requireNonNull(artifactsDir, "artifactsDir");
        this.todos = Objects.requireNonNull(todos, "todos");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.registered = Objects.requireNonNull(registered, "registered");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.register = Objects.requireNonNull(register, "register");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.consent = Objects.requireNonNull(consent, "consent");
        this.concerns = Objects.requireNonNull(concerns, "concerns");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.end = Objects.requireNonNull(end, "end");
        this.withdraw = Objects.requireNonNull(withdraw, "withdraw");
        this.person = Objects.requireNonNull(person, "person");
        this.judge = Objects.requireNonNull(judge, "judge");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    /** As above, with no command judge and no hooks: every set that needs consent is put to the
     *  person, and no {@code approval.pre} is asked. */
    public AcceptanceGate(Function<String, Optional<OrchestrationRecord>> runByConversation,
            Function<String, Optional<String>> artifactsDir, TodoLists todos,
            Commands.Port commands,
            Function<String, List<OrchestrationAcceptance.Registered>> registered,
            Function<String, Optional<String>> requirements, Register register,
            Function<String, Optional<RunApproval>> approvals, CheckConsent consent,
            Function<String, List<Concerns.Concern>> concerns, OrchestrationRecorder recorder,
            TurnEnd end,
            BiConsumer<String, String> withdraw, Person person) {
        this(runByConversation, artifactsDir, todos, commands, registered, requirements, register,
                approvals, consent, concerns, recorder, end, withdraw, person, CommandJudge.NONE,
                RunHooks.NONE);
    }

    /** As above, with no hooks: no {@code approval.pre} is asked. */
    public AcceptanceGate(Function<String, Optional<OrchestrationRecord>> runByConversation,
            Function<String, Optional<String>> artifactsDir, TodoLists todos,
            Commands.Port commands,
            Function<String, List<OrchestrationAcceptance.Registered>> registered,
            Function<String, Optional<String>> requirements, Register register,
            Function<String, Optional<RunApproval>> approvals, CheckConsent consent,
            Function<String, List<Concerns.Concern>> concerns, OrchestrationRecorder recorder,
            TurnEnd end,
            BiConsumer<String, String> withdraw, Person person, CommandJudge judge) {
        this(runByConversation, artifactsDir, todos, commands, registered, requirements, register,
                approvals, consent, concerns, recorder, end, withdraw, person, judge,
                RunHooks.NONE);
    }

    /** As above, with no command judge: every set that needs consent is put to the person. */
    public AcceptanceGate(Function<String, Optional<OrchestrationRecord>> runByConversation,
            Function<String, Optional<String>> artifactsDir, TodoLists todos,
            Commands.Port commands,
            Function<String, List<OrchestrationAcceptance.Registered>> registered,
            Function<String, Optional<String>> requirements, Register register,
            Function<String, Optional<RunApproval>> approvals, CheckConsent consent,
            Function<String, List<Concerns.Concern>> concerns, OrchestrationRecorder recorder,
            TurnEnd end,
            BiConsumer<String, String> withdraw, Person person, RunHooks hooks) {
        this(runByConversation, artifactsDir, todos, commands, registered, requirements, register,
                approvals, consent, concerns, recorder, end, withdraw, person, CommandJudge.NONE,
                hooks);
    }

    @Override
    public List<TodoOp> check(String conversation, List<TodoOp> ops, Home home) {
        OrchestrationRecord run = runByConversation.apply(conversation)
                .filter(found -> !found.state().terminal()).orElse(null);
        if (run == null) {
            return ops;
        }
        // EVERY acceptance-keyed done move in the batch, in order, and not only the first (Task
        // 10 review, finding 1): one todo_write that marks spec done and walks on to mark
        // acceptance done had its spec registered and its acceptance let through with nothing
        // run. Each is judged on the working copy as the board would make it — the spec's
        // registration first, so the acceptance move after it runs what was just registered.
        List<DoneMoves.Move> moves = DoneMoves.all(run, todos.list(conversation), ops,
                stage -> stage.acceptance() != null);
        List<TodoOp> passed = ops;
        for (DoneMoves.Move move : moves) {
            StageRules.Stage stage = run.stages().stream()
                    .filter(each -> each.id().equals(move.stage())).findFirst().orElseThrow();
            passed = OrchestrationDefinition.ACCEPTANCE_WRITTEN.equals(stage.acceptance())
                    ? written(run, home, passed, move)
                    : required(run, home, passed, move, stage);
        }
        return passed;
    }

    /**
     * The acceptance stage's step for a run whose acceptance cannot run on this server — no
     * command port, or no store to register the commands in. It refuses every move of a stage
     * with an {@code acceptance:} key to {@code done} and lets anything else through: failing
     * closed, on {@link StageChecks#cannotRun}'s reason, where the plain write would have let a
     * run finish with nothing run at all — the measured case this gate exists for.
     *
     * @param run the run
     * @param todos the conductor's list
     * @return a step that refuses those moves
     */
    static TodoTools.BeforeApply cannotRun(OrchestrationRecord run, TodoLists todos) {
        Set<String> gated = run.stages().stream().filter(stage -> stage.acceptance() != null)
                .map(StageRules.Stage::id).collect(Collectors.toSet());
        return (conversation, ops, home) -> {
            Map<String, TodoItem> items = todos.list(conversation).stream()
                    .collect(Collectors.toMap(TodoItem::id, item -> item, (a, b) -> a));
            for (int i = 0; i < ops.size(); i++) {
                if (ops.get(i) instanceof TodoOp.Update update
                        && update.status() == TodoStatus.DONE) {
                    TodoItem item = items.get(update.id());
                    if (item != null && item.locked() && gated.contains(item.stageId())) {
                        throw refused(i + 1, "stage '" + item.stageId() + "' reads or runs"
                                + " spec.md's " + Acceptance.HEADING + ", and this run's"
                                + " acceptance cannot run on this server, so it cannot be marked"
                                + " done here");
                    }
                }
            }
            return ops;
        };
    }

    private List<TodoOp> written(OrchestrationRecord run, Home home, List<TodoOp> ops,
            DoneMoves.Move move) {
        int n = move.index() + 1;
        String spec = spec(run, home, n);
        Acceptance.Section section = section(spec, n);
        // What is stored with the set is the requirements it is held to: spec.md without the
        // section, compared again at the acceptance stage.
        String stated = Acceptance.requirements(spec);
        // Approved once (plan choice 4): registering again — a spec stage marked done after a
        // return to it — keeps the approval of every command that is unchanged, so only a changed
        // or new command is asked about (registerAll).
        List<OrchestrationAcceptance.Registered> set = registerAll(run, home, stated,
                section.commands(), n, move.stage());
        boolean asking = set.stream().map(this::state).anyMatch(RunApproval.ASKED::equals);
        boolean judged = !asking && set.stream().map(OrchestrationAcceptance.Registered::approval)
                .filter(Objects::nonNull).distinct().map(approvals).flatMap(Optional::stream)
                .anyMatch(approval -> RunApproval.JUDGE.equals(approval.answeredBy()));
        int checks = section.checks().size();
        // The move stands while the person is asked (plan choice 4): the conductor goes on with
        // its plan, and the answers are read at the acceptance stage, where they are needed.
        return withSummary(ops, move, move.summary() + " — acceptance: " + count(set.size())
                + (checks == 0 ? "" : ", " + checks + (checks == 1 ? " check" : " checks")
                        + " for the person")
                + (asking ? "; the person is asked to allow them"
                        : judged ? "; the command judge allowed them" : ""));
    }

    private List<TodoOp> required(OrchestrationRecord run, Home home, List<TodoOp> ops,
            DoneMoves.Move move, StageRules.Stage stage) {
        int n = move.index() + 1;
        String spec = spec(run, home, n);
        Acceptance.Section section = section(spec, n);
        List<Acceptance.Command> parsed = section.commands();
        // THE BAR CANNOT BE LOWERED (final review). The requirements the set was approved against
        // are stored with it; a spec whose requirements were rewritten since is refused, never
        // registered again here — a conductor whose commands fail could otherwise rewrite the
        // requirements and the commands together into a weaker pair that passes. The section is
        // the conductor's to change; what it is held to is not.
        //
        // The refusal names only moves the conductor has (re-review): `acceptance` cannot return
        // to the spec stage, so "mark it done again" was not one. It carries the approved text,
        // so putting it back exactly is possible; compared with trailing whitespace and blank
        // lines ignored, so an editor's reflow is not a changed requirement.
        String written = run.stages().stream()
                .filter(each -> OrchestrationDefinition.ACCEPTANCE_WRITTEN.equals(each.acceptance()))
                .map(StageRules.Stage::id).findFirst().orElse("spec");
        String approved = requirements.apply(run.id()).orElse(null);
        if (approved == null) {
            throw refused(n, "no acceptance was approved for this run at `" + written + "`, and `"
                    + move.stage() + "` cannot return to it: orchestration_ask your caller with"
                    + " this refusal");
        }
        if (!Acceptance.sameRequirements(approved, Acceptance.requirements(spec))) {
            String shown = approved.length() <= REQUIREMENTS_SHOWN ? approved
                    : approved.substring(0, REQUIREMENTS_SHOWN) + "\n… (cut here: "
                            + approved.length() + " characters in all)";
            throw new TodoRefused("todo_write refused operation " + n + ": spec.md's"
                    + " requirements (its text outside " + Acceptance.HEADING + ") differ from"
                    + " those its acceptance commands were approved against, and `" + move.stage()
                    + "` cannot return to `" + written + "`. Put that text back exactly as"
                    + " approved (below), or, if the requirements must change, orchestration_ask"
                    + " your caller with this refusal. Nothing was changed.\n\n"
                    + Utterances.fence("approved requirements", shown));
        }
        // THE CHECKER'S FINDINGS STAND (spec 2026-10-01 §3): a concern its end pass found not to
        // hold keeps the stage from being done, until a return has it fixed and the end pass,
        // run again when the stage is started again, finds it holds.
        String back = stage.mayReturnTo().isEmpty() ? null : stage.mayReturnTo().get(0);
        int returnsLeft = Math.max(0, run.maxReturns() - run.returnsUsed());
        List<Concerns.Concern> known = concerns.apply(run.id());
        List<Concerns.Concern> failing = known.stream().filter(Concerns.Concern::doesNotHold)
                .toList();
        if (!failing.isEmpty()) {
            throw new TodoRefused("todo_write refused operation " + n + ": "
                    + Checking.findings(failing, back, returnsLeft) + " Nothing was changed.");
        }
        List<Concerns.Concern> forPerson = known.stream()
                .filter(Concerns.Concern::thePersonsToCheck).toList();
        boolean personsTurn = !section.checks().isEmpty() || !forPerson.isEmpty();
        String digest = personsTurn ? productDigest(section, forPerson) : null;
        boolean accepted = personsTurn && person.productAccepted(run.id())
                .filter(digest::equals).isPresent();
        // What runs is what is in spec.md now, and what the person was asked about: a section
        // edited since the spec stage is registered again — new approvals, asked of the person
        // even on an open side (registerAll) — before anything runs.
        List<OrchestrationAcceptance.Registered> set = registered.apply(run.id());
        if (!sameLines(set, parsed)) {
            set = registerAll(run, home, approved, parsed, n, move.stage());
        }
        List<String> waiting = new ArrayList<>();
        List<String> refusedByPerson = new ArrayList<>();
        for (OrchestrationAcceptance.Registered each : set) {
            String state = state(each);
            if (state == null) {
                continue;
            }
            if (RunApproval.ASKED.equals(state)) {
                waiting.add(shown(each.argv()));
            } else if (refusedByPerson(state)) {
                refusedByPerson.add(shown(each.argv()));
            }
        }
        if (!refusedByPerson.isEmpty()) {
            throw refused(n, "the person did not allow " + String.join(", ", refusedByPerson)
                    + "; change those lines in spec.md's " + Acceptance.HEADING + " with file_edit"
                    + " and mark " + move.stage() + " done again, which asks again");
        }
        if (!waiting.isEmpty()) {
            // The turn ends to wait ONLY when the stage is in progress in the stored list — the
            // one place the answers are spoken to (Orchestrations.speakAcceptanceAnswersIfDone
            // speaks only to a conductor at acceptance). A write that walked the stage from
            // pending to done is refused whole, so the stored list still says pending: ending the
            // turn there left a conductor nothing would ever speak to again (Task 10 re-review).
            boolean waitingThere = todos.list(run.conductorConversation()).stream().anyMatch(
                    item -> move.stage().equals(item.stageId())
                            && item.status() == TodoStatus.IN_PROGRESS);
            if (!waitingThere) {
                throw refused(n, move.stage() + " is waiting for the person to allow "
                        + String.join(", ", waiting) + "; mark " + move.stage() + " in_progress"
                        + " on its own, then done: the answers come to you there");
            }
            end.request(Outcome.Ending.AWAITING, "waiting for the person to allow the acceptance"
                    + " commands");
            throw refused(n, move.stage() + " is waiting for the person to allow "
                    + String.join(", ", waiting) + "; your turn ends here and their answer"
                    + " arrives as your next message");
        }
        // The person's product check, like an approval, is spoken only to a conductor whose
        // acceptance stage is in progress in the stored list: a write walking it from pending to
        // done is refused whole, and its question would be answered to a stage still pending.
        if (personsTurn && !accepted && todos.list(run.conductorConversation()).stream()
                .noneMatch(item -> move.stage().equals(item.stageId())
                        && item.status() == TodoStatus.IN_PROGRESS)) {
            throw refused(n, move.stage() + " has checks for the person; mark " + move.stage()
                    + " in_progress on its own, then done: the person is asked there");
        }
        // What the person accepted was this section, after these commands passed: marking the
        // stage done again on their acceptance does not run them a second time.
        List<String> failures = new ArrayList<>();
        for (OrchestrationAcceptance.Registered each : accepted ? List.<OrchestrationAcceptance
                .Registered>of() : set) {
            String failed = ran(run, home, each, n);
            if (failed != null) {
                failures.add(failed);
            }
        }
        if (!failures.isEmpty()) {
            // Counted with the run's check failures (V69): at the project's failed-checks the
            // person is asked whether it goes on, with the first failure's output, instead of the
            // move being refused again.
            String first = failures.get(0);
            String command = first.lines().findFirst().orElse("");
            int colon = command.indexOf(": ");
            String what = "acceptance command " + (colon > 0 ? command.substring(0, colon) : command)
                    + (failures.size() > 1 ? " (and " + (failures.size() - 1) + " more)" : "");
            if (person.checkFailed(run.id(), what, Utterances.lastLines(first, FAILURE_LINES))) {
                end.request(Outcome.Ending.AWAITING, "the person is asked whether the run goes on"
                        + " after its acceptance commands kept failing");
                throw new TodoRefused("todo_write refused operation " + n + ": " + move.stage()
                        + " is not done: " + failures.size() + " of " + count(set.size())
                        + " failed, and the run's checks have failed as many times as the person"
                        + " allows, so they are asked whether it goes on. Your turn ends here, and"
                        + " their answer arrives as your next message. Nothing was changed.\n\n"
                        + String.join("\n\n", failures));
            }
            // Sends the run back like a failed check (plan choice 3): the move is refused and the
            // conductor makes the return, to the stage the definition names. Nothing moves itself.
            // With no return left there is no way on from here (final review): marking acceptance
            // done again only runs the same commands to the same failure. The conductor is told
            // to stop and put it to its caller, who can decide what a person decides.
            String next = back == null ? ""
                    : run.returnsUsed() >= run.maxReturns()
                            ? " This run has used all " + run.maxReturns() + " of its returns, so"
                                    + " it cannot go back to `" + back + "`: stop marking "
                                    + move.stage() + " done, and orchestration_ask your caller"
                                    + " with this output."
                            : " Return to `" + back + "` (move it from done to in_progress) and"
                                    + " have it fixed with this output.";
            throw new TodoRefused("todo_write refused operation " + n + ": " + move.stage()
                    + " is not done: " + failures.size() + " of " + count(set.size())
                    + " failed." + next + " Nothing was changed.\n\n"
                    + String.join("\n\n", failures));
        }
        if (personsTurn && !accepted) {
            // EVERY run: LINE PASSED; what no command observes is the person's (spec 2026-10-01
            // §1), in one question only they may answer — no model caller or parent may.
            String question = productQuestion(run, set, section.checks(), forPerson);
            if (!person.askProduct(run.id(), digest, question)) {
                throw refused(n, "every acceptance command passed, and " + move.stage() + " has"
                        + " checks only a person can make, but this run has nobody to ask: stop"
                        + " marking " + move.stage() + " done, and orchestration_ask your caller"
                        + " with this refusal");
            }
            end.request(Outcome.Ending.AWAITING, "the person is asked to check the product");
            throw new TodoRefused("todo_write refused operation " + n + ": " + move.stage()
                    + " is not done yet: " + (set.isEmpty() ? "it has no acceptance command"
                            : "every acceptance command passed (" + count(set.size()) + ")")
                    + ", and what no command here can observe is the person's, so they are asked"
                    + " to check the product. Your turn ends here, and their answer arrives as"
                    + " your next message. Nothing was changed.");
        }
        return withSummary(ops, move, move.summary() + " — acceptance passed: "
                + count(set.size()) + (accepted ? "; the person checked the product and accepted"
                        + " it" : ""));
    }

    /**
     * The digest of a product check (spec 2026-10-01 §1): sha-256, lower-case hex, of every line
     * of the section and each concern the person is to check. Their {@code accept} accepts
     * exactly this; a changed line or concern is asked again.
     */
    static String productDigest(Acceptance.Section section, List<Concerns.Concern> forPerson) {
        StringBuilder text = new StringBuilder();
        section.commands().forEach(command -> text.append(command.line()).append('\n'));
        section.checks().forEach(check -> text.append(check.line()).append('\n'));
        forPerson.forEach(concern -> text.append(concern.id()).append(' ')
                .append(concern.personCheck()).append('\n'));
        return sha256(text.toString());
    }

    /**
     * The person's product check: how the product starts — each command that had to keep running
     * — the {@code check:} lines, and what the acceptance checker could not check itself. Every
     * line is the conductor's or the checker's text, shown as data for the person to act on.
     */
    static String productQuestion(OrchestrationRecord run,
            List<OrchestrationAcceptance.Registered> set, List<Acceptance.Check> checks,
            List<Concerns.Concern> forPerson) {
        String id = run.id();
        StringBuilder text = new StringBuilder("`" + id + "` (`" + run.definitionName() + "`)"
                + (set.isEmpty() ? " has no acceptance command to run."
                        : "'s acceptance commands all passed: " + set.size() + " of "
                                + set.size() + ".")
                + " What no command here can observe is yours to check.");
        List<OrchestrationAcceptance.Registered> starts = set.stream()
                .filter(each -> each.runsFor() != null).toList();
        if (!starts.isEmpty()) {
            text.append("\n\nHow it starts:");
            starts.forEach(each -> text.append("\n- `").append(String.join(" ", each.argv()))
                    .append("` in ").append(each.cwd()).append(" (it kept running for ")
                    .append(each.runsFor()).append("s)"));
        }
        if (!checks.isEmpty()) {
            text.append("\n\nCheck:");
            for (int i = 0; i < checks.size(); i++) {
                text.append("\n").append(i + 1).append(". ").append(checks.get(i).what())
                        .append(" — you should see: ").append(checks.get(i).expect());
            }
        }
        if (!forPerson.isEmpty()) {
            text.append("\n\nThe acceptance checker could not check these itself:");
            forPerson.forEach(concern -> text.append("\n- ").append(concern.id()).append(" (")
                    .append(concern.about()).append("): ").append(concern.personCheck()));
        }
        text.append("\n\n`/answer ").append(id).append(" accept` accepts the product. Any other"
                + " answer is your notes: they go to the conductor, and the run goes back to have"
                + " them fixed.");
        return text.toString();
    }

    /** @return null when it passed, else its report: why, then the tail of its output */
    private String ran(OrchestrationRecord run, Home home,
            OrchestrationAcceptance.Registered each, int n) {
        String shown = shown(each.argv()) + (each.stdin() == null ? ""
                : " with stdin `" + each.stdin().strip().replace("\n", "\\n") + "`");
        Duration runsFor = each.runsFor() == null ? null : Duration.ofSeconds(each.runsFor());
        CommandRunner.Outcome outcome;
        try {
            // Where it was consented to run (Task 10 review, finding 3): the directory is pinned at
            // registration, as StageChecks pins the check's — a first root that moved since would
            // otherwise run the command somewhere the person was never asked about.
            Commands.Placed placed = commands.place(home, Path.of(each.cwd()), each.argv());
            // Consent is for the side it was given on (the check's final review F3).
            if (!placed.side().equals(each.side())) {
                throw refused(n, shown + " was allowed on the " + each.side() + " side, and its"
                        + " directory is on the " + placed.side() + " side now; send the move"
                        + " again once the project is where it was");
            }
            String forbidden = Commands.refusal(placed.argv(), placed.side(), placed.allowed(),
                    placed.offBecause());
            if (forbidden != null) {
                throw refused(n, shown + " could not run: " + forbidden);
            }
            // The run tool's hooks, judged every time (F1): only a deny counts here.
            Commands.Verdict verdict = commands.judge(placed);
            if (verdict.denied() != null) {
                throw refused(n, shown + " could not run: " + verdict.denied());
            }
            if (runsFor == null) {
                outcome = commands.run(placed, each.stdin());
            } else {
                // runs-for (spec 2026-10-01 §1): the deadline is the stop, on whichever side it
                // runs — and no side may be asked to let a command run past its own timeout.
                if (placed.allowed().timeout().compareTo(runsFor) < 0) {
                    throw refused(n, shown + " must keep running for " + each.runsFor() + "s,"
                            + " longer than the " + placed.allowed().timeout().toSeconds() + "s a"
                            + " command may run on the " + placed.side() + " side (its"
                            + " environment.yml timeout): shorten its `runs-for:` in spec.md, or"
                            + " ask your caller to have the timeout raised");
                }
                outcome = commands.runFor(placed, each.stdin(), runsFor);
            }
        } catch (WorkspaceRefusedException | WorkspaceUnavailableException unreachable) {
            throw refused(n, shown + " could not run: " + unreachable.getMessage()
                    + "; send the move again once it can");
        }
        // `expect:` is matched against all the output the runner kept — the side's whole output
        // bound — and never against the tail shown below (Task 10 review, finding 6): a menu
        // printed first and followed by forty lines of play is still printed.
        String output = outcome.stdout() + "\n" + outcome.stderr();
        boolean cut = outcome.stdoutCut() > 0 || outcome.stderrCut() > 0;
        String missing = each.expect() != null && !output.contains(each.expect())
                ? "its output does not contain `" + each.expect() + "`" + (cut ? " (its output"
                        + " passed the side's output bound, so only its end was kept)" : "")
                : null;
        String why;
        if (runsFor != null) {
            // An exit before the deadline is the failure, whatever its code: measured
            // 2026-09-30, orc_3190A00C6035D3B5, a game whose main.py was a no-op exited 0.
            why = CommandRunner.ranFor(outcome, runsFor)
                    ? missing == null ? null
                            : "kept running for " + each.runsFor() + "s, but " + missing
                    : outcome.cancelled() ? "was cancelled after " + seconds(outcome)
                    : outcome.timedOut() ? "was stopped after " + seconds(outcome) + ", before"
                            + " the " + each.runsFor() + "s it must keep running: the side's own"
                            + " timeout is shorter"
                    : "exited " + outcome.exitCode() + " after " + seconds(outcome)
                            + ", before the " + each.runsFor() + "s it must keep running";
        } else {
            why = outcome.timedOut() ? "did not finish in " + seconds(outcome)
                    : outcome.exitCode() == null || outcome.exitCode() != each.exit()
                            ? "exited " + outcome.exitCode() + ", not " + each.exit()
                            : missing != null ? "exited " + outcome.exitCode() + ", and " + missing
                            : null;
        }
        // It ran, whatever it came to: recorded before the verdict is turned into a refusal.
        recorder.acceptanceRan(run, String.join(" ", each.argv()) + (runsFor == null ? ""
                : " (runs-for " + each.runsFor() + "s)"), why == null, why);
        return why == null ? null
                : shown + ": " + why + "\n" + Commands.tail(outcome, TAIL_LINES);
    }

    /**
     * Register {@code parsed} as the run's set. A command whose argv, stdin, exit, expectation,
     * side and directory are those of a command already registered keeps that command's approval
     * (Task 10 review, finding 4): the person is asked once about each command, and a denial
     * stands until the line changes. The exit and the expectation are in the key because the
     * approval's reason shows them ({@link #why}): a reused approval never shows the person a bar
     * the command is no longer held to. An approval of the old set that is still asked and no
     * longer used is withdrawn.
     *
     * <p><b>Every other command that needs consent is asked about in ONE question</b> (V67):
     * measured 2026-09-29, orc_318DFD3782228160, fifteen approvals one per command, and the
     * person's "Why do I get approval bombed?" The commands share one approval, whose reason lists
     * each with what it is given and must show ({@link #setWhy}). So a set's approval is kept
     * command by command only once a person allowed it — each command was then allowed; one still
     * asked, denied, or allowed by the judge answered the set as a whole, and is kept only while
     * every command it asked about is still there unchanged. Otherwise its commands are asked
     * again with the rest: a set that changed is a new set. And a still-asked approval is not kept
     * beside a new question, so the person is asked one thing at a time.
     *
     * <p><b>The command judge is shown the set first</b> ({@link #consentFor}): clearly safe, it is
     * allowed unasked, and the record says so. It is never asked over a hook — a deny refuses, an
     * ask goes to the person — nor about a set asked only because the section changed on an open
     * side, which is the person's (below).
     *
     * <p><b>A changed section is the person's, whatever the side's mode</b> (final review). An open
     * side records a first set as consented, as {@code setCheck} records a check; but a section
     * that differs from one already registered is a bar the conductor moved after it was set, and
     * on an open side nobody would ever see it move. So then every command is asked about, open
     * side or not — an unchanged one keeping a person's approval it already has — and a section
     * that lost a command reuses none: dropping the one command that fails asks nothing new, so
     * the person is shown the whole smaller set instead.
     *
     * <p><b>Nobody is asked until every command has passed {@code approval.pre}</b> (spec
     * 2026-09-28-hooks-reach-the-log §3). The commands are placed and judged first, then — only
     * when the set goes to a person, not when the command judge allows it or every command keeps
     * an approval — each command of the question is put to the hooks, once somebody could be
     * asked at all ({@link CheckConsent#canAsk}), and only then is the one question written: a
     * hook denying the third command leaves no question behind and nothing registered, and every
     * note joins the question. The run is read again after the hooks, so a run that ended while
     * they ran asks nobody.
     *
     * @param stage the stage whose done move registers the set, for the hooks' context
     */
    private List<OrchestrationAcceptance.Registered> registerAll(OrchestrationRecord run,
            Home home, String requirements, List<Acceptance.Command> parsed, int n,
            String stage) {
        // ENDED SINCE THE GATE READ IT (measured 2026-09-29, orc_318D26A46144920B): the person
        // cancelled the run while the gate was in a model call, and eleven commands were then
        // asked of them for a run that was over. Read again before anything is asked.
        if (ended(run)) {
            throw refused(n, endedSentence(run));
        }
        List<OrchestrationAcceptance.Registered> before = registered.apply(run.id());
        boolean changed = !before.isEmpty() && !sameLines(before, parsed);
        boolean shrunk = parsed.size() < before.size();
        int size = parsed.size();
        List<Placing> placings = new ArrayList<>();
        Set<Integer> claimedOld = new HashSet<>();
        for (int i = 0; i < size; i++) {
            Acceptance.Command command = parsed.get(i);
            String shown = shown(command.argv());
            Commands.Placed placed;
            try {
                placed = commands.place(home, null, command.argv());
            } catch (WorkspaceRefusedException | WorkspaceUnavailableException unreachable) {
                throw refused(n, "the acceptance command " + shown + " could not be placed: "
                        + unreachable.getMessage());
            }
            String forbidden = Commands.refusal(placed.argv(), placed.side(), placed.allowed(),
                    placed.offBecause());
            if (forbidden != null) {
                throw refused(n, "the acceptance command " + shown + " could not run: "
                        + forbidden);
            }
            Commands.Verdict verdict = commands.judge(placed);
            if (verdict.denied() != null) {
                throw refused(n, "the acceptance command " + shown + " was refused by a hook: "
                        + verdict.denied());
            }
            // setCheck's rule: an open side with no hook asking is consented as the check is;
            // anything else needs consent — an approval kept from before, or the set's one.
            boolean openMode = EnvironmentFile.OPEN.equals(placed.allowed().mode());
            boolean open = !changed && verdict.asked() == null && openMode;
            String cwd = placed.cwd().toString();
            OrchestrationAcceptance.Registered claimed = open || shrunk ? null : before.stream()
                    .filter(old -> old.approval() != null && !claimedOld.contains(old.position())
                            && old.argv().equals(command.argv())
                            && Objects.equals(old.stdin(), command.stdin())
                            && old.exit() == command.exit()
                            && Objects.equals(old.expect(), command.expect())
                            && Objects.equals(old.runsFor(), command.runsFor())
                            && old.side().equals(placed.side()) && old.cwd().equals(cwd)
                            && approvals.apply(old.approval()).isPresent())
                    .findFirst().orElse(null);
            if (claimed != null) {
                claimedOld.add(claimed.position());
            }
            placings.add(new Placing(command, placed, cwd, open,
                    verdict.asked() != null || openMode, claimed));
        }
        // An approval that answered its set as a whole is kept only for the whole set.
        Set<String> partial = new HashSet<>();
        for (OrchestrationAcceptance.Registered old : before) {
            if (old.approval() != null && !claimedOld.contains(old.position())
                    && !keptAlone(old.approval())) {
                partial.add(old.approval());
            }
        }
        boolean asking = false;
        for (int i = 0; i < size; i++) {
            Placing placing = placings.get(i);
            if (placing.claimed() != null && partial.contains(placing.claimed().approval())) {
                placings.set(i, placing = placing.unclaimed());
            }
            asking |= !placing.open() && placing.claimed() == null;
        }
        if (asking) {
            // One question at a time: a still-asked approval kept beside the new one would be a
            // second, so its commands join the new question and it is withdrawn below.
            for (int i = 0; i < size; i++) {
                Placing placing = placings.get(i);
                if (placing.claimed() != null && RunApproval.ASKED.equals(
                        state(placing.claimed()))) {
                    placings.set(i, placing.unclaimed());
                }
            }
        }
        List<Placing> needing = placings.stream()
                .filter(placing -> !placing.open() && placing.claimed() == null).toList();
        String fresh = needing.isEmpty() ? null : consentFor(run, needing, n, stage);
        Set<String> kept = new HashSet<>();
        List<OrchestrationAcceptance.Registered> set = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Placing placing = placings.get(i);
            Acceptance.Command command = placing.command();
            String approval = placing.open() ? null
                    : placing.claimed() != null ? placing.claimed().approval() : fresh;
            if (approval != null) {
                kept.add(approval);
            }
            set.add(new OrchestrationAcceptance.Registered(run.id(), i, command.line(),
                    command.argv(), command.stdin(), command.exit(), command.expect(),
                    placing.placed().side(), placing.cwd(),
                    placing.open() ? OrchestrationChecks.OPEN : OrchestrationChecks.APPROVAL,
                    approval, Instant.now(), command.runsFor()));
        }
        register.replace(run.id(), requirements, set);
        Set<String> withdrawn = new HashSet<>();
        for (OrchestrationAcceptance.Registered old : before) {
            if (old.approval() != null && !kept.contains(old.approval())
                    && withdrawn.add(old.approval())
                    && RunApproval.ASKED.equals(state(old))) {
                withdraw.accept(old.approval(), SUPERSEDED);
            }
        }
        // And again after: an ending while these were asked withdrew what the run had registered
        // then, and not these. Withdrawn here, and the move refused — the run is over.
        if (ended(run)) {
            Set<String> asked = new HashSet<>();
            for (OrchestrationAcceptance.Registered each : set) {
                if (each.approval() != null && asked.add(each.approval())
                        && RunApproval.ASKED.equals(state(each))) {
                    withdraw.accept(each.approval(), RUN_ENDED);
                }
            }
            throw refused(n, endedSentence(run));
        }
        return set;
    }

    /**
     * One command of a set as registration found it.
     *
     * @param open consented by an open side, needing no approval
     * @param personOnly the person's alone to allow: a hook asks about it, or it is on an open
     *     side and asked only because the section changed — never the judge's
     * @param claimed the command already registered whose approval it keeps, or null
     */
    private record Placing(Acceptance.Command command, Commands.Placed placed, String cwd,
            boolean open, boolean personOnly, OrchestrationAcceptance.Registered claimed) {
        Placing unclaimed() {
            return new Placing(command, placed, cwd, open, personOnly, null);
        }
    }

    /**
     * Whether an approval is kept for each of its commands alone: one a person allowed. Any other
     * — still asked, denied, allowed by the command judge, or gone — answered its set whole.
     */
    private boolean keptAlone(String approval) {
        return approvals.apply(approval).filter(found -> !RunApproval.JUDGE.equals(
                found.answeredBy()) && (RunApproval.ALLOWED.equals(found.state())
                        || RunApproval.USED.equals(found.state()))).isPresent();
    }

    /**
     * The one approval every command of {@code needing} runs under: allowed by the command judge
     * when it finds them clearly safe, else asked of the person as one question, with the judge's
     * words when it gave any. Fails closed — a judge that fails, times out or answers anything but
     * its verdict is a person asked — and never goes over a hook or the person: a set with a
     * command a hook asks about, or one asked only because the section changed on an open side,
     * is put to the person without the judge.
     *
     * @return the approval's id
     */
    private String consentFor(OrchestrationRecord run, List<Placing> needing, int n,
            String stage) {
        boolean personOnly = needing.stream().anyMatch(Placing::personOnly);
        CommandJudge.Verdict verdict = personOnly ? CommandJudge.Verdict.NOT_JUDGED
                : CommandJudge.safely(judge, needing.stream()
                        .map(placing -> new CommandJudge.Command(placing.command().argv(),
                                placing.command().stdin(), placing.cwd(),
                                placing.placed().side()))
                        .toList(), usage.forOperation(UsageAttribution.Operation.REVIEW, usage.agentName()));
        String side = needing.get(0).placed().side();
        String cwd = needing.get(0).cwd();
        List<List<String>> set = needing.stream().map(placing -> placing.command().argv())
                .toList();
        String why = setWhy(needing.stream().map(placing -> line(placing, side, cwd)).toList());
        if (verdict.clear()) {
            // Nobody is asked, so approval.pre is not either.
            return consent.allowedByJudge(run, List.of(), set, side, cwd, why, verdict.why())
                    .orElseThrow(() -> nobodyToAsk(n));
        }
        // THE PERSON: approval.pre for every command of the set first, before the question
        // exists — and only once somebody could be asked (ruling F5). Its records are parked with
        // the run's gates and land after this todo_write.
        if (!consent.canAsk(run)) {
            throw nobodyToAsk(n);
        }
        List<String> notes = beforeAsking(run, needing, n, stage);
        // ENDED WHILE THE JUDGE OR THE HOOKS RAN (ruling F1): read again, so nobody is asked about
        // a run that is over. Nothing has been asked yet, so nothing is withdrawn.
        if (ended(run)) {
            throw refused(n, endedSentence(run));
        }
        String asked = verdict.why() == null ? why : why + "\n" + JUDGED + verdict.why();
        return consent.askSet(run, set, side, cwd,
                        new Gate(null, notes, List.of()).applyTo(asked), verdict.why())
                .map(CheckConsent.Asked::approval)
                .orElseThrow(() -> nobodyToAsk(n));
    }

    /**
     * {@code approval.pre} for each command of a set a person is about to be asked about (spec
     * 2026-09-28-hooks-reach-the-log §3), one call per command as the run tool asks one: its own
     * argv and directory, and its own reason ({@link #why}: what it is given and must show) — the
     * shape {@link Approving} has for one command. A set's one approval takes no {@code project}
     * answer, so the scopes offered are {@code once} and {@code conversation}. Any denial refuses
     * the whole move, with nothing registered and nobody asked.
     *
     * @return every note the hooks gave, in order and each once, for the set's one question
     */
    private List<String> beforeAsking(OrchestrationRecord run, List<Placing> needing, int n,
            String stage) {
        HookContext.Orchestration about =
                new HookContext.Orchestration(run.id(), run.definitionName(), stage);
        Set<String> notes = new LinkedHashSet<>();
        for (Placing placing : needing) {
            Acceptance.Command command = placing.command();
            Gate gate = hooks.recorded(hooks.approvalPre(placing.placed().environment(), about,
                    new Approving(command.argv(), placing.cwd(),
                            why(command.stdin(), command.exit(), command.expect(),
                                    command.runsFor()), false, SET_SCOPES)));
            if (gate.isDenied()) {
                throw refused(n, "the acceptance command " + shown(command.argv()) + " was not"
                        + " put to the person: a hook on approval.pre refused it: "
                        + gate.denied());
            }
            notes.addAll(gate.notes());
        }
        return List.copyOf(notes);
    }

    /** What a set's one approval may be answered with, besides deny: no project prefix covers a
     *  set ({@code ApprovalFrames}). */
    private static final List<String> SET_SCOPES = List.of(RunApproval.ONCE,
            RunApproval.CONVERSATION);

    private static TodoRefused nobodyToAsk(int n) {
        return refused(n, "nobody can be asked to allow the acceptance commands: the project has"
                + " no id on this server");
    }

    /** Before the command judge's words, in a question it put to the person. */
    static final String JUDGED = "The command judge did not find them clearly safe: ";

    /** One command of a set's question: itself, what it is given and must show, and where it
     *  runs when that is not where the set does. */
    private static String line(Placing placing, String side, String cwd) {
        Acceptance.Command command = placing.command();
        boolean elsewhere = !placing.placed().side().equals(side) || !placing.cwd().equals(cwd);
        return shown(command.argv()) + " — " + bar(command.stdin(), command.exit(),
                command.expect(), command.runsFor()) + (elsewhere ? " (in " + placing.cwd() + ", on the "
                        + placing.placed().side() + " side)" : "");
    }

    /**
     * What the person is told a set's one approval is for: {@link #SET_WHY}, then each command
     * on its own line.
     *
     * @param lines each command, with what it is given and must show
     * @return the approval's reason
     */
    static String setWhy(List<String> lines) {
        return SET_WHY + " — " + (lines.size() == 1 ? "this one" : "all " + lines.size())
                + ", asked once:\n" + String.join("\n", lines);
    }

    /**
     * What the person is told an acceptance command's approval is for: {@link #WHY}, then what
     * it is given and what it must show (Task 10 review, finding 2) — so a command given other
     * input is visibly another request, not the same line asked again.
     *
     * @param stdin its input, or null
     * @param exit the exit code it must end with
     * @param expect what its output must contain, or null
     * @return the approval's reason
     */
    static String why(String stdin, int exit, String expect) {
        return why(stdin, exit, expect, null);
    }

    /**
     * {@link #why(String, int, String)} for a command that may have to keep running.
     *
     * @param runsFor how many seconds it must still be running after, or null
     */
    static String why(String stdin, int exit, String expect, Integer runsFor) {
        return WHY + " — " + bar(stdin, exit, expect, runsFor);
    }

    /** What a command is given and what it must show: an exit, or that it keeps running. */
    private static String bar(String stdin, int exit, String expect, Integer runsFor) {
        return (stdin == null ? "given no input" : "given the input `" + input(stdin) + "`")
                + (runsFor == null ? ", it must exit " + exit
                        : ", it must still be running after " + runsFor + "s, and is then"
                                + " stopped")
                + (expect == null ? "" : " and print `" + expect + "`");
    }

    /**
     * @param reason an approval's reason
     * @return whether it is one {@link #why} or {@link #setWhy} wrote — an acceptance command's,
     *     or a set's
     */
    static boolean isAcceptance(String reason) {
        return reason != null && (reason.startsWith(WHY) || reason.startsWith(SET_WHY));
    }

    private static String input(String stdin) {
        String shown = stdin.replace("\n", "\\n");
        return shown.length() <= INPUT_SHOWN ? shown : shown.substring(0, INPUT_SHOWN) + "… ("
                + stdin.getBytes(StandardCharsets.UTF_8).length + " bytes in all)";
    }

    private static boolean sameLines(List<OrchestrationAcceptance.Registered> set,
            List<Acceptance.Command> parsed) {
        return set.stream().map(OrchestrationAcceptance.Registered::line).toList()
                .equals(parsed.stream().map(Acceptance.Command::line).toList());
    }

    /** Whether the run has ended since the gate read it — or can no longer be found. */
    private boolean ended(OrchestrationRecord run) {
        return runByConversation.apply(run.conductorConversation())
                .map(now -> now.state().terminal()).orElse(true);
    }

    private String endedSentence(OrchestrationRecord run) {
        String state = runByConversation.apply(run.conductorConversation())
                .map(now -> now.state().wire()).orElse("gone");
        return "this orchestration has ended (" + state + ") while the move was being checked, so"
                + " nothing is asked of the person and nothing is marked done";
    }

    /** @return its approval's state — {@code "gone"} for one no longer found — or null when it
     *      runs under an open side's consent and has none */
    private String state(OrchestrationAcceptance.Registered each) {
        return each.approval() == null ? null
                : approvals.apply(each.approval()).map(RunApproval::state).orElse("gone");
    }

    /** Denied, revoked or gone: an answer no waiting brings back. */
    private static boolean refusedByPerson(String state) {
        return !RunApproval.ASKED.equals(state) && !RunApproval.ALLOWED.equals(state)
                && !RunApproval.USED.equals(state);
    }

    /** sha-256 of {@code text}, in lower-case hex. */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }

    private String spec(OrchestrationRecord run, Home home, int n) {
        String dir = artifactsDir.apply(run.id()).orElseThrow(() -> refused(n, "this run names"
                + " no artifacts directory, so it has no spec.md to read acceptance from"));
        String slash = dir.endsWith("/") ? dir : dir + "/";
        try {
            return commands.read(home, slash + "spec.md");
        } catch (WorkspaceRefusedException | WorkspaceUnavailableException unreadable) {
            throw refused(n, "spec.md could not be read in " + slash + ": "
                    + unreadable.getMessage());
        }
    }

    private static Acceptance.Section section(String spec, int n) {
        try {
            return Acceptance.section(spec);
        } catch (Acceptance.Unwritten unwritten) {
            throw refused(n, unwritten.getMessage());
        }
    }

    private static List<TodoOp> withSummary(List<TodoOp> ops, DoneMoves.Move move,
            String summary) {
        List<TodoOp> passed = new ArrayList<>(ops);
        TodoOp.Update update = (TodoOp.Update) ops.get(move.index());
        passed.set(move.index(), new TodoOp.Update(update.id(), update.status(), update.text(),
                summary));
        return List.copyOf(passed);
    }

    private static String count(int n) {
        return n + (n == 1 ? " acceptance command" : " acceptance commands");
    }

    private static String shown(List<String> argv) {
        return "`" + String.join(" ", argv) + "`";
    }

    private static String seconds(CommandRunner.Outcome outcome) {
        return String.format("%.1fs", outcome.millis() / 1000.0);
    }

    private static TodoRefused refused(int n, String why) {
        return new TodoRefused("todo_write refused operation " + n + ": " + why
                + ". Nothing was changed.");
    }
}
