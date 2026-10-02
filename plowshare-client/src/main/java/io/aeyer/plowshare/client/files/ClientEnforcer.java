package io.aeyer.plowshare.client.files;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileSource;
import io.aeyer.plowshare.protocol.FileSearch;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.GlobSpellings;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Replacement;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The client's own answer to a server's file request, checked against the
 * workspace this session holds <em>now</em>.
 *
 * <p>Modern sessions advertise source-byte serving: {@link FileRequest#SOURCE}
 * checks the fence and streams disk bytes without conversion. The server owns
 * conversion and its text cache. READ/STAT/GREP retain the legacy text behavior
 * for older servers, including the local converter described below.
 *
 * <h2>This is the enforcement point, not a copy of the server's</h2>
 *
 * <p>The spec's claim is that the client is where containment actually happens,
 * and this class is what makes that true rather than a slogan. It is a
 * <b>second execution</b> of {@link FileAccess} — the same class the server runs,
 * moved into {@code plowshare-protocol} so there could not be two implementations
 * of it — in the process that opens the file, against state only this process
 * has. Excalibur's {@code file_grep} child process is the precedent, and its
 * words: <i>a second, independent enforcement in the process that actually opens
 * the files</i>.
 *
 * <p>What makes it independent is not a rewritten algorithm. It is <b>what it
 * checks against</b>: the server routed on roots it read one round trip ago and
 * this checks against {@link Workspace} as it stands at the moment the file would
 * be opened. When those disagree the client wins, and the disagreement is the
 * design working rather than a race to fix.
 *
 * <h2>What this class does not decide</h2>
 *
 * <p>Whether the agent asking was allowed to write at all. That is the {@code
 * scopes:} its definition declared, which lives on the server and is checked
 * there before a write request is ever sent — telling this process about it would
 * hand the enforcement of a declaration to the party the declaration is about.
 * The two halves are split by who knows what, and neither is a duplicate of the
 * other.
 *
 * <h2>The two kinds of failure, drawn where {@code LocalProvider} draws them</h2>
 *
 * <ul>
 *   <li><b>refused</b> — outside the workspace, no workspace set at all, not a
 *       regular file, not text, a pattern that cannot be used. The model reads
 *       the sentence and names something else;
 *   <li><b>unavailable</b> — a root that was set and is no longer on the disk.
 *       Not the same state as one nobody ever set: that one is somebody's to
 *       set, and this one makes every later answer meaningless. Without the
 *       split, a {@code file_glob} over a deleted tree is a confident {@code (no
 *       matches)}.
 * </ul>
 *
 * <p>A failure of this machine's own disk — a full filesystem, an unreadable
 * directory — is <b>refused</b>, with {@code LocalProvider}'s argument and its
 * cost: no portable type separates those from an ordinary caller's mistake, and
 * splitting on the types that <em>are</em> named would put an ordinary mistake on
 * the outage side. The platform's reason is carried into the answer, where a
 * reader sees it.
 *
 * <h2>Every answer is facts, and the server words them</h2>
 *
 * <p>A write, an edit, a delete and a move answer with a {@link FileResult} and
 * no sentence, refused or not; so does every refusal of a read, a stat, a glob
 * and a search, a picture a read named, and an outage (spec 2026-09-30, the
 * file side reports facts; the server words them). What a model reads is
 * written once, on the server, from what this class reports: where the new text
 * is and the lines around it, which rule refused it and about which path.
 * <b>This class holds no word of it.</b> The one exception is a {@link
 * FileRequest#RUN}'s own consent and bounds, whose output is already data and
 * whose refusals are that spec's last step; its working directory's fence is
 * facts like every other path's.
 *
 * <h2>Limits are this machine's own, and that is the deliberate half</h2>
 *
 * <p>{@link GlobSpellings} is shared because two expansions of {@code **&#47;}
 * return two different file sets into one listing with nothing to say so. These
 * limits are not shared, because a limit produces a <em>refusal</em> — a sentence
 * naming the file and the number — and a laptop and a server are entitled to
 * different answers about how much of their own memory they will spend.
 *
 * <p><b>{@link Window} is on the shared side, and it is the sharpest case of the
 * rule rather than an exception to it.</b> It looks like a limit and is not one:
 * it produces an <em>answer</em>, and a client that cut a range differently from
 * the server would make a file's contents depend on which machine the job
 * happened to run on — two plausible windows, neither of which looks like a
 * failure. So {@link FileRequest#window} decides what a frame asks for and
 * {@link Window#cut} decides what comes back, on both halves of the wire, and
 * nothing in this class does the arithmetic itself.
 */
public final class ClientEnforcer {

    /**
     * The most this client will read into memory as one string.
     *
     * <p>Measured on JDK 21: {@code Files.readString} of an 8 MiB file returns
     * all 8388608 characters, so nothing in the platform bounds this. The number
     * matches {@code LocalProvider.MAX_FILE_BYTES} today and is deliberately not
     * shared with it: it is a judgement about the heap of whichever machine is
     * doing the reading, and this one is somebody's laptop.
     *
     * <p>Refused at, never truncated to — a prefix handed back reads as the file.
     */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    /**
     * The most paths one {@link FileRequest#GLOB} will return.
     *
     * <p>Refused at, never truncated to, for the reason {@code
     * LocalProvider.MAX_MATCHES} gives: a list that stops at a limit reads as a
     * complete one, which is the confident empty answer wearing a different hat.
     */
    static final int MAX_MATCHES = 10_000;

    private final Workspace workspace;

    /**
     * The adaptor in front of a read, and the buffer it keeps.
     *
     * <p>One per enforcer, which is one per session: what a session converted is
     * held for as long as it is serving and given back with it. A buffer shared
     * across sessions would outlive the workspace it was filled from, and this
     * process may root somewhere else at any moment.
     */
    private final Conversions conversions;

    /**
     * Where a picture this client reads gets its name, and never where one is
     * stored.
     *
     * <p>{@link ImageUploads#NONE} names nothing and this class then behaves
     * exactly as it did before workspace images existed: a PNG is refused as not
     * UTF-8 text. That is {@code LocalProvider}'s {@code ImageStore.NONE} one
     * module over, and it is what makes the whole change additive rather than a
     * new answer every session gets.
     */
    private final ImageUploads uploads;

    /**
     * The file under a root that says whether commands may run there — read by
     * this client for its own consent, and the one hidden path the harness's
     * {@link FileRequest#DEFINITIONS} mark may read.
     */
    static final Path ENVIRONMENT_FILE = Path.of(".plowshare", "environment.yml");

    /**
     * The cancel flag of every {@link FileRequest#RUN} still going, by request id.
     *
     * <p>A {@link FileRequest#CANCEL} names a run by its id and nothing else, and
     * it arrives on another thread while the run's own thread is blocked in
     * {@link CommandRunner#run} — which asks its flag every 100 ms. Removed when
     * the run returns, so a late cancel finds nothing and is still answered done.
     */
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    /** A client that names no pictures. See {@link ImageUploads#NONE}. */
    public ClientEnforcer(Workspace workspace) {
        this(workspace, ImageUploads.NONE);
    }

    /**
     * The same enforcer, on a session that can name a picture it reads.
     *
     * @param uploads where a named file whose bytes {@link ImageFormat}
     *     recognises is sent to get an id. {@link ImageUploads#NONE} for a
     *     session that names none, which is the one-argument form above
     */
    public ClientEnforcer(Workspace workspace, ImageUploads uploads) {
        this(workspace, new Conversions(), uploads);
    }

    /**
     * @param conversions what this client can convert, and where it holds the
     *     results. A parameter so a test can bound the buffer without an
     *     eight-megabyte fixture; nothing in main passes anything but the
     *     standard set
     */
    ClientEnforcer(Workspace workspace, Conversions conversions) {
        this(workspace, conversions, ImageUploads.NONE);
    }

    ClientEnforcer(Workspace workspace, Conversions conversions, ImageUploads uploads) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.conversions = Objects.requireNonNull(conversions, "conversions");
        this.uploads = Objects.requireNonNull(uploads, "uploads");
    }

    /**
     * Do it, or say why not.
     *
     * <p><b>Never throws for anything a server can send.</b> Every outcome is a
     * reply, because the thing on the other end is a job blocked on an answer: an
     * exception escaping here sends nothing, the request sits until its deadline,
     * and it is reported as a session that went quiet — a true sentence about a
     * client that is sitting right there, and the wrong thing for anybody to read.
     *
     * <p><b>The one exception is a null request, and it is deliberate and
     * qualified.</b> {@code requireNonNull} below is about this process's own
     * wiring rather than about a frame: {@link ChannelClient} only calls this with
     * a request Jackson bound, and a null one would mean the caller passed
     * nothing. It is still worth naming, because that caller runs this on an
     * anonymous virtual thread — so the failure is a dropped answer with no log
     * line, which is the worst shape a bug can have here. Nothing constructs a
     * null request today; if a second caller ever does, this needs a log line
     * rather than a throw.
     */
    public FileReply answer(FileRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return switch (request.op() == null ? "" : request.op()) {
                case FileRequest.ROOTS -> FileReply.listed(request.id(), strings(roots()));
                case FileRequest.SOURCE -> source(request);
                case FileRequest.READ -> read(request);
                case FileRequest.STAT -> stat(request);
                case FileRequest.GLOB ->
                        FileReply.listed(request.id(), strings(glob(request.pattern())));
                // The needle is worked out before the disk is touched, exactly
                // as the window is: a frame this build cannot serve is refused
                // without a walk. `Needle` and not matching written out here,
                // for FileProvider.grep's reason — two implementations of "does
                // this line match" make a file's contents depend on where the
                // job ran.
                case FileRequest.GREP -> grep(request.id(), needle(request), request.path());
                case FileRequest.WRITE -> FileReply.changed(request.id(),
                        write(request.path(), request.content(), request.creating()));
                case FileRequest.EDIT -> FileReply.changed(request.id(),
                        edit(request.path(), request.replacing(), request.content()));
                case FileRequest.DELETE -> FileReply.changed(request.id(), delete(request.path()));
                case FileRequest.MOVE -> FileReply.changed(request.id(),
                        move(request.path(), request.to()));
                // Minutes, possibly, on this thread. ChannelClient answers every
                // request on a virtual thread of its own, so the cancel for this
                // run is answered while it waits.
                case FileRequest.RUN -> FileReply.ran(request.id(), run(request));
                case FileRequest.CANCEL -> {
                    cancel(request.path());
                    yield FileReply.done(request.id());
                }
                // A server asking for something this client has never heard of.
                // The two halves ship separately, so this is a version gap
                // rather than a bug, and it is refused rather than reported as
                // an outage: this client is fine, and the operator's fix is to
                // match the two builds.
                default -> FileReply.refused(request.id(),
                        FileResult.refused(request.op(), FileResult.UNKNOWN_OP, null));
            };
        } catch (Refused correctable) {
            // Facts on every path but a run's own consent and bounds, which
            // still carry their sentence.
            return correctable.facts != null
                    ? FileReply.refused(request.id(), correctable.facts)
                    : FileReply.refused(request.id(), correctable.getMessage());
        } catch (Vanished gone) {
            return FileReply.unavailable(request.id(), gone.facts);
        } catch (RuntimeException unexpected) {
            // A bug in this class rather than a state it knows about. Reported
            // as an outage and not as a refusal, because a model told "you may
            // try something else" about a defect will spend every remaining turn
            // trying something else.
            return FileReply.unavailable(request.id(), FileResult.unavailable(request.op(),
                    FileResult.INTERNAL, null, String.valueOf(unexpected)));
        }
    }

    // --- the operations ------------------------------------------------------

    private List<Path> roots() {
        // Reachability before anything else, and empty is an ordinary answer:
        // "what can I see?" is the question, and "nothing yet" is a true reply
        // to it. The sentence saying WHY nothing is reached through a glob,
        // which is how ProviderRouter.absence asks a provider which state it is
        // in without FileProvider needing a method for it.
        return reachable(FileRequest.ROOTS).roots();
    }

    /**
     * One window of one file, or the refusal saying why there is not one.
     *
     * <p>The window is worked out before the file is opened, so a frame this
     * build cannot serve is refused without touching the disk. {@link
     * Window#cut} and not arithmetic written out here: {@code FileProvider.read}
     * makes the same call for the same reason, and two ranges computed from one
     * request would make a file's contents depend on which machine the job ran
     * on.
     *
     * <p>{@link Window.LineTooWide} is translated for {@link #window}'s reason,
     * one step later. {@code Window} can raise nothing but an unchecked
     * exception, and this one reaching {@link #answer}'s {@code
     * RuntimeException} clause would come back {@code UNAVAILABLE} and <b>end
     * the run</b> — over a file this client read without difficulty and could
     * not put on a socket, which is the exact ending this refusal exists to
     * remove.
     *
     * <p><b>Answered with the line, its size and the bound</b>, and the server
     * words it, as it words the same catch in its own provider.
     *
     * <p>A picture the read named answers with its id and no lines ({@link
     * FileReply#named}): the one line a reader is handed is the server's to
     * write, and to cut with this same window.
     */
    private FileReply read(FileRequest request) {
        String op = FileRequest.READ;
        Window window = window(request, op);
        Decoded decoded = lines(request, op);
        if (decoded.named() != null) {
            return FileReply.named(request.id(), decoded.named());
        }
        try {
            return FileReply.answered(request.id(), window.cut(decoded.lines()));
        } catch (Window.LineTooWide unreadable) {
            throw new Refused(FileResult.lineTooWide(op, request.path(), unreadable.offset(),
                    unreadable.bytes(), Window.MAX_WINDOW_BYTES));
        }
    }

    /**
     * The window this frame asks for, or the refusal saying why it is not one.
     *
     * <p>{@link FileRequest#window} owns what the two boxed fields mean — that
     * an over-cap limit is clamped rather than refused, and that a frame with
     * neither of them asks for the first window of the file. Neither is decided
     * again here: this method's whole content is a call and a translation, and
     * anything else in it would be the second half of a doubled enforcement
     * disagreeing with the first.
     *
     * <p>The translation is the part that has to be here. {@code Window}'s
     * canonical constructor still throws for the frames the clamp deliberately
     * does not cover — a negative offset, a limit that cannot return a line —
     * and the protocol module has neither module's refusal type, so an
     * unchecked exception is all it can raise. Left alone it would reach {@link
     * #answer}'s {@code RuntimeException} clause and come back as {@code
     * UNAVAILABLE}, which <b>ends the run</b> over a frame this client is
     * perfectly well enough to answer. That is the version gap the unknown-op
     * arm already refuses rather than reports as an outage, arriving through a
     * different field of the same frame, and it is answered the same way. The
     * server's own {@code Window.of} call cannot produce this at all, so this
     * is the only place in the system where it can happen — the reason {@code
     * InvalidPathException} is caught in {@link #permitted} and nowhere else.
     */
    private Window window(FileRequest request, String op) {
        try {
            return request.window();
        } catch (IllegalArgumentException unusable) {
            // Which field: the constructor refuses a negative offset first.
            throw new Refused(request.offset() != null && request.offset() < 0
                    ? FileResult.unservable(op, "offset", request.offset(), null)
                    : FileResult.unservable(op, "limit", request.limit(), null));
        }
    }

    /**
     * The needle this frame asks for, or the refusal saying why it is not one.
     *
     * <p>{@link #window}'s shape and its argument, at the other op. {@code
     * FileRequest.sought} owns what the two fields mean — that an absent {@code
     * ignoreCase} is a case-sensitive search — and neither is decided again
     * here, because the two halves reading one frame two ways is exactly what
     * putting the matching in {@code plowshare-protocol} was for.
     *
     * <p>The translation is the part that must be here. A blank literal raises
     * unchecked out of a module that has neither side's refusal type; left
     * alone it would reach {@link #answer}'s {@code RuntimeException} clause and
     * come back as {@code UNAVAILABLE}, which <b>ends the run</b> over a frame
     * this client is perfectly well enough to answer and a model could have
     * fixed by typing something.
     */
    private Needle needle(FileRequest request) {
        try {
            return request.sought();
        } catch (IllegalArgumentException unusable) {
            throw new Refused(FileResult.unservable(FileRequest.GREP, "needle", null,
                    request.needle()));
        }
    }

    /**
     * How long the file is, without moving any of it.
     *
     * <p>Every guard a read passes, because a stat is not a cheaper way past
     * them: a job that could count the lines of a file it may not open would
     * learn the difference between a one-line marker and a database dump. That
     * is {@link #lines} being shared rather than a check repeated here.
     *
     * <p>The {@code more} and {@code stoppedBy} it reports are {@code
     * LocalProvider.stat}'s, and they have to be, since both answers reach a
     * caller through one type: {@link Window#cut} establishes that {@link
     * Span#END} means nothing follows, and a stat that carried no lines of a
     * file that has some would otherwise tell a caller it had already seen it.
     * {@link Span#LINES} and not {@link Span#BYTES} for the remainder, because
     * {@code BYTES} says a wider limit would not help and asking for lines is
     * exactly what a caller stats in order to plan. An empty file is the
     * ordinary {@code END}, which is the same answer a read of it gives.
     */
    private FileReply stat(FileRequest request) {
        Decoded decoded = lines(request, FileRequest.STAT);
        if (decoded.named() != null) {
            // The server counts the line it words, as a read of it would carry.
            return FileReply.named(request.id(), decoded.named());
        }
        int total = decoded.lines().size();
        return FileReply.answered(request.id(),
                new Span(List.of(), 0, total, total > 0, total > 0 ? Span.LINES : Span.END));
    }

    /**
     * Every line of one file, behind every guard {@link FileRequest#READ} and
     * {@link FileRequest#STAT} share.
     *
     * <h2>The whole file is read and then cut, not the window read</h2>
     *
     * <p>{@code LocalProvider} owns the full argument and this half must not
     * drift from it, so the short form: the strict decoder's promise is about
     * the file and not about a byte range — a prefix that splits a multi-byte
     * character raises the same {@code MalformedInputException} this method
     * turns into "not UTF-8 text", so a client that decoded a range would refuse
     * an ordinary file for where its window happened to fall — and {@link
     * Span#totalLines} has to be true, which cannot be known without looking at
     * all of the file.
     *
     * <p>So the window bounds the wire and {@link #MAX_FILE_BYTES} still bounds
     * this machine's heap, unchanged and still reachable: a file above it is
     * refused whatever window is asked of it.
     *
     * <h2>What a line is here</h2>
     *
     * <p>{@code String.lines}, and its behaviour was run on JDK 21 before this
     * was written rather than assumed: a terminator is not part of the line it
     * ends and {@code \n}, {@code \r\n} and a lone {@code \r} all end one; a
     * trailing terminator adds no empty last line, so {@code "a\nb"} and {@code
     * "a\nb\n"} are both two lines; and an empty file is no lines at all.
     *
     * <p><b>So a window of a CRLF file arrives without its carriage returns</b>,
     * because the lines are now the reply's only carrier and what is not in a
     * line is not delivered. That is a real change in what a reader is handed
     * and it is deliberate — the alternative shows a model a trailing {@code
     * \r} on every line and charges the byte ceiling for each — and it is not
     * this machine's to decide differently: it changes an <em>answer</em> rather
     * than a refusal, which is the line {@link GlobSpellings} is drawn on, and
     * the two halves disagreeing about line endings would put two different
     * files into one window with nothing to say so.
     */
    private Decoded lines(FileRequest request, String op) {
        String named = request.path();
        Path environment = environmentFileForDefinitions(request, op);
        if (environment != null) {
            // As it lies: a consent file is text, and nothing the harness reads
            // should be converted or uploaded on its way.
            return decode(environment, named, AS_IT_LIES, op);
        }
        return decode(permitted(named, op), named, CONVERTING, op);
    }

    /**
     * A file's lines — or, for a picture this client named, the facts of its
     * naming and no lines, which the server words and then cuts or counts.
     */
    private record Decoded(List<String> lines, FileResult named) {

        static Decoded of(List<String> lines) {
            return new Decoded(lines, null);
        }
    }

    /**
     * The canonical {@code .plowshare/environment.yml} of one of this session's
     * roots, when the request is the harness's {@link FileRequest#DEFINITIONS}
     * read of exactly that file — and null for everything else, which goes
     * through {@link #permitted} as it always has.
     *
     * <h2>One file, and not the definition directories</h2>
     *
     * <p>The server reads this file to resolve the environment a run gets, and
     * the terminal client already lets the mark read {@code .plowshare/agents}
     * and {@code .plowshare/bots}. <b>This client still does not</b>: TODO §19
     * records that honouring the mark for definitions here would answer the
     * questions about how local definitions run by default. The environment is
     * the one {@code .plowshare} file whose answer is decided — a client's
     * {@code .plowshare} wins for its own side (§22) — so it is the one this
     * exception names.
     *
     * <p><b>Compared canonically, so a link cannot widen it.</b> A {@code
     * .plowshare} that is a link elsewhere canonicalises to somewhere that is not
     * this path and falls through to the fence, which refuses it. Only a read and
     * a stat reach here; a write under the same mark is refused as any hidden
     * write is.
     *
     * <p><b>A relative spelling is resolved against the roots, not against this
     * process's working directory.</b> {@code ChannelDefinitions} sends {@code
     * .plowshare/environment.yml} as it is, because the server cannot know where
     * this machine keeps its workspace; the first root holding the file answers,
     * and with none holding it the first root does, which is then absent.
     */
    private Path environmentFileForDefinitions(FileRequest request, String op) {
        if (!FileRequest.DEFINITIONS.equals(request.purpose()) || request.path() == null) {
            return null;
        }
        List<Path> roots = reachable(op).roots();
        Path named;
        try {
            named = Path.of(request.path());
        } catch (InvalidPathException unusable) {
            return null;
        }
        Path first = null;
        for (Path root : roots) {
            Path candidate = FileAccess.canonical(root.resolve(named));
            if (!candidate.equals(root.resolve(ENVIRONMENT_FILE))) {
                continue;
            }
            if (named.isAbsolute() || Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
            if (first == null) {
                first = candidate;
            }
        }
        return first;
    }

    /**
     * Whether {@link #decode} may put a file through {@link Conversions}, or
     * hand it to {@link ImageUploads}, before deciding it is not text.
     *
     * <p><b>Two named constants and not a bare boolean at three call sites</b>,
     * because the difference between them is a real decision and one of them is
     * the one somebody will want to change.
     *
     * <p>A read, a stat and a search of <em>one named file</em> convert and
     * name; a <em>walk</em> does neither. The line is not which operation it is
     * — it is whether the caller named the file. A conversion is per document
     * and can be seconds; a walk over a workspace holding two hundred reports
     * would turn one {@code file_grep} into two hundred extractions nobody asked
     * for and that nothing bounds, and it would do it on the socket a job is
     * blocked on. A file the caller named is a cost the caller chose.
     *
     * <h2>One flag for both, and the second cost is the larger one</h2>
     *
     * <p><b>Naming a picture is gated on this same constant rather than on one
     * of its own</b>, and that is the decision: the question both answer is
     * whether the caller named this file, and two flags would be two places for
     * the answer to drift. {@code LocalProvider.NAMING} is the same pair on the
     * server, drawn on the same line.
     *
     * <p>What is <em>not</em> the same is what a walk would cost if it crossed
     * this line here. On the server a naming walk writes two hundred sidecars to
     * its own disk. <b>Here it would upload two hundred of the user's files over
     * the network, one at a time, on the socket a job is blocked on</b> — and it
     * would do it to a workspace that never volunteered them. The server's
     * version of this mistake is expensive; this one sends somebody's pictures
     * somewhere. It is also the containment half read the other way: a walk that
     * minted ids would be the closest thing to an enumeration of a tree's
     * pictures an agent could reach, which is what {@code
     * NothingEnumeratesImagesTest} exists to keep out of reach.
     *
     * <p><b>The consequence is stated rather than hidden: a walk does not find
     * what a search of the same file by name would.</b> That is a real seam and
     * the least bad of the three available answers — the other two are a search
     * that can take minutes, or a read that refuses a document a search can see.
     * It is also exactly today's behaviour for these files, which the walk skips
     * as unreadable, so nothing that worked has changed shape.
     */
    private static final boolean CONVERTING = true;

    /** @see #CONVERTING */
    private static final boolean AS_IT_LIES = false;

    private FileReply source(FileRequest request) {
        try { return sourceBytes(request); }
        catch (NoSuchFileException absent) { throw new Refused(FileResult.noFile(FileRequest.READ, request.path())); }
        catch (IOException failed) { throw new Refused(FileResult.failed(FileRequest.READ, FileResult.FAILED, request.path(), null, failed.getMessage())); }
    }

    private FileReply sourceBytes(FileRequest request) throws IOException {
        String named = request.path();
        Path target = permitted(named, FileRequest.READ);
        BasicFileAttributes before = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new Refused(FileResult.refused(FileRequest.READ,
                before.isDirectory() ? FileResult.DIRECTORY : FileResult.NOT_REGULAR, named));
        if (before.size() > FileSource.MAX_BYTES) throw new Refused(FileResult.tooLarge(FileRequest.READ, named, before.size(), FileSource.MAX_BYTES));
        FileSource source;
        try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            if (request.offset() == null && request.limit() == null) {
                java.security.MessageDigest digest;
                try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
                catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                byte[] buffer = new byte[FileSource.CHUNK_BYTES];
                long total = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    total += read;
                    if (total > FileSource.MAX_BYTES) throw new IOException("source grew beyond the byte limit");
                    digest.update(buffer, 0, read);
                }
                if (total != before.size()) throw new IOException("source changed while hashing");
                source = new FileSource(total, java.util.HexFormat.of().formatHex(digest.digest()), null, null);
            } else {
                if (request.offset() == null || request.offset() < 0 || request.offset() > before.size()
                        || request.limit() == null || request.limit() < 1 || request.limit() > FileSource.CHUNK_BYTES)
                    throw new Refused(FileResult.unservable(FileRequest.READ, "limit", request.limit(), null));
                input.skipNBytes(request.offset());
                byte[] bytes = input.readNBytes((int) Math.min(request.limit(), before.size() - request.offset()));
                source = new FileSource(before.size(), null, request.offset(), java.util.Base64.getEncoder().encodeToString(bytes));
            }
        }
        BasicFileAttributes after = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !target.equals(permitted(named, FileRequest.READ))) throw new IOException("source changed while reading");
        return FileReply.source(request.id(), source);
    }

    /**
     * One file's lines, once it is settled that this session may open it.
     *
     * <p>Split out of {@link #lines} so that {@link #grep}'s walk can read a
     * file {@code FileSearch.eachFile} has already checked, rather than asking
     * {@link #permitted} about a path it has just resolved. Private and taking
     * the resolved target, so that the split cannot become a way into a file
     * nothing checked.
     *
     * <h2>The adaptor sits here, between the bytes and the decoder</h2>
     *
     * <p>This is the one place in this client that turns a file into lines, so
     * it is the one place a conversion can be in front of. {@link Conversions}
     * is asked <b>after</b> the size ceiling and the regular-file check and
     * <b>before</b> the strict UTF-8 decode: after, because a document above
     * this machine's ceiling is refused whatever it is and a converter that ran
     * first would have loaded it to find that out; before, because the decode is
     * what would otherwise turn the bytes away.
     *
     * <p>Everything downstream is untouched. {@link #read} still calls {@code
     * Window.cut}, {@link #stat} still counts what a read would return, and
     * neither can tell the lines apart from a text file's — which is the whole
     * of "the agent's API does not change".
     *
     * @param target the canonical, permitted file to open
     * @param named the path as it arrived, which is what every sentence below
     *     quotes back — a model reads its own spelling and not this machine's
     * @param converting {@link #CONVERTING} where the caller named this file,
     *     {@link #AS_IT_LIES} inside a walk. That constant owns the argument
     * @param op the op this answers, which a refusal's facts name
     */
    private Decoded decode(Path target, String named, boolean converting, String op) {
        try {
            // An attribute read and not a failed open. Measured: the open
            // SUCCEEDS on a directory with NOFOLLOW_LINKS, and readAllBytes is
            // what throws, as an IOException carrying the platform's "Is a
            // directory".
            //
            // So this is not about protecting the decoder — an earlier version of
            // this comment claimed it was. It is about the sentence: without it
            // the read lands in the residue clause and the server relays whatever
            // this machine's libc said, which is a string the other end cannot
            // reproduce.
            // NOFOLLOW on both closes the window on the FINAL COMPONENT only;
            // `target` is canonical, so no legitimate last name is a link. It
            // does not close the check-to-open window in general -- a directory
            // component above the last is still resolved at open time -- and
            // this comment said it did until a review read it.
            //
            // What is left is a MEASURED BOUNDARY and not a pending fix, and
            // this comment said the latter until slice 3c task 7 rewrote the
            // server half: the post-open identity check the design spec asked
            // for cannot be expressed on Java 21 at all, and the version that
            // can be built does not close the window. `LocalProvider.read`
            // carries the full account -- the language facts, the numbers, and
            // why no file tool an agent holds can reach this.
            BasicFileAttributes about = Files.readAttributes(
                    target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!about.isRegularFile()) {
                throw new Refused(FileResult.refused(op, about.isDirectory()
                        ? FileResult.DIRECTORY : FileResult.NOT_REGULAR, named));
            }
            if (about.size() > MAX_FILE_BYTES) {
                throw new Refused(FileResult.tooLarge(op, named, about.size(), MAX_FILE_BYTES));
            }
            byte[] bytes;
            try (InputStream open = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                bytes = open.readAllBytes();
            }
            if (converting) {
                // THE PICTURE IS ASKED ABOUT BEFORE THE CONVERTER, and the order
                // is a rule rather than an accident of which line was written
                // first. A picture is NAMED and not read -- that is the whole of
                // §6a -- and the day a converter in this client learns a format
                // ImageFormat also holds (an OCR pass over a scan is the obvious
                // one; §7 of the ingress note names the two sentences it would
                // falsify), the wrong answer is silently turning a named picture
                // back into somebody's guess at its text. Nothing in the shipped
                // set overlaps today, so the order changes no answer this build
                // gives; it is here so that adding the converter is not also a
                // change to what a read of a picture means.
                // a_picture_is_named_even_where_a_converter_would_have_claimed_it
                // installs that converter and holds the order.
                FileResult picture = named(bytes, named, op);
                if (picture != null) {
                    return new Decoded(null, picture);
                }
                List<String> extracted = converted(bytes, named, op);
                if (extracted != null) {
                    return Decoded.of(extracted);
                }
            }
            // A decoder that REPORTs. `new String(bytes, UTF_8)` substitutes
            // U+FFFD silently — measured — so a client written that way sends a
            // page of replacement characters across the wire and the server
            // hands it to a model as the file. That one is not a limit and is
            // not this machine's business to decide differently from the
            // server's: it changes an ANSWER rather than a refusal, which is the
            // line GlobSpellings is drawn on.
            return Decoded.of(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
                    .toString().lines().toList());
        } catch (NoSuchFileException absent) {
            throw new Refused(FileResult.noFile(op, named));
        } catch (CharacterCodingException notText) {
            throw new Refused(FileResult.notText(op, named, FileResult.NOT_UTF8));
        } catch (IOException failed) {
            // The residue, refused with LocalProvider's argument and its cost: a
            // full disk lands here too and is not the caller's mistake, and no
            // portable type separates it from the ordinary cases in this clause.
            throw new Refused(FileResult.failed(op, FileResult.FAILED, named, null,
                    failed.getMessage()));
        }
    }

    /**
     * The lines of this file's conversion, or {@code null} if it is not a format
     * this client converts.
     *
     * <p>{@code null} rather than a refusal, and it is what keeps this change
     * additive: a PNG, an executable, a UTF-16 document — everything that is not
     * a format with a converter — falls through to {@link #decode}'s strict
     * decoder and is turned away with the sentence it has always been turned
     * away with. A
     * refusal invented here would be a new answer for every binary in every
     * workspace, and that is a change to what a model reads for no gain.
     *
     * <p>The other branch fires only where a refusal already fires. An
     * encrypted PDF refused as "not UTF-8 text" would be a true fact about the
     * bytes and a useless one about the file: this client <em>does</em> read
     * PDFs, so the reason this read failed is the document. The facts name the
     * format and the converter's reason ({@link FileResult#ENCRYPTED} or {@link
     * FileResult#DAMAGED}), and the server words them.
     *
     * <p><b>An empty conversion is a real answer.</b> A scanned PDF holds no
     * extractable text and this returns no lines for it, which is what an empty
     * file already does. Calling it a failure would be this client asserting
     * that a document it converted correctly is broken.
     */
    private List<String> converted(byte[] bytes, String named, String op) {
        Conversion conversion;
        try {
            conversion = conversions.of(bytes);
        } catch (Converter.Failed unconvertible) {
            throw new Refused(FileResult.unreadable(op, FileResult.REFUSED,
                    unconvertible.reason(), named, unconvertible.format()));
        }
        return conversion == null ? null : conversion.text().lines().toList();
    }

    /**
     * The lines that <b>name</b> the picture in these bytes, or {@code null} if
     * they are not a picture this client can name.
     *
     * <h2>Name, do not attach</h2>
     *
     * <p>{@code LocalProvider.named} is the server's half and owns the argument;
     * the short of it is that the answer is an id and never the image, because a
     * glob-then-read loop over a directory of two hundred PNGs would otherwise
     * attach two hundred pictures to one context, on a system that cannot count
     * an image in tokens. So this answers with the id, and the server words the
     * one line a reader is handed and cuts and counts it as a file's — {@code
     * Window.cut} cuts it, {@code file_stat} counts it — and no tool this client
     * answers has learned to answer with bytes.
     *
     * <h2>An upload, because the channel this reply goes back on carries lines</h2>
     *
     * <p>This is the whole difference from the server's half and it is not an
     * implementation detail. A server-rooted project's picture is already on the
     * server's disk, so naming it writes a record and copies nothing. This one
     * is on somebody's laptop, and {@code FileRequest}'s operations — roots,
     * read, stat, glob, write, grep, edit, delete, move — have no byte operation
     * to carry it back.
     * §6a of the ingress note makes that an either/or: <b>a remote workspace's
     * picture is an upload or it is unreachable.</b> So the bytes go over HTTP,
     * out of the process that opened the file, and the server's line for a
     * client's picture says so rather than "nothing was copied", which is false
     * here.
     *
     * <h2>{@link ImageFormat} is the allow-list, and it is asked before anything
     * leaves this machine</h2>
     *
     * <p>Not after. The alternative — upload it and let a {@code 415} decide —
     * would send a user's arbitrary binaries to a server to be refused, which is
     * a worse thing than the wasted round trip it saves. A BMP, a TIFF, an SVG
     * or a HEIC is not named, falls through to the strict decoder, and is
     * refused as it has always been refused.
     *
     * <p><b>Null and not a refusal</b> for everything it declines, on {@link
     * #converted}'s reasoning: an executable, a UTF-16 document and a TIFF all
     * keep the answer they had.
     *
     * <h2>A picture that could not be uploaded is a refusal and not "not UTF-8
     * text"</h2>
     *
     * <p>The three the server separates are separated here, because the remedies
     * are: convert it, shrink it, or tell whoever runs the server. Letting any
     * of them fall through to the decoder would answer a model with a true fact
     * about the bytes and a useless one about the file — it would go looking at
     * the encoding of a PNG for a fault that is on the other machine. This is
     * {@link #converted}'s "the sentence names the format" one file over.
     *
     * @param named the path as the caller wrote it, which is what the sentence
     *     quotes back and what the picture is recorded as. A model reads its own
     *     spelling and not this machine's
     */
    private FileResult named(byte[] bytes, String named, String op) {
        ImageFormat format = ImageFormat.of(bytes);
        if (format == null) {
            return null;
        }
        String id;
        try {
            id = uploads.name(named, bytes);
        } catch (ImageUploads.Unnameable refused) {
            // The status decides which of the three remedies the server words;
            // no arm here reads the server's prose to work out which it is.
            throw new Refused(FileResult.imageRefused(op, named, format.declared(),
                    refused.status(), refused.getMessage(), bytes.length));
        } catch (IOException unreachable) {
            // Distinguished from every refusal above, on ServerClient's standing
            // rule: this one is a fact about the network and the others are
            // facts about the file, and a caller told the wrong one acts on the
            // wrong thing. Refused rather than Vanished, which would end the
            // run: this machine's disk is fine, and the file may well be
            // nameable a turn later.
            throw new Refused(FileResult.imageUnreached(op, named, format.declared(),
                    unreachable.getMessage()));
        }
        if (id == null) {
            // This client names none. The file goes back to being not UTF-8
            // text, which is the answer it had before any of this existed.
            return null;
        }
        // The id and the format, and no line: the server writes the one line a
        // reader is handed — that this was uploaded, and what to do with the
        // id — so `file_stat` still says this file has one line, counted there.
        return FileResult.named(op, named, format.declared(), id);
    }

    private List<Path> glob(String pattern) {
        FileAccess access = reachable(FileRequest.GLOB);
        refuseIfNothingIsReachable(FileRequest.GLOB);
        if (pattern == null || pattern.isBlank()) {
            throw new Refused(FileResult.pattern(FileResult.NO_PATTERN, pattern, null));
        }
        if (pattern.startsWith("/")) {
            // Measured on JDK 21: an absolute glob compiles and matches no
            // relative path, so leaving it alone is a confident empty answer
            // rather than an error.
            throw new Refused(FileResult.pattern(FileResult.ABSOLUTE_PATTERN, pattern, null));
        }
        List<PathMatcher> matchers;
        try {
            matchers = GlobSpellings.matchers(pattern);
        } catch (GlobSpellings.Unusable unusable) {
            // GlobSpellings raises unchecked, because plowshare-protocol has
            // neither module's refusal type; it carries its own facts.
            throw new Refused(unusable.result());
        }
        List<Path> hits = new ArrayList<>();
        for (Path root : access.roots()) {
            walk(root, matchers, access, hits);
        }
        return List.copyOf(hits);
    }

    /**
     * Every line a needle names, under one path or under every root.
     *
     * <p>The server half is {@code LocalProvider.grep} and the two are the same
     * three decisions: a named file is refused exactly as a read of it would be,
     * a named directory and an absent path are the same walk, and a file the
     * walk cannot read as text is skipped rather than fatal. {@code
     * FileProvider.grep} argues all three in one place; what is this machine's
     * own is which files it declines to read, which is {@link #MAX_FILE_BYTES}
     * and this disk's permissions.
     */
    private FileReply grep(String id, Needle needle, String named) {
        String op = FileRequest.GREP;
        FileAccess access = reachable(op);
        refuseIfNothingIsReachable(op);
        List<Found.Match> into = new ArrayList<>();
        boolean capped;
        if (named != null) {
            Path target = permitted(named, op);
            // `into` is filled before Found.of copies it, and the two steps are
            // written apart rather than nested so that nothing here depends on
            // the order Java evaluates arguments in.
            if (Files.isDirectory(target)) {
                capped = sweep(target, access, needle, into);
            } else {
                Decoded decoded = decode(target, named, CONVERTING, op);
                if (decoded.named() != null) {
                    // A picture: the server searches the line it words.
                    return FileReply.named(id, decoded.named());
                }
                capped = needle.find(target.toString(), decoded.lines(), into);
            }
            return FileReply.found(id, Found.of(into, capped));
        }
        capped = false;
        for (Path root : access.roots()) {
            // Not `capped = sweep(...)`: a root that held nothing would clear a
            // cap an earlier root had set, and only the break would hide it.
            if (sweep(root, access, needle, into)) {
                capped = true;
                break;
            }
        }
        return FileReply.found(id, Found.of(into, capped));
    }

    /**
     * One root's matching lines, with the refusals that are this machine's own.
     *
     * <p>{@link #walk} is the same division for a glob: {@code
     * FileSearch.eachFile} owns which files a walk reaches and that containment
     * is checked on each of them, {@link Needle#find} owns which lines match and
     * how many come back, and what is left here is what this machine says about
     * a directory of its own it cannot list.
     *
     * @return whether the allowance discarded a match, which is also what stops
     *     the walk
     */
    private boolean sweep(Path root, FileAccess access, Needle needle, List<Found.Match> into) {
        boolean[] capped = {false};
        try {
            FileSearch.eachFile(root, access, file -> {
                List<String> lines;
                try {
                    lines = decode(file, file.toString(), AS_IT_LIES, FileRequest.GREP).lines();
                } catch (Refused unreadable) {
                    // A binary, a file above this machine's ceiling, one it may
                    // not open. Skipped, and FileProvider.grep argues it: every
                    // real workspace has such files, and refusing the whole
                    // search over one of them is a tool that cannot be used
                    // without a path — which is the case it exists for.
                    return true;
                }
                if (needle.find(file.toString(), lines, into)) {
                    // Set and never cleared, and the walk stops. Written this way
                    // round rather than as an assignment per file because the two
                    // are not the same: an assignment would be reset to false by
                    // the next file that happened to hold nothing, and it is only
                    // the stop below that hides it — a flag whose truth depends on
                    // the loop ending is a flag one refactor away from a search
                    // that capped and said it had not.
                    //
                    // THE STOP ITSELF HAS NO TEST, and the mutant that returns
                    // true here survives the whole suite. It has to: with the
                    // flag set the way it is above, walking on changes no answer
                    // at all — it only reads the rest of the repository to
                    // discover it has nowhere to put it. That is the cost this
                    // return exists to avoid and there is no instrument for a
                    // cost, so it is kept on the argument rather than on a green
                    // test. Saying so is better than a test that appears to make
                    // the claim by asserting the answer, which is the same either
                    // way.
                    capped[0] = true;
                    return false;
                }
                return true;
            });
        } catch (IOException failed) {
            // Named rather than skipped, as `walk` does for a glob: a tree
            // searched in part and reported as searched in full is the confident
            // empty answer. A DIRECTORY that cannot be listed, and not a file
            // that cannot be read — the two are told apart by where they are
            // caught, and only the second is skippable.
            throw new Refused(FileResult.failed(FileRequest.GREP, FileResult.UNLISTABLE,
                    root.toString(), null, failed.getMessage()));
        }
        return capped[0];
    }

    /**
     * @param creating {@link FileRequest#creating}: the server sends it when this
     *     run has not read the path, and it is enforced here rather than there
     *     because only this machine can ask the disk and write in one step
     */
    private FileResult write(String named, String content, boolean creating) {
        String op = FileRequest.WRITE;
        if (content == null) {
            throw new Refused(FileResult.missing(op, "content"));
        }
        Path target = permitted(named, op);
        if (Files.isDirectory(target)) {
            throw new Refused(FileResult.refused(op, FileResult.DIRECTORY, named));
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                // The directories above a permitted target are inside the same
                // root or are the root itself, which already exists, so this
                // creates nothing outside the workspace. Not a guard: `parent`
                // is null only for a filesystem root, which isDirectory has just
                // refused — it is here because the alternative is a
                // NullPointerException inside an answer, which would come back
                // as an outage over a path that could simply have been refused.
                Files.createDirectories(parent);
            }
            if (creating) {
                // CREATE_NEW and not an exists check first: the check and the
                // open are one system call, so a file that appears between the
                // server's decision and this write is refused rather than
                // overwritten.
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
            } else {
                // TRUNCATE_EXISTING, or a shorter write over a longer file leaves
                // a tail behind and produces a file neither version ever had.
                // NOFOLLOW because writing through a link with the flag raises
                // rather than following it out of the workspace.
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            }
        } catch (FileAlreadyExistsException there) {
            throw new Refused(FileResult.refused(op, FileResult.EXISTS, named));
        } catch (IOException failed) {
            throw new Refused(FileResult.failed(op, FileResult.FAILED, named, null,
                    failed.getMessage()));
        }
        // Counted from what was sent, as a read of it would count: the bytes as
        // UTF-8 encodes them, and the lines as String.lines() splits them.
        return FileResult.written(named, content.getBytes(StandardCharsets.UTF_8).length,
                (int) content.lines().count());
    }

    /**
     * Replace the one occurrence of {@code replacing} in a file, on the machine
     * that holds the file's own bytes.
     *
     * <p>Here and not on the server because a read carries lines, and a file's
     * CRLFs and its last newline never reach the server. {@link Replacement}
     * decides what one occurrence is — the server's {@code LocalProvider} runs
     * the same class — and a strictly decoded file re-encodes to the same bytes,
     * so everything the edit did not name is written back byte for byte.
     *
     * @return where the new text is and the lines around it, which the server
     *     shows the model so its next edit is built from the file as it is
     *     rather than from its copy from before this one
     */
    private FileResult edit(String named, String replacing, String replacement) {
        String op = FileRequest.EDIT;
        if (replacing == null) {
            throw new Refused(FileResult.missing(op, "replacing"));
        }
        if (replacement == null) {
            throw new Refused(FileResult.missing(op, "content"));
        }
        Path target = existingFile(named, op);
        try {
            // The ceiling a read has, for its reason: the whole file is loaded.
            long size = Files.size(target);
            if (size > MAX_FILE_BYTES) {
                throw new Refused(FileResult.tooLarge(op, named, size, MAX_FILE_BYTES));
            }
            byte[] bytes;
            try (InputStream open = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                bytes = open.readAllBytes();
            }
            String text = Replacement.decode(bytes);
            if (text == null) {
                throw new Refused(FileResult.notText(op, named, FileResult.NOT_UTF8));
            }
            Replacement.Edited edited;
            try {
                edited = Replacement.edit(text, replacing, replacement);
            } catch (Replacement.Refused refused) {
                throw new Refused(refused.result(named));
            }
            // No CREATE: a file removed between the read and this write is
            // refused as absent rather than brought back holding only the edit.
            // TRUNCATE_EXISTING and NOFOLLOW for write's reasons.
            Files.write(target, edited.text().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            return edited.result(named);
        } catch (NoSuchFileException absent) {
            throw new Refused(FileResult.noFile(op, named));
        } catch (IOException failed) {
            throw new Refused(FileResult.failed(op, FileResult.FAILED, named, null,
                    failed.getMessage()));
        }
    }

    /**
     * Remove one file. A directory, a link and an absent path are refused.
     *
     * <p>Answered with how big it was, and how many lines — counted from its
     * bytes, as a read counts them, when it is small enough to load.
     */
    private FileResult delete(String named) {
        String op = FileRequest.DELETE;
        Path target = existingFile(named, op);
        try {
            long size = Files.size(target);
            Integer lines = null;
            if (size <= MAX_FILE_BYTES) {
                try (InputStream open = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                    lines = FileResult.lineCount(open.readAllBytes());
                }
            }
            Files.delete(target);
            return FileResult.deleted(named, size, lines);
        } catch (NoSuchFileException absent) {
            throw new Refused(FileResult.noFile(op, named));
        } catch (IOException failed) {
            throw new Refused(FileResult.failed(op, FileResult.FAILED, named, null,
                    failed.getMessage()));
        }
    }

    /**
     * Rename one file, never over an existing one.
     *
     * <p><b>Both paths through {@link #permitted}</b>: a move is a write at its
     * destination and a removal at its source, and a fence checked on one of
     * them is a way to carry a file out of the workspace or a hidden one into
     * view.
     */
    private FileResult move(String named, String to) {
        String op = FileRequest.MOVE;
        Path source = existingFile(named, op);
        if (to == null) {
            throw new Refused(FileResult.missing(op, "to"));
        }
        Path destination = permitted(to, op);
        // Both spellings, NOFOLLOW: `destination` is canonical, so a link sitting
        // at `to` — dangling or not — is only visible under the name as sent.
        if (Files.exists(Path.of(to), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new Refused(FileResult.destinationExists(named, to));
        }
        try {
            long size = Files.size(source);
            Path parent = destination.getParent();
            if (parent != null) {
                // write's argument: the directories above a permitted target are
                // inside the same root or are the root itself.
                Files.createDirectories(parent);
            }
            // No REPLACE_EXISTING, so the check above is not the only guard: a
            // file that appears in between raises rather than being replaced.
            Files.move(source, destination);
            return FileResult.moved(named, to, size);
        } catch (FileAlreadyExistsException there) {
            throw new Refused(FileResult.destinationExists(named, to));
        } catch (NoSuchFileException absent) {
            throw new Refused(FileResult.noFile(op, named));
        } catch (IOException failed) {
            throw new Refused(FileResult.failed(op, FileResult.FAILED, named, to,
                    failed.getMessage()));
        }
    }

    /**
     * The canonical path of a regular file this session may change, or the
     * refusal saying why not — what {@link #edit}, {@link #delete} and {@link
     * #move}'s source share.
     *
     * <h2>The link is asked about under the name as sent</h2>
     *
     * <p>{@link #permitted} canonicalises, and canonicalising resolves a link in
     * the last component — so the canonical path is never a link, and a check on
     * it would delete or rename the file a link points at while calling it the
     * link. {@code Files.isSymbolicLink} does not follow the last component, so
     * asked of {@code named} it answers about the name the caller wrote.
     *
     * @param op the change being made, which the refusal's facts name
     */
    private Path existingFile(String named, String op) {
        Path target = permitted(named, op);
        if (Files.isSymbolicLink(Path.of(named))) {
            throw new Refused(FileResult.refused(op, FileResult.LINK, named));
        }
        BasicFileAttributes about;
        try {
            about = Files.readAttributes(
                    target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            throw new Refused(FileResult.noFile(op, named));
        } catch (IOException failed) {
            throw new Refused(FileResult.failed(op, FileResult.FAILED, named, null,
                    failed.getMessage()));
        }
        if (about.isDirectory()) {
            throw new Refused(FileResult.refused(op, FileResult.DIRECTORY, named));
        }
        if (about.isSymbolicLink()) {
            throw new Refused(FileResult.refused(op, FileResult.LINK, named));
        }
        if (!about.isRegularFile()) {
            throw new Refused(FileResult.refused(op, FileResult.NOT_REGULAR, named));
        }
        return target;
    }

    // --- commands ------------------------------------------------------------

    /**
     * Run one command in a directory of this session's workspace, if this
     * machine's own environment file lets it.
     *
     * <h2>This machine consents on its own</h2>
     *
     * <p>The request carries what the server resolved — {@link
     * FileRequest#shells} among it — and <b>none of that is taken on trust</b>.
     * The server's gate decides whether a run is sent; this decides whether it
     * starts, from {@code .plowshare/environment.yml} under the root the working
     * directory is in, read here from the disk and not through the fence: it is
     * this client's consent, not a model's read. A server cannot run a command on
     * a machine whose own file forbids it, which is the spec's decision 5.
     *
     * <p>Only the {@code local:} section counts. A {@code server:} section in a
     * client's file is the server's to refuse and has nothing to say about this
     * machine. An absent file is the default, which is off; a file that cannot be
     * read is off, and the refusal quotes why.
     *
     * <p>The timeout and the output bound are the smaller of the request's and
     * this file's, so a server cannot keep a command on this machine longer than
     * this machine said. The variables are the request's: the server resolved them
     * from this same file, and a project's own {@code local:} may name ones this
     * file does not repeat.
     */
    private CommandRunner.Outcome run(FileRequest request) {
        Path cwd = permitted(request.path(), FileRequest.RUN);
        if (!Files.isDirectory(cwd)) {
            throw new Refused("path " + request.path() + " is not a directory on this machine, so"
                    + " a command cannot run in it");
        }
        List<String> argv = request.argv();
        // Not argv.contains(null): an immutable list throws for that question.
        if (argv == null || argv.isEmpty() || argv.stream().anyMatch(Objects::isNull)) {
            throw new Refused("a command needs at least the program to run; none was sent");
        }
        EnvironmentFile.Side own = consent(rootOf(cwd));
        if (EnvironmentFile.isShell(argv.get(0)) && !own.shells()) {
            throw new Refused("'" + argv.get(0) + "' is a shell, and this machine's "
                    + ENVIRONMENT_FILE + " does not allow shells here; name the program itself,"
                    + " or set shells: true under local: on this machine");
        }
        Duration timeout = own.timeout();
        if (request.timeoutMillis() != null) {
            if (request.timeoutMillis() <= 0) {
                throw new Refused("a command's timeout must be positive, not "
                        + request.timeoutMillis() + " ms");
            }
            timeout = Duration.ofMillis(Math.min(request.timeoutMillis(), timeout.toMillis()));
        }
        long outputBytes = own.outputBytes();
        if (request.outputBytes() != null) {
            if (request.outputBytes() <= 0) {
                throw new Refused("a command's output bound must be positive, not "
                        + request.outputBytes() + " bytes");
            }
            outputBytes = Math.min(request.outputBytes(), outputBytes);
        }
        CommandRunner.Command command = new CommandRunner.Command(argv, cwd,
                request.env() == null ? Map.of() : request.env(),
                // Only what this machine's own file also names: a server may narrow
                // which host variables a command sees, and may not reach for one this
                // file did not.
                request.inherit() == null ? List.of()
                        : request.inherit().stream().filter(own.inherit()::contains).toList(),
                timeout, outputBytes, request.stdin());

        AtomicBoolean cancelled = new AtomicBoolean();
        String id = request.id();
        if (id != null && running.putIfAbsent(id, cancelled) != null) {
            // Ids are unique among outstanding requests, so this is a server bug
            // — and starting a second command a cancel could not tell apart from
            // the first would be this client's.
            throw new Refused("a command is already running on this machine under request id '"
                    + id + "'");
        }
        try {
            return CommandRunner.run(command, System.getenv(), cancelled::get);
        } catch (CommandRunner.Refused unstartable) {
            // Unchecked out of the protocol module, which has neither module's
            // refusal type; left alone it would come back as an outage over a
            // program that is merely not on PATH.
            throw new Refused(unstartable.getMessage());
        } finally {
            if (id != null) {
                running.remove(id, cancelled);
            }
        }
    }

    /** Ask the run with this request id to stop. Nothing running under it is fine. */
    private void cancel(String runId) {
        if (runId == null) {
            return;
        }
        AtomicBoolean flag = running.get(runId);
        if (flag != null) {
            flag.set(true);
        }
    }

    /**
     * Stop every command this client is running. {@link ChannelClient} calls this
     * when its socket goes: nobody is left to read their outcomes, and a command
     * left alone would run on to its deadline — up to an hour — for no one.
     */
    void cancelAll() {
        running.values().forEach(flag -> flag.set(true));
    }

    /** The root of this session's workspace a permitted path is in — the deepest. */
    private Path rootOf(Path permitted) {
        Path best = null;
        for (Path root : reachable(FileRequest.RUN).roots()) {
            if (permitted.startsWith(root)
                    && (best == null || root.getNameCount() > best.getNameCount())) {
                best = root;
            }
        }
        if (best == null) {
            // permitted() has already said this path is under a root.
            throw new IllegalStateException("no root holds " + permitted);
        }
        return best;
    }

    /**
     * This machine's own side, from {@code .plowshare/environment.yml} under
     * {@code root}, or the refusal saying why nothing may run there.
     */
    private EnvironmentFile.Side consent(Path root) {
        Path file = root.resolve(ENVIRONMENT_FILE);
        String text;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new Refused(unreadable("it is larger than " + MAX_FILE_BYTES + " bytes"));
            }
            byte[] bytes = Files.readAllBytes(file);
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (NoSuchFileException absent) {
            throw new Refused(off() + ", which is the default when " + root + " has no such file");
        } catch (CharacterCodingException notText) {
            throw new Refused(unreadable("it is not UTF-8 text"));
        } catch (IOException failed) {
            throw new Refused(unreadable(failed.getMessage()));
        }
        EnvironmentFile.Side own;
        try {
            // Its local: section alone; a server: section here speaks for the
            // server and not for this machine.
            own = EnvironmentFile.Side.DEFAULT.with(EnvironmentFile.parse(text).local());
        } catch (EnvironmentFile.Unreadable unparseable) {
            throw new Refused(unreadable(unparseable.getMessage()));
        }
        if (own.isOff()) {
            throw new Refused(off());
        }
        return own;
    }

    private static String off() {
        return "this machine's " + ENVIRONMENT_FILE + " does not allow commands to run here;"
                + " its local mode is off";
    }

    private static String unreadable(String why) {
        return "this machine's " + ENVIRONMENT_FILE + " does not allow commands to run here; it"
                + " could not be read, so its local mode is off: " + why;
    }

    // --- containment ---------------------------------------------------------

    /**
     * The workspace, once it is known that the directories are still on the disk.
     *
     * <p>Before containment and not after: a session whose workspace has been
     * deleted is not a session that should be told a path is "outside every
     * root", which would be true of a workspace that no longer exists. {@link
     * FileAccess} cannot notice this — its roots are resolved when it is built —
     * which is why its javadoc hands the question to whoever owns the disk.
     *
     * <p>Two states and two sentences, measured: {@code Files.isDirectory} is
     * false both for a deleted directory and for one replaced by a regular file,
     * while {@code Files.exists} tells them apart. Reporting a file that is
     * sitting right there as absent sends a human looking for it.
     *
     * <p><b>{@code List.of()} for the exclusions, and it always will be.</b> The
     * server's mandatory exclusions are facts about the server's disk — its
     * configuration, its agent definitions — and none of them is a fact about
     * this machine. That is exactly why "hidden means hidden" is a predicate
     * inside {@link FileAccess#permits} rather than an entry in that list: an
     * exclusion-shaped rule would reach this process not at all, and this is the
     * machine where the CLI keeps its own copy of the operator token. {@code
     * a_hidden_file_in_the_workspace_is_neither_read_nor_listed_nor_searched} is
     * what holds that here, over a workspace this file sets and a store it never
     * builds.
     */
    private FileAccess reachable(String op) {
        List<Path> roots = workspace.roots();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                throw new Vanished(FileResult.unavailable(op, FileResult.ROOT_GONE,
                        root.toString(), null));
            }
            if (!Files.isDirectory(root)) {
                throw new Vanished(FileResult.unavailable(op, FileResult.ROOT_NOT_DIRECTORY,
                        root.toString(), null));
            }
        }
        return FileAccess.of(roots, List.of());
    }

    /** @param op the op being refused, whose facts name it */
    private void refuseIfNothingIsReachable(String op) {
        if (!workspace.isSet()) {
            throw new Refused(FileResult.refused(op, FileResult.NO_WORKSPACE, null));
        }
    }

    /**
     * The canonical path this operation may act on, or the refusal saying why not.
     *
     * <p>Canonicalised once and handed to {@code permits}, so the path that is
     * checked is the path that is opened. {@code FileAccess.canonical} is
     * <b>not</b> idempotent past its hop budget — {@code permits(p)} and {@code
     * permits(canonical(p))} can differ — so asking about the path as it was
     * typed and then opening its canonical form is a check about a different file
     * from the one that gets touched.
     */
    /**
     * @param op the op, which a refusal's facts name
     */
    private Path permitted(String named, String op) {
        FileAccess access = reachable(op);
        refuseIfNothingIsReachable(op);
        if (named == null) {
            throw new Refused(FileResult.refused(op, FileResult.NO_PATH, null));
        }
        Path candidate;
        try {
            candidate = FileAccess.canonical(Path.of(named));
        } catch (InvalidPathException unusable) {
            // Measured: Path.of("a\0b") raises InvalidPathException, unchecked.
            // The server hands a Path to its own seam and never meets this; here
            // the path arrived as a string off a wire, so this is the one place
            // in the system where it can happen.
            throw new Refused(FileResult.failed(op, FileResult.UNNAMEABLE, named, null,
                    unusable.getMessage()));
        }
        if (access.permits(candidate)) {
            return candidate;
        }
        // Inside a root and still refused: this client holds no exclusions, so
        // it is FileAccess's "hidden means hidden" — said as that, and not as a
        // path outside a workspace the model can see it is under.
        if (access.roots().stream().anyMatch(candidate::startsWith)) {
            throw new Refused(FileResult.fenced(op, FileResult.HIDDEN, named,
                    strings(access.roots())));
        }
        // THE ANSWER THAT MAKES A MOVED WORKSPACE CORRECTABLE. Given only when
        // the path really was inside a root this session held before its last
        // workspace_set: "moved" about a path that was never anybody's is trap 7,
        // and a model told the file is simply not there stops looking for it —
        // having read it successfully two turns ago.
        if (workspace.heldPreviously(candidate)) {
            throw new Refused(FileResult.fenced(op, FileResult.WORKSPACE_MOVED, named,
                    strings(access.roots())));
        }
        throw new Refused(FileResult.fenced(op, FileResult.OUTSIDE, named,
                strings(access.roots())));
    }

    // --- the walk ------------------------------------------------------------

    /**
     * The hits under one root, with the two refusals that are this machine's own.
     *
     * <p>{@link FileSearch#matching} owns the search — which candidates the
     * pattern names, that a directory is not a hit, and that containment is
     * checked on the resolved path. Those three change an <em>answer</em>: a
     * client reading any of them differently from the server puts a different
     * file set into the same {@code file_glob} listing, silently. This file used
     * to hold its own copy of all four steps, and the two that matter were among
     * them.
     *
     * <p>What stays here is the pair that produces a <em>refusal</em>: how many
     * hits this machine will return, and what it says about a directory of its
     * own it cannot read.
     */
    private void walk(Path root, List<PathMatcher> matchers, FileAccess access,
            List<Path> hits) {
        try {
            FileSearch.matching(root, matchers, access, hits, MAX_MATCHES);
        } catch (FileSearch.TooManyMatches tooMany) {
            throw new Refused(FileResult.tooManyMatches(FileRequest.GLOB, tooMany.limit()));
        } catch (IOException failed) {
            // Named rather than skipped: a partial listing returned as a complete
            // one is the confident empty answer in miniature.
            throw new Refused(FileResult.failed(FileRequest.GLOB, FileResult.UNLISTABLE,
                    root.toString(), null, failed.getMessage()));
        }
    }

    private static List<String> strings(List<Path> paths) {
        return paths.stream().map(Path::toString).toList();
    }

    /** The caller was wrong and can be right next turn. Private, so nothing
     *  outside this class can put one into {@link #answer}'s catch. */
    private static final class Refused extends RuntimeException {

        /** What the reply carries; null only for a run's own refusal. */
        final transient FileResult facts;

        /** A run's own consent or bounds, which still answer in words. */
        Refused(String message) {
            super(message);
            this.facts = null;
        }

        /** Every other refusal: facts, and no sentence for anybody. */
        Refused(FileResult facts) {
            super(Objects.requireNonNull(facts, "facts").kind()
                    + (facts.reason() == null ? "" : " (" + facts.reason() + ")"));
            this.facts = facts;
        }
    }

    /** This machine's disk is not there to be asked. Private for the same
     *  reason, and separate from {@link Refused} for {@code
     *  WorkspaceRefusedException}'s: the two mean opposite things to a run, and a
     *  shared supertype is a catch clause waiting to collapse them. */
    private static final class Vanished extends RuntimeException {

        final transient FileResult facts;

        Vanished(FileResult facts) {
            super(facts.kind() + " (" + facts.reason() + ")");
            this.facts = facts;
        }
    }
}
