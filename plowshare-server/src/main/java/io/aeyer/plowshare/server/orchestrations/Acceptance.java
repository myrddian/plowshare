package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.CommandRunner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * spec.md's {@code ## Acceptance} section (spec 2026-09-29 §1b): one command per requirement a
 * person could observe, one per line in a fenced block, as {@link #FORMAT}. Commands run with no
 * shell, so a shell's operators are refused and input is given with {@code stdin:}. A blank line
 * in the block, or one starting with {@link #COMMENT}, is skipped.
 *
 * <p><b>Why a section at all.</b> Measured, orc_31893856D8F462A1: a run finished with 74 tests
 * green and every review clean while {@code python -m rpg.main} did nothing — main.py defined
 * {@code main()} and never called it. The tests the coder wrote exercised the functions; nothing
 * ran the product the way a person runs it. These commands are that, written into the spec
 * before any code exists, and run by the harness rather than reported by a model.
 *
 * <h2>Two kinds of line (spec 2026-10-01, the acceptance checker §1)</h2>
 *
 * <p>A {@code run:} line is the harness's to verify, as above; with {@code runs-for:} it passes
 * when the program is still running that many seconds in, which is how a command shows a program
 * starts and stays up. A {@code check:} line is the person's: whatever no command here can
 * observe — a window, a sound, how it feels to play — said as what to do and what they should
 * see. Measured 2026-09-30, orc_3190A00C6035D3B5: 51 tests green, nine of nine commands passed,
 * and the game's main.py was still a no-op. A requirement no command can observe is accepted by
 * the person, never by a test passing.
 */
public final class Acceptance {

    /** The heading the section starts with. */
    public static final String HEADING = "## Acceptance";

    /** A {@code run:} line's format, verbatim from the spec. */
    public static final String FORMAT = "run: <command> | stdin: <text> | exit: 0 | expect: <text>";

    /** A {@code run:} line that must keep running, which takes {@code runs-for:} in place of
     *  {@code exit:}. */
    public static final String RUNS_FOR_FORMAT = "run: <command> | runs-for: <seconds>s";

    /** A {@code check:} line's format: what the person does, and what they should see. */
    public static final String CHECK_FORMAT = "check: <what to do> | expect: <what the person"
            + " should see>";

    /** The longest {@code runs-for:} a line may ask, in seconds: ten minutes. */
    public static final int MOST_RUNS_FOR = 600;

    /** What a line in the block starts with to be a comment, skipped as a blank line is. */
    public static final String COMMENT = "#";

    private static final Set<String> KEYS = Set.of("run", "stdin", "exit", "expect", "runs-for");
    private static final Set<String> CHECK_KEYS = Set.of("check", "expect");
    private static final Pattern SECONDS = Pattern.compile("([0-9]{1,4})s?");
    private static final Pattern SHELL = Pattern.compile(";|&&|\\|\\||>|<|`|\\$\\(");
    /** A double-quoted argument, closed; what {@link #argv} hands the program as one word. */
    private static final Pattern QUOTED_ARGUMENT = Pattern.compile("\"[^\"]*\"");
    /** How much of a line a refusal quotes: a 64 KiB {@code stdin:} is not read back whole. */
    private static final int QUOTED = 160;

    /**
     * One command, as the harness runs it.
     *
     * @param line the line as written, which is how the registered set is compared
     * @param argv the program and its arguments, run with no shell
     * @param stdin the input, ending with a line break, or null for none
     * @param exit the exit code it must end with; 0, and never read, when {@code runsFor} is set
     * @param expect text its output must contain, or null
     * @param runsFor how many seconds it must still be running after, then stopped; null for a
     *     command that must exit with {@code exit}
     */
    public record Command(String line, List<String> argv, String stdin, int exit, String expect,
            Integer runsFor) {
        /** Copies {@code argv}, so a command registered is the command that runs. */
        public Command {
            argv = List.copyOf(argv);
        }

        /** A command that must exit: every line before {@code runs-for:} existed. */
        public Command(String line, List<String> argv, String stdin, int exit, String expect) {
            this(line, argv, stdin, exit, expect, null);
        }
    }

    /**
     * One {@code check:} line: the person's to verify, at the acceptance stage.
     *
     * @param line the line as written
     * @param what what the person is to do
     * @param expect what they should see when the product works
     */
    public record Check(String line, String what, String expect) {
    }

    /**
     * The whole section: the harness's commands and the person's checks, each in its order.
     *
     * @param commands the {@code run:} lines
     * @param checks the {@code check:} lines
     */
    public record Section(List<Command> commands, List<Check> checks) {
        public Section {
            commands = List.copyOf(commands);
            checks = List.copyOf(checks);
        }
    }

    /** A section that is missing or is not the format, with the sentence the conductor reads. */
    public static final class Unwritten extends IllegalArgumentException {
        Unwritten(String sentence) {
            super(sentence);
        }
    }

    private Acceptance() {
    }

    /**
     * @param spec spec.md's text
     * @return its {@code run:} commands, in order — none for a section of {@code check:} lines
     * @throws Unwritten when there is no section, no block, no line, or a line not the format
     */
    public static List<Command> parse(String spec) {
        return section(spec).commands();
    }

    /**
     * @param spec spec.md's text
     * @return its {@code run:} commands and its {@code check:} lines, each in order
     * @throws Unwritten when there is no section, no block, no line, or a line not the format
     */
    public static Section section(String spec) {
        List<String> lines = spec.lines().toList();
        int heading = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(HEADING)) {
                heading = i;
                break;
            }
        }
        if (heading < 0) {
            throw new Unwritten("spec.md has no `" + HEADING + "` section: write one line per"
                    + " requirement a person could observe, one per line in a fenced block: `"
                    + FORMAT + "` for what a command here can observe, `" + RUNS_FOR_FORMAT
                    + "` for a program that must start and stay up, and `" + CHECK_FORMAT
                    + "` for what only the person can");
        }
        int open = -1;
        int close = -1;
        for (int i = heading + 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            // A heading ends the section only outside the block: `## …` inside it is a comment.
            if (open < 0 && line.startsWith("## ")) {
                break;
            }
            if (line.startsWith("```")) {
                if (open < 0) {
                    open = i;
                } else {
                    close = i;
                    break;
                }
            }
        }
        if (open < 0 || close < 0) {
            throw new Unwritten("spec.md's `" + HEADING + "` section has no fenced block of"
                    + " commands: put them one per line between ``` lines, as `" + FORMAT + "`");
        }
        List<Command> commands = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        for (int i = open + 1; i < close; i++) {
            String line = lines.get(i).strip();
            // A blank line or a comment is the conductor's note to a reader, never a command.
            // Measured 2026-09-29, orc_318D26A46144920B: `# Requirement: …` lines naming what
            // each command observes were refused as a line that is not the format. The number a
            // later refusal names is still the file's own line, i + 1.
            if (line.isEmpty() || line.startsWith(COMMENT)) {
                continue;
            }
            if (line.startsWith("check:")) {
                checks.add(check(line, i + 1));
            } else {
                commands.add(command(line, i + 1));
            }
        }
        if (commands.isEmpty() && checks.isEmpty()) {
            throw new Unwritten("spec.md's `" + HEADING + "` block has no line in it: one per"
                    + " line, as `" + FORMAT + "`, `" + RUNS_FOR_FORMAT + "` or `" + CHECK_FORMAT
                    + "` (a line starting with `" + COMMENT + "` is a comment)");
        }
        return new Section(commands, checks);
    }

    /**
     * spec.md's text with its {@code ## Acceptance} section taken out — the requirements the
     * commands are held to, which the gate stores when the section is approved and compares at
     * the acceptance stage (final review: a conductor whose commands fail could otherwise rewrite
     * the requirements and the commands together, and have the verifier find the weaker pair
     * consistent). The section runs from its heading to the next level-one or level-two heading,
     * or the end. Each line's trailing whitespace and the text's ends are stripped, so an editor
     * that trims a line does not read as a changed requirement.
     *
     * @param spec spec.md's text
     * @return everything outside the section; the whole text when there is none
     */
    public static String requirements(String spec) {
        List<String> kept = new ArrayList<>();
        boolean inside = false;
        boolean fenced = false;
        for (String line : spec.lines().toList()) {
            String stripped = line.strip();
            if (stripped.equals(HEADING)) {
                inside = true;
                continue;
            }
            if (inside && stripped.startsWith("```")) {
                fenced = !fenced;
            }
            // A comment in the block (`# …`) is not a heading: the section runs on past it.
            if (inside && !fenced && (stripped.startsWith("## ") || stripped.startsWith("# "))) {
                inside = false;
            }
            if (!inside) {
                kept.add(line.stripTrailing());
            }
        }
        return String.join("\n", kept).strip();
    }

    /**
     * Whether two {@link #requirements} texts say the same thing: equal once each line's trailing
     * whitespace and every blank line are set aside — so an editor that trims, or reflows the
     * spacing between paragraphs, has not changed a requirement, and a changed word has.
     *
     * @param approved the requirements as stored
     * @param now the requirements as spec.md holds them now
     * @return whether they are the same
     */
    public static boolean sameRequirements(String approved, String now) {
        return normalised(approved).equals(normalised(now));
    }

    private static List<String> normalised(String text) {
        return text.lines().map(String::stripTrailing).filter(line -> !line.isEmpty()).toList();
    }

    private static Command command(String line, int number) {
        String[] fields = line.split(" \\| ");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String field : fields) {
            int colon = field.indexOf(':');
            if (colon <= 0) {
                throw unwritten(number, line, "'" + quoted(field.strip()) + "' is not `key: value`"
                        + " — a command runs with no shell, so give its input with `stdin:`");
            }
            String key = field.substring(0, colon).strip();
            if (!KEYS.contains(key)) {
                throw unwritten(number, line, "'" + quoted(key)
                        + "' is not run, stdin, exit, expect or runs-for");
            }
            if (byKey.putIfAbsent(key, field.substring(colon + 1).strip()) != null) {
                throw unwritten(number, line, "'" + key + "' appears twice");
            }
        }
        String run = byKey.get("run");
        if (!fields[0].strip().startsWith("run:") || run == null || run.isEmpty()) {
            throw unwritten(number, line, "it does not start with `run: <command>`");
        }
        // Outside double quotes only: a quoted argument is handed to the program whole, so the
        // `;` in `python -c "import game; game.main()"` is Python's and never a shell's. Measured
        // 2026-09-29: that line was refused, and the conductor rewrote its acceptance three times.
        if (SHELL.matcher(QUOTED_ARGUMENT.matcher(run).replaceAll("\"\"")).find()) {
            throw unwritten(number, line, "commands run with no shell, so `;`, `&&`, `||`, `>`,"
                    + " `<`, backticks and `$(` cannot be used; give input with `stdin:`");
        }
        String exit = byKey.get("exit");
        Integer runsFor = null;
        if (byKey.containsKey("runs-for")) {
            // A program that must keep running is stopped, so it has no exit code to hold it to.
            if (exit != null) {
                throw unwritten(number, line, "`exit:` and `runs-for:` cannot both be given: a"
                        + " command that must keep running is stopped at the end of its"
                        + " `runs-for:`, so it never exits with a code");
            }
            Matcher seconds = SECONDS.matcher(byKey.get("runs-for"));
            int asked = seconds.matches() ? Integer.parseInt(seconds.group(1)) : -1;
            if (asked < 1 || asked > MOST_RUNS_FOR) {
                throw unwritten(number, line, "`runs-for:` is whole seconds from 1 to "
                        + MOST_RUNS_FOR + ", as `runs-for: 5s`");
            }
            runsFor = asked;
        } else if (exit == null || !exit.matches("[0-9]{1,3}") || Integer.parseInt(exit) > 255) {
            throw unwritten(number, line, "`exit:` is required, a number from 0 to 255 — or,"
                    + " for a program that must start and keep running, `runs-for:` in its"
                    + " place");
        }
        String stdin = byKey.containsKey("stdin") ? input(byKey.get("stdin")) : null;
        if (stdin != null) {
            // Task 2's review: input is bounded as output is (CommandRunner.MAX_STDIN_BYTES), and
            // refused here, where the conductor can still change the line — not at the done move,
            // after a person was asked to allow a command no side would then run.
            int bytes = stdin.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > CommandRunner.MAX_STDIN_BYTES) {
                throw unwritten(number, line, "`stdin:` is " + bytes + " bytes, more than the "
                        + CommandRunner.MAX_STDIN_BYTES + " a command may be given");
            }
        }
        String expect = byKey.get("expect");
        return new Command(line, argv(run, number, line), stdin,
                runsFor == null ? Integer.parseInt(exit) : 0,
                expect == null || expect.isEmpty() ? null : expect, runsFor);
    }

    private static Check check(String line, int number) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String field : line.split(" \\| ")) {
            int colon = field.indexOf(':');
            String key = colon <= 0 ? "" : field.substring(0, colon).strip();
            if (!CHECK_KEYS.contains(key)) {
                throw unwrittenCheck(number, line, "'" + quoted(field.strip()) + "' is not check"
                        + " or expect — a check is the person's, so it runs nothing and takes no"
                        + " command keys");
            }
            if (byKey.putIfAbsent(key, field.substring(colon + 1).strip()) != null) {
                throw unwrittenCheck(number, line, "'" + key + "' appears twice");
            }
        }
        String what = byKey.get("check");
        String expect = byKey.get("expect");
        if (what == null || what.isEmpty()) {
            throw unwrittenCheck(number, line, "it does not say what the person is to do");
        }
        if (expect == null || expect.isEmpty()) {
            throw unwrittenCheck(number, line, "`expect:` is required: what the person should see"
                    + " when it works");
        }
        return new Check(line, what, expect);
    }

    private static String input(String written) {
        String text = written.replace("\\n", "\n");
        return text.endsWith("\n") ? text : text + "\n";
    }

    private static List<String> argv(String command, int number, String line) {
        List<String> argv = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : command.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    argv.add(word.toString());
                    word.setLength(0);
                    any = false;
                }
            } else {
                word.append(c);
                any = true;
            }
        }
        if (quoted) {
            throw unwritten(number, line, "a quote in the command is not closed");
        }
        if (any) {
            argv.add(word.toString());
        }
        return argv;
    }

    private static String quoted(String text) {
        return text.length() <= QUOTED ? text : text.substring(0, QUOTED) + "…";
    }

    private static Unwritten unwrittenCheck(int number, String line, String why) {
        return new Unwritten("spec.md line " + number + " (`" + quoted(line) + "`) is not `"
                + CHECK_FORMAT + "`: " + why);
    }

    private static Unwritten unwritten(int number, String line, String why) {
        return new Unwritten("spec.md line " + number + " (`" + quoted(line) + "`) is not `"
                + FORMAT + "`: " + why);
    }
}
