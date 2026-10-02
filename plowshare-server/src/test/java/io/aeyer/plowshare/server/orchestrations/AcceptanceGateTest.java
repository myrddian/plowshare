package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Commands;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunHooks;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Acceptance in front of the board (spec 2026-09-29 §1b), with no database. */
class AcceptanceGateTest {

    private static final Home HOME = Home.of("rpg");
    private static final String CONV = "cnv_root";
    private static final String DIR = "docs/orchestrations/2026-09-28-implement_specification-orc_1/";

    private OrchestrationRecord run = run(0);

    /** The run, with {@code used} of its 2 returns spent. */
    private static OrchestrationRecord run(int used) {
        return run(used, OrchestrationState.RUNNING);
    }

    private static OrchestrationRecord run(int used, OrchestrationState state) {
        return new OrchestrationRecord("orc_1",
                "implement_specification", Tier.SHIPPED, "sha256:x", "src", "test",
                List.of(new StageRules.Stage("spec", List.of(), false, "written"),
                        new StageRules.Stage("phases", List.of()),
                        new StageRules.Stage("review", List.of("phases")),
                        new StageRules.Stage("acceptance", List.of("phases"), false, "required")),
                2, used, "rpg", CONV, null, "interlocutor", "enzo", null, null, 0, null,
                state, null, null, null, 0, 0, false, null, Instant.EPOCH,
                state.terminal() ? Instant.EPOCH : null);
    }

    private final List<TodoItem> items = new ArrayList<>(List.of(
            item("td_spec", "spec", TodoStatus.IN_PROGRESS, "spec.md written"),
            item("td_phases", "phases", TodoStatus.DONE, "4 phases"),
            item("td_review", "review", TodoStatus.DONE, "clean"),
            item("td_acceptance", "acceptance", TodoStatus.IN_PROGRESS, "ran it")));
    private String spec = AcceptanceTest.SPEC;
    private final List<OrchestrationAcceptance.Registered> registered = new ArrayList<>();
    private final Map<String, String> approvalStates = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private final List<String> askedWhy = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private final Map<String, CommandRunner.Outcome> outcomes = new HashMap<>();
    private String mode = EnvironmentFile.OPEN;
    /** What the run tool's hooks say of every command. */
    private Commands.Verdict hook = Commands.Verdict.ALLOWED;
    /** Run as the command judge is asked: the run ending while it thinks. */
    private Runnable judgeHook = () -> { };
    /** The run's acceptance-checker concerns, as the store holds them (spec 2026-10-01). */
    private final List<Concerns.Concern> concerns = new ArrayList<>();
    private final TurnEnd end = new TurnEnd();
    private final List<String> recorded = new ArrayList<>();
    /** Set, the port runs each command for real, in this directory, and reads spec.md from it. */
    private Path real;
    /** The run's first root as the port finds it now, where a command with no cwd is placed. */
    private Path root = Path.of("/repo");
    /** Where each command the port ran was placed. */
    private final List<Path> ranIn = new ArrayList<>();
    private final List<String> withdrawn = new ArrayList<>();
    private final List<String> withdrawnWhy = new ArrayList<>();
    /** Run as the person's consent asks its first question: the run ending as it is asked. */
    private Runnable onAsk = () -> { };
    /** The requirements stored with the registered set, as the store keeps them. */
    private String requirements;
    /** The product check the person accepted, as the store keeps it (spec 2026-10-01 §1). */
    private String productAccepted;
    /** What the person was asked to check, and the digest each question carried. */
    private final List<String> personAsked = new ArrayList<>();
    private final List<String> personAskedDigest = new ArrayList<>();
    /** False for a run no person can be asked about — no account behind it. */
    private boolean personCanBeAsked = true;

    /** Each question the person was asked (V67): the commands it named, in order. */
    private final List<List<String>> questions = new ArrayList<>();
    /** The judge's words each question carried, or null. */
    private final List<String> askedJudged = new ArrayList<>();
    /** Who answered each approval, where not the person. */
    private final Map<String, String> answeredBy = new HashMap<>();
    /** How many approvals were written, asked or allowed by the judge — each one's id. */
    private int approvalsMade;
    /** What the command judge answers, and what it was shown, call by call. */
    private CommandJudge.Verdict judgeVerdict = CommandJudge.Verdict.NOT_JUDGED;
    private RuntimeException judgeFailure;
    private final List<List<CommandJudge.Command>> judgeShown = new ArrayList<>();
    /** The judge's words on each set it allowed. */
    private final List<String> judgedAllowed = new ArrayList<>();
    private final CommandJudge judge = commands -> {
        judgeHook.run();
        judgeShown.add(commands);
        if (judgeFailure != null) {
            throw judgeFailure;
        }
        return judgeVerdict;
    };

    private final AcceptanceGate.Person person = new AcceptanceGate.Person() {
        @Override public Optional<String> productAccepted(String run) {
            return Optional.ofNullable(productAccepted);
        }
        @Override public boolean askProduct(String run, String digest, String question) {
            if (!personCanBeAsked) {
                return false;
            }
            personAsked.add(question);
            personAskedDigest.add(digest);
            return true;
        }
        @Override public boolean checkFailed(String run, String what, String output) {
            checkFailures.add(what + " | " + output);
            return checkFailures.size() >= failedChecksLimit;
        }
    };

    /** Each failure the person's port was told of (V69), and the count it asks at. */
    private final List<String> checkFailures = new ArrayList<>();
    private int failedChecksLimit = Integer.MAX_VALUE;

    private static TodoItem item(String id, String stage, TodoStatus status, String summary) {
        return new TodoItem(id, CONV, null, 0, stage, status, summary, true, stage, Instant.EPOCH);
    }

    private final TodoLists todos = new TodoLists() {
        @Override public List<TodoItem> list(String conversation) { return items; }
        @Override public List<TodoItem> apply(String c, List<TodoOp> o, String s) {
            throw new AssertionError("the gate never applies");
        }
        @Override public Optional<Notice> noticeFor(String conversation) {
            throw new AssertionError("the gate never reads a notice");
        }
        @Override public void noticed(String conversation,
                io.aeyer.plowshare.server.todos.TodoNotices.Seen seen) {
            throw new AssertionError("the gate never acks a notice");
        }
        @Override public void forget(String conversation) {
            throw new AssertionError("the gate never forgets a notice");
        }
    };

    private final Commands.Port port = new Commands.Port() {
        @Override public Commands.Placed place(Home home, Path cwd, List<String> argv) {
            EnvironmentFile.Side d = EnvironmentFile.Side.DEFAULT;
            Path where = cwd != null ? cwd : real != null ? real : root;
            return new Commands.Placed(argv, where, null, "local", new EnvironmentFile.Side(mode,
                    false, d.inherit(), d.env(), d.timeout(), d.outputBytes(), d.isolation()),
                    null);
        }
        @Override public Commands.Verdict judge(Commands.Placed placed) {
            return hook;
        }
        @Override public CommandRunner.Outcome run(Commands.Placed placed) {
            return run(placed, null);
        }
        @Override public CommandRunner.Outcome run(Commands.Placed placed, String stdin) {
            ran.add(placed.argv() + " <" + stdin);
            ranIn.add(placed.cwd());
            if (real != null) {
                return CommandRunner.run(new CommandRunner.Command(placed.argv(), real, Map.of(),
                        List.of("PATH"), Duration.ofSeconds(30), 1024 * 1024, stdin),
                        System.getenv(), () -> false);
            }
            String joined = String.join(" ", placed.argv());
            return outcomes.entrySet().stream().filter(e -> joined.startsWith(e.getKey()))
                    .map(Map.Entry::getValue).findFirst()
                    .orElse(new CommandRunner.Outcome(0, false, false, "ok", 0, "", 0, 100));
        }
        @Override public CommandRunner.Outcome runFor(Commands.Placed placed, String stdin,
                Duration runsFor) {
            ran.add(placed.argv() + " <" + stdin + " for " + runsFor.toSeconds() + "s");
            ranIn.add(placed.cwd());
            if (real != null) {
                return CommandRunner.run(new CommandRunner.Command(placed.argv(), real, Map.of(),
                        List.of("PATH"), runsFor, 1024 * 1024, stdin), System.getenv(),
                        () -> false);
            }
            String joined = String.join(" ", placed.argv());
            // Unscripted, a program keeps running until it is stopped at its deadline.
            return outcomes.entrySet().stream().filter(e -> joined.startsWith(e.getKey()))
                    .map(Map.Entry::getValue).findFirst()
                    .orElse(new CommandRunner.Outcome(null, true, false, "started", 0, "", 0,
                            runsFor.toMillis()));
        }
        @Override public String read(Home home, String relative) {
            assertEquals(DIR + "spec.md", relative);
            if (real != null) {
                try {
                    return Files.readString(real.resolve(relative));
                } catch (IOException unreadable) {
                    throw new AssertionError(unreadable);
                }
            }
            return spec;
        }
    };

