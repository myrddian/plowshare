package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.files.FileRefusedException;
import io.aeyer.plowshare.server.files.FileWords;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Definitions on somebody's laptop, read down the socket that exists so the
 * server can ask.
 *
 * <p>{@code AgentsProperties} reads {@code global/} off this server's own disk
 * and {@link FilesystemDefinitions} is that reading; this is the tier spec §2
 * puts on the machine that submitted the job instead — an MCP client running
 * Plowshare as a local harness, naming its own project, whose files never leave
 * it. Nothing here is ever uploaded or stored: every definition is read fresh,
 * over {@link SessionChannel}, for the one resolution that asked for it.
 *
 * <h2>Why this does not build a {@link io.aeyer.plowshare.server.files.RemoteProvider}</h2>
 *
 * <p><b>Deliberately not routed through {@code RemoteProvider}.</b> That class
 * exists to bound what a <em>running agent</em> may reach — every read gated
 * behind the grants an agent's own definition declared, {@code
 * refuseIfNoGrant} first among them. Loading definitions is not an agent
 * reaching a file; it is this server reading an operator's own checkout, before
 * any agent exists to hold a grant. Minting a synthetic grant so this could run
 * through the same door {@code RemoteProvider} guards would be a grant no
 * agent's own definition declared and one an agent could observe or inherit,
 * which is the leak the grant model exists to prevent.
 *
 * <p><b>The consequence is that this class does its own containment, where
 * {@code RemoteProvider} does none at all.</b> That class's own javadoc says so
 * plainly: the doubled enforcement for a remote path is the router (narrowing
 * against roots it fetched) and the client (refusing against the workspace its
 * session holds), and {@code RemoteProvider} itself adds nothing between them.
 * Going under it skips both of those, so {@link #isContained} is what stands in
 * their place — the one check this class cannot borrow and must make for
 * itself. It borrows the router's <em>instrument</em> rather than its code:
 * {@link FileRequest#ROOTS} is asked once and the answer is what a hit is
 * anchored against, exactly as {@code ProviderRouter} narrows against roots it
 * fetched a round trip ago.
 *
 * <h2>Every window is paged to the end, or the file contributes nothing</h2>
 *
 * <p>{@link FileRequest#read} carries one {@link Window} and a {@link Span}
 * comes back cut to it — {@code RemoteProvider}'s own javadoc argues why no
 * range is invented on this side of the wire, and the same argument holds here.
 * The largest shipped definition is small enough that one window covers it
 * today, which is exactly the condition under which a truncation bug hides: a
 * file cut short still opens with a {@code ---} fence and still parses into a
 * valid-looking {@link AgentDefinition}, with a silently different prompt. So
 * {@link #read} loops while {@link Span#more()} is true and only ever hands
 * {@link AgentRegistry} a file assembled to its end — never a prefix of one.
 *
 * <p><b>And it is bounded by numbers this server picked</b>, not by any the
 * peer sent: {@link #MAX_DEFINITION_BYTES} and {@link #MAX_DEFINITION_LINES}
 * bound one file, and {@link #MAX_DEFINITIONS} and {@link #MAX_SOURCE_BYTES}
 * bound the whole source, because how many files a listing names is a number
 * the client picks too. See {@link #read} for why every bound derived from the
 * reply is worthless on its own.
 *
 * <h2>Every channel failure is an empty result, never a throw</h2>
 *
 * <p>A closed socket, a client that timed out, a client with no {@code
 * .plowshare/} at all, a reply this build cannot make sense of, a file that
 * will not page to completion, a file past a ceiling above — none of them are
 * an operator's mistake, and none of them may fail somebody's run. Each is
 * logged at debug and this source contributes nothing for the piece that
 * failed: an unreadable {@code agents/} does not stop {@code bots/} from being
 * tried, and one unreadable file does not stop its siblings from being read.
 * This is the deliberate opposite of {@link FilesystemDefinitions}, which
 * throws on a directory this server's own disk cannot list — a directory on
 * this server that will not read is an operator's misconfiguration; a laptop
 * that went away mid-run is ordinary, and {@link DefinitionResolver} must still
 * resolve something for every other caller when it happens.
 */
public final class ChannelDefinitions implements DefinitionSource {

    private static final Logger log = LoggerFactory.getLogger(ChannelDefinitions.class);

    /** What the brief and {@link DefinitionResolver} both default to when a
     *  deployment names no other client-side directory. */
    public static final String DEFAULT_DIRECTORY = ".plowshare";

    /**
     * The most a single definition may be, in bytes of text, before it stops
     * being read at all.
     *
     * <h2>Why a constant here and not the total the reply reports</h2>
     *
     * <p><b>Every bound derived from the reply is a bound the attacker picks.</b>
     * {@link Span#totalLines()} is an {@code int} and arrives from the peer: a
     * client answering {@code 2_147_483_647} on its first page, and then full
     * frames with {@code more=true}, satisfies every consistency check this
     * class can make — the total never changes, the offset never overtakes it,
     * no page is empty — while the accumulator grows toward a terabyte, one
     * {@code MAX_TEXT_MESSAGE_CHARS} frame ({@code FileChannelConfig}, 1 MiB) at
     * a time. The only bound that survives a hostile peer is one this process
     * chose before the socket opened.
     *
     * <h2>Why this number</h2>
     *
     * <p>{@code LocalProvider.MAX_FILE_BYTES} and {@code
     * ClientEnforcer.MAX_FILE_BYTES} are 8 MiB, and they are the ceiling on an
     * <em>arbitrary file a model asked for</em>. This is a much narrower thing:
     * a definition is a system prompt with a frontmatter fence on it. The
     * largest one this repository ships is a few kilobytes; 1 MiB is three
     * orders of magnitude above that and already a quarter of a million tokens,
     * which no model would accept as a system prompt in the first place. It is
     * also exactly one {@code MAX_TEXT_MESSAGE_CHARS} frame, so a definition
     * this class refuses is one that could never have crossed this channel
     * unpaged either. Tighter than 8 MiB deliberately: this ceiling is
     * multiplied by however many files a client's listing names, where {@code
     * MAX_FILE_BYTES} bounds one read a model waited for.
     */
    static final long MAX_DEFINITION_BYTES = 1024L * 1024L;

    /**
     * The most lines a single definition may be, checked alongside {@link
     * #MAX_DEFINITION_BYTES} rather than instead of it.
     *
     * <p><b>Two ceilings because there are two costs.</b> A million empty lines
     * is a handful of kilobytes of text and a million {@code String} objects on
     * the heap, which the byte ceiling would let through; one line of a million
     * characters is one object and the byte ceiling is the only thing that
     * catches it.
     *
     * <p>It is also what bounds the <em>number of round trips</em>. {@link #read}
     * already refuses a page that is empty while claiming more remains, so every
     * further round adds at least one line — which makes this ceiling a hard cap
     * of {@value #MAX_DEFINITION_LINES} exchanges for one file, in the worst case
     * of a client answering a single line at a time. A well-behaved client
     * reaching the same ceiling spends ten rounds, since {@link
     * Window#MAX_WINDOW_LINES} is 2 000.
     *
     * <p>20 000 lines is roughly a 400-page prompt. Nothing that is a definition
     * is near it, and nothing that is near it is a definition.
     */
    static final int MAX_DEFINITION_LINES = 20_000;

    /**
     * The most {@code .md} entries this source will even consider under one
     * subdirectory, and the most bytes it will hold across every definition it
     * assembles.
     *
     * <p>{@code .md} entries and not entries: the glob asks for {@code *} on
     * purpose, so a directory of screenshots beside two definitions answers
     * with hundreds of names and holds two definitions. A count that did not
     * filter first would be a ceiling measuring the wrong thing.
     *
     * <h2>Why the per-file ceilings are not enough on their own</h2>
     *
     * <p>{@link #MAX_DEFINITION_BYTES} bounds one file, and one file is not what
     * this source returns. {@code ClientEnforcer.MAX_MATCHES} lets a glob answer
     * with ten thousand names, every one of which this class would read to its
     * own 1 MiB ceiling and then <b>keep</b>, in the list it hands back — so a
     * ceiling that is only per-file leaves the SOURCE unbounded by exactly the
     * argument that made the per-reply ceiling insufficient. The number of files
     * is a number the client picks too.
     *
     * <h2>Why exceeding these means nothing at all, and not what fitted</h2>
     *
     * <p>Every other ceiling in this class costs one file. These two cost the
     * whole source, deliberately. Stopping partway and returning what fitted
     * would hand {@link DefinitionResolver} a definition set that is missing
     * names for a reason nothing downstream can see — and a resolution against a
     * silently incomplete set does not fail, it resolves to a <em>different</em>
     * agent. That is {@link #read}'s own argument about a truncated file that
     * still parses, one level up, and it gets the same answer.
     *
     * <p>512 is two orders of magnitude above any definitions directory anybody
     * keeps, and 8 MiB is the figure {@code LocalProvider.MAX_FILE_BYTES} and
     * {@code ClientEnforcer.MAX_FILE_BYTES} already use for one arbitrary file —
     * borrowed here as the total for a whole tier, which is the ratio that says
     * what this tier is for.
     */
    static final int MAX_DEFINITIONS = 512;

    /** @see #MAX_DEFINITIONS */
    static final long MAX_SOURCE_BYTES = 8L * 1024 * 1024;

    private static final String AGENTS = "agents";
    private static final String BOTS = "bots";

    private final SessionChannel channel;
    private final String sessionId;
    private final String directory;
    private final List<String> subdirectories;

    /** The client's own roots, fetched at most once per instance and never
     *  re-asked — see {@link #clientRoots}. Null until the first hit that needs
     *  them; empty is a real answer and not a "not yet". */
    private List<Path> roots;

    public ChannelDefinitions(SessionChannel channel, String sessionId, String directory) {
        this(channel, sessionId, directory, List.of(AGENTS, BOTS));
    }

    public ChannelDefinitions(SessionChannel channel, String sessionId) {
        this(channel, sessionId, DEFAULT_DIRECTORY);
    }

    private ChannelDefinitions(SessionChannel channel, String sessionId, String directory,
            List<String> subdirectories) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.directory = directory == null ? DEFAULT_DIRECTORY : directory;
        this.subdirectories = List.copyOf(subdirectories);
    }

    /** The client's {@code .plowshare/orchestrations}, and nothing else. */
    public static ChannelDefinitions orchestrations(SessionChannel channel, String sessionId) {
        return new ChannelDefinitions(channel, sessionId, DEFAULT_DIRECTORY, List.of("orchestrations"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Names the client and the session rather than a path, because {@link
     * DefinitionSource}'s own javadoc says what a bare path would be here: a
     * message naming a filesystem path that exists on no disk in this
     * deployment. The directory named is this session's own, not this server's.
     */
    @Override
    public String describe() {
        return "session " + sessionId + "'s " + directory;
    }

    @Override
    public List<Definition> list() {
        // The configured directory is one segment of a path and must be able to
        // be one on ANY client's filesystem -- see isSafeSegment. A deployment
        // that configured '.plowshare/nested' has named two, and the shape check
        // in isContained is written against exactly one; rather than let the
        // arithmetic quietly widen by a segment, this source contributes
        // nothing and says why. Warn and not debug: unlike every other empty
        // result in this class, this one is an operator's mistake on this
        // server, not a laptop being a laptop.
        if (!isSafeSegment(directory)) {
            log.warn("session '{}': '{}' is configured as the client-side definition directory,"
                    + " and that is not a single path segment on every filesystem a client may"
                    + " run; no client-tier definitions will be read", sessionId, directory);
            return List.of();
        }
        List<Definition> found = new ArrayList<>();
        long[] bytes = {0};
        try {
            for (String subdirectory : subdirectories) {
                collect(subdirectory, found, bytes);
            }
        } catch (TooMuch spent) {
            // The whole source, and not the file it happened to notice on --
            // see MAX_DEFINITIONS for why a partial definition set is worse
            // than none.
            //
            // WARN, and for the same reason the misconfigured directory above
            // is: this is the only other state in this class where a client
            // that is present, answering, and entirely well is told nothing at
            // all -- and where the reason is a number THIS server chose rather
            // than anything that went wrong on the laptop. At debug it is
            // exactly the silent inertness this whole task has been about: a
            // legitimate-but-large definitions directory would contribute
            // nothing, every run would still succeed, and nobody would ever
            // learn why their agents were missing. Every other empty result
            // here stays at debug because a closed socket or an unreadable
            // directory is the laptop's business and is ordinary; a ceiling
            // this server enforced is this server's business and is not.
            //
            // The message has to carry what an operator can act on, which is
            // why TooMuch's own sentence names the ceiling and its value: the
            // question after reading this line is "raise which limit, from
            // what?", and a line that does not answer it is a line that sends
            // somebody to this file to find out.
            log.warn("session '{}': its client-tier definitions under {} contributed nothing"
                    + " at all, because {}", sessionId, directory, spent.getMessage());
            return List.of();
        }
        found.sort(Comparator.comparing(Definition::name));
        return List.copyOf(found);
    }

    /**
     * The client's {@code .plowshare/bots/default}, read over the channel.
     *
     * <p>Every failure is "no default here" rather than an error, on this class's
     * rule for every other read: a client tier that cannot be reached contributes
     * nothing, and the next tier out is asked instead.
     */
    public Optional<DefinitionResolver.DefaultBot> defaultBot() {
        if (!isSafeSegment(directory)) {
            return Optional.empty();
        }
        String path = directory + "/" + BOTS + "/" + DefinitionResolver.DEFAULT_FILE;
        try {
            // Marked by ask(), like every request this class sends.
            FileReply reply = ask(FileRequest.read(id(), path, Window.of(0, 1)));
            if (reply.span() == null) {
                return Optional.empty();
            }
            return DefinitionResolver.firstLine(reply.span().lines())
                    .map(name -> new DefinitionResolver.DefaultBot(
                            name, "session " + sessionId + ": " + path));
        } catch (RuntimeException unreachable) {
            log.debug("session '{}': {} could not be read, so the client tier names no default"
                    + " bot: {}", sessionId, path, unreachable.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The client's {@code .plowshare/environment.yml}, or {@code null} when it could
     * not be read — absent, refused, or a client that could not be asked.
     *
     * <p>Null for all three, on this class's rule: a client tier that cannot be
     * reached contributes nothing. For an environment that is safe in the one
     * direction that matters, because the client re-reads its own file before it
     * runs anything and refuses what it forbids.
     */
    public String environmentFile() {
        if (!isSafeSegment(directory)) {
            return null;
        }
        return read(directory + "/" + ENVIRONMENT_FILE);
    }

    /**
     * The client's {@code .plowshare/environment.yml}, telling an absent file from one that could
     * not be read (Task 12's review). {@link #environmentFile} folds both into null, which is
     * right for what may run — the client re-reads its own file — but not for the person's caps:
     * a file that is not there sets no caps, while one this server failed to read says nothing
     * about them, and the caps last read should stand rather than silently fall back to the
     * definitions'.
     *
     * @return the file's text, {@link FileRead#ABSENT}, or why it could not be read
     */
    public FileRead environmentFileRead() {
        if (!isSafeSegment(directory)) {
            return FileRead.unreadable("the session's definitions directory '" + directory
                    + "' is not a name this server reads under");
        }
        return readWhole(directory + "/" + ENVIRONMENT_FILE);
    }

    /**
     * A file read whole from the client, or why not.
     *
     * @param text the file's text, or null when it was not read
     * @param unreadable why it could not be read, or null — null with a null {@code text} is a
     *     file that is not there
     */
    public record FileRead(String text, String unreadable) {

        /** No such file: an answer, not a failure. */
        public static final FileRead ABSENT = new FileRead(null, null);

        /**
         * @param why the sentence saying why
         * @return a read that failed for {@code why}
         */
        public static FileRead unreadable(String why) {
            return new FileRead(null, why);
        }

        /** @return whether the file is not there */
        public boolean absent() {
            return text == null && unreadable == null;
        }
    }

    /** How a client's file tools open the refusal for a file that is not there. */
    private static final String ABSENT_OPENING = "there is no file at ";

    /** The file {@link #environmentFile} reads, inside {@link #directory}. */
    public static final String ENVIRONMENT_FILE = "environment.yml";

    /** More than this source will read for one session, which costs the source
     *  and not the file. Raised out of {@link #collect} and caught in {@link
     *  #list} -- deliberately NOT thrown anywhere {@link #readOne}'s catch-all
     *  could swallow it, which would turn "the source contributes nothing" back
     *  into "this one file contributes nothing" without a word being said. */
    private static final class TooMuch extends RuntimeException {

        TooMuch(String what) {
            super(what);
        }
    }

    /**
     * Every {@code .md} definition directly under {@code <directory>/<subdir>},
     * appended to {@code into} — or nothing at all if the listing could not be
     * had.
     *
     * <p>Appended rather than returned, and {@code bytes} passed in, so that
     * every subdirectory this instance reads shares one budget: {@link
     * #MAX_SOURCE_BYTES} is a bound on what this SESSION may put on this
     * server's heap, and one bound per subdirectory would multiply by however
     * many are configured.
     *
     * <p>One glob rather than a dedicated "list a directory" op, because {@link
     * FileRequest} has none — {@code <subdir>/*} matched non-recursively is the
     * closest thing to it the wire already offers. The pattern deliberately
     * matches every file and not only {@code *.md}: {@link
     * FilesystemDefinitions}' own case-insensitive suffix check is repeated
     * here rather than leant on a case-sensitive glob, so the two sources agree
     * on what counts as a definition regardless of how a client's filesystem
     * spells an extension.
     *
     * <p>The pattern is relative, and must be: {@code ClientEnforcer.glob}
     * refuses an absolute one outright, and matches what it is given against
     * each candidate <em>relativised to the root it was found under</em>. What
     * comes back is not relative — see {@link #isContained}.
     */
    private void collect(String subdir, List<Definition> into, long[] bytes) {
        List<String> hits;
        try {
            FileReply reply = ask(FileRequest.glob(id(), directory + "/" + subdir + "/*"));
            if (reply.paths() == null) {
                throw brokenAnswer(FileRequest.GLOB, "answered without a list, and an absent"
                        + " list is not an empty one");
            }
            hits = reply.paths();
        } catch (RuntimeException failed) {
            log.debug("session '{}': {}/{} could not be listed, so it contributes nothing: {}",
                    sessionId, directory, subdir, failed.getMessage());
            return;
        }
        // ONLY THE '.md' ENTRIES COUNT. The glob is deliberately '*' and not
        // '*.md' -- see this method's own javadoc on why the suffix test is
        // made here rather than left to a client's case-sensitive matcher --
        // so `hits` holds every file in the directory, definitions and
        // otherwise. Counting all of them against MAX_DEFINITIONS would let a
        // bots/ directory containing six hundred screenshots kill the whole
        // tier without holding a single definition, which is a ceiling
        // measuring the wrong thing.
        //
        // Matched against the RAW string rather than the parsed filename,
        // because this runs before any parsing: for anything that goes on to
        // survive readOne's own check the two agree, since neither normalising
        // nor containment can change a path's last three characters. The
        // authoritative test is still readOne's, on the normalised name.
        List<String> definitions = new ArrayList<>();
        for (String hit : hits) {
            if (hit != null && (hit.toLowerCase(Locale.ROOT).endsWith(".md") || (subdir.equals("orchestrations") && hit.toLowerCase(Locale.ROOT).endsWith(".js")))) {
                definitions.add(hit);
            }
        }
        // Before a single read: a listing this long is not a definitions
        // directory, and the alternative to refusing it here is one round trip
        // per name to discover the same thing.
        if (definitions.size() > MAX_DEFINITIONS) {
            throw new TooMuch(directory + "/" + subdir + " was answered with "
                    + definitions.size() + " '.md' entries and this server will not read more"
                    + " than " + MAX_DEFINITIONS + " from one directory (MAX_DEFINITIONS)");
        }
        for (String hit : definitions) {
            Definition definition = readOne(hit, subdir);
            if (definition == null) {
                continue;
            }
            bytes[0] += definition.text().getBytes(StandardCharsets.UTF_8).length;
            if (bytes[0] > MAX_SOURCE_BYTES) {
                throw new TooMuch("they came to more than " + MAX_SOURCE_BYTES + " bytes in"
                        + " total, which is the most this server will hold for one session"
                        + " (MAX_SOURCE_BYTES)");
            }
            into.add(definition);
        }
    }

    /**
     * One file, read to completion and turned into a {@link Definition} — or
     * {@code null}, for every reason this class does not throw.
     *
     * <p>Wrapped in a catch-all (M3) so Ruling 3 holds by construction: nothing
     * below is currently known to throw past the exchanges that already have
     * their own {@code try}, but a class that must never fail somebody's run
     * should not depend on staying that way by accident.
     */
    private Definition readOne(String path, String subdir) {
        try {
            return readOneUnchecked(path, subdir);
        } catch (RuntimeException unexpected) {
            log.debug("session '{}': '{}' could not be turned into a definition, so it"
                    + " contributes nothing: {}", sessionId, path, unexpected.toString());
            return null;
        }
    }

    private Definition readOneUnchecked(String path, String subdir) {
        if (path == null) {
            return null;
        }
        Path parsed;
        try {
            parsed = Path.of(path);
        } catch (RuntimeException unusable) {
            log.debug("session '{}': answered a listing with '{}', which is not a path this"
                    + " server can even name: {}", sessionId, path, unusable.getMessage());
            return null;
        }
        // C1: checked and used must be the SAME value. A path like
        // '.plowshare/bots/sub/../mine.md' normalises to something this class
        // would accept, but the READ REQUEST below would carry the raw string
        // -- and it is the CLIENT that resolves 'sub/..', potentially through a
        // symlink 'sub' points at, to a file this check never saw. Rather than
        // reason about what a client's filesystem does with '..' and symlinks,
        // any path that a normalise would CHANGE is refused outright: the value
        // checked and the value sent are then trivially the same string.
        //
        // Compared as STRINGS and not as Paths, because Path equality is
        // already blind to two of the differences this guard exists to catch:
        // '.plowshare//bots/x.md' and '.plowshare/bots/x.md/' both parse EQUAL
        // to the path they normalise to, so a Path comparison passes them and
        // the raw string -- doubled slash, trailing slash and all -- is what
        // gets forwarded. They name the same file today on the filesystems we
        // know of, which is why this is a tidiness fix and not a bypass; it is
        // made anyway because "the string checked is the string sent" is the
        // property, and a check that only holds up to some other layer's notion
        // of equality is not that property.
        Path normalized = parsed.normalize();
        if (!normalized.toString().equals(path)) {
            log.debug("session '{}': answered a listing under {}/{} with '{}', which is not"
                    + " already in its own normalised form; refused rather than read, since a"
                    + " client resolves '..' and this server cannot see what it resolves"
                    + " against", sessionId, directory, subdir, path);
            return null;
        }
        if (!isContained(normalized, subdir)) {
            log.debug("session '{}': answered a listing under {}/{} with '{}', which does not"
                    + " stay under it; refused rather than read",
                    sessionId, directory, subdir, path);
            return null;
        }
        String filename = normalized.getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".md") && !(subdir.equals("orchestrations") && filename.toLowerCase(Locale.ROOT).endsWith(".js"))) {
            return null;
        }
        String name = Normalizer.normalize(
                filename.substring(0, filename.length() - ".md".length()), Normalizer.Form.NFC);

        String text = read(path);
        if (text == null) {
            return null;
        }
        return new Definition(name, "session " + sessionId + ": " + path, text);
    }

    /**
     * Every {@code <directory>/<subdir>} hit is checked against this rather
     * than trusted, for the reason this class's own javadoc gives at length:
     * going under {@code RemoteProvider} means going under its containment too.
     *
     * <p><b>{@code hit} is guaranteed to be the string a client answered with</b>
     * — {@link #readOneUnchecked} refuses anything {@link Path#normalize()}
     * would have changed before this is ever called (C1) — so what is left to
     * check here is the shape.
     *
     * <h2>The property, stated once</h2>
     *
     * <p><b>A hit is accepted only if, resolved the way the CLIENT will resolve
     * it, it names exactly one file sitting directly inside {@code
     * <directory>/<subdir>}</b> — nothing deeper, nothing beside it, and no
     * segment that some other filesystem would split into more segments than
     * this one does. That is three separate obligations, and each of the three
     * below is one of them.
     *
     * <h2>1. Every segment must be a segment everywhere (C2a)</h2>
     *
     * <p>This check runs on a server, in that server's {@link
     * java.nio.file.FileSystem}, on a string the <em>client</em> is going to
     * resolve in its own. On a POSIX server {@code ..\..\secret.md} is ONE
     * opaque name: it normalises unchanged, it is not absolute, and it counts as
     * a single segment — so a purely structural check made in this JVM's
     * semantics passes it, and a Windows client then resolves the two
     * backslashes as separators and climbs two directories out of {@code
     * .plowshare/}. The fix cannot be to enumerate Windows: it is to refuse any
     * segment that is not a segment under every separator convention this
     * system could meet, which is what {@link #isSafeSegment} is. Nothing is
     * lost that a definition needs — a file whose name contains a backslash or a
     * colon is not one anybody wrote a definition into.
     *
     * <h2>2. An absolute hit is anchored on the client's OWN roots (C2b)</h2>
     *
     * <p><b>This is the shape a real client actually sends, and an earlier
     * version of this check refused it outright.</b> {@code ClientEnforcer.glob}
     * walks {@code FileAccess.roots()}, whose entries {@code FileAccess.of}
     * canonicalises through {@code toRealPath()}/{@code toAbsolutePath()}; the
     * candidates {@code FileSearch.matching} collects are therefore prefixed
     * with an absolute root, and {@code ClientEnforcer} stringifies them with
     * {@code Path::toString}. A production reply is {@code
     * /Users/x/proj/.plowshare/bots/mine.md}. Refusing every absolute path made
     * this whole source contribute nothing against every real client, silently,
     * at debug — a failure no fixture in this suite could see, because every
     * fixture answers with relative strings.
     *
     * <p>So an absolute hit is not refused and it is not trusted either: {@link
     * FileRequest#ROOTS} is asked (once — {@link #clientRoots}) and the hit must
     * be exactly {@code <root>/<directory>/<subdir>/<one segment>} for one of
     * the roots that came back. That is the router's own instrument, one layer
     * down: <em>the client tells us what it can see, and nothing outside what it
     * told us is read.</em> A client that answers with no roots gets no absolute
     * hit accepted, which is the same containment reading "it can see nothing"
     * correctly rather than as an absence of a rule.
     *
     * <h2>3. A relative hit is anchored on its own head</h2>
     *
     * <p>A hit with no root component is resolved by the client against a
     * working directory neither end named, so there is nothing to anchor it to —
     * but there is also nothing to escape with, once (1) has run and {@code ..}
     * and every foreign separator are gone. {@code <directory>/<subdir>/<name>}
     * and nothing else is accepted, which lands inside SOME {@code
     * <directory>/<subdir>} wherever the client resolves it. That is a weaker
     * statement than the absolute case makes and it is the strongest one
     * available for a path spelled this way; the client's own {@code
     * FileAccess.permits} is the second half of it, refusing a read outside its
     * roots however this server got the name.
     */
    private boolean isContained(Path hit, String subdir) {
        for (Path segment : hit) {
            if (!isSafeSegment(segment.toString())) {
                return false;
            }
        }
        if (hit.isAbsolute()) {
            for (Path root : clientRoots()) {
                Path inside = root.resolve(directory).resolve(subdir);
                if (hit.getNameCount() == inside.getNameCount() + 1 && hit.startsWith(inside)) {
                    return true;
                }
            }
            return false;
        }
        return hit.getNameCount() == 3
                && directory.equals(hit.getName(0).toString())
                && subdir.equals(hit.getName(1).toString());
    }

    /**
     * Is this string one path segment, on this server's filesystem and on every
     * client's?
     *
     * <p>Three separator conventions and one normalisation quirk, and the
     * refusals are deliberately wider than any single platform needs:
     *
     * <ul>
     *   <li><b>{@code /}</b> — cannot survive {@code Path.of} on a POSIX server,
     *       but a segment handed to this method is not always one this JVM
     *       split, and the check costs nothing;
     *   <li><b>{@code \}</b> — the case that started this. A separator on
     *       Windows and an ordinary character here, so {@code ..\..\secret.md}
     *       is one segment to this server and three to the client that resolves
     *       it;
     *   <li><b>{@code :}</b> — a drive separator on Windows ({@code C:x} is
     *       relative to the drive's OWN current directory, not to the path it
     *       appears in) and the alternate-data-stream separator on NTFS
     *       ({@code x:evil.md}). This is the one refusal that can cost a real
     *       user something: a colon is legal in a POSIX filename. It is refused
     *       anyway, because a Windows client cannot produce one (it is illegal
     *       there) so a colon arriving from one is an injection, and the price
     *       on the POSIX side is a definitions file that does not load — this
     *       class's designed failure — against a drive-relative escape;
     *   <li><b>control characters</b>, {@code NUL} first among them, which
     *       truncate a path at whatever layer eventually hands it to a C
     *       library;
     *   <li><b>a segment that is nothing but dots and spaces.</b> This catches
     *       {@code .} and {@code ..} — which {@code normalize} has already dealt
     *       with, so this is the belt to that brace — and it catches the thing
     *       {@code normalize} has NOT dealt with: Windows strips trailing dots
     *       and spaces from a name, so {@code ".. "} is one opaque segment to
     *       this server and is {@code ..} to the client.
     * </ul>
     *
     * <p><b>The last three are not reachable through {@link #isContained} as it
     * stands</b>, and they are here anyway. The exact-shape check is what makes
     * them unreachable — a colon or a {@code ".. "} can only appear in the one
     * segment the shape leaves free, the filename, where a {@code .md} suffix is
     * also demanded — so they are unreachable exactly as long as that shape is
     * never relaxed by a segment. Only the backslash is reachable today, and it
     * is reachable in precisely that free segment: {@code
     * .plowshare/bots/..\..\secret.md} is one legal filename to this server and
     * three segments climbing two directories to a Windows client. A rule that
     * covers only the reachable case is a rule that has to be re-derived by
     * whoever next changes the shape.
     *
     * <p>{@link ChannelHooks} holds its answers to the same rule.
     */
    static boolean isSafeSegment(String segment) {
        if (segment == null) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c < 0x20) {
                return false;
            }
        }
        int end = segment.length();
        while (end > 0 && (segment.charAt(end - 1) == '.' || segment.charAt(end - 1) == ' ')) {
            end--;
        }
        return end > 0;
    }

    /**
     * What this session says it can see — asked at most once, and empty for
     * every reason this class does not throw.
     *
     * <p>Memoised rather than re-asked per hit: a listing naming forty files
     * would otherwise cost forty extra round trips to learn the same answer,
     * and a roots set that changed halfway through one listing would make
     * containment depend on which file was checked first. One answer, for the
     * one resolution this instance exists for — which is the same lifetime
     * {@link DefinitionResolver}'s cache gives it.
     *
     * <p><b>A failed fetch memoises as empty, on purpose.</b> The alternative —
     * retry on the next hit — turns one dead socket into one round trip per
     * file in the listing, all of them failing the same way. Empty is also the
     * safe reading: with no roots, no absolute hit is contained, so a client
     * this server could not ask contributes nothing rather than everything.
     */
    private List<Path> clientRoots() {
        if (roots != null) {
            return roots;
        }
        List<Path> fetched = new ArrayList<>();
        try {
            FileReply reply = ask(FileRequest.roots(id()));
            if (reply.paths() == null) {
                throw brokenAnswer(FileRequest.ROOTS, "answered without a list, and an absent"
                        + " list is not an empty one");
            }
            for (String named : reply.paths()) {
                if (named == null) {
                    continue;
                }
                Path root;
                try {
                    root = Path.of(named);
                } catch (RuntimeException unusable) {
                    continue;
                }
                // A root this server cannot use as an anchor is dropped rather
                // than repaired. `FileAccess.of` canonicalises every root it
                // builds, so a real client's are already absolute and already
                // normalised; one that is neither did not come from there, and
                // resolving a hit against an un-normalised anchor would put the
                // '..' this class refuses back into the comparison.
                if (root.isAbsolute() && root.normalize().equals(root)) {
                    fetched.add(root);
                }
            }
        } catch (RuntimeException failed) {
            log.debug("session '{}': its roots could not be had, so no absolute listing entry"
                    + " can be anchored and none contributes anything: {}",
                    sessionId, failed.getMessage());
            fetched.clear();
        }
        roots = List.copyOf(fetched);
        return roots;
    }

    /**
     * One file, paged to its end — bounded against a hostile or merely buggy
     * client the whole way, per Ruling 2 and Ruling 3 together. {@code null}
     * rather than a partial string in every failure case: Ruling 2's whole
     * point is that a file this class could not page to completion
     * contributes nothing rather than the prefix it managed to read.
     *
     * <h2>C3: the ceiling is this server's, and nothing the peer says moves it</h2>
     *
     * <p><b>Every check derived from the reply bounds only an honest client.</b>
     * The checks below are all still here and all still worth making — the
     * total must not change between pages, an empty page may not claim more
     * remains, the running tally may not overtake the total, and the offset is
     * this method's own count and never the peer's echo — but a client that
     * simply says {@code totalLines = 2_147_483_647} on its FIRST reply
     * satisfies every one of them forever, at 1 MiB a frame, until this server
     * is out of heap. The bound was a number the attacker picked.
     *
     * <p>So the real bound is {@link #MAX_DEFINITION_BYTES} and {@link
     * #MAX_DEFINITION_LINES}, fixed in this class, checked <b>as each line is
     * appended</b> and not once at the end — an accumulator that is only
     * measured after the loop has finished is an accumulator with no ceiling at
     * all. Crossing either one means this file contributes nothing: there is no
     * truncation, for the reason Ruling 2 gives about prefixes that still parse.
     *
     * <p><b>A page may not be longer than the window that asked for it.</b>
     * {@link Window#cut} is what a client is supposed to use and it cannot
     * overshoot; a reply that does is a client answering a question nobody
     * asked, and it is the cheapest way to make one round trip carry far more
     * than one window's worth of heap. Checked against {@link Window#limit()} of
     * the window actually sent rather than against {@link Window#MAX_WINDOW_LINES},
     * so it stays true if this method ever asks for less.
     *
     * <p>Together the two ceilings also bound the number of exchanges: an empty
     * page claiming more is already refused, so every further round adds at
     * least one line, and {@link #MAX_DEFINITION_LINES} is therefore the most
     * rounds one file can cost.
     */
    private String read(String path) {
        return readWhole(path).text();
    }

    /** {@link #read}, saying why a file contributes nothing — absent, or unreadable and why. */
    private FileRead readWhole(String path) {
        List<String> lines = new ArrayList<>();
        long bytes = 0;
        int offset = 0;
        long expectedTotal = -1;
        while (true) {
            Window window = Window.of(offset, Window.MAX_WINDOW_LINES);
            Span span;
            try {
                FileReply reply = ask(FileRequest.read(id(), path, window));
                if (reply.span() == null) {
                    throw brokenAnswer(FileRequest.READ, "answered without the lines it was"
                            + " asked for, and an absent window is not an empty one");
                }
                span = reply.span();
            } catch (RuntimeException failed) {
                log.debug("session '{}': '{}' could not be read to completion, so it contributes"
                        + " nothing: {}", sessionId, path, failed.getMessage());
                // The one failure that is an answer and not a fault: the file is not there.
                // Every client's file tools open that refusal with these words (rule 7's own
                // test, ToolLines.outcome), on the first page, before anything was read.
                return offset == 0 && absent(failed) ? FileRead.ABSENT
                        : FileRead.unreadable(String.valueOf(failed.getMessage()));
            }
            // M4: Span's own contract is that stoppedBy is one of exactly three
            // words; anything else is a reply this build cannot page past, per
            // Span's own javadoc on why the field is a string and not an enum.
            if (!isPageable(span.stoppedBy())) {
                log.debug("session '{}': '{}' answered a window stopped by the unrecognised"
                        + " reason '{}', so it contributes nothing",
                        sessionId, path, span.stoppedBy());
                return FileRead.unreadable("the session's answer could not be assembled");
            }
            if (span.lines().size() > window.limit()) {
                log.debug("session '{}': '{}' answered {} lines to a window that asked for {},"
                        + " so it contributes nothing", sessionId, path,
                        span.lines().size(), window.limit());
                return FileRead.unreadable("the session's answer could not be assembled");
            }
            if (expectedTotal < 0) {
                expectedTotal = span.totalLines();
            } else if (span.totalLines() != expectedTotal) {
                log.debug("session '{}': '{}' reported {} total lines and then {}, so it"
                        + " contributes nothing rather than an assembly built against a moving"
                        + " target", sessionId, path, expectedTotal, span.totalLines());
                return FileRead.unreadable("the session's answer could not be assembled");
            }
            if (span.lines().isEmpty() && span.more()) {
                // No forward progress. Kept as its own check rather than folded
                // into the bound below: a client pinning totalLines just above
                // the current line count and answering empty pages forever
                // would otherwise loop until the bound below catches it one
                // page at a time -- forever, since zero lines never reaches it.
                log.debug("session '{}': '{}' answered an empty window while claiming more"
                        + " remains, so it contributes nothing", sessionId, path);
                return FileRead.unreadable("the session's answer could not be assembled");
            }
            for (String line : span.lines()) {
                // THE CEILING, per line and not per file: an accumulator only
                // measured after the loop is an accumulator with no ceiling.
                // Both numbers are this server's own -- see the constants.
                bytes += line == null ? 1 : line.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (bytes > MAX_DEFINITION_BYTES) {
                    log.debug("session '{}': '{}' is past {} bytes, which is more than this"
                            + " server will assemble for one definition, so it contributes"
                            + " nothing", sessionId, path, MAX_DEFINITION_BYTES);
                    return FileRead.unreadable("the file is larger than this server assembles");
                }
                lines.add(line);
                if (lines.size() > MAX_DEFINITION_LINES) {
                    log.debug("session '{}': '{}' is past {} lines, which is more than this"
                            + " server will assemble for one definition, so it contributes"
                            + " nothing", sessionId, path, MAX_DEFINITION_LINES);
                    return FileRead.unreadable("the file is larger than this server assembles");
                }
            }
            if (lines.size() > expectedTotal) {
                // More lines accumulated than the file claimed to have in
                // total -- an overlapping or duplicated window, or a client
                // that inflated the count of what it actually sent rather than
                // the total. Either way this assembly cannot be trusted.
                log.debug("session '{}': '{}' answered with more lines than its own reported"
                        + " total of {}, so it contributes nothing", sessionId, path, expectedTotal);
                return FileRead.unreadable("the session's answer could not be assembled");
            }
            if (!span.more()) {
                break;
            }
            offset += span.lines().size();
            if (offset >= expectedTotal) {
                // The bound: this method's own tally has reached or passed the
                // file's own reported length while the reply still claims more
                // remains, which is the peer's story failing to add up rather
                // than a file this class can keep paging.
                log.debug("session '{}': '{}' claims more remains at offset {} against a"
                        + " reported total of {}, so it contributes nothing",
                        sessionId, path, offset, expectedTotal);
                return FileRead.unreadable("the session's answer could not be assembled");
            }
        }
        return new FileRead(String.join("\n", lines), null);
    }

    /** {@link Span#stoppedBy()} is one of exactly these three, by its own
     *  contract; anything else is a reply built against a later release or a
     *  client that made the field up, and either way this build cannot page
     *  past it -- the same "unrecognised is unavailable" direction {@link
     *  FileReply#outcome()} takes for its own open string field. */
    private static boolean isPageable(String stoppedBy) {
        return Span.LINES.equals(stoppedBy) || Span.BYTES.equals(stoppedBy)
                || Span.END.equals(stoppedBy);
    }

    /**
     * One exchange, with the answer checked for being one — {@code
     * RemoteProvider.ask}'s own shape, copied rather than reinvented, minus the
     * grant it has no equivalent of here.
     */
    private FileReply ask(FileRequest request) {
        // Marked here, in the one place every request this class sends passes
        // through, so no call site above can forget: Ruling 7 says only
        // FileRequest.DEFINITIONS reaches a client's .plowshare/agents or
        // .plowshare/bots, and this is the harness saying so rather than a
        // model's file tool, which never calls forDefinitions() at all.
        FileReply reply = channel.ask(sessionId, request.forDefinitions());
        if (!request.id().equals(reply.id())) {
            throw brokenAnswer(request.op(), "answered a different request");
        }
        // Facts from a current client, worded by the one renderer; an old
        // client's own words otherwise.
        if (FileReply.REFUSED.equals(reply.outcome()) && reply.result() != null) {
            throw new FileRefusedException(reply.result(), true);
        }
        String said = FileWords.said(reply);
        if (FileReply.REFUSED.equals(reply.outcome())) {
            throw new WorkspaceRefusedException(said == null
                    ? "the client refused this and gave no reason"
                    : said);
        }
        if (!FileReply.OK.equals(reply.outcome())) {
            throw new WorkspaceUnavailableException(said == null
                    ? "the session '" + sessionId + "' could not answer and gave no reason"
                    : said);
        }
        return reply;
    }

    /**
     * Whether a read failed because the file is not there: the facts say so, or
     * — from a client built before facts — its words open as every client's did.
     */
    private static boolean absent(RuntimeException failed) {
        if (failed instanceof FileRefusedException refused) {
            return FileResult.NO_FILE.equals(refused.facts().kind());
        }
        return failed instanceof WorkspaceRefusedException && failed.getMessage() != null
                && failed.getMessage().startsWith(ABSENT_OPENING);
    }

    private WorkspaceUnavailableException brokenAnswer(String op, String what) {
        return new WorkspaceUnavailableException("the session '" + sessionId + "' " + what
                + " when asked to " + op + ", so nothing can be concluded from it");
    }

    /** {@code RemoteProvider.id()}'s own reasoning: random rather than a
     *  counter, since a counter would have to be shared with every other
     *  provider on the same socket. */
    private static String id() {
        return UUID.randomUUID().toString();
    }
}
