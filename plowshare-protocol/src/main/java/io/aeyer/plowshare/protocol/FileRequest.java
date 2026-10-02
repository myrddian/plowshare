package io.aeyer.plowshare.protocol;

import java.util.List;
import java.util.Map;

/**
 * One thing the server asks the machine that owns the files to do.
 *
 * <p>It travels down the WebSocket the client opened, and the client answers
 * with a {@link FileReply} carrying the same {@link #id}. <b>Correlation is the
 * id and nothing else</b> — not arrival order — because a job is a virtual
 * thread and several of them are blocked on this one socket at any moment, so
 * two answers can cross.
 *
 * <h2>Why the frames live here</h2>
 *
 * <p>{@code plowshare-server} writes them and {@code plowshare-client} reads
 * them, and the client must not depend on the server. Hand-writing the same JSON
 * on both sides was the alternative — {@code ServerClient.IndexEntry} does it
 * that way for the HTTP surface and its javadoc says the shared type would be
 * better — and it fails silently here in a way it does not there: a server
 * sending {@code "path"} to a client reading {@code "file"} refuses every
 * request with a message about a missing path, and the two halves ship
 * separately.
 *
 * <h2>The fields not every op uses are null, and that is the shape</h2>
 *
 * <p>An operation with arguments, not five record types behind a discriminator.
 * {@link #op} says which fields mean anything, the factory methods are the only
 * way this is built inside Plowshare, and a reader who wants to know what {@code
 * read} carries reads {@link #read}.
 *
 * <h2>{@link #offset} and {@link #limit} are boxed on purpose</h2>
 *
 * <p>Absent is a state this record has to be able to hold. A {@link #STAT}, a
 * {@link #GLOB} and a {@link #ROOTS} have no window to carry, and a {@link
 * #READ} sent by a server built before windows existed carries none either — and
 * <b>null is the only thing that tells those apart from a caller asking for line
 * zero</b>, which is a request a reader has to answer differently. An {@code
 * int} would make "no window was sent" and "the window starts at the top of the
 * file" the same frame, and the second of those is the commonest read there is.
 * {@code TurnRecord.promptTokens} is boxed for the same reason and argues it at
 * length: an absence read as a measurement is worse than an absence that cannot
 * be expressed.
 *
 * <p>What an absent window <em>means</em> is {@link #window}'s to say, and it
 * says it once so that no reader of this frame has to decide.
 *
 * @param id the correlation, minted by the server, unique among the requests
 *     outstanding on one session
 * @param op one of {@link #ROOTS}, {@link #READ}, {@link #STAT}, {@link #GLOB},
 *     {@link #WRITE}, {@link #GREP}, {@link #EDIT}, {@link #DELETE}, {@link #MOVE}
 * @param path the file, for {@link #READ}, {@link #STAT}, {@link #WRITE},
 *     {@link #EDIT} and {@link #DELETE}, and the source for {@link #MOVE},
 *     spelled as the model wrote it — and optionally for {@link #GREP}, the one
 *     op on this frame for which an absent path is an instruction rather than an
 *     omission: it means every root. <b>Sent as the caller typed it and never
 *     canonicalised here</b>: {@code FileAccess.canonical} is not idempotent past
 *     its hop budget, so a path canonicalised on the server and again on the
 *     client resolves two different distances along a long link chain — and the
 *     client is the only one of the two that can resolve it against the disk it
 *     is about anyway
 * @param pattern the glob, for {@link #GLOB}. <b>Not reused by {@link #GREP},
 *     which is what {@link #needle} is for</b> — that field's javadoc argues why
 *     one string field must not carry two things matched by different rules
 * @param content what to write, for {@link #WRITE}, verbatim — a trailing
 *     newline is part of a file and an empty string is an ordinary edit — and
 *     the replacement text for {@link #EDIT}, which may be empty
 * @param offset the first line for READ, or the first byte for SOURCE; absent
 *     SOURCE offset and limit request fresh size/hash metadata
 * @param limit how many lines for READ or bytes for SOURCE, or null. <b>Kept
 *     exactly as it was sent, cap and all</b>: this is the frame, and {@link
 *     #window} is where it becomes a bound this build will serve
 * @param needle what to look for, for {@link #GREP}. <b>A separate field from
 *     {@link #pattern} rather than a second meaning for it</b>, though both are
 *     strings: {@code pattern} is matched against a <em>path</em> by {@code
 *     GlobSpellings}, and this is matched against the <em>text of a line</em> by
 *     {@link Needle}. One field holding both would make what a frame is compared
 *     with depend on the op rather than merely which fields are relevant, and a
 *     grep whose argument is called {@code pattern} is also a grep a model
 *     writes {@code .*} into.
 *     <p><b>Named for what it is looked for in and not for how it is matched</b>,
 *     which the spec's §3a asks of every layer below the tool description: the
 *     first implementation matches literally, a bounded regex or a shelled-out
 *     engine is the change that would justify extracting a seam, and a field
 *     called {@code literal} would be a wire record that had to be renamed on
 *     the day one arrived
 * @param ignoreCase whether the search folds case, for {@link #GREP}, or null.
 *     <b>Boxed for {@link #offset}'s reason and with a different default.</b> A
 *     server built before this op sends no such key, and a lenient mapper binds
 *     the absence to null; {@link #needle} reads null as case-sensitive, which
 *     is the reading whose answer is never a surprise
 * @param purpose {@link #DEFINITIONS} for a request the harness sends to read
 *     {@code .plowshare/agents} or {@code .plowshare/bots} — see {@link
 *     #forDefinitions()} — {@link #HOOKS} for one reading {@code .plowshare/hooks/}
 *     — see {@link #forHooks()} — or null for every request on a model's own path.
 *     Null from every factory below, on purpose: this is not one more argument
 *     a caller fills in, it is a mark the harness stamps on afterward, in the
 *     one place {@code ChannelDefinitions} or {@code ChannelHooks} sends every
 *     request through
 * @param replacing the text to replace, for {@link #EDIT}: it must occur in the
 *     file exactly once, and {@link Replacement} is the rule every side runs
 * @param to the destination, for {@link #MOVE}, spelled as the model wrote it
 *     and never canonicalised here, for {@link #path}'s reason
 * @param createOnly for {@link #WRITE}: true refuses when the file already
 *     exists, which is how a whole-file write that has not read its target is
 *     kept from overwriting one. <b>Boxed for {@link #ignoreCase}'s reason</b>:
 *     a frame from a server built before it carries no key, and null must read
 *     as the write that frame always meant
 * @param argv the command, for {@link #RUN}: the program and its arguments, run
 *     with no shell. {@link #path} is its working directory
 * @param env the variables {@link #RUN} sets explicitly, already resolved from
 *     the environment file on the server
 * @param inherit the host variables {@link #RUN} passes through
 * @param timeoutMillis how long {@link #RUN} may take before it is killed
 * @param outputBytes how much of each of stdout and stderr {@link #RUN} keeps,
 *     from the end
 * @param shells whether the server resolved this side as allowing a shell. <b>A
 *     client does not take this on trust</b>: it reads its own environment file
 *     and refuses what that forbids, whatever this says
 * @param stdin what to write to {@link #RUN}'s command input, or null to close
 *     it at once — an acceptance command's {@code stdin:} (spec 2026-09-29 §1b)
 */
