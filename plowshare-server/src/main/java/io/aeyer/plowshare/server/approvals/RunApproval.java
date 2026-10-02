package io.aeyer.plowshare.server.approvals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * One command a run asked a person about, and — once answered — what they allowed.
 *
 * @param argv the command; the empty list for a set, which names its commands in {@code commands}
 * @param scope null while asked or when denied
 * @param prefix the leading arguments a {@code project} approval covers; null otherwise
 * @param commands the acceptance set one approval asks about — each command's argv, in order — or
 *     null for an approval of one command (V67)
 * @param judged the command judge's one line about it, or null when it was not asked or gave none
 *     (V67)
 */
public record RunApproval(
        String id,
        long projectId,
        String conversation,
        String askedIn,
        String handle,
        String agent,
        String side,
        List<String> argv,
        String cwd,
        String reason,
        String state,
        String scope,
        List<String> prefix,
        String answeredBy,
        Instant answeredAt,
        Instant deliveredAt,
        Instant createdAt,
        List<List<String>> commands,
        String judged) {

    /** An approval of one command, never judged: every approval before V67. */
    public RunApproval(String id, long projectId, String conversation, String askedIn,
            String handle, String agent, String side, List<String> argv, String cwd, String reason,
            String state, String scope, List<String> prefix, String answeredBy, Instant answeredAt,
            Instant deliveredAt, Instant createdAt) {
        this(id, projectId, conversation, askedIn, handle, agent, side, argv, cwd, reason, state,
                scope, prefix, answeredBy, answeredAt, deliveredAt, createdAt, null, null);
    }

    /** Compatibility shape for an approval addressed directly to its asking conversation. */
    public RunApproval(String id, long projectId, String conversation, String askedIn, String agent,
            String side, List<String> argv, String cwd, String reason, String state, String scope,
            List<String> prefix, String answeredBy, Instant answeredAt, Instant createdAt) {
        this(id, projectId, conversation, askedIn, null, agent, side, argv, cwd, reason, state,
                scope, prefix, answeredBy, answeredAt, null, createdAt);
    }

    public static final String ASKED = "asked";
    public static final String ALLOWED = "allowed";
    public static final String DENIED = "denied";
    public static final String USED = "used";
    public static final String REVOKED = "revoked";

    /**
     * The {@code answeredBy} of an approval nobody answered: withdrawn by the harness because the
     * acceptance section it asked about changed (spec 2026-09-29 §1b), so a person answering it
     * late is told why it is gone rather than that someone else denied it.
     */
    public static final String SUPERSEDED = "superseded";

    /**
     * The {@code answeredBy} of an approval nobody answered: withdrawn by the harness because the
     * orchestration it was asked for has ended — measured 2026-09-29, orc_318D26A46144920B: eleven
     * acceptance commands were asked of the person for a run cancelled a moment before, and
     * nothing withdrew them.
     */
    public static final String RUN_ENDED = "run ended";

    /**
     * The {@code answeredBy} of an approval nobody was asked: the command judge found what it was
     * shown clearly safe — it only builds, tests, checks or runs the project itself — and allowed
     * it (agents/command_judge.md). Measured 2026-09-29, orc_318DFD3782228160: fifteen approvals,
     * one per acceptance command, and the person's "Why do I get approval bombed?"
     */
    public static final String JUDGE = "command judge";

    public static final String ONCE = "once";
    public static final String CONVERSATION = "conversation";
    public static final String PROJECT = "project";

    /**
     * What a person may allow an approval for, in the order {@code approval.answer} lists them;
     * {@code approval.pre} shows it to a hook as the scopes offered (spec
     * 2026-09-28-hooks-reach-the-log decision 5).
     */
    public static final List<String> SCOPES = List.of(ONCE, CONVERSATION, PROJECT);

    /** How a question's reason begins the input a command is given ({@link #input}). */
    public static final String GIVEN_INPUT = "given input: `";

    /** How many characters of a command's input a question shows before it cuts. */
    static final int INPUT_SHOWN = 80;

    /** How many hex digits of the input's SHA-256 a question carries. */
    private static final int DIGEST_SHOWN = 12;

    /**
     * What a question says about the input a {@code run} call gives its command (spec 2026-09-30,
     * no fake dependencies §4): its first {@value #INPUT_SHOWN} characters with line breaks shown as
     * {@code \n}, its length, and a digest of the whole — so a person sees what they allow, and
     * {@link #givenSameInput} can tell that a later call gives exactly it.
     *
     * @param stdin the input, or null for none
     * @return the line, or null when there is no input
     */
    public static String input(String stdin) {
        if (stdin == null) {
            return null;
        }
        byte[] bytes = stdin.getBytes(StandardCharsets.UTF_8);
        String shown = stdin.replace("\n", "\\n");
        if (shown.length() > INPUT_SHOWN) {
            shown = shown.substring(0, INPUT_SHOWN) + "…";
        }
        return GIVEN_INPUT + shown + "` (" + bytes.length + " bytes, sha256 "
                + digest(bytes).substring(0, DIGEST_SHOWN) + ")";
    }

    /**
     * @param reason why a person is asked, or null
     * @param stdin the command's input, or null
     * @return the reason with {@link #input} after it — or alone, when there is no input
     */
    public static String withInput(String reason, String stdin) {
        String input = input(stdin);
        if (input == null) {
            return reason;
        }
        return reason == null || reason.isBlank() ? input : reason + " — " + input;
    }

    /**
     * Whether a question asked with {@code reason} was asked about a call given exactly
     * {@code stdin}: a question that showed input covers that input only, and one that showed none
     * covers no input only — the input to a program that reads its instructions from stdin is
     * the program's instructions.
     */
    public static boolean givenSameInput(String reason, String stdin) {
        if (stdin == null) {
            return reason == null || !reason.contains(GIVEN_INPUT);
        }
        return reason != null && reason.contains(input(stdin));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("every JVM has SHA-256", unreachable);
        }
    }

    public RunApproval {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(askedIn, "askedIn");
        Objects.requireNonNull(agent, "agent");
        argv = List.copyOf(argv);
        prefix = prefix == null ? null : List.copyOf(prefix);
        commands = commands == null ? null
                : commands.stream().map(each -> List.copyOf(each)).toList();
    }

    /** Whether this approval asks about an acceptance set rather than one command. */
    public boolean isSet() {
        return commands != null;
    }

    /** Every command it covers: the set's, or its one. */
    public List<List<String>> covered() {
        return commands != null ? commands : List.of(argv);
    }

    /** The prefix a person is offered first: the program and its first argument — nothing for a
     *  set, which no project approval covers. */
    public List<String> defaultPrefix() {
        return argv.subList(0, Math.min(2, argv.size()));
    }
}
