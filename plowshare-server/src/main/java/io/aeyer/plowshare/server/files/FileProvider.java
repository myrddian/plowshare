package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * One thing that resolves file requests against a real disk.
 *
 * <p><b>This is the seam, and it is the reason tasks 1–6 of this slice need no
 * transport at all.</b> Server-side code — the file tools, the router that
 * picks between providers — talks to this and never to a socket. {@link
 * LocalProvider} answers from the server's own filesystem; {@code
 * RemoteProvider} will answer over a WebSocket to the machine that submitted the
 * job. Two implementations of one interface, so nothing above this line has to
 * know which kind it is holding.
 *
 * <h2>The two kinds of failure, which must not be merged</h2>
 *
 * <p>The failures of this interface come in exactly two kinds, and the spec's
 * error semantics turn on the difference. Not every method has both — {@link
 * #name()} cannot fail at all and {@link #roots()} raises only the second —
 * but nothing here fails in a third way:
 *
 * <ul>
 *   <li>{@link WorkspaceRefusedException} — the <b>caller was wrong</b>. The path
 *       is outside every root, the pattern is absolute, the file is not text.
 *       The model that asked can correct it on its next turn, so it becomes a
 *       tool result rather than ending the run;
 *   <li>{@link WorkspaceUnavailableException} — the <b>disk could not be asked</b>.
 *       A root that has vanished, a client that has gone. Nothing can be
 *       concluded from the answer, so it ends the run rather than becoming one:
 *       {@code JobRuntime.dependencyFailure} names it, and a job whose tool
 *       raises it stops as {@code UNAVAILABLE}. That type's own javadoc owns the
 *       argument and names the pair of tests that hold the line in both
 *       directions.
 * </ul>
 *
 * <p>Each exception's own javadoc argues its side; what belongs here is that
 * <b>one interface raises both</b>, so an implementation cannot quietly report
 * infrastructure as a caller's mistake by having only one type available to it.
 *
 * <h2>The rule every implementation follows: never answer empty to mean "no"</h2>
 *
 * <p>An empty result is reserved for <em>searched, and there was nothing</em>. A
 * provider that has no roots, or that would have to truncate to answer, says so
 * by raising rather than by returning less than it was asked for. Excalibur's
 * rule from {@code FileAccess.resolve}, and the failure that motivates it: a
 * bare {@code (no matches)} where the real answer is "you were granted nowhere
 * to look" cost one of its agents 15 of its 16 turns on 2026-08-20, inventing
 * new patterns against a root list that was empty the whole time.
 *
 * <p>{@link #roots()} is the one method for which an empty list is a real
 * answer, because "what can I see?" is exactly the question it is asked.
 */
public interface FileProvider {

    /**
     * What to call this provider when something has to name it.
     *
     * <p>Its purpose is the ambiguity refusal: two providers whose roots both
     * cover one path are refused <em>with both named</em>. {@link
     * AmbiguousPathException} is where that argument now lives, including the
     * sentence this javadoc used to end on — it predates that type, and a
     * duplicate of an argument is the thing this file's own neighbours keep
     * getting wrong. What belongs here is only that a provider without a name
     * cannot appear in that refusal.
     */
    String name();

    /**
     * The roots this provider covers <em>right now</em>.
     *
     * <p><b>This may shrink or grow between two calls, and that is not a bug —
     * so no caller may cache it.</b> A remote provider's human moves their
     * workspace mid-run with {@code workspace_set} and the client
     * re-advertises; an operator moves a project's workspace in the {@code
     * projects} table, which is an ordinary operation on a server whose project
     * list is not configuration. {@link LocalProvider} re-reads that table on
     * every call for exactly this reason — a snapshot taken when the job started
     * is a leash that outlives its own revocation.
     *
     * <p>Empty is an ordinary answer and means "nothing is reachable through
     * me": a job in the global tier, a project with no workspace, an agent whose
     * definition asked for no grant. It is the one place this interface returns
     * an empty result rather than raising, because listing what a job can see is
     * the question {@code file_roots} exists to ask.
     *
     * <p><b>Every root is canonical on the filesystem it belongs to</b> — real
     * paths, not the spelling whatever named it happened to use. {@link
     * ProviderRouter} compares a canonicalised candidate against these, and a
     * root advertised as it was typed is one no candidate can match on any host
     * whose tree is reached through a symlink — which on this one is every host
     * whose temp directory is under {@code /tmp} or {@code /var}. It was already
     * true of {@link LocalProvider}, whose roots come out of {@link FileAccess}
     * and are real-pathed there, and {@code
     * a_projects_workspace_is_the_root_its_jobs_reach} is what holds it; it is
     * written down here because the router now depends on it and {@code
     * RemoteProvider} has to do the same for the tree on the far side.
     *
     * @throws WorkspaceUnavailableException if the roots cannot be established —
     *     a client that has gone, a root that is no longer there
     */
    List<Path> roots();

    /**
     * One window of one file's text.
     *
     * <h2>There is no unwindowed read here, and there is not going to be one</h2>
     *
     * <p>A read with no bound on it is the request that closed the file channel
     * with {@code 1009} and took the session with it, and the fix is not a
     * bounded read <em>beside</em> an unbounded one: every caller that has the
     * shorter call available takes it. {@code FileRequest.read} refuses the same
     * overload on the same argument, one layer down.
     *
     * <p><b>Both implementations cut with {@link Window#cut}, and neither works
     * out a range of its own.</b> That is the doubled enforcement this interface
     * exists to carry into two processes: a remote provider that cut differently
     * from a local one would make a file's contents depend on where the job
     * happened to run, and both answers would look like an answer. What is left
     * to an implementation is how a file becomes lines — what a decoder does
     * with bytes that are not text, what a CRLF costs, whether a last line
     * without a newline is a line — and {@link LocalProvider#read} is where this
     * server's answers to those are argued.
     *
     * <p>The window bounds what crosses a wire and says nothing about what an
     * implementation holds while it cuts. {@link LocalProvider#MAX_FILE_BYTES}
     * is the bound on the second of those and is unaffected by this: a file too
     * large to hold is still refused whatever window is asked of it.
     *
     * @param window which lines, and how many. Never null — an absent window is
     *     a question {@code FileRequest.window()} has already answered by the
     *     time anything reaches this seam
     * @return the lines that fit, where they started, how long the file is, and
     *     which limit stopped the read. An empty {@link Span} is the ordinary
     *     answer for an offset at or past the end of the file and for a file
     *     with nothing in it — neither is a refusal
     * @throws WorkspaceRefusedException if the path lies outside every root, or
     *     is not a regular file, or is not UTF-8 text, or is larger than the
     *     implementation will read
     * @throws WorkspaceUnavailableException if the disk could not be asked
     */
    Span read(Path path, Window window);

    /**
     * How long one file is, without moving any of it.
     *
     * <p>The answer is a {@link Span} with no lines in it, so that a caller can
     * tell how many windows a file is before it spends a turn on the first one:
     * <b>paging becomes plannable rather than exploratory</b>. Its {@link
     * Span#totalLines()} is the same number the same file's {@link #read} would
     * report, because it is counted the same way.
     *
     * <p><b>It carries the line count and not the byte size.</b> {@link Span}
     * has nowhere to put a size, and giving it one would put a second unit into
     * a type whose whole vocabulary is lines — an offset in lines, a limit in
     * lines, a total in lines. What a caller does with the answer is ask for a
     * window, and a window is spelled in lines.
     *
     * <p>Every refusal {@link #read} raises is raised here too and for the same
     * reasons. <b>A stat is not a cheaper way past the guards</b>: a file
     * outside the leash, one that is not text, one too large to hold, all
     * refuse before a line is counted, and a stat that answered where a read
     * would not would be a way to measure a file this job may not see.
     *
     * @throws WorkspaceRefusedException if the path lies outside every root, or
     *     is not a regular file, or is not UTF-8 text, or is larger than the
     *     implementation will read
     * @throws WorkspaceUnavailableException if the disk could not be asked
     */
    Span stat(Path path);

    /**
     * Every file below a root whose path, relative to that root, matches the
     * pattern.
     *
     * <p>An empty list means the search ran and matched nothing. A provider with
     * no roots raises instead, because an empty list there would read as "there
     * are no such files" when the truth is "you were granted nowhere to look".
     *
     * @throws WorkspaceRefusedException if there is nothing to search, or the
     *     pattern cannot be used, or more files match than the implementation
     *     will return — never a silent truncation
     * @throws WorkspaceUnavailableException if the disk could not be asked
     */
    List<Path> glob(String pattern);

    /**
     * Every line a needle names, under one path or under every root.
     *
     * <h2>The leash is per file, and a search with no path is where that
     * matters</h2>
     *
     * <p>A {@code null} path means every root this provider covers, which is the
     * question {@code file_glob} cannot answer and the whole reason this method
     * exists. <b>It must not become a way to see what {@link #read} would
     * refuse.</b> So the containment check is applied to each file the walk
     * reaches and not once to the request, and both implementations reach it
     * through {@code FileSearch.eachFile} rather than writing the filter out
     * again — that class argues at length why a second copy of that one line is
     * the copy that matters.
     *
     * <p><b>Both implementations match with {@link Needle}</b>, exactly as both
     * cut with {@link Window#cut}, and for the reason {@link #read} gives: two
     * implementations of "does this line match" would make a file's contents
     * depend on where the job ran, and a search that matched differently hands
     * back a shorter list with nothing in it to say a line was missed. The caps
     * are on that record too, and are the one pair of limits in this system that
     * is not each machine's own — they change an answer rather than producing a
     * refusal.
     *
     * <h2>What a walk does with a file it cannot read as text</h2>
     *
     * <p><b>Skips it, and the search goes on.</b> A repository has an image and
     * a compiled artefact in it; refusing the whole search because one file in a
     * tree is not UTF-8 would make a no-path search a thing that cannot be run
     * on any real workspace, and there is no partial-answer problem here — an
     * unsearchable file has no matches to leave out of the count. The same goes
     * for a file too large for this implementation to hold and for one it may
     * not open.
     *
     * <p><b>A path the caller named is not skipped.</b> There the model asked
     * about that file, and every refusal {@link #read} raises is raised here for
     * the same reasons — a search is not a cheaper way past the guards, exactly
     * as {@link #stat} is not.
     *
     * @param needle what to look for, and whether case matters. Never null
     * @param path one file or one directory, or null for every root
     * @return the matches in file order, and whether the allowance stopped the
     *     search. An empty {@link Found} is the ordinary answer for a needle that
     *     is not there, and is not a refusal
     * @throws WorkspaceRefusedException if there is nothing to search, or the
     *     named path lies outside every root, or the named file cannot be read
     *     as text
     * @throws WorkspaceUnavailableException if the disk could not be asked
     */
    Found grep(Needle needle, Path path);

    /**
     * Replace one file's contents, creating it and the directories above it.
     *
     * <p><b>A change answers with facts</b>, and so do the four below: what was
     * done, as the file side reports it ({@link Changed}), and — when it was
     * refused — a {@link FileRefusedException} carrying the facts beside the
     * words {@link FileWords} made of them. Every sentence about a change is the
     * server's (spec 2026-09-30, the file side reports facts; the server words
     * them).
     *
     * @return what was written, or {@link Changed#fromOldClient} from a client
     *     built before facts
     * @throws WorkspaceRefusedException if the path lies outside every root, or
     *     this provider holds no write grant, or the path names something that
     *     cannot be written
     * @throws WorkspaceUnavailableException if the disk could not be asked
     */
    Changed write(Path path, String content);

    /**
     * {@link #write}, refusing when the file is already there.
     *
     * <p>How a whole-file write that has not read its target is kept from
     * overwriting one: the check is the owning machine's, made as it creates the
     * file, so no second request can land between a look and the write.
     *
     * @throws WorkspaceRefusedException for everything {@link #write} refuses,
     *     and when the file already exists
     * @throws WorkspaceUnavailableException if the disk could not be asked
     */
    Changed create(Path path, String content);

    /**
     * Replace the one occurrence of {@code old} in a file with {@code
     * replacement}, on the machine that holds the file's own bytes.
     *
     * <p>{@link io.aeyer.plowshare.protocol.Replacement} is the rule, and every
     * implementation executes it or its held-to-the-same-table copy. Applying it
     * here rather than above this seam is the point: a read carries lines, so a
     * file's CRLFs and its last newline never reach a caller that could rebuild
     * the file from them.
     *
     * <p>The same class finds what to report, so every implementation reports
     * alike: where the new text is and the lines around it, and — for an {@code
     * old} that is not there — the nearest lines of the file. {@link FileWords}
     * words both.
     *
     * @return the edit's facts; or, from a client built before facts, {@link
     *     Changed#fromOldClient} with that client's view of the edited lines, or
     *     with nothing from one built before views
     * @throws WorkspaceRefusedException if the path is outside every root, there
     *     is no write grant, the file is absent, not a regular file, too large or
     *     not UTF-8, or {@code old} is empty, absent or occurs more than once
     * @throws WorkspaceUnavailableException if the disk could not be asked
     */
    Changed edit(Path path, String old, String replacement);

    /**
     * Remove one file.
     *
     * @throws WorkspaceRefusedException if the path is outside every root, there
     *     is no write grant, or it names nothing, a directory or a link
     * @throws WorkspaceUnavailableException if the disk could not be asked
     */
    Changed delete(Path path);

    /**
     * Rename one file, never over an existing one, creating the directories
     * above the destination.
     *
     * <p>Both paths belong to this provider; a move between two is refused above
     * this seam, because no single machine could carry it out.
     *
     * @throws WorkspaceRefusedException if either path is outside every root,
     *     there is no write grant, the source names nothing, a directory or a
     *     link, or the destination already exists
     * @throws WorkspaceUnavailableException if the disk could not be asked
     */
    Changed move(Path from, Path to);

    /**
     * Start a command in a directory this provider owns and wait for it, on the
     * machine that directory is on.
     *
     * <p><b>Nothing here decides whether it may run.</b> {@code RunTool}'s gate has
     * done that from the environment before this is called; what is checked here
     * is the fence on {@code cwd} and the write grant, since a command can change
     * anything in the tree it runs in. A remote provider's client decides again,
     * from its own file.
     *
     * @param side what the environment allows on this side, already resolved
     * @param timeout how long it may take, already capped at {@code side}'s
     * @param cancelled asked while the command runs; true kills it
     * @throws WorkspaceRefusedException if {@code cwd} is outside every root or
     *     not a directory, there is no write grant, or the program cannot be found
     *     or started
     * @throws WorkspaceUnavailableException if the machine could not be asked
     */
    CommandRunner.Outcome run(Path cwd, List<String> argv,
            EnvironmentFile.Side side, Duration timeout,
            BooleanSupplier cancelled);

    /**
     * {@link #run(Path, List, EnvironmentFile.Side, Duration, BooleanSupplier)} with {@code stdin}
     * written to the command — spec 2026-09-29 §1b. A provider that cannot give input refuses.
     *
     * @param stdin the command's input, or null for none
     * @return how the command ended
     */
    default CommandRunner.Outcome run(Path cwd, List<String> argv, EnvironmentFile.Side side,
            Duration timeout, String stdin, BooleanSupplier cancelled) {
        if (stdin == null) {
            return run(cwd, argv, side, timeout, cancelled);
        }
        throw new WorkspaceRefusedException(name() + " cannot give a command input");
    }
}