public record FileRequest(
        String id,
        String op,
        String path,
        String pattern,
        String content,
        Integer offset,
        Integer limit,
        String needle,
        Boolean ignoreCase,
        String purpose,
        String replacing,
        String to,
        Boolean createOnly,
        List<String> argv,
        Map<String, String> env,
        List<String> inherit,
        Long timeoutMillis,
        Long outputBytes,
        Boolean shells,
        String stdin) {

    /**
     * The nine-argument shape every existing call site still builds — {@code
     * purpose} is not a model's or a caller's to set, so nothing above this
     * class names it, and the field starts null exactly as it always has for a
     * request nothing has marked. {@link #forDefinitions()} and {@link #forHooks()} are the only
     * ways it ever becomes non-null.
     */
    public FileRequest(
            String id,
            String op,
            String path,
            String pattern,
            String content,
            Integer offset,
            Integer limit,
            String needle,
            Boolean ignoreCase) {
        this(id, op, path, pattern, content, offset, limit, needle, ignoreCase, null);
    }

    /** The ten-argument shape, from before the edit, delete and move fields existed. */
    public FileRequest(
            String id,
            String op,
            String path,
            String pattern,
            String content,
            Integer offset,
            Integer limit,
            String needle,
            Boolean ignoreCase,
            String purpose) {
        this(id, op, path, pattern, content, offset, limit, needle, ignoreCase, purpose,
                null, null, null);
    }

    /** The thirteen-argument shape, from before {@link #RUN} existed. */
    public FileRequest(
            String id,
            String op,
            String path,
            String pattern,
            String content,
            Integer offset,
            Integer limit,
            String needle,
            Boolean ignoreCase,
            String purpose,
            String replacing,
            String to,
            Boolean createOnly) {
        this(id, op, path, pattern, content, offset, limit, needle, ignoreCase, purpose,
                replacing, to, createOnly, null, null, null, null, null, null, null);
    }

    /** What can this session see? Answered with {@link FileReply#paths()}. */
    public static final String ROOTS = "roots";

    /** One window of one file. Answered with {@link FileReply#span()}. */
    public static final String READ = "read";

    /** Raw source: absent offset/limit asks for size and SHA-256; otherwise one
     * bounded byte range. Conversion and text-windowing remain on the server. */
    public static final String SOURCE = "source";

    public static FileRequest source(String id, String path, Integer offset, Integer limit) {
        return new FileRequest(id, SOURCE, path, null, null, offset, limit, null, null);
    }

    /** How big is it, and how many lines? Answered with {@link FileReply#span()}. */
    public static final String STAT = "stat";

    /** Every file matching a pattern. Answered with {@link FileReply#paths()}. */
    public static final String GLOB = "glob";

    /** Replace one file. Answered with {@link FileReply#changed}. */
    public static final String WRITE = "write";

    /**
     * Every line under a path — or under every root — that a needle names.
     * Answered with {@link FileReply#found()}.
     *
     * <p><b>The only op on this frame whose {@link #path} may be absent on
     * purpose.</b> Absent means every root this session holds, which is the
     * question {@code file_glob} cannot answer and the reason this op exists:
     * asking it per file would need a glob and a loop, which is the walk the
     * tool was written to remove.
     */
    public static final String GREP = "grep";

    /** Replace the one occurrence of {@link #replacing} with {@link #content}.
     *  Answered with {@link FileReply#changed}: where the new text is and the
     *  lines around it, or why it was not made. */
    public static final String EDIT = "edit";

    /** Remove one file. Answered with {@link FileReply#changed}. */
    public static final String DELETE = "delete";

    /** Rename one file to {@link #to}, never over an existing one. Answered with
     *  {@link FileReply#changed}. */
    public static final String MOVE = "move";

    /** Start {@link #argv} in {@link #path} and wait for it. Answered with the
     *  outcome fields of {@link FileReply}. */
    public static final String RUN = "run";

    /** Kill the {@link #RUN} whose request id is {@link #path}. Answered with an ok
     *  reply carrying nothing, whether or not that command was still running. */
    public static final String CANCEL = "cancel";

    /**
     * {@link #purpose}'s one value: this request is the harness reading its own
     * definitions, not a model reaching for a file. See {@link #forDefinitions()}.
     */
    public static final String DEFINITIONS = "definitions";

    /**
     * {@link #purpose}'s second value: the harness reading a session's {@code .plowshare/hooks/}
     * to snapshot a log's local hooks (spec 2026-09-30-local-hooks-are-served decision 2). Its
     * own mark and not {@link #DEFINITIONS}: a hook is code the server runs, not a definition, and
     * each capability gets its own mark. See {@link #forHooks()}.
     */
    public static final String HOOKS = "hooks";

    public static FileRequest roots(String id) {
        return new FileRequest(id, ROOTS, null, null, null, null, null, null, null);
    }

    /**
     * One window of one file.
     *
     * <p><b>There is no unwindowed overload of this, deliberately.</b> A read
     * with no bound on it is the request that closed the file channel with
     * {@code 1009} and took the session with it, and a factory that still built
     * one would be the shortest path back to that bug — every caller that has
     * one available takes it, because it is the shorter call.
     */
    public static FileRequest read(String id, String path, Window window) {
        return new FileRequest(
                id, READ, path, null, null, window.offset(), window.limit(), null, null);
    }

    /**
     * How long the file is, without moving any of it.
     *
     * <p>No window, because there is nothing to bound: the answer is a {@link
     * Span} with no lines in it. That is what makes a windowed read plannable
     * rather than exploratory — a caller can tell how many windows a file is
     * before it spends a turn on the first one.
     */
    public static FileRequest stat(String id, String path) {
        return new FileRequest(id, STAT, path, null, null, null, null, null, null);
    }

    public static FileRequest glob(String id, String pattern) {
        return new FileRequest(id, GLOB, null, pattern, null, null, null, null, null);
    }

    public static FileRequest write(String id, String path, String content) {
        return new FileRequest(id, WRITE, path, null, content, null, null, null, null);
    }

    /** A write that refuses to replace a file that is already there. */
    public static FileRequest create(String id, String path, String content) {
        return new FileRequest(id, WRITE, path, null, content, null, null, null, null, null,
                null, null, true);
    }

    public static FileRequest edit(String id, String path, String replacing, String replacement) {
        return new FileRequest(id, EDIT, path, null, replacement, null, null, null, null, null,
                replacing, null, null);
    }

    public static FileRequest delete(String id, String path) {
        return new FileRequest(id, DELETE, path, null, null, null, null, null, null);
    }

    public static FileRequest move(String id, String path, String to) {
        return new FileRequest(id, MOVE, path, null, null, null, null, null, null, null,
                null, to, null);
    }

    public static FileRequest run(String id, String cwd, List<String> argv, Map<String, String> env,
            List<String> inherit, long timeoutMillis, long outputBytes, boolean shells) {
        return run(id, cwd, argv, env, inherit, timeoutMillis, outputBytes, shells, null);
    }

    /** {@link #run}, with {@code stdin} to write to the command's input — or null to close it. */
    public static FileRequest run(String id, String cwd, List<String> argv, Map<String, String> env,
            List<String> inherit, long timeoutMillis, long outputBytes, boolean shells, String stdin) {
        return new FileRequest(id, RUN, cwd, null, null, null, null, null, null, null, null, null,
                null, List.copyOf(argv), Map.copyOf(env), List.copyOf(inherit), timeoutMillis,
                outputBytes, shells, stdin);
    }

    /** Kill the command {@code runId} started. */
    public static FileRequest cancel(String id, String runId) {
        return new FileRequest(id, CANCEL, runId, null, null, null, null, null, null);
    }

    /** Whether this write may only create: {@link #createOnly}, with null read as false. */
    public boolean creating() {
        return createOnly != null && createOnly;
    }

    /**
     * Every line a needle names, under one path or under every root.
     *
     * <p>The path is nullable here and nowhere else on this record, and the
     * factory takes it rather than offering a second overload without it. Two
     * factories would make the shorter one the one every caller reaches for —
     * {@link #read}'s javadoc records what that costs when the shorter call is
     * the dangerous one — and here neither is dangerous: a search with no path
     * is the ordinary case, and the leash is what bounds it either way.
     */
    public static FileRequest grep(String id, String path, Needle needle) {
        return new FileRequest(id, GREP, path, null, null, null, null,
                needle.text(), needle.ignoreCase());
    }

    /**
     * The window this frame asks for: what {@link #offset} and {@link #limit}
     * mean once, in the one place, rather than at whichever call site reads them
     * next.
     *
     * <h2>A limit above the cap is brought down to it, not refused</h2>
     *
     * <p>This delegates to {@link Window#of}, and the delegation is the whole
     * argument. The cap is enforced twice — {@code Window.of} clamps and the
     * canonical constructor throws — and <b>the two halves of the doubled
     * enforcement must not disagree about the same number merely because one of
     * them arrived over a socket</b>. A server calling {@code Window.of(0,
     * 50_000)} in this process gets the cap; if a frame carrying 50 000 were
     * refused instead, the same read would succeed locally and fail remotely,
     * which is precisely the drift this module exists to prevent and the hardest
     * kind to see — both behaviours look correct from inside their own half.
     *
     * <p>Refusing is defensible on its own terms and is rejected on those terms:
     * it is honest to a client that is out of spec, and the honesty costs a
     * client built against an older or newer release a whole turn to learn a
     * number this method already knows. Nothing about the clamp is silent — the
     * {@link Span} in the reply says which lines came back and whether more
     * remains, so a caller that asked for too much is told exactly what it got.
     *
     * <p>It clamps in one direction only, which is {@code Window.of}'s split and
     * not a new one: a negative offset or a limit below one still throws. A
     * limit that is too large is a caller being optimistic about a file it has
     * not read; a limit that cannot return a line is a caller being wrong, and a
     * window that returns nothing while reporting that more remains is a loop a
     * model cannot get out of.
     *
     * <h2>A frame with no window asks for the first window</h2>
     *
     * <p>A null {@link #offset} is the top of the file and a null {@link #limit}
     * is {@link Window#MAX_WINDOW_LINES}, each defaulting on its own. <b>This is
     * what a server built before this change sends</b> — those keys are simply
     * not in its frames, and a lenient mapper binds the missing keys to null
     * rather than failing, which is the version skew the two halves shipping
     * separately guarantees will happen.
     *
     * <p>Answering it with the first window rather than refusing it is the
     * choice with the smaller blast radius in both directions. Refusing turns a
     * build mismatch into every file read failing, where today every read under
     * the transport buffer works; answering it with the <em>whole file</em> is
     * not available, because that is the request this slice exists to make
     * impossible. The first window is what the old caller would have got anyway
     * for any file that fitted, and for the files that did not it is the
     * difference between a truncated answer and a dead session.
     */
    public Window window() {
        return Window.of(offset == null ? 0 : offset,
                limit == null ? Window.MAX_WINDOW_LINES : limit);
    }

    /**
     * What this frame asks to be looked for: what {@link #needle} and {@link
     * #ignoreCase} mean, once, rather than at whichever call site reads them
     * next.
     *
     * <p><b>Named apart from the component it is built from because a record
     * cannot have both.</b> {@link #window} has no such collision and would have
     * been called the same thing; this is the same method wearing the only other
     * name available.
     *
     * <p>{@link #window}'s shape and its argument. The defaulting and the
     * refusal both live here so that the server half and the client half cannot
     * read one frame two ways — a client that treated an absent {@code
     * ignoreCase} as <em>fold</em> while the server treated it as <em>do
     * not</em> would return different matches for one search, and neither
     * answer would look like a failure.
     *
     * <p><b>It throws where {@link #window} clamps, and the difference is what
     * can be salvaged.</b> A limit above the cap has an obvious smaller value
     * that is certainly what the caller wanted; a needle with nothing in it has
     * no such repair — every line contains the empty string, so the only
     * available "fix" is to answer with the first {@link Needle#MAX_MATCHES}
     * lines of the tree, which is the confident empty answer inverted.
     * {@code ClientEnforcer} turns the exception into a refusal the model can
     * read, exactly as it does for an unserviceable window.
     */
    public Needle sought() {
        return new Needle(needle, ignoreCase != null && ignoreCase);
    }

    /**
     * The same request, marked as the harness reading definitions rather than a
     * model reaching for a file.
     *
     * <p>A client keeps every hidden path from a model (TODO §13) and lets this
     * mark, and only this mark, read {@code .plowshare/agents} and
     * {@code .plowshare/bots}. Nothing on a model's path may call this: the
     * restriction is on the model, and this is how the harness says it is not one.
     */
    public FileRequest forDefinitions() {
        return new FileRequest(id, op, path, pattern, content, offset, limit, needle, ignoreCase,
                DEFINITIONS, replacing, to, createOnly, argv, env, inherit, timeoutMillis, outputBytes,
                shells, stdin);
    }

    /**
     * The same request, marked as the harness reading a session's local hooks. A client lets this
     * mark, and only this mark, read, stat and glob {@code .plowshare/hooks/<one name>}; nothing
     * on a model's path may call it (spec 2026-09-30-local-hooks-are-served decision 2).
     */
    public FileRequest forHooks() {
        return new FileRequest(id, op, path, pattern, content, offset, limit, needle, ignoreCase,
                HOOKS, replacing, to, createOnly, argv, env, inherit, timeoutMillis, outputBytes,
                shells, stdin);
    }
}
