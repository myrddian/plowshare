package io.aeyer.plowshare.server.orchestrations;

import java.util.List;
import java.util.Objects;

/**
 * The command judge (agents/command_judge.md): shown the commands a run wants consent for — an
 * acceptance set, or a run's check — it says whether they are clearly safe, so a person is not
 * asked about a test runner fifteen times. Measured 2026-09-29, orc_318DFD3782228160: fifteen
 * approvals, one per acceptance command, and the person's "Why do I get approval bombed?"
 *
 * <p><b>It fails closed.</b> Only an answer of {@code clear} lets anything through unasked; a
 * failure, a timeout or an answer that is not the verdict's JSON is thrown, and the caller then
 * asks the person, as it always did. It is never asked over a hook: a hook's deny refuses and a
 * hook's ask goes to the person without it. Nothing it is shown is an instruction to it.
 */
@FunctionalInterface
public interface CommandJudge {

    /** Nobody judges: every command that needs consent is put to the person, as before V67. */
    CommandJudge NONE = commands -> Verdict.NOT_JUDGED;

    /**
     * One command the judge is shown.
     *
     * @param argv the program and its arguments, run with no shell
     * @param stdin what it is given on standard input, or null
     * @param cwd the directory it runs in
     * @param side {@code server} or {@code local}: whose machine it runs on
     */
    record Command(List<String> argv, String stdin, String cwd, String side) {
        /** Copies {@code argv}, so what was judged is what runs. */
        public Command {
            argv = List.copyOf(argv);
            Objects.requireNonNull(cwd, "cwd");
            Objects.requireNonNull(side, "side");
        }
    }

    /**
     * @param clear whether every command is clearly safe
     * @param why the judge's one line about it, or null when it gave none — the model's words,
     *     shown to the person as data
     */
    record Verdict(boolean clear, String why) {
        /** No judge was asked, or none gave a verdict. */
        public static final Verdict NOT_JUDGED = new Verdict(false, null);
    }

    /**
     * @param commands what the run wants consent for; at least one
     * @return the verdict
     * @throws RuntimeException when it gives none — a failure, a timeout, an unreadable answer;
     *     the caller asks the person
     */
    Verdict judge(List<Command> commands);
    default Verdict judge(List<Command> commands, io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner) {
        return judge(commands);
    }

    /**
     * {@link #judge}, failing closed: anything but a verdict is {@link Verdict#NOT_JUDGED}.
     *
     * @param judge the judge
     * @param commands what it is shown
     * @return its verdict, or not clear
     */
    static Verdict safely(CommandJudge judge, List<Command> commands) {
        return safely(judge, commands, io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    }
    static Verdict safely(CommandJudge judge, List<Command> commands,
            io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner) {
        try {
            Verdict verdict = owner.status() == io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Status.LEGACY_UNATTRIBUTED
                    ? judge.judge(commands) : judge.judge(commands, owner);
            return verdict == null ? Verdict.NOT_JUDGED : verdict;
        } catch (RuntimeException failed) {
            org.slf4j.LoggerFactory.getLogger(CommandJudge.class).warn("the command judge gave"
                    + " no verdict; the person is asked", failed);
            return Verdict.NOT_JUDGED;
        }
    }
}