    /** False for a run whose project has no id on this server: nobody can be asked. */
    private boolean consentCanAsk = true;

    private final CheckConsent consent = new CheckConsent() {
        @Override public boolean canAsk(OrchestrationRecord run) {
            return consentCanAsk;
        }
        @Override public Optional<Asked> ask(OrchestrationRecord run, List<String> argv,
                String side, String cwd) {
            throw new AssertionError("an acceptance command is asked with its own reason");
        }
        @Override public Optional<Asked> ask(OrchestrationRecord run, List<String> argv,
                String side, String cwd, String why) {
            throw new AssertionError("an acceptance set is asked about as one question");
        }
        @Override public Optional<Asked> askSet(OrchestrationRecord run,
                List<List<String>> commands, String side, String cwd, String why,
                String judged) {
            onAsk.run();
            commands.forEach(argv -> asked.add(String.join(" ", argv)));
            questions.add(commands.stream().map(argv -> String.join(" ", argv)).toList());
            askedWhy.add(why);
            askedJudged.add(judged);
            String id = "apr_" + ++approvalsMade;
            approvalStates.put(id, RunApproval.ASKED);
            return Optional.of(new Asked(id, "?"));
        }
        @Override public Optional<String> allowedByJudge(OrchestrationRecord run,
                List<String> argv, List<List<String>> commands, String side, String cwd,
                String why, String judged) {
            assertEquals(List.of(), argv, "a set's own argv is empty");
            assertTrue(AcceptanceGate.isAcceptance(why), why);
            String id = "apr_" + ++approvalsMade;
            approvalStates.put(id, RunApproval.ALLOWED);
            answeredBy.put(id, RunApproval.JUDGE);
            judgedAllowed.add(judged);
            return Optional.of(id);
        }
    };

    private final OrchestrationRecorder recorder = new OrchestrationRecorder() {
        @Override public void acceptanceRan(OrchestrationRecord run, String command,
                boolean passed, String why) {
            recorded.add(command + " " + passed + " " + why);
        }
    };

    private RunApproval approval(String id) {
        return new RunApproval(id, 7L, CONV, CONV, "enzo", "implement_specification", "local",
                List.of("python"), "/repo", null, approvalStates.get(id), null, null,
                answeredBy.get(id), null, null, Instant.EPOCH);
    }

    private AcceptanceGate gate() {
        return gate(RunHooks.NONE);
    }

    private AcceptanceGate gate(RunHooks hooks) {
        return new AcceptanceGate(c -> Optional.of(run), id -> Optional.of(DIR), todos, port,
                id -> List.copyOf(registered), id -> Optional.ofNullable(requirements),
                (id, stated, set) -> {
                    requirements = stated;
                    registered.clear();
                    registered.addAll(set);
                },
                id -> Optional.ofNullable(approvalStates.get(id)).map(state -> approval(id)),
                consent, id -> List.copyOf(concerns), recorder, end, (id, why) -> {
                    withdrawn.add(id);
                    withdrawnWhy.add(why);
                }, person, judge, hooks);
    }

    private static List<TodoOp> done(String id) {
        return List.of(new TodoOp.Update(id, TodoStatus.DONE, null, null));
    }

    @Test
    void marking_spec_done_registers_the_commands_and_says_so() {
        List<TodoOp> after = gate().check(CONV, done("td_spec"), HOME);

        assertEquals(3, registered.size());
        assertEquals(List.of(), asked, "an open side asks nobody");
        assertEquals(List.of(), ran, "nothing runs at spec");
        assertTrue(((TodoOp.Update) after.get(0)).summary()
                .startsWith("spec.md written — acceptance: 3 acceptance commands"));
    }

    /**
     * V67, measured 2026-09-29, orc_318DFD3782228160: fifteen approvals, one per command, and the
     * person's "Why do I get approval bombed?" The set is ONE question, every command under it.
     */
    @Test
    void a_side_that_asks_asks_the_person_once_for_the_whole_set_and_the_move_stands() {
        mode = EnvironmentFile.ASK;

        List<TodoOp> after = gate().check(CONV, done("td_spec"), HOME);

        assertEquals(List.of(List.of("python -m rpg.main", "python -m pytest -q",
                "python -c import rpg.save")), questions, "one question, naming every command");
        assertEquals(1, askedWhy.size());
        assertTrue(AcceptanceGate.isAcceptance(askedWhy.get(0)), askedWhy.toString());
        assertEquals(List.of("apr_1", "apr_1", "apr_1"),
                registered.stream().map(OrchestrationAcceptance.Registered::approval).toList());
        assertTrue(((TodoOp.Update) after.get(0)).summary()
                .endsWith("; the person is asked to allow them"));
        assertTrue(end.requested().isEmpty(), "the conductor goes on with its plan meanwhile");
    }

    /**
     * Review finding 2: what the person is asked about shows what will run — its input, and what
     * it must show — so the same command given other input is visibly another request.
     */
    @Test
    void an_approval_shows_the_input_and_what_the_command_must_show() {
        mode = EnvironmentFile.ASK;

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(AcceptanceGate.SET_WHY + " — all 3, asked once:\n"
                + "`python -m rpg.main` — given the input `3\\n`, it must exit 0 and print"
                + " `Welcome to the RPG`\n"
                + "`python -m pytest -q` — given no input, it must exit 0\n"
                + "`python -c import rpg.save` — given no input, it must exit 0 and print `ok`",
                askedWhy.get(0));
    }

    /** Approved once: a spec stage marked done again with the same section asks nobody again. */
    @Test
    void a_spec_marked_done_again_with_the_same_section_keeps_the_answers_it_has() {
        mode = EnvironmentFile.ASK;
        gate().check(CONV, done("td_spec"), HOME);
        approvalStates.put("apr_1", RunApproval.DENIED);

        List<TodoOp> after = gate().check(CONV, done("td_spec"), HOME);

        assertEquals(1, questions.size(), "no set is asked about twice, and a denial stands");
        assertEquals(List.of("apr_1", "apr_1", "apr_1"),
                registered.stream().map(OrchestrationAcceptance.Registered::approval).toList());
        assertTrue(((TodoOp.Update) after.get(0)).summary()
                .endsWith("acceptance: 3 acceptance commands"), "nobody is still being asked");
        assertEquals(List.of(), withdrawn);
    }

    /**
     * Review finding 4, with V67's one question: a changed section keeps the approval the person
     * gave every unchanged command, asks only about the changed one — together with anything still
     * asked, so the person has one question at a time — and withdraws the still-asked approval
     * nothing uses any more.
     */
    @Test
    void a_changed_line_is_asked_about_with_what_is_still_asked_and_the_old_question_withdrawn() {
        mode = EnvironmentFile.ASK;
        gate().check(CONV, done("td_spec"), HOME);
        approvalStates.put("apr_1", RunApproval.ALLOWED);
        spec = spec.replace("stdin: 3", "stdin: 1\\n3");

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(List.of("apr_2", "apr_1", "apr_1"),
                registered.stream().map(OrchestrationAcceptance.Registered::approval).toList(),
                "the same command given other input is a new question; the person's allowing"
                        + " the set holds for each command of it");
        assertEquals(List.of("python -m rpg.main"), questions.get(1));
        assertEquals(List.of(), withdrawn, "apr_1 was answered, so there is nothing to withdraw");

        spec = spec.replace("run: python -m pytest -q | exit: 0",
                "run: python -m pytest -q tests | exit: 0");
        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(List.of("apr_3", "apr_3", "apr_1"),
                registered.stream().map(OrchestrationAcceptance.Registered::approval).toList());
        assertEquals(List.of("python -m rpg.main", "python -m pytest -q tests"), questions.get(2),
                "the still-asked command joins the new question");
        assertEquals(List.of("apr_2"), withdrawn);
        assertEquals(List.of(AcceptanceGate.SUPERSEDED), withdrawnWhy);
    }

    /**
     * V67: a set the person denied — or has not answered — answered it whole, so a changed line
     * asks about the whole set again: the denial may have been for the line that changed.
     */
    @Test
    void a_changed_line_of_a_denied_set_asks_about_the_whole_set_again() {
        mode = EnvironmentFile.ASK;
        gate().check(CONV, done("td_spec"), HOME);
        approvalStates.put("apr_1", RunApproval.DENIED);
        spec = spec.replace("run: python -m pytest -q | exit: 0",
                "run: python -m pytest -q tests | exit: 0");

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(List.of("python -m rpg.main", "python -m pytest -q tests",
                "python -c import rpg.save"), questions.get(1));
        assertEquals(List.of("apr_2", "apr_2", "apr_2"),
                registered.stream().map(OrchestrationAcceptance.Registered::approval).toList());
        assertEquals(List.of(), withdrawn, "apr_1 was answered, so there is nothing to withdraw");
    }

    /**
     * Review finding 1: one todo_write that marks spec done and then acceptance done never lets
     * acceptance through without running its commands — each acceptance-keyed move in the batch
     * is judged, in order, on the batch's working copy.
     */
    @Test
    void spec_and_acceptance_done_in_one_write_runs_the_commands() {
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false, "", 0, "",
                0, 180));
        List<TodoOp> both = List.of(new TodoOp.Update("td_spec", TodoStatus.DONE, null, null),
                new TodoOp.Update("td_acceptance", TodoStatus.DONE, null, null));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, both, HOME));

        assertTrue(refused.getMessage().contains("todo_write refused operation 2: acceptance is"
                + " not done: 1 of 3 acceptance commands failed"), refused.getMessage());
        assertEquals(3, ran.size(), ran.toString());
    }

    /** As above, with acceptance walked from pending through in progress inside the write. */
    @Test
    void acceptance_walked_to_done_in_the_same_write_as_spec_is_still_run() {
        items.set(3, item("td_acceptance", "acceptance", TodoStatus.PENDING, null));
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false,
                "Welcome to the RPG", 0, "", 0, 180));
        List<TodoOp> walk = List.of(new TodoOp.Update("td_spec", TodoStatus.DONE, null, null),
                new TodoOp.Update("td_acceptance", TodoStatus.IN_PROGRESS, null, null),
                new TodoOp.Update("td_acceptance", TodoStatus.DONE, null, "it runs"));

        List<TodoOp> after = gate().check(CONV, walk, HOME);

        assertEquals(3, ran.size(), ran.toString());
        assertTrue(((TodoOp.Update) after.get(0)).summary().contains("acceptance: 3"));
        assertTrue(((TodoOp.Update) after.get(2)).summary()
                .startsWith("it runs — acceptance passed: 3 acceptance commands"));
    }

    /**
     * Re-review: a write that walks acceptance pending → in progress → done while a command is
     * still asked is refused whole, so the stored list still says pending — and the answers are
     * spoken only to a conductor at acceptance. Ending the turn there stranded the run; it is
     * told to mark the stage in progress on its own instead, and its turn goes on.
     */
    @Test
    void a_write_that_walks_acceptance_to_done_while_asked_does_not_end_the_turn() {
        mode = EnvironmentFile.ASK;
        gate().check(CONV, done("td_spec"), HOME);
        // As the board leaves it: spec done, so acceptance may start.
        items.set(0, item("td_spec", "spec", TodoStatus.DONE, "spec.md written"));
        items.set(3, item("td_acceptance", "acceptance", TodoStatus.PENDING, null));
        List<TodoOp> walk = List.of(
                new TodoOp.Update("td_acceptance", TodoStatus.IN_PROGRESS, null, null),
                new TodoOp.Update("td_acceptance", TodoStatus.DONE, null, "it runs"));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, walk, HOME));

        assertTrue(refused.getMessage().contains("mark acceptance in_progress on its own, then"
                + " done"), refused.getMessage());
        assertTrue(end.requested().isEmpty(), "the turn goes on");
        assertEquals(List.of(), ran);
    }

    /** The same, with spec marked done in the very write that walks acceptance to done. */
    @Test
    void spec_and_a_walked_acceptance_in_one_write_on_an_asking_side_does_not_end_the_turn() {
        mode = EnvironmentFile.ASK;
        items.set(3, item("td_acceptance", "acceptance", TodoStatus.PENDING, null));
        List<TodoOp> walk = List.of(new TodoOp.Update("td_spec", TodoStatus.DONE, null, null),
                new TodoOp.Update("td_acceptance", TodoStatus.IN_PROGRESS, null, null),
                new TodoOp.Update("td_acceptance", TodoStatus.DONE, null, "it runs"));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, walk, HOME));

        assertTrue(refused.getMessage().contains("todo_write refused operation 3: acceptance is"
                + " waiting for the person to allow"), refused.getMessage());
        assertTrue(refused.getMessage().contains("in_progress on its own"), refused.getMessage());
        assertTrue(end.requested().isEmpty(), "the turn goes on");
        assertEquals(List.of(), ran);
    }

    /** Re-review: a changed expectation is a changed bar, so it is asked about again. */
    @Test
    void a_changed_expectation_is_asked_about_again() {
        mode = EnvironmentFile.ASK;
        gate().check(CONV, done("td_spec"), HOME);
        approvalStates.put("apr_1", RunApproval.ALLOWED);
        spec = spec.replace("expect: Welcome to the RPG", "expect: Main Menu");

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals("apr_2", registered.get(0).approval());
        assertTrue(askedWhy.get(1).endsWith("print `Main Menu`"), askedWhy.get(1));
        assertEquals(List.of("apr_1", "apr_1"), registered.subList(1, 3).stream()
                .map(OrchestrationAcceptance.Registered::approval).toList());
    }

    /** Review finding 3: a command runs in the directory it was consented for, not today's root. */
    @Test
    void a_command_runs_where_it_was_registered_even_if_the_first_root_moved() {
        gate().check(CONV, done("td_spec"), HOME);
        root = Path.of("/elsewhere");
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false,
                "Welcome to the RPG", 0, "", 0, 180));

        gate().check(CONV, done("td_acceptance"), HOME);

        assertEquals(List.of(Path.of("/repo"), Path.of("/repo"), Path.of("/repo")), ranIn);
    }

    /** Review finding 6: `expect:` is matched on all the output kept, not on the tail shown. */
    @Test
    void an_expectation_printed_before_the_tail_shown_is_still_seen() {
        gate().check(CONV, done("td_spec"), HOME);
        String play = "Welcome to the RPG\n" + String.join("\n",
                java.util.Collections.nCopies(200, "> you walk north"));
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false, play, 0,
                "", 0, 180));

        List<TodoOp> after = gate().check(CONV, done("td_acceptance"), HOME);

        assertTrue(((TodoOp.Update) after.get(0)).summary().contains("acceptance passed"));
    }

    @Test
    void an_expectation_missed_in_output_that_was_cut_says_so() {
        gate().check(CONV, done("td_spec"), HOME);
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false, "> north",
                4096, "", 0, 180));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertTrue(refused.getMessage().contains("only its end was kept"), refused.getMessage());
    }

    @Test
    void a_spec_with_no_acceptance_section_is_not_done() {
        spec = "# spec\n\nRunnable with `python -m rpg.main`.\n";

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_spec"), HOME));
        assertTrue(refused.getMessage().contains("spec.md has no `## Acceptance` section"));
        assertEquals(List.of(), registered);
    }

    // --- a run that ends while the gate is at work (measured 2026-09-29) ---------------------

    /**
     * Measured 2026-09-29, orc_318D26A46144920B: the person cancelled the run while the gate was
     * in a model call, and eleven acceptance commands were then asked of them. Read again before
     * anything is asked: nothing is, and the move is refused.
     */
    @Test
    void a_run_cancelled_while_the_judge_ran_asks_the_person_nothing() {
        mode = EnvironmentFile.ASK;
        judgeHook = () -> run = run(0, OrchestrationState.CANCELLED);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_spec"), HOME));

        assertTrue(refused.getMessage().contains("this orchestration has ended (cancelled)"),
                refused.getMessage());
        assertEquals(List.of(), asked);
        assertEquals(List.of(), registered);
    }

    /** …and a run that ends between that read and the asking has what was just asked withdrawn. */
    @Test
    void a_run_cancelled_as_its_commands_are_asked_has_them_withdrawn() {
        mode = EnvironmentFile.ASK;
        onAsk = () -> run = run(0, OrchestrationState.CANCELLED);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_spec"), HOME));

        assertTrue(refused.getMessage().contains("has ended"), refused.getMessage());
        assertEquals(3, asked.size());
        assertEquals(List.of("apr_1"), withdrawn, "the set's one question, withdrawn once");
        assertEquals(List.of(AcceptanceGate.RUN_ENDED), withdrawnWhy);
    }

    /**
     * Measured, orc_31893856D8F462A1: 74 tests green, every review clean — and `python -m
     * rpg.main` did nothing, because main.py defined main() and never called it (no `__main__`
     * guard). Run the way a person runs it, it exits 0 and prints nothing.
     */
    @Test
    void the_measured_missing_main_guard_fails_acceptance() {
        gate().check(CONV, done("td_spec"), HOME);
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false, "", 0, "",
                0, 180));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        String said = refused.getMessage();
        assertTrue(said.contains("acceptance is not done: 1 of 3 acceptance commands failed"), said);
        assertTrue(said.contains("`python -m rpg.main` with stdin `3`: exited 0, and its output"
                + " does not contain `Welcome to the RPG`"), said);
        assertTrue(said.contains("Return to `phases`"), said);
        assertTrue(ran.contains("[python, -m, rpg.main] <3\n"), ran.toString());
        assertTrue(recorded.stream().anyMatch(line -> line.contains("rpg.main") && line.contains("false")));
        assertEquals(1, checkFailures.size(), "counted with the run's check failures (V69)");
        assertTrue(end.requested().isEmpty(), "under the limit: refused as before");
    }

    /**
     * V69: acceptance failures count with the run's check failures, and the one the limit is
     * reached on asks the person — with the failing command and its output — and ends the turn.
     */
    @Test
    void the_acceptance_failure_at_the_limit_asks_the_person_and_ends_the_turn() {
        failedChecksLimit = 1;
        gate().check(CONV, done("td_spec"), HOME);
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false, "", 0, "",
                0, 180));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertEquals(Outcome.Ending.AWAITING, end.requested().orElseThrow().ending());
        assertTrue(refused.getMessage().contains("so they are asked whether it goes on"),
                refused.getMessage());
        String told = checkFailures.get(0);
        assertTrue(told.startsWith("acceptance command `python -m rpg.main` with stdin `3` |"),
                told);
        assertTrue(told.contains("does not contain `Welcome to the RPG`"), told);
    }

    /**
     * The same case, replayed with a real Python and a real main.py rather than a scripted
     * outcome: {@code printf 3 | python -m rpg.main}, as a person would type it, written as the
     * section's {@code stdin: 3}. Without the guard the module is imported, defines {@code main()}
     * and exits 0 having printed nothing, so "Main Menu" is never seen; with it, the menu prints
     * and acceptance passes. What 74 green tests could not tell apart, this does.
     */
    @Test
    void the_measured_case_replayed_with_a_real_main_py(@TempDir Path project)
            throws IOException {
        assumeTrue(onPath("python3"), "python3 is not on this machine's PATH");
        real = project;
        Files.createDirectories(project.resolve(DIR));
        Files.writeString(project.resolve(DIR + "spec.md"), """
                # The text RPG

                ## Acceptance

                ```
                run: python3 -m rpg.main | stdin: 3 | exit: 0 | expect: Main Menu
                ```
                """);
        Files.createDirectories(project.resolve("rpg"));
        Files.writeString(project.resolve("rpg/__init__.py"), "");
        String main = """
                def main():
                    print("Main Menu")
                    print("1. New game  2. Load  3. Quit")
                    choice = input("> ")
                    if choice.strip() == "3":
                        print("Goodbye")
                """;
        Files.writeString(project.resolve("rpg/main.py"), main);
        gate().check(CONV, done("td_spec"), HOME);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertTrue(refused.getMessage().contains("`python3 -m rpg.main` with stdin `3`: exited 0,"
                + " and its output does not contain `Main Menu`"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Return to `phases`"), refused.getMessage());

        Files.writeString(project.resolve("rpg/main.py"),
                main + "\n\nif __name__ == \"__main__\":\n    main()\n");

        List<TodoOp> after = gate().check(CONV, done("td_acceptance"), HOME);

        assertTrue(((TodoOp.Update) after.get(0)).summary()
                .startsWith("ran it — acceptance passed: 1 acceptance command"));
    }

    @Test
    void every_command_passing_lets_acceptance_be_done() {
        gate().check(CONV, done("td_spec"), HOME);
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false,
                "Welcome to the RPG\n> ", 0, "", 0, 180));
        outcomes.put("python -c", new CommandRunner.Outcome(0, false, false, "ok", 0, "", 0, 90));

        List<TodoOp> after = gate().check(CONV, done("td_acceptance"), HOME);

        assertTrue(((TodoOp.Update) after.get(0)).summary()
                .startsWith("ran it — acceptance passed: 3 acceptance commands"));
        assertEquals(3, recorded.size());
    }

    @Test
    void a_wrong_exit_code_or_a_timeout_fails_with_its_own_words() {
        gate().check(CONV, done("td_spec"), HOME);
        outcomes.put("python -m pytest", new CommandRunner.Outcome(1, false, false, "2 failed",
                0, "", 0, 900));
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(null, true, false,
                "Welcome to the RPG\n> ", 0, "", 0, 30_000));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        String said = refused.getMessage();
        assertTrue(said.contains("2 of 3 acceptance commands failed"), said);
        assertTrue(said.contains("`python -m pytest -q`: exited 1, not 0"), said);
        assertTrue(said.contains("2 failed"), "the failing command's output tail: " + said);
        assertTrue(said.contains("did not finish in 30.0s"), said);
    }

    @Test
    void commands_the_person_has_not_answered_end_the_turn_and_run_nothing() {
        mode = EnvironmentFile.ASK;
        gate().check(CONV, done("td_spec"), HOME);
        ran.clear();

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertTrue(refused.getMessage().contains("waiting for the person to allow"));
        assertEquals(List.of(), ran);
        assertEquals(Outcome.Ending.AWAITING, end.requested().orElseThrow().ending());
    }

    @Test
    void a_command_the_person_denied_is_refused_with_the_way_to_ask_again() {
        mode = EnvironmentFile.ASK;
        gate().check(CONV, done("td_spec"), HOME);
        approvalStates.put("apr_1", RunApproval.DENIED);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertTrue(refused.getMessage().contains("the person did not allow `python -m rpg.main`,"
                + " `python -m pytest -q`, `python -c import rpg.save`"), refused.getMessage());
        assertEquals(List.of(), ran);
    }

    /**
     * Final review: an open side records a first set as consented, but a section changed after
     * it was registered is a bar the conductor moved, and is put to the person whatever the
     * side's mode — registered again, every command asked about, nothing run until they answer.
     */
    @Test
    void a_changed_section_on_an_open_side_is_still_asked_of_the_person_before_it_runs() {
        gate().check(CONV, done("td_spec"), HOME);
        assertEquals(List.of(), asked, "the first set, on an open side, asks nobody");
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false,
                "Welcome to the RPG", 0, "", 0, 180));
        spec = spec.replace("run: python -m pytest -q | exit: 0",
                "run: python -m pytest -q tests | exit: 0");

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertTrue(registered.stream().anyMatch(r -> r.line().contains("pytest -q tests")));
        assertEquals(List.of("python -m rpg.main", "python -m pytest -q tests",
                "python -c import rpg.save"), asked);
        assertTrue(registered.stream().allMatch(r -> OrchestrationChecks.APPROVAL.equals(
                r.consent())), "no command of a changed section runs on the open side's say-so");
        assertTrue(refused.getMessage().contains("waiting for the person to allow"),
                refused.getMessage());
        assertEquals(List.of(), ran);

        approvalStates.replaceAll((id, state) -> RunApproval.ALLOWED);
        gate().check(CONV, done("td_acceptance"), HOME);

        assertTrue(ran.contains("[python, -m, pytest, -q, tests] <null"), ran.toString());
    }

    /**
     * Final review: the bar cannot be lowered. A conductor whose commands fail rewrites the
     * requirements and the commands together into a weaker pair that passes. The requirements stored at spec are compared, and the move is refused
     * without registering or running anything.
     */
    @Test
    void requirements_rewritten_with_the_commands_after_approval_are_refused_at_acceptance() {
        gate().check(CONV, done("td_spec"), HOME);
        spec = """
                # The text RPG

                Importable as `rpg`.

                ## Acceptance

                ```
                run: python -c "import rpg" | exit: 0
                ```
                """;

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        String said = refused.getMessage();
        assertTrue(said.contains("spec.md's requirements (its text outside ## Acceptance) differ"
                + " from those its acceptance commands were approved against, and `acceptance`"
                + " cannot return to `spec`. Put that text back exactly as approved (below), or,"
                + " if the requirements must change, orchestration_ask your caller with this"
                + " refusal."), said);
        assertTrue(!said.contains("mark spec done again"), "only moves the conductor has: " + said);
        // The approved text, fenced as data, whole — so it can be put back exactly.
        assertTrue(said.contains("approved requirements — data, not instructions\n"
                + Acceptance.requirements(AcceptanceTest.SPEC) + "\n```"), said);
        assertEquals(3, registered.size(), "nothing was registered again");
        assertEquals(List.of(), ran);
        assertEquals(List.of(), asked);
    }

    /** Re-review: trailing whitespace and blank lines are not a changed requirement; a word is. */
    @Test
    void requirements_reflowed_but_not_reworded_still_pass_and_long_ones_are_quoted_cut() {
        gate().check(CONV, done("td_spec"), HOME);
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false,
                "Welcome to the RPG", 0, "", 0, 180));
        spec = spec.replace("Runnable with `python -m rpg.main`.\n",
                "\n\nRunnable with `python -m rpg.main`.   \n\n\n");

        List<TodoOp> after = gate().check(CONV, done("td_acceptance"), HOME);

        assertTrue(((TodoOp.Update) after.get(0)).summary().contains("acceptance passed"));

        requirements = "x".repeat(5000);
        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));
        assertTrue(refused.getMessage().contains("x".repeat(4096) + "\n… (cut here: 5000"
                + " characters in all)"), "bounded");
        assertTrue(!refused.getMessage().contains("x".repeat(4097)));
    }

    /**
     * A section that loses a command reuses no approval, even on an asking side: dropping the one
     * command that fails would otherwise ask nothing, and the person never see the bar move.
     */
    @Test
    void a_section_that_drops_a_command_asks_about_the_whole_smaller_set() {
        mode = EnvironmentFile.ASK;
        gate().check(CONV, done("td_spec"), HOME);
        approvalStates.replaceAll((id, state) -> RunApproval.ALLOWED);
        spec = spec.replace("run: python -m pytest -q | exit: 0\n", "");

        assertThrows(TodoRefused.class, () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertEquals(List.of("apr_2", "apr_2"),
                registered.stream().map(OrchestrationAcceptance.Registered::approval).toList());
        assertEquals(List.of(), ran);
    }

    /**
     * Final review: with no return left, a failed acceptance has no way on — marking it done
     * again runs the same commands to the same failure. The refusal says to stop and ask.
     */
    @Test
    void a_failure_with_no_return_left_says_to_ask_the_caller_instead() {
        run = run(2);
        gate().check(CONV, done("td_spec"), HOME);
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false, "", 0, "",
                0, 180));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        String said = refused.getMessage();
        assertTrue(said.contains("This run has used all 2 of its returns, so it cannot go back to"
                + " `phases`: stop marking acceptance done, and orchestration_ask your caller with"
                + " this output."), said);
        assertTrue(!said.contains("Return to `phases`"), said);
        assertTrue(said.contains("does not contain `Welcome to the RPG`"), said);
    }

    /**
     * Final review: the board refuses to start acceptance while spec is not done, so a write that
     * walks acceptance to done before spec is marked done is refused whole. The gate judged its
     * simulated move anyway and ran the commands the board then threw away; none of the write's
     * moves is judged now.
     */
    @Test
    void acceptance_walked_to_done_before_spec_is_done_runs_nothing() {
        items.set(3, item("td_acceptance", "acceptance", TodoStatus.PENDING, null));
        List<TodoOp> walk = List.of(
                new TodoOp.Update("td_acceptance", TodoStatus.IN_PROGRESS, null, null),
                new TodoOp.Update("td_acceptance", TodoStatus.DONE, null, "it runs"),
                new TodoOp.Update("td_spec", TodoStatus.DONE, null, null));

        List<TodoOp> after = gate().check(CONV, walk, HOME);

        assertEquals(List.of(), ran);
        assertEquals(List.of(), registered, "nothing of a write the board refuses whole is done");
        assertEquals(walk, after, "passed to the board untouched, which refuses it");
    }

    @Test
    void a_move_of_no_acceptance_stage_passes_through() {
        List<TodoOp> review = List.of(new TodoOp.Update("td_review", TodoStatus.IN_PROGRESS,
                null, null));

        assertEquals(review, gate().check(CONV, review, HOME));
        assertEquals(List.of(), registered);
    }

    // --- V67: one question for the set, and the command judge before it ---------------------

    /**
     * The {@code used} trap: an answer is read at the acceptance stage for EVERY command under the
     * set's one approval — allowed, or spent by a run tool call ({@code used}) — and not only the
     * first.
     */
    @Test
    void answering_the_one_approval_lets_every_command_of_the_set_run() {
        for (String answer : List.of(RunApproval.ALLOWED, RunApproval.USED)) {
            ran.clear();
            registered.clear();
            approvalStates.clear();
            mode = EnvironmentFile.ASK;
            gate().check(CONV, done("td_spec"), HOME);
            String approval = registered.get(0).approval();
            approvalStates.put(approval, answer);
            outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false,
                    "Welcome to the RPG", 0, "", 0, 180));
            outcomes.put("python -c", new CommandRunner.Outcome(0, false, false, "ok", 0, "", 0,
                    40));

            List<TodoOp> after = gate().check(CONV, done("td_acceptance"), HOME);

            assertEquals(3, ran.size(), answer + ": every command ran, not only the first: " + ran);
            assertTrue(((TodoOp.Update) after.get(0)).summary()
                    .endsWith("acceptance passed: 3 acceptance commands"), answer);
        }
    }

    /** The measured case, orc_318DFD3782228160: fifteen commands are one question, not fifteen. */
    @Test
    void fifteen_commands_are_one_question() {
        mode = EnvironmentFile.ASK;
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            lines.append("run: python -m pytest -q tests/test_main_loop.py::test_").append(i)
                    .append(" | exit: 0\n");
        }
        spec = spec.replaceAll("(?s)```\n.*?```", "```\n" + lines + "```");

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(1, questions.size());
        assertEquals(15, questions.get(0).size());
        assertEquals(15, askedWhy.get(0).lines().count() - 1, askedWhy.get(0));
        assertEquals(1, registered.stream().map(OrchestrationAcceptance.Registered::approval)
                .distinct().count());
    }

    /** …or none, when the judge finds them clearly safe — allowed by it, and said so. */
    @Test
    void a_set_the_judge_finds_clearly_safe_is_allowed_without_asking() {
        mode = EnvironmentFile.ASK;
        judgeVerdict = new CommandJudge.Verdict(true, "they run the project's own tests");

        List<TodoOp> after = gate().check(CONV, done("td_spec"), HOME);

        assertEquals(List.of(), questions, "nobody is asked");
        assertEquals(List.of("they run the project's own tests"), judgedAllowed);
        assertEquals(List.of("apr_1", "apr_1", "apr_1"),
                registered.stream().map(OrchestrationAcceptance.Registered::approval).toList());
        assertTrue(((TodoOp.Update) after.get(0)).summary()
                .endsWith("acceptance: 3 acceptance commands; the command judge allowed them"),
                ((TodoOp.Update) after.get(0)).summary());
        CommandJudge.Command first = judgeShown.get(0).get(0);
        assertEquals(new CommandJudge.Command(List.of("python", "-m", "rpg.main"), "3\n",
                "/repo", "local"), first, "shown the argv, the input, the directory and the side");
        assertEquals(3, judgeShown.get(0).size());

        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false,
                "Welcome to the RPG", 0, "", 0, 180));
        gate().check(CONV, done("td_acceptance"), HOME);

        assertEquals(3, ran.size(), "what the judge allowed runs at acceptance");
    }

    /** Not clear, a failure or a timeout, or an answer that is not a verdict: one question, with
     *  the judge's words when it gave any. */
    @Test
    void a_set_the_judge_does_not_clear_is_one_question_with_its_words() {
        mode = EnvironmentFile.ASK;
        judgeVerdict = new CommandJudge.Verdict(false, "`python -c` runs code given inline");

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(1, questions.size());
        assertTrue(askedWhy.get(0).endsWith("\n" + AcceptanceGate.JUDGED
                + "`python -c` runs code given inline"), askedWhy.get(0));
        assertEquals("`python -c` runs code given inline", askedJudged.get(0));

        for (RuntimeException failure : List.of(new IllegalStateException("no verdict within"
                + " 60000 ms"), new IllegalStateException("the judge's answer is not JSON"))) {
            registered.clear();
            judgeFailure = failure;

            gate().check(CONV, done("td_spec"), HOME);

            assertEquals(List.of(), judgedAllowed, "a judge that gives no verdict allows nothing");
            assertTrue(isNull(askedJudged.get(askedJudged.size() - 1)));
            assertTrue(askedWhy.get(askedWhy.size() - 1).startsWith(AcceptanceGate.SET_WHY));
        }
        assertEquals(3, questions.size());
    }

    private static boolean isNull(Object value) {
        return value == null;
    }

    /** A hook that asks puts the set to the person without the judge; one that denies refuses. */
    @Test
    void a_hook_is_never_overruled_by_the_judge() {
        mode = EnvironmentFile.ASK;
        judgeVerdict = new CommandJudge.Verdict(true, "clear");
        hook = new Commands.Verdict(null, "a hook wants the person to see this");

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(List.of(), judgeShown, "the judge is not asked over a hook");
        assertEquals(1, questions.size());

        registered.clear();
        hook = new Commands.Verdict("no tests on Fridays", null);
        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_spec"), HOME));

        assertTrue(refused.getMessage().contains("was refused by a hook: no tests on Fridays"),
                refused.getMessage());
        assertEquals(List.of(), judgeShown);
        assertEquals(List.of(), judgedAllowed);
    }

    /** An open side needs neither the person nor the judge; a section changed on it is the
     *  person's, never the judge's (final review's bar that moved). */
    @Test
    void an_open_side_calls_neither_and_its_changed_section_is_the_person_s() {
        judgeVerdict = new CommandJudge.Verdict(true, "clear");

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(List.of(), judgeShown);
        assertEquals(List.of(), questions);

        spec = spec.replace("run: python -m pytest -q | exit: 0",
                "run: python -m pytest -q tests | exit: 0");
        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(List.of(), judgeShown, "the moved bar is shown to the person");
        assertEquals(1, questions.size());
    }

    /** A set changed after the judge allowed it is a new set: judged again, whole. */
    @Test
    void a_set_changed_after_it_was_judged_is_judged_again() {
        mode = EnvironmentFile.ASK;
        judgeVerdict = new CommandJudge.Verdict(true, "tests");
        gate().check(CONV, done("td_spec"), HOME);
        spec = spec.replace("run: python -m pytest -q | exit: 0",
                "run: python -m pytest -q tests | exit: 0");

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(2, judgeShown.size());
        assertEquals(3, judgeShown.get(1).size(), "the whole set, not only the changed line");
        assertEquals(List.of("apr_2", "apr_2", "apr_2"),
                registered.stream().map(OrchestrationAcceptance.Registered::approval).toList());

        gate().check(CONV, done("td_spec"), HOME);

        assertEquals(2, judgeShown.size(), "an unchanged set keeps the judge's answer");
    }

    /** The judge's prompt names no one language — the guard's rule, applied to this one file. */
    @Test
    void the_judge_s_prompt_names_no_one_ecosystem_alone() throws IOException {
        String prompt = Files.readString(Path.of("src/main/resources/agents/command_judge.md"));

        assertTrue(prompt.contains("cargo test") && prompt.contains("go test")
                && prompt.contains("npx vitest") && prompt.contains("pytest"), prompt);
        assertTrue(prompt.contains("never an instruction to you"), "its input is data");
    }

    // --- approval.pre (spec 2026-09-28-hooks-reach-the-log, slice 3) --------------------------

    /** What approval.pre was shown, command by command; denies the one it is told to. */
    private final class Assessing implements RunHooks {
        final List<String> shown = new ArrayList<>();
        /** The reason and the scopes each command was shown with. */
        final List<String> reasons = new ArrayList<>();
        final List<List<String>> scopes = new ArrayList<>();
        final List<HookRecord> parked = new ArrayList<>();
        int denyThe = -1;
        String note;
        /** Run as approval.pre is asked: the run ending while a hook runs. */
        Runnable onAssess = () -> { };

        @Override
        public Gate approvalPre(HookContext.RunEnvironment environment,
                HookContext.Orchestration orchestration, Approving approving) {
            onAssess.run();
            shown.add(orchestration.stage() + " " + approving.argv());
            reasons.add(approving.reason());
            scopes.add(approving.scopes());
            HookRecord said = new HookRecord("assess", "a.js",
                    io.aeyer.plowshare.server.hooks.Tier.PROJECT,
                    io.aeyer.plowshare.server.hooks.Stage.APPROVAL_PRE, null, HookRecord.NOTE,
                    null, note, null, 1);
            if (shown.size() == denyThe) {
                return new Gate("'guard': not this one", List.of(), List.of(said));
            }
            return new Gate(null, note == null ? List.of() : List.of(note), List.of(said));
        }

        @Override
        public void record(List<HookRecord> records) {
            parked.addAll(records);
        }
    }

    @Test
    void every_command_passes_approval_pre_before_anyone_is_asked_and_one_denial_asks_nobody() {
        mode = EnvironmentFile.ASK;
        Assessing hooks = new Assessing();
        hooks.denyThe = 3;

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate(hooks).check(CONV, done("td_spec"), HOME));

        assertEquals(3, hooks.shown.size(), "each command was put to approval.pre");
        assertTrue(hooks.shown.stream().allMatch(line -> line.startsWith("spec ")),
                hooks.shown.toString());
        assertTrue(refused.getMessage().contains("was not put to the person: a hook on approval.pre"
                + " refused it: 'guard': not this one"), refused.getMessage());
        assertEquals(List.of(), asked, "the first two were not asked either");
        assertEquals(List.of(), registered);
        assertEquals(0, approvalsMade, "no approval was written, asked or allowed");
        assertEquals(3, hooks.parked.size(), "every decision, the denial too, is parked for the log");
    }

    /**
     * V67 with §3: a set put to the person passes approval.pre command by command, each with its
     * own argv and its own reason (what it is given and must show) — the shape {@link Approving}
     * has for one command — offered once or conversation, since no project prefix covers a set.
     * The judge is asked first, and did not clear it.
     */
    @Test
    void a_set_put_to_the_person_passes_approval_pre_command_by_command() {
        mode = EnvironmentFile.ASK;
        judgeVerdict = new CommandJudge.Verdict(false, "`python -c` runs code given inline");
        Assessing hooks = new Assessing();
        hooks.note = "runs in the sandbox";

        gate(hooks).check(CONV, done("td_spec"), HOME);

        assertEquals(1, judgeShown.size(), "the judge first");
        assertEquals(3, hooks.shown.size(), "then approval.pre, once per command");
        assertEquals(List.of("spec [python, -m, rpg.main]", "spec [python, -m, pytest, -q]",
                "spec [python, -c, import rpg.save]"), hooks.shown);
        assertEquals(AcceptanceGate.WHY + " — given the input `3\\n`, it must exit 0 and print"
                + " `Welcome to the RPG`", hooks.reasons.get(0));
        assertTrue(hooks.reasons.stream().allMatch(why -> why.startsWith(AcceptanceGate.WHY)),
                hooks.reasons.toString());
        assertTrue(hooks.scopes.stream().allMatch(List.of(RunApproval.ONCE,
                RunApproval.CONVERSATION)::equals), hooks.scopes.toString());
        assertEquals(1, questions.size(), "then the one question");
        assertTrue(askedWhy.get(0).contains(AcceptanceGate.JUDGED
                + "`python -c` runs code given inline\n\nruns in the sandbox"), askedWhy.get(0));
    }

    /** Nobody is asked when the judge clears the set, so approval.pre is not asked either. */
    @Test
    void a_set_the_judge_allows_asks_approval_pre_nothing() {
        mode = EnvironmentFile.ASK;
        judgeVerdict = new CommandJudge.Verdict(true, "they run the project's own tests");
        Assessing hooks = new Assessing();

        gate(hooks).check(CONV, done("td_spec"), HOME);

        assertEquals(List.of(), hooks.shown);
        assertEquals(List.of(), questions);
        assertEquals(List.of("they run the project's own tests"), judgedAllowed);
        assertEquals(3, registered.size());
    }

    /** A run hook that asks puts the set to the person without the judge — and approval.pre is
     *  asked, since a person is. */
    @Test
    void a_set_a_run_hook_asks_about_skips_the_judge_but_not_approval_pre() {
        mode = EnvironmentFile.ASK;
        judgeVerdict = new CommandJudge.Verdict(true, "clear");
        hook = new Commands.Verdict(null, "a hook wants the person to see this");
        Assessing hooks = new Assessing();

        gate(hooks).check(CONV, done("td_spec"), HOME);

        assertEquals(List.of(), judgeShown);
        assertEquals(3, hooks.shown.size());
        assertEquals(1, questions.size());
    }

    /** V67 with §3: the set is one question, so each command's note joins that one — each note
     *  once, however many commands' hooks gave it. */
    @Test
    void an_approval_pre_note_joins_the_set_s_one_question() {
        mode = EnvironmentFile.ASK;
        Assessing hooks = new Assessing();
        hooks.note = "runs in the sandbox";

        gate(hooks).check(CONV, done("td_spec"), HOME);

        assertEquals(1, questions.size(), "one question for the set");
        assertEquals(3, asked.size());
        String why = askedWhy.get(0);
        assertTrue(why.startsWith(AcceptanceGate.SET_WHY), why);
        assertTrue(why.endsWith("\n\nruns in the sandbox"), why);
        assertEquals(why.indexOf("runs in the sandbox"), why.lastIndexOf("runs in the sandbox"),
                "the same note from three commands joins once: " + why);
        assertEquals(3, hooks.parked.size());
    }

    /** Different notes from different commands all join the one question, in order. */
    @Test
    void every_command_s_note_joins_the_set_s_one_question() {
        mode = EnvironmentFile.ASK;
        List<String> notes = List.of("first reads only", "second writes /tmp", "third is slow");
        RunHooks hooks = new RunHooks() {
            int i;

            @Override
            public Gate approvalPre(HookContext.RunEnvironment environment,
                    HookContext.Orchestration orchestration, Approving approving) {
                return new Gate(null, List.of(notes.get(i++)), List.of());
            }
        };

        gate(hooks).check(CONV, done("td_spec"), HOME);

        assertEquals(1, questions.size());
        assertTrue(askedWhy.get(0).endsWith("\n\nfirst reads only\n\nsecond writes /tmp\n\n"
                + "third is slow"), askedWhy.get(0));
    }

    @Test
    void an_open_side_asks_nobody_and_so_asks_approval_pre_nothing() {
        Assessing hooks = new Assessing();

        gate(hooks).check(CONV, done("td_spec"), HOME);

        assertEquals(List.of(), hooks.shown);
        assertEquals(3, registered.size());
    }

    /** A command whose approval is kept asks nobody, so approval.pre is not asked about it. */
    @Test
    void a_kept_approval_is_not_put_to_approval_pre_again() {
        mode = EnvironmentFile.ASK;
        gate().check(CONV, done("td_spec"), HOME);
        approvalStates.replaceAll((id, state) -> RunApproval.ALLOWED);
        Assessing hooks = new Assessing();

        gate(hooks).check(CONV, done("td_spec"), HOME);

        assertEquals(List.of(), hooks.shown);
        assertEquals(3, asked.size(), "nobody was asked again");
    }

    /** Ruling F5: no hook fires for a question nobody could be asked. */
    @Test
    void a_project_nobody_can_be_asked_for_asks_approval_pre_nothing() {
        mode = EnvironmentFile.ASK;
        consentCanAsk = false;
        Assessing hooks = new Assessing();

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate(hooks).check(CONV, done("td_spec"), HOME));

        assertTrue(refused.getMessage().contains("nobody can be asked to allow the acceptance"
                + " commands"), refused.getMessage());
        assertEquals(List.of(), hooks.shown);
        assertEquals(List.of(), asked);
        assertEquals(List.of(), registered);
    }

    /** Ruling F1: a run that ends while approval.pre runs asks nobody (orc_318D26A46144920B). */
    @Test
    void a_run_cancelled_while_approval_pre_ran_asks_the_person_nothing() {
        mode = EnvironmentFile.ASK;
        Assessing hooks = new Assessing();
        hooks.onAssess = () -> run = run(0, OrchestrationState.CANCELLED);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate(hooks).check(CONV, done("td_spec"), HOME));

        assertTrue(refused.getMessage().contains("this orchestration has ended (cancelled)"),
                refused.getMessage());
        assertEquals(List.of(), asked);
        assertEquals(List.of(), registered);
        assertEquals(List.of(), withdrawn, "nothing was asked, so nothing is withdrawn");
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, program))) {
                return true;
            }
        }
        return false;
    }

    // --- runs-for and check: lines (spec 2026-10-01, the acceptance checker §1) ---------------

    private static final String GAME = """
            # Space Invaders

            A window opens, the ship moves with the arrow keys, and a hit plays a sound.

            ## Acceptance

            ```
            run: python -m pytest -q | exit: 0
            run: python game/main.py | runs-for: 5s
            check: run `python game/main.py` and press the arrow keys | expect: a window with the ship, which moves
            ```
            """;

    /** The acceptance stage, in progress in the stored list, as the board leaves it. */
    private void atAcceptance() {
        items.set(0, item("td_spec", "spec", TodoStatus.DONE, "spec.md written"));
    }

    @Test
    void a_runs_for_line_passes_on_a_program_still_running_when_it_is_stopped() {
        spec = GAME.replaceAll("(?m)^check: .*$", "");
        gate().check(CONV, done("td_spec"), HOME);
        atAcceptance();

        List<TodoOp> after = gate().check(CONV, done("td_acceptance"), HOME);

        assertTrue(ran.contains("[python, game/main.py] <null for 5s"), ran.toString());
        assertTrue(((TodoOp.Update) after.get(0)).summary()
                .startsWith("ran it — acceptance passed: 2 acceptance commands"));
        assertTrue(recorded.contains("python game/main.py (runs-for 5s) true null"),
                recorded.toString());
        assertEquals(List.of(), personAsked, "no check: line, so nobody is asked");
    }

    @Test
    void a_runs_for_line_fails_on_an_early_exit_with_its_output_tail() {
        spec = GAME.replaceAll("(?m)^check: .*$", "");
        gate().check(CONV, done("td_spec"), HOME);
        outcomes.put("python game/main.py", new CommandRunner.Outcome(0, false, false,
                "pygame 2.5 hello from the community", 0, "", 0, 150));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        String said = refused.getMessage();
        assertTrue(said.contains("`python game/main.py`: exited 0 after 0.2s, before the 5s it must"
                + " keep running"), said);
        assertTrue(said.contains("--- stdout ---\npygame 2.5 hello from the community"), said);
        assertTrue(said.contains("Return to `phases`"), said);
    }

    @Test
    void a_runs_for_cut_short_by_the_side_s_own_timeout_is_no_pass() {
        spec = GAME.replaceAll("(?m)^check: .*$", "");
        gate().check(CONV, done("td_spec"), HOME);
        outcomes.put("python game/main.py", new CommandRunner.Outcome(null, true, false, "", 0,
                "", 0, 3000));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertTrue(refused.getMessage().contains("was stopped after 3.0s, before the 5s it must"
                + " keep running"), refused.getMessage());
    }

    @Test
    void a_runs_for_longer_than_the_side_lets_a_command_run_is_refused_before_it_runs() {
        spec = GAME.replaceAll("(?m)^check: .*$", "").replace("runs-for: 5s", "runs-for: 600s");
        gate().check(CONV, done("td_spec"), HOME);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertTrue(refused.getMessage().contains("must keep running for 600s, longer than the"
                + " 300s a command may run on the local side"), refused.getMessage());
        assertFalse(ran.stream().anyMatch(line -> line.contains("for 600s")), ran.toString());
    }

    /** What the person is asked to allow says the command must keep running, not exit. */
    @Test
    void a_runs_for_line_s_approval_says_it_must_keep_running() {
        mode = EnvironmentFile.ASK;
        spec = GAME;

        gate().check(CONV, done("td_spec"), HOME);

        assertTrue(askedWhy.get(0).contains("`python game/main.py` — given no input, it must still"
                + " be running after 5s, and is then stopped"), askedWhy.get(0));
    }

    /**
     * Every run: line passed, and the section has a check: line: the person is asked — one
     * question, theirs alone — how it starts and what to check, and the conductor's turn ends.
     */
    @Test
    void check_lines_are_asked_of_the_person_once_every_run_line_passed() {
        spec = GAME;
        List<TodoOp> registeredAt = gate().check(CONV, done("td_spec"), HOME);
        assertTrue(((TodoOp.Update) registeredAt.get(0)).summary().endsWith(
                "acceptance: 2 acceptance commands, 1 check for the person"));
        atAcceptance();

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertEquals(2, ran.size(), "the run: lines ran first");
        assertTrue(refused.getMessage().contains("acceptance is not done yet: every acceptance"
                + " command passed (2 acceptance commands), and what no command here can observe"
                + " is the person's, so they are asked to check the product. Your turn ends here"),
                refused.getMessage());
        assertEquals(Outcome.Ending.AWAITING, end.requested().orElseThrow().ending());
        assertEquals(1, personAsked.size());
        String question = personAsked.get(0);
        assertTrue(question.startsWith("`orc_1` (`implement_specification`)'s acceptance commands"
                + " all passed: 2 of 2. What no command here can observe is yours to check."),
                question);
        assertTrue(question.contains("How it starts:\n- `python game/main.py` in /repo (it kept"
                + " running for 5s)"), question);
        assertTrue(question.contains("Check:\n1. run `python game/main.py` and press the arrow keys"
                + " — you should see: a window with the ship, which moves"), question);
        assertTrue(question.endsWith("`/answer orc_1 accept` accepts the product. Any other answer"
                + " is your notes: they go to the conductor, and the run goes back to have them"
                + " fixed."), question);
        assertEquals(List.of(AcceptanceGate.productDigest(Acceptance.section(GAME), List.of())),
                personAskedDigest);
    }

    /** Accepted: the stage is done on the person's word, and nothing runs a second time. */
    @Test
    void the_person_s_acceptance_lets_the_stage_be_done_without_running_again() {
        spec = GAME;
        gate().check(CONV, done("td_spec"), HOME);
        atAcceptance();
        productAccepted = AcceptanceGate.productDigest(Acceptance.section(GAME), List.of());

        List<TodoOp> after = gate().check(CONV, done("td_acceptance"), HOME);

        assertEquals(List.of(), ran);
        assertEquals(List.of(), personAsked);
        assertTrue(((TodoOp.Update) after.get(0)).summary().endsWith("acceptance passed: 2"
                + " acceptance commands; the person checked the product and accepted it"));
    }

    /** What the person accepted was that section: a changed check: line is asked again. */
    @Test
    void a_check_changed_since_the_person_accepted_is_asked_again() {
        spec = GAME;
        gate().check(CONV, done("td_spec"), HOME);
        atAcceptance();
        productAccepted = AcceptanceGate.productDigest(Acceptance.section(GAME), List.of());
        spec = GAME.replace("which moves", "which moves left and right");

        assertThrows(TodoRefused.class, () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertEquals(2, ran.size());
        assertEquals(1, personAsked.size());
    }

    @Test
    void check_lines_with_nobody_to_ask_send_the_conductor_to_its_caller() {
        spec = GAME;
        personCanBeAsked = false;
        gate().check(CONV, done("td_spec"), HOME);
        atAcceptance();

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertTrue(refused.getMessage().contains("this run has nobody to ask: stop marking"
                + " acceptance done, and orchestration_ask your caller"), refused.getMessage());
        assertTrue(end.requested().isEmpty());
    }

    /** As an approval is, the product check is asked only of a stage in progress in the list. */
    @Test
    void check_lines_on_a_stage_walked_to_done_in_one_write_are_refused_before_anything_runs() {
        spec = GAME;
        gate().check(CONV, done("td_spec"), HOME);
        items.set(0, item("td_spec", "spec", TodoStatus.DONE, "spec.md written"));
        items.set(3, item("td_acceptance", "acceptance", TodoStatus.PENDING, null));
        List<TodoOp> walk = List.of(
                new TodoOp.Update("td_acceptance", TodoStatus.IN_PROGRESS, null, null),
                new TodoOp.Update("td_acceptance", TodoStatus.DONE, null, "it runs"));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, walk, HOME));

        assertTrue(refused.getMessage().contains("mark acceptance in_progress on its own, then"
                + " done"), refused.getMessage());
        assertEquals(List.of(), ran);
        assertEquals(List.of(), personAsked);
    }

    // --- the checker's concerns at the acceptance stage (spec 2026-10-01 §3) ------------------

    private static Concerns.Concern concern(String id, String about, String state,
            String verdict, String finding, String personCheck) {
        return new Concerns.Concern("orc_1", id, about, "why " + id, Concerns.AT_PLAN, state, 0,
                null, null, null, verdict, finding, personCheck, null, Instant.EPOCH);
    }

    @Test
    void a_concern_that_does_not_hold_keeps_acceptance_from_being_done_and_runs_nothing() {
        gate().check(CONV, done("td_spec"), HOME);
        concerns.add(concern("c1", "nothing starts the game", Concerns.CHECKED,
                Concerns.DOES_NOT_HOLD, "game/main.py defines main() and never calls it", null));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        String said = refused.getMessage();
        assertTrue(said.contains("one does not hold, so the acceptance commands were not run"),
                said);
        assertTrue(said.contains("Found: game/main.py defines main() and never calls it"), said);
        assertTrue(said.contains("Return to `phases` (move it from done to in_progress)"), said);
        assertEquals(List.of(), ran);
    }

    @Test
    void what_the_checker_could_not_check_joins_the_person_s_question_beside_the_checks() {
        spec = GAME;
        gate().check(CONV, done("td_spec"), HOME);
        atAcceptance();
        concerns.add(concern("c1", "the game starts", Concerns.CHECKED, Concerns.HOLDS,
                "main() is called", null));
        concerns.add(concern("c2", "a hit plays a sound", Concerns.FOR_THE_PERSON,
                Concerns.CANNOT_CHECK, "only a mocked mixer", "shoot an invader and listen"));

        assertThrows(TodoRefused.class, () -> gate().check(CONV, done("td_acceptance"), HOME));

        String question = personAsked.get(0);
        assertTrue(question.contains("The acceptance checker could not check these itself:\n"
                + "- c2 (a hit plays a sound): shoot an invader and listen"), question);
        assertFalse(question.contains("c1"), "a concern that holds is not the person's");
    }

    /** The concerns the person accepted with the product are the check they accepted. */
    @Test
    void a_product_accepted_with_its_concerns_passes_once_they_are_resolved() {
        spec = GAME;
        gate().check(CONV, done("td_spec"), HOME);
        atAcceptance();
        Concerns.Concern asked = concern("c2", "a hit plays a sound", Concerns.FOR_THE_PERSON,
                Concerns.CANNOT_CHECK, "only a mocked mixer", "shoot an invader and listen");
        productAccepted = AcceptanceGate.productDigest(Acceptance.section(GAME), List.of(asked));
        concerns.add(concern("c2", "a hit plays a sound", Concerns.RESOLVED,
                Concerns.CANNOT_CHECK, "only a mocked mixer", "shoot an invader and listen"));

        List<TodoOp> after = gate().check(CONV, done("td_acceptance"), HOME);

        assertEquals(List.of(), personAsked);
        assertTrue(((TodoOp.Update) after.get(0)).summary().endsWith("the person checked the"
                + " product and accepted it"));
    }

    /** No check: lines, but a concern the checker could not check: the person is still asked. */
    @Test
    void a_concern_for_the_person_alone_asks_the_person() {
        gate().check(CONV, done("td_spec"), HOME);
        atAcceptance();
        outcomes.put("python -m rpg.main", new CommandRunner.Outcome(0, false, false,
                "Welcome to the RPG", 0, "", 0, 180));
        concerns.add(concern("c1", "it feels like a game", Concerns.FOR_THE_PERSON,
                Concerns.CANNOT_CHECK, "only a person can tell", "play one round"));

        assertThrows(TodoRefused.class, () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertEquals(1, personAsked.size());
        assertFalse(personAsked.get(0).contains("Check:"), personAsked.get(0));
    }

    /**
     * The measured Space Invaders case (orc_3190A00C6035D3B5), replayed with a real Python: 51
     * tests green and nine commands passed, and game/main.py was the scaffold's no-op. A
     * {@code runs-for} line fails it on its early exit; a main.py that starts its loop passes.
     */
    @Test
    void the_measured_space_invaders_no_op_fails_its_runs_for_line(@TempDir Path project)
            throws IOException {
        assumeTrue(onPath("python3"), "python3 is not on this machine's PATH");
        real = project;
        Files.createDirectories(project.resolve(DIR));
        Files.writeString(project.resolve(DIR + "spec.md"), """
                # Space Invaders

                ## Acceptance

                ```
                run: python3 game/main.py | runs-for: 2s
                ```
                """);
        Files.createDirectories(project.resolve("game"));
        Files.writeString(project.resolve("game/main.py"), """
                def main():
                    pass  # the scaffold's game loop, never written
                """);
        gate().check(CONV, done("td_spec"), HOME);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> gate().check(CONV, done("td_acceptance"), HOME));

        assertTrue(refused.getMessage().contains("`python3 game/main.py`: exited 0 after"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("before the 2s it must keep running"),
                refused.getMessage());

        Files.writeString(project.resolve("game/main.py"), """
                import time

                def main():
                    print("Space Invaders", flush=True)
                    while True:
                        time.sleep(0.05)

                if __name__ == "__main__":
                    main()
                """);

        List<TodoOp> after = gate().check(CONV, done("td_acceptance"), HOME);

        assertTrue(((TodoOp.Update) after.get(0)).summary()
                .startsWith("ran it — acceptance passed: 1 acceptance command"));
    }
}
