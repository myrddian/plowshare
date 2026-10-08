package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionCloseListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The set of definitions one caller can see and run.
 *
 * <p><b>Resolution is per caller and not per project</b>, because spec §2 puts one of the two
 * project-level sources on the caller's own machine. Two callers asking about one project can
 * honestly get different answers: a console session sees what is on the server, and a client
 * session sees that plus its own local definitions. This class holds a project's {@code agents/}
 * and {@code bots/} under {@link DataLayout}, and — for a caller whose {@link Caller#sessionId()}
 * names a session with a file channel attached to it, rooting that very project — that session's
 * own {@link ChannelDefinitions} behind them, all layered over the boot set built once at wiring
 * time.
 *
 * <p><b>The boot set is never rebuilt.</b> Only project tiers are, and each is read fresh the first
 * time a project is asked for, cached after that, and read again when the directories it was read
 * from have changed — see "a bot dropped in resolves on the next lookup" below, which is the
 * guarantee this whole class exists to deliver. Nothing a project tier holds can be {@code
 * required} — {@link #readProject} passes {@code Set.of()} to the tier's own read, so nothing in it
 * can ever abort <em>that</em> read. A bad project definition is a disabled one, reported through
 * {@link #refusalsFor}, and so is a withheld edge or a withheld tool — the tier read's own {@code
 * disabled()}, {@code withheldEdges()} and {@code withheldTools()} are all folded into it, because
 * nothing this class withholds may be withheld without being named.
 *
 * <p><b>A project's own definitions are validated against the boot set's names as well as its
 * own</b> — {@link AgentRegistry}'s package-private four-{@code Set} {@code read} overload exists
 * for exactly this caller — so a project agent may declare {@code calls:} naming an agent the boot
 * set defines and keep that edge. Without it, every such edge would read to the tier's own read as
 * a typo — "no file in this directory defines it" — and would be silently withheld before the merge
 * below ever saw it.
 *
 * <p><b>A project file named after a {@code required} agent is refused, not silently
 * substituted.</b> The inherited definition keeps running and the refusal is named in {@link
 * #refusalsFor} — an agent silently not being the one an operator wrote is the invisible-fact
 * failure this codebase designs against throughout {@link AgentRegistry}.
 *
 * <p><b>Every refusal this class decides is also logged at {@code WARN}, because {@link
 * #refusalsFor} answers only somebody who already suspects.</b> A structured map is the right form
 * for a surface to render, and it is the wrong and only form for an operator who is looking at a
 * file and wondering why nothing changed. The two that would otherwise be silent are the {@code
 * required}-name collision above and the whole-project fallback in {@link #readProject} — that
 * fallback was accepted <em>because</em> it fails visibly rather than silently, and a map with no
 * reader is not visible. The per-file disablements are logged by {@code AgentRegistry.read} itself,
 * in the same words, whichever tier it is reading; the ones {@link DefinitionChecks} adds after
 * that loop has run are logged by {@link #build}.
 *
 * <p><b>A fault that only exists once the tier is merged with the boot set is caught, not
 * thrown.</b> The tier's own read cannot see everything: a callee whose own file failed to parse is
 * still a name the tier believes exists (the disable rule), but that name is never added to the
 * merged map, so a caller of it becomes a genuinely unreachable callee only once merged; a shadowed
 * agent an inherited caller calls, or a grant that escalates once two directories are combined, are
 * the same shape one level over. Those are still faults of <em>this project's own tier</em> and
 * must not propagate out of {@link #forCaller} as an exception mid-run, so {@link #readProject}
 * wraps the merge in a catch: on a set-level fault the whole project falls back to the unmodified
 * boot set and the failure is named once, under a key no real agent can have, in {@link
 * #refusalsFor}.
 *
 * <p><b>Registered Applications read their resources from the server workspace.</b> They work
 * without a definitions data directory or a connected client. Externals with no data directory
 * retain the boot set. Application revisions participate in cache identity; a client session cannot
 * replace the deployed tier. Invalid Application boundaries refuse resource reads.
 *
 * <p><b>A bot dropped into {@code projects/&lt;id&gt;/bots/} resolves on the next lookup, with no
 * restart</b> — spec §5, and the one thing this slice exists to deliver. The cache is therefore
 * <em>stamped</em> rather than merely populated: {@link #forCaller} takes a {@link Stamp} of the
 * project's two directories before it serves a cached entry, and rebuilds the tier when the stamp
 * has moved. The alternative shapes were both wrong here. Reading the tier on every call is what
 * {@link AgentRegistry}'s own javadoc excludes in as many words — "asking the map on a second call
 * would mean re-parsing and re-validating every definition per delegation" — and a time-to-live
 * would make the guarantee a promise about a clock ("within N seconds") when the spec's promise is
 * about an ordering ("on the next lookup"). The stamp keeps the ordering and costs two directory
 * listings, with one {@code stat} per entry and no file opened, no frontmatter parsed and no graph
 * re-validated unless something actually moved.
 *
 * <p><b>What the stamp can and cannot see, stated rather than assumed.</b> It is each entry's name,
 * size and modification time, which catches a file added, removed, renamed, truncated or written to
 * — every way an operator changes what a tier holds. It cannot see an edit that leaves both the
 * size and the recorded modification time unchanged, which needs a rewrite landing inside one
 * filesystem timestamp tick with the byte count preserved; a dropped-in file, which is the case §5
 * names, always moves the stamp because it is a name that was not there before. The directories are
 * stamped rather than the files this loader would accept, so a stray {@code .swp} costs a rebuild
 * that changes nothing rather than a rule about suffixes kept in two places.
 *
 * <p><b>The database is the authority on which projects exist, and the filesystem is never asked
 * first.</b> Spec §9.6: {@link #forCaller} asks {@code projectExists} before it touches the cache,
 * the stamp or the disk, so a {@code projects/<id>/} directory whose id matches no row is simply
 * never resolved for — not an error, and nothing evicts it. A directory left behind by a deleted
 * project is inert on that same rule: no entry is ever made for a project the database does not
 * name, so a stale directory cannot resurrect a project's definitions once the row that named it is
 * gone, however often it changes. The other direction is not repaired either — a directory deleted
 * while its project row still exists leaves that project broken, and the system recreating the
 * directory yields an empty one rather than restoring anything. Neither direction is healed
 * automatically and neither is reported at boot; the filesystem is consulted per resolution and
 * never audited up front.
 *
 * <p><b>A session's own cache entries do not share that fate.</b> The database names a project once
 * and keeps naming it; a session id is chosen by the <em>client</em>, not by any row, and {@link
 * SessionChannel#ask}'s own javadoc says so. A client that cycled session ids would otherwise pin
 * one {@link AgentRegistry} per id in {@link #byProject} forever — nothing in the shape above ever
 * removes an entry keyed by a session that is never coming back. {@link #sessionClosed} is the
 * eviction that closes that gap: this class implements {@link SessionCloseListener}, and {@code
 * FileChannelHandler} calls it when a session's file channel closes, so both {@link #byProject} and
 * {@link #refusals} drop everything keyed to that session at the moment nothing can ever ask for it
 * again under that id.
 *
 * <p><b>And that eviction has to beat an install that is still in flight, and an install that
 * begins after it has finished.</b> A session closing while its own first resolution is mid-flight
 * would otherwise be swept before the entry it is about to write exists — the entry lands after the
 * sweep has passed, keyed by a session id that is now dead and client-chosen. A resolution that
 * <em>begins</em> after {@link #sessionClosed} has already returned is worse, because it needs no
 * race at all: any {@link #forCaller} call for {@code (project, S)} starting after {@code S} has
 * gone would install an entry nothing will ever sweep, deterministically, every time. And {@code
 * Runs.start} does not validate that the {@code session} a request names was ever attached — a body
 * naming a session nothing has attached to submits, which is the deliberate shape of that endpoint
 * — so an authenticated caller naming arbitrary, never-live session ids could mint one permanent
 * entry per id, per project. That is the unbounded growth this whole mechanism exists to bound,
 * reopened through the one door it left unlocked.
 *
 * <p><b>Both are closed by one question, asked at install time: is anything holding this session's
 * file channel right now?</b> {@link #sessionLive} is that question and {@code
 * session.SessionRegistry} answers it — {@code find} returns empty for an id nothing ever attached
 * to and inserts nothing by being asked, so asking cannot itself become the leak. {@code
 * FileChannelHandler.afterConnectionClosed} detaches the channel from that registry <em>before</em>
 * it calls its close listeners, so from the moment a session closes the answer for it is no, for
 * good. An entry is therefore kept under a session key only while that session still holds the one
 * thing a session contributes here.
 *
 * <p><b>That makes eviction total rather than best-effort, which is the second reason the question
 * is about the file channel and not about a session existing.</b> {@link #sessionClosed} has
 * exactly one caller, {@code FileChannelHandler}, and that class is also what detaches the role
 * being asked about. So the sessions this class keys entries by and the sessions whose closing it
 * is told about are the same set, by construction: every session-keyed entry belongs to a session
 * whose close fires the sweep. A weaker question — has anything ever attached, is any role attached
 * — would key entries by listener-only sessions, which close through {@code EventChannelHandler}
 * and are never reported here at all, and those entries would be exactly the permanent ones this
 * section exists to stop.
 *
 * <p><b>A caller whose session is not there is served, not refused, and what it is served is the
 * sessionless answer.</b> Its resolution runs under {@code CacheKey(projectId, null)} — the entry
 * every sessionless caller on that project already shares — and builds no {@link
 * ChannelDefinitions} layer at all. That is not a downgrade, because there is no other answer to
 * give: the only thing a session contributes to a resolution is that layer, read down its file
 * channel, and a session with no channel attached answers every request on it with "gone", which
 * {@link ChannelDefinitions} turns into an empty listing. The tier such a caller would get from
 * building the layer anyway is the tier it gets from not building it, minus a round trip that
 * cannot succeed and minus a cache entry keyed by an id nothing will ever close. It also cannot
 * leak one operator's local paths into another's report — the reason {@link CacheKey} carries a
 * session at all — because a build with no channel layer has no such path in it to leak.
 *
 * <p><b>A counter delta could not have expressed any of that, which is why there is no longer
 * one.</b> {@code closes} was a global count, read before a build and again after the write, and
 * all it could ever say was "some session closed while you were building" — never "the key you just
 * installed belongs to a session that was already gone before you started", which is the sentence
 * the leak above needed. It could not say <em>whose</em> close it had seen either: a resolution
 * racing an <em>unrelated</em> session's close withdrew a build that was good, and dropped that
 * key's {@link #refusals} with it <em>after</em> {@link #forCaller} had already handed the registry
 * to its caller — so a report could come back empty for a build that had refused something real.
 * The question asked now is about one session, this key's own, so an unrelated close costs nothing
 * and takes nothing.
 *
 * <p><b>The question is asked after the entry is written, and that ordering is the proof.</b>
 * Write, then ask. Let {@code P} be the write and {@code C} the question, and let a close be its
 * registry detach {@code D} followed by its sweep {@code S}. If {@code D} lands before {@code C},
 * the answer is no and this method removes its own entry. If {@code D} lands after {@code C}, then
 * {@code S} follows {@code D} and therefore follows {@code P}, so the sweep finds the entry and
 * removes it. There is no third case. Asking before writing instead would leave one: a close whose
 * detach lands after the question and whose sweep lands before the write.
 *
 * <p><b>What this costs, stated rather than left to be discovered.</b> Not a rebuild per lookup: a
 * caller whose session holds no file channel resolves under the sessionless key and is served from
 * the entry that key already caches, so it pays a directory stamp and nothing else. What it does
 * cost is a distinction — two callers on one project, one with a live file channel and one without,
 * no longer necessarily hold two entries — and one honest loss of freshness: a client whose channel
 * attaches <em>between</em> {@link #keyFor} asking and its own definitions mattering gets the
 * sessionless answer for that one call, and its own {@code .plowshare/} from the next. The previous
 * shape did worse with the same interleaving, and permanently: it cached the empty channel tier
 * under that session's key, where no stamp covers it — {@link Stamp} watches the two server
 * directories and nothing on a client's machine — so the emptiness outlived the client's arrival.
 *
 * <p>None of that erases what this design bought. Building the tier outside {@link #byProject}'s
 * bin lock — see {@link #forCaller}'s own javadoc — is a real improvement independent of the
 * eviction question above; moving filesystem work and blocking channel round trips off a {@link
 * ConcurrentHashMap} bin lock stands on its own, and an install that is a plain write rather than a
 * mapping function's atomic install is what lets this method withdraw its own entry at all.
 */
public final class DefinitionResolver implements SessionCloseListener {

  /** Who is asking: a project, a session, both or neither. */
  public record Caller(Long projectId, String sessionId, String handle) {
    public Caller(Long projectId, String sessionId) {
      this(projectId, sessionId, null);
    }

    public Caller withoutSession() {
      return new Caller(projectId, null, handle);
    }

    /** A caller that is the server itself — no project, no channel. */
    public static Caller server() {
      return new Caller(null, null);
    }
  }

  /**
   * A project tier's cache key: the project alone for a caller with no channel, and the project
   * <em>and</em> session for one with a channel that is actually attached.
   *
   * <p><b>A caller whose session is not attached to anything is keyed as the sessionless caller it
   * effectively is</b> — see {@link #keyFor} and this class's own javadoc. The two reasons below
   * are both reasons to key by session when a session contributed something; neither applies to a
   * resolution that read no channel at all, and keying such a resolution by an id a client minted
   * is the leak that section closes.
   *
   * <p><b>Not the project alone.</b> {@link ChannelDefinitions}' own lifetime is the socket's — a
   * session that reconnects may hold a different {@code .plowshare/}, or none at all, and caching
   * only by project would hand a later session the first one's client-side definitions, or the
   * reverse. Two callers on one project with different sessions can honestly see different
   * registries, exactly as this class's own top javadoc says, so each session gets its own cache
   * entry rather than sharing the project's.
   *
   * <p><b>Also {@link #refusals}' key, and for the same reason plus one more.</b> A channel-tier
   * refusal's message carries {@code ChannelDefinitions}' own origin format — {@code "session <id>:
   * <path>"} — which names a path on one operator's own machine. Keying {@link #refusals} by
   * project alone would let a second session's build overwrite the first's refusals wholesale (a
   * plain {@link Long} key has no session to distinguish them by) and would surface the first
   * operator's local file paths to whoever's build ran last under the same project id — a privacy
   * leak, not merely a stale report.
   */
  private record CacheKey(Long projectId, String sessionId, Long personalId) {}

  /**
   * What one project's two server-side directories looked like when a cached tier was read from
   * them, and the whole of what {@link #forCaller} compares before serving that tier again.
   *
   * <p>One string per directory, each the sorted {@code name -> size@millis} of everything in it —
   * sorted because a directory listing has no defined order, and per entry because a directory's
   * own modification time moves when a file is added or removed and <em>not</em> when one is edited
   * in place. See this class's javadoc for what that does and does not catch and why the cheaper
   * directory-only stamp was not enough.
   */
  private record Stamp(String agents, String bots) {}

  /**
   * One cached tier and the stamp it was read at, held together rather than in two maps.
   *
   * <p>A second map keyed the same way would be a second thing for {@link #sessionClosed} to sweep
   * and a second place for the pair to disagree — a registry serving under a stamp that had already
   * been replaced is a stale read that nothing would ever notice.
   */
  private record Cached(Stamp stamp, AgentRegistry registry) {}

  /**
   * The {@link #refusalsFor} key for a project-wide fallback — no real agent name can collide with
   * it, since a name is a file stem and never carries parentheses or spaces.
   */
  private static final String TIER = "(the project tier)";

  private static final Logger log = LoggerFactory.getLogger(DefinitionResolver.class);

  private java.util.function.Function<Caller, Long> personalIds = caller -> null;

  public void usePersonalResources(java.util.function.Function<Caller, Long> personalIds) {
    this.personalIds = personalIds;
  }

  private final AgentRegistry bootSet;
  private java.util.function.Function<Long, ProjectConfiguration> projectConfiguration =
      id -> ProjectConfiguration.NONE;

  public void useProjectConfiguration(
      java.util.function.Function<Long, ProjectConfiguration> source) {
    projectConfiguration = source;
  }

  private ApplicationResources applicationResources = ApplicationResources.NONE;

  /** Composition supplies registered server sources; client sessions cannot replace them. */
  public void useApplicationResources(ApplicationResources resources) {
    applicationResources = Objects.requireNonNull(resources);
  }

  private final DataLayout data;
  private final LongPredicate projectExists;
  private ScopedTools scopedTools = ScopedTools.NONE;

  public void useScopedTools(ScopedTools tools) {
    scopedTools = Objects.requireNonNull(tools);
  }

  private Set<String> knownTools(Long project) {
    var names = new java.util.TreeSet<>(knownTools);
    names.addAll(scopedTools.names(project));
    return Set.copyOf(names);
  }

  private final Set<String> knownTools;
  private final Set<String> required;
  private final SessionChannel channel;
  private final Predicate<String> sessionLive;
  private final BiPredicate<Long, String> sessionRoots;
  private final DefinitionChecks checks;
  private final Map<CacheKey, Cached> byProject = new ConcurrentHashMap<>();
  private final Map<CacheKey, Map<String, String>> refusals = new ConcurrentHashMap<>();

  /**
   * @param projectExists whether a project id still has a row — the database's word on which
   *     projects exist, asked by {@link #forCaller} before the cache or the filesystem is touched.
   *     See this class's own javadoc, "the database is the authority", for why a project whose row
   *     is gone is never resolved for even when its directory is still there
   * @param channel how to reach a caller's own session, for the one caller in {@link #forCaller}
   *     whose {@link Caller#sessionId()} names a session {@code sessionLive} and {@code
   *     sessionRoots} both answer yes for. Never asked for a caller with no session, never for one
   *     whose session roots some other project, and never for one whose session is not attached to
   *     anything — {@link #readProject} only builds a {@link ChannelDefinitions} layer when {@link
   *     #keyFor} kept the session in the key
   * @param sessionLive whether a session id names a session with a file channel attached to it
   *     <em>right now</em> — {@code SessionRegistry}'s word, asked at the moment a session-keyed
   *     entry would be installed and again nowhere else. A {@link Predicate} rather than the
   *     registry itself, on {@code projectExists}' own precedent one parameter up: this class asks
   *     one bit of an authority that owns a great deal more than that bit, and taking the bit is
   *     what keeps a test able to state the answer it is testing against. See this class's javadoc,
   *     "both are closed by one question", for why a counter could not answer it
   * @param sessionRoots whether a live session roots the project with this id right now — {@code
   *     PresenceRegistry}'s word, through {@code ProjectStore}'s name for the id. Asked by {@link
   *     #keyFor} after {@code sessionLive}, so a session contributes its {@code .plowshare/} only
   *     to the project that directory belongs to. Handed a {@code null} project id by {@link
   *     #refusalsFor}, for which the answer must be no. A predicate rather than the two registries,
   *     for {@code sessionLive}'s own reason
   * @param checks the questions about a definition that need a fleet to answer, so that a tier read
   *     here is judged by the same rules {@code AgentsConfig} judged the boot set by. {@link
   *     DefinitionChecks#NONE} for a context with no dispatcher to ask, named at the call site
   *     rather than defaulted here — a silent default is how the two came to disagree in the first
   *     place
   */
  public DefinitionResolver(
      AgentRegistry bootSet,
      DataLayout data,
      LongPredicate projectExists,
      Set<String> knownTools,
      Set<String> required,
      SessionChannel channel,
      Predicate<String> sessionLive,
      BiPredicate<Long, String> sessionRoots,
      DefinitionChecks checks) {
    this.sessionRoots = Objects.requireNonNull(sessionRoots, "sessionRoots");
    this.bootSet = Objects.requireNonNull(bootSet, "bootSet");
    this.data = Objects.requireNonNull(data, "data");
    this.projectExists = Objects.requireNonNull(projectExists, "projectExists");
    this.knownTools = Set.copyOf(knownTools);
    this.required = Set.copyOf(required);
    this.channel = Objects.requireNonNull(channel, "channel");
    this.sessionLive = Objects.requireNonNull(sessionLive, "sessionLive");
    this.checks = Objects.requireNonNull(checks, "checks");
  }

  /**
   * The fixtures' constructor: every live session counts as rooting whatever project it asks about,
   * which is what every test written before {@code sessionRoots} existed was measuring.
   * <b>Production never uses it</b> — {@code AgentsConfig.definitionResolver} names the predicate —
   * and a new caller should not either, since this default is exactly the leak that parameter
   * closes.
   */
  public DefinitionResolver(
      AgentRegistry bootSet,
      DataLayout data,
      LongPredicate projectExists,
      Set<String> knownTools,
      Set<String> required,
      SessionChannel channel,
      Predicate<String> sessionLive,
      DefinitionChecks checks) {
    this(
        bootSet,
        data,
        projectExists,
        knownTools,
        required,
        channel,
        sessionLive,
        (projectId, session) -> true,
        checks);
  }

  /**
   * The registry built once at boot, from the shipped seed and (if this deployment keeps a data
   * directory) {@code global/agents/} and {@code global/bots/} layered over it.
   */
  public AgentRegistry bootSet() {
    return bootSet;
  }

  /**
   * What this caller's tier refused, by name, with the reason. Empty for a caller never resolved,
   * and for one resolved on a deployment that keeps no data directory, which has no tier to have
   * refused anything.
   *
   * <p>Takes the whole {@link Caller} and not merely a project id (I2): refusals are keyed the same
   * way the registry cache is, by project <em>and</em> session, so asking about one session's build
   * never returns — or silently loses — another session's.
   *
   * <p><b>Keyed the same way</b> means through {@link #keyFor} and not through {@link CacheKey}'s
   * constructor: a caller whose session is not attached to anything was <em>resolved</em> as a
   * sessionless caller, so the refusals that belong to it are the sessionless ones. Building the
   * key any other way here would answer such a caller with an empty map for a build that had
   * refused something real. The answer can still be empty if the session went away between that
   * resolution and this question, which is the ordinary shape of asking about something that has
   * changed rather than a case this method could be written out of.
   */
  public Map<String, String> refusalsFor(Caller caller) {
    Objects.requireNonNull(caller, "caller");
    return Map.copyOf(refusals.getOrDefault(keyFor(caller), Map.of()));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Drops every {@link #byProject} and {@link #refusals} entry keyed to {@code sessionId}, of
   * which there is at most one per project this session ever asked about. A session that reconnects
   * under the same id starts with nothing cached rather than inheriting a stale {@link
   * ChannelDefinitions} tier — see this class's own top javadoc for why a client-chosen id must not
   * be allowed to pin a cache entry forever.
   *
   * <p><b>This is the eviction and not the whole of the bound.</b> A resolution running while this
   * method runs may write its entry after the two sweeps below have passed it, and a resolution
   * starting after this method returns would never be swept at all; neither is closed here. Both
   * are closed by {@link #forCaller} asking, after it writes an entry, whether that entry's session
   * is still attached to anything — an ordering that works precisely because {@code
   * FileChannelHandler.afterConnectionClosed} detaches the channel from {@code SessionRegistry}
   * before it calls this method. See this class's own javadoc for the proof and for what the close
   * counter that used to stand here could not express.
   */
  @Override
  public void sessionClosed(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    byProject.keySet().removeIf(key -> sessionId.equals(key.sessionId()));
    refusals.keySet().removeIf(key -> sessionId.equals(key.sessionId()));
  }

  /**
   * Forget every cached tier for {@code projectId}, across every session, so the next {@link
   * #forCaller} call for it reads {@code agentsFor}/{@code botsFor} fresh regardless of what {@link
   * Stamp} they carry right now.
   *
   * <h2>Why this exists although the cache is already stamped</h2>
   *
   * <p>The stamp is not a substitute for this, and this class's own javadoc already says so under
   * "what the stamp can and cannot see": it is each entry's name, size and modification time, and
   * it explicitly cannot see an edit that lands within one filesystem timestamp tick and leaves the
   * byte count unchanged — the stamp does not move, and {@link #forCaller} keeps serving the cached
   * registry it already had. {@link DefinitionWriter} is exactly the caller most likely to produce
   * that gap: a replacement it writes is validated first and is often the same length as what it
   * replaces — a corrected sentence, a swapped tool name, a fixed typo in a {@code calls:} line,
   * none of which changes the file's size — and the whole point of an HTTP surface in front of it
   * is to make a write visible on the very next lookup, not on whichever later write happens to
   * land in a different tick or at a different size. An explicit invalidation on every successful
   * write is the only thing that closes a gap the stamp's own javadoc documents rather than hides.
   *
   * <p><b>Every session's entry for {@code projectId}, and not merely the one keyed by no
   * session.</b> {@link #byProject} is keyed by {@link CacheKey}, which is project <em>and</em>
   * session together — see that record's own javadoc — so one project has as many live cache
   * entries as it has sessions that have ever asked {@link #forCaller} about it. Dropping only
   * {@code new CacheKey(projectId, null)} would leave every session-scoped entry still serving the
   * tier as it stood before this write, which for a caller with a session — the console, an
   * attached client — is the ordinary case and not an edge one.
   *
   * <p>{@link #refusals} is dropped alongside {@link #byProject}, on {@link #sessionClosed}'s own
   * precedent of clearing both together: a refusal recorded before this write would otherwise go on
   * answering {@link #refusalsFor} until the next rebuild happens to overwrite it, and there is no
   * reason to let a caller read a stale one in the meantime.
   *
   * <p>Harmless and a no-op for {@code projectId == null}: {@link #forCaller} answers the (never
   * rebuilt) boot set directly for a caller with no project, before either map is ever consulted,
   * so nothing is ever cached under a {@code null} project id for this to find.
   */
  public void invalidate(Long projectId) {
    byProject.keySet().removeIf(key -> Objects.equals(projectId, key.projectId()));
    refusals.keySet().removeIf(key -> Objects.equals(projectId, key.projectId()));
  }

  /**
   * List the caller's current definitions, including edits on its attached client. The server
   * directory stamp cannot see client-side edits. Only this caller's rooted session entry is
   * dropped; sessionless tiers still use their disk stamps, and other sessions retain their own
   * registries and refusals.
   */
  public AgentRegistry refreshForCaller(Caller caller) {
    Objects.requireNonNull(caller, "caller");
    CacheKey key = keyFor(caller);
    if (key.sessionId() != null) {
      byProject.remove(key);
      refusals.remove(key);
    }
    return forCaller(caller);
  }

  /**
   * The registry {@code caller} sees: its project's own definitions, layered over the boot set, or
   * the boot set itself when there is no project — or when the project named no longer has a row.
   *
   * <p><b>{@code projectExists} is asked before the cache and before the disk</b>, which is the
   * ordering Spec §9.6 requires: the database decides whether there is a project tier to read at
   * all, and the filesystem is never asked first. A caller naming a deleted project's id gets the
   * boot set on every call, at the cost of one predicate test — cheaper than the cache lookup it
   * precedes, and paid whether or not {@code projects/<id>/} exists on disk.
   *
   * <p><b>A cached tier is served only while its {@link Stamp} still holds</b>, which is what makes
   * a bot dropped into {@code projects/&lt;id&gt;/bots/} resolvable on the next lookup with no
   * restart. The stamp is taken before the cache is consulted, because a cached entry cannot be
   * trusted without it and taking it after would mean serving one stale answer per change.
   *
   * <p><b>The tier is built outside the map and installed afterwards</b>, rather than inside a
   * {@code computeIfAbsent} mapping function. Two reasons, and the second is the load-bearing one:
   * reading a project tier does filesystem work and — for a caller with a session — blocking
   * channel round trips to somebody's laptop, none of which may happen while a {@link
   * ConcurrentHashMap} bin lock is held; and an install that is a plain write is one this method
   * can <em>withdraw</em>, which a mapping function's atomic install cannot be. The cost is that
   * two callers racing on one cold key may both read the tier and the later write wins. They read
   * the same directories at the same moment and differ only in what a client's own socket answered,
   * which is per session and so cannot collide on one key at all.
   *
   * <p><b>A session-keyed entry is withdrawn unless that session is still attached to something
   * when the write has landed.</b> The question is asked here and after the write on purpose — see
   * this class's own javadoc for why that ordering is what makes the answer a proof, and for why a
   * caller whose session is gone is served rather than refused. The registry it is handed is the
   * one just built for it either way: declining to <em>cache</em> is not declining to answer.
   */
  public AgentRegistry forCaller(Caller caller) {
    Objects.requireNonNull(caller, "caller");
    if (caller.projectId() == null && personalIds.apply(caller) != null) {
      caller = new Caller(personalIds.apply(caller), caller.sessionId(), caller.handle());
    }
    if (caller.projectId() == null || !projectExists.test(caller.projectId())) {
      return bootSet;
    }
    if (!data.keepsAnything() && applicationResources.root(caller.projectId()).isEmpty()) {
      // No data directory means no tree to hold a project tier at all --
      // every caller for every project resolves to the boot set, exactly
      // as AgentsConfig.agentRegistry's own global-tier guard means no
      // global/ is layered on such a deployment. data.agentsFor/botsFor
      // throw on a null root, so this has to be decided before the stamp
      // below calls either of them rather than caught after. Nothing is
      // cached: there is no directory whose change could ever make this
      // answer different.
      return bootSet;
    }
    CacheKey key = keyFor(caller);
    Stamp ownStamp = stamp(caller.projectId());
    Stamp inheritedStamp = key.personalId() == null ? new Stamp("", "") : stamp(key.personalId());
    Stamp stamp =
        new Stamp(
            ownStamp.agents() + inheritedStamp.agents(), ownStamp.bots() + inheritedStamp.bots());
    Cached cached = byProject.get(key);
    if (cached != null && cached.stamp().equals(stamp)) {
      return cached.registry();
    }
    AgentRegistry built = readProject(key);
    byProject.put(key, new Cached(stamp, built));
    if (key.sessionId() != null && !sessionLive.test(key.sessionId())) {
      // The session this entry is keyed by has stopped being attached to
      // anything since keyFor asked -- it closed while the tier above was
      // being read. Nothing will ever ask for this key again under an id
      // its client minted, and sessionClosed's sweep may already have run
      // past the absent key before the write above landed, so this is the
      // removal that has to happen: see this class's javadoc for why
      // asking here rather than before the write leaves no third case.
      //
      // The refusals go with it, and only ever for this key's own dead
      // session. That is the whole difference from the close counter this
      // replaced, which fired on ANY session's close and so could drop a
      // live caller's refusals after forCaller had already answered it.
      byProject.remove(key);
      refusals.remove(key);
    }
    return built;
  }

  /** The file a tier's {@code bots/} holds to say who answers there by default. */
  public static final String DEFAULT_FILE = "default";

  /** The classpath counterpart of every writable tier's {@code bots/default}. */
  private static final String SHIPPED_DEFAULT = "bots/" + DEFAULT_FILE;

  /**
   * Who a tier names in {@code bots/default}, and where it said so.
   *
   * @param name the first line of the file, trimmed; never blank
   * @param where the file, spelled for a person reading a refusal
   */
  public record DefaultBot(String name, String where) {}

  /**
   * The bot the caller's tier prefers, most specific tier first.
   *
   * <h2>A different kind of thing from a definition, resolved the same way</h2>
   *
   * <p>A bot is a character somebody returns to, and a character that exists globally may have no
   * counterpart in a project — so a tier may say which of its bots answers when nobody names one.
   * The file is one line, beside the definitions it points at, and it means the same thing at every
   * tier because the layout is the same at every tier: the project's own {@code bots/}, then the
   * connected client's {@code .plowshare/bots/}, then {@code global/bots/}.
   *
   * <p><b>This names; it does not validate.</b> Whether the name resolves, and whether it is a bot
   * at all, is {@link io.aeyer.plowshare.server.api.AgentRows}'s question, asked against the
   * registry this caller would actually get — a name checked here against a different registry
   * would be a second opinion.
   *
   * <p>The client tier is read only inside a project that exists, which is the rule {@link
   * #readProject} already keeps for definitions: the global tier has no client of its own to defer
   * to.
   */
  public Optional<DefaultBot> defaultBot(Caller caller) {
    Objects.requireNonNull(caller, "caller");
    if (!data.keepsAnything() && applicationResources.root(caller.projectId()).isEmpty()) {
      return shippedDefaultIfDefined();
    }
    Long projectId = caller.projectId();
    if (projectId != null && projectExists.test(projectId)) {
      var manifestDefault = projectConfiguration.apply(projectId).defaultBot();
      if (manifestDefault.isPresent()) return manifestDefault;
      Optional<DefaultBot> own = namedIn(botsDirectory(projectId).resolve(DEFAULT_FILE));
      if (own.isPresent()) {
        return own;
      }
      // keyFor's own question and not sessionLive alone: a session's
      // bots/default belongs to the project that session roots, and a live
      // session asking about some other project has no say in it there.
      String session = keyFor(caller).sessionId();
      if (session != null) {
        var manifestClient =
            ProjectConfiguration.local(new ChannelDefinitions(channel, session), null).defaultBot();
        if (manifestClient.isPresent()) return manifestClient;
        Optional<DefaultBot> client = new ChannelDefinitions(channel, session).defaultBot();
        if (client.isPresent()) {
          return client;
        }
      }
    }
    Long personal = personalIds.apply(caller);
    if (personal != null && !personal.equals(projectId) && data.keepsAnything()) {
      var manifestPersonal = projectConfiguration.apply(personal).defaultBot();
      if (manifestPersonal.isPresent()) return manifestPersonal;
      Optional<DefaultBot> inherited = namedIn(botsDirectory(personal).resolve(DEFAULT_FILE));
      if (inherited.isPresent()) return inherited;
    }
    return data.keepsAnything()
        ? namedIn(botsDirectory(null).resolve(DEFAULT_FILE)).or(this::shippedDefaultIfDefined)
        : shippedDefaultIfDefined();
  }

  /**
   * A custom boot set used by an embedding or a test need not contain the shipped definitions. In
   * that case the shipped preference is not a name that tier actually owns and must not manufacture
   * a disabled row.
   */
  private Optional<DefaultBot> shippedDefaultIfDefined() {
    return shippedDefault()
        .filter(
            named ->
                bootSet.find(named.name()).isPresent()
                    || bootSet.disabled().containsKey(named.name()));
  }

  /**
   * The default that ships inside the jar. Writable project, client and global tiers all remain
   * able to override it; this is only the floor.
   */
  private static Optional<DefaultBot> shippedDefault() {
    ClassLoader loader = DefinitionResolver.class.getClassLoader();
    try (InputStream in = loader.getResourceAsStream(SHIPPED_DEFAULT)) {
      if (in == null) {
        return Optional.empty();
      }
      try (BufferedReader lines =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        return firstLine(lines.lines().toList())
            .map(name -> new DefaultBot(name, "the shipped " + SHIPPED_DEFAULT));
      }
    } catch (IOException unreadable) {
      log.warn(
          "The shipped {} could not be read, so no shipped default bot is named: {}",
          SHIPPED_DEFAULT,
          unreadable.toString());
      return Optional.empty();
    }
  }

  private static Optional<DefaultBot> namedIn(Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return firstLine(Files.readAllLines(file, StandardCharsets.UTF_8))
          .map(name -> new DefaultBot(name, file.toString()));
    } catch (IOException | UncheckedIOException unreadable) {
      log.warn(
          "{} could not be read, so that tier names no default bot: {}",
          file,
          unreadable.toString());
      return Optional.empty();
    }
  }

  /** The first line, trimmed, or nothing when it is blank or absent. */
  static Optional<String> firstLine(List<String> lines) {
    if (lines.isEmpty() || lines.get(0) == null) {
      return Optional.empty();
    }
    String name = lines.get(0).trim();
    return name.isEmpty() ? Optional.empty() : Optional.of(name);
  }

  /**
   * Which entry a caller resolves under: its own session's, or the project's sessionless one.
   *
   * <p><b>A session is a cache key only while something is attached to it.</b> {@link #sessionLive}
   * is asked here rather than trusted from the {@link Caller}, because a session id is a name a
   * client chose and not a claim anything checked — {@code Runs.start} accepts a body naming a
   * session nothing has ever attached to, deliberately, and an entry keyed by such an id is one
   * nothing will ever remove. See this class's own javadoc for what that leaves the caller with,
   * which is the sessionless answer and not an error: a session with no file channel attached has
   * nothing to have contributed a {@link ChannelDefinitions} layer through — every request on it
   * answers "gone" — so the two answers are the same answer.
   *
   * <p><b>And only for the project that session roots.</b> {@link #sessionRoots} is asked second,
   * with the same outcome and for the same kind of reason: a client that roots A and moved its tier
   * to B — {@code /project}, which lends nothing and so leaves A's channel open — is a live session
   * asking about a project whose directory it does not hold. Its {@code .plowshare/} is A's (spec
   * §5), so for B it has nothing to contribute and is resolved as the sessionless caller it is
   * there. Answering yes would layer A's agents over B's and let A's {@code bots/default} pick who
   * answers in B.
   */
  private CacheKey keyFor(Caller caller) {
    String sessionId = caller.sessionId();
    if (applicationResources.root(caller.projectId()).isPresent()
        || sessionId == null
        || !sessionLive.test(sessionId)
        || !sessionRoots.test(caller.projectId(), sessionId)) {
      return new CacheKey(caller.projectId(), null, personalIds.apply(caller));
    }
    return new CacheKey(caller.projectId(), sessionId, personalIds.apply(caller));
  }

  /**
   * What the project's two server-side directories look like right now.
   *
   * <p>Never throws: an unreadable directory answers with a constant, so the cache neither rebuilds
   * on every call while it stays unreadable nor keeps serving a reading from before it became so.
   * The tier read that follows is where an unreadable directory is actually reported — through
   * {@link #refusalsFor} and the log — and this method must not pre-empt it with an exception out
   * of {@link #forCaller}.
   */
  private Stamp stamp(Long projectId) {
    return new Stamp(
        fingerprint(agentsDirectory(projectId)) + scopedTools.revision(projectId),
        fingerprint(botsDirectory(projectId)));
  }

  /** Package-private so OrchestrationResolver stamps its directory the same way. */
  static String fingerprint(Path directory) {
    if (!Files.isDirectory(directory)) {
      return "(absent)";
    }
    // Sorted, because a directory listing has no defined order and two
    // readings of an unchanged directory must produce equal stamps. Name,
    // size and modification time: see this class's javadoc for what that
    // set catches and the one edit it cannot.
    Map<String, String> entries = new TreeMap<>();
    try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
      for (Path entry : listing) {
        BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class);
        entries.put(
            entry.getFileName().toString(),
            attributes.size() + "@" + attributes.lastModifiedTime().toMillis());
      }
    } catch (IOException | UncheckedIOException unreadable) {
      // Includes the ordinary race of a file being deleted between the
      // listing and its stat: the next call stamps it cleanly, and the
      // worst this costs is one extra read of a tier that was changing
      // underneath the reader anyway.
      return "(unreadable)";
    }
    // A release switch invalidates cached definitions even if size and modification times match.
    return directory.toString() + "\0" + entries;
  }

  /**
   * One project's tier, read fresh and merged over {@link #bootSet}.
   *
   * <p>Most specific first, matching how {@link LayeredDefinitions} awards a clash to the first
   * layer named: {@code agentsFor(projectId)}, then {@code botsFor(projectId)}, then — only for a
   * key {@link #keyFor} kept a session in — a {@link ChannelDefinitions} reading that session's own
   * {@code .plowshare/}, and the boot set behind all three.
   *
   * <p><b>The channel is last and therefore loses any clash with the server's own project tree.</b>
   * This is the one place in the whole chain where the more specific source loses, and it is
   * deliberate: spec §2 says the tree is the authority, and a connected client must not be able to
   * silently change what an operator configured in {@code projects/&lt;id&gt;/}. A name only the
   * channel defines still makes it through — the layering only ever narrows a clash, never an
   * addition.
   *
   * <p><b>Whether this deployment has a tree at all is decided by {@link #forCaller} and not
   * here</b>, because {@link #stamp} calls {@code data.agentsFor}/{@code botsFor} before this
   * method is ever reached and both throw on a {@code null} root.
   *
   * <p>Never throws. Every per-file fault the tier's own read can absorb is folded into {@link
   * #refusalsFor} by {@link #build}; a fault that only exists once this tier is merged with the
   * boot set is caught here, and costs the whole project a fallback to the unmodified boot set
   * rather than escaping as an exception mid-run. {@link ChannelDefinitions} itself never throws
   * either way — every channel failure is already an empty listing by the time this method's caller
   * sees it.
   */
  private AgentRegistry readProject(CacheKey key) {
    Long projectId = key.projectId();
    List<DefinitionSource> layers =
        new ArrayList<>(
            List.of(
                new FilesystemDefinitions(agentsDirectory(projectId)),
                new FilesystemDefinitions(botsDirectory(projectId))));
    if (key.sessionId() != null) {
      layers.add(new ChannelDefinitions(channel, key.sessionId()));
    }
    if (key.personalId() != null && !key.personalId().equals(projectId)) {
      layers.add(new FilesystemDefinitions(agentsDirectory(key.personalId())));
      layers.add(new FilesystemDefinitions(botsDirectory(key.personalId())));
    }
    DefinitionSource tier = new LayeredDefinitions(List.copyOf(layers));

    try {
      return build(key, tier);
    } catch (RuntimeException brokenTier) {
      // The tier's own per-file disable rule could not absorb this: a
      // graph-level fault visible only once the tier is combined with
      // the boot set -- a caller of a name that failed to parse and so
      // never made it into the merged map (AgentRegistry.read's own
      // disable rule keeps such an edge alive at the tier level, on the
      // promise that the callee still exists somewhere; the promise is
      // broken here, because it does not survive into `merged`), a
      // shadowed non-required agent an inherited caller calls, a grant
      // that escalates once two directories are combined, or the
      // directory itself being unreadable. No single project may take
      // its own resolution down further than "you get the boot set",
      // so this project falls back to it entirely and the one thing an
      // operator is told is that it happened and why -- named under a
      // key no real agent can collide with, since `readProject` never
      // recorded a per-name refusal for a merge that did not complete.
      String reason =
          "this project's own definitions could not be resolved, so none of them"
              + " are being served; the boot set is running unmodified for this project"
              + " instead: "
              + brokenTier.getMessage();
      refusals.put(key, Map.of(TIER, reason));
      // AND SAID OUT LOUD, not merely recorded. refusalsFor is the
      // structured form and it is answered only when something asks;
      // dropping every override an operator wrote for a project is not a
      // fact they should have to make an API call to discover. This is
      // the one refusal in this class with no per-agent row on the agents
      // surface to carry it either -- TIER is not a name anything lists --
      // so the log is the only place it can be met. AgentRegistry.read
      // logs its own disablements at WARN in these same words, which is
      // where the voice comes from.
      log.warn("The definitions of project {} are ALL WITHHELD, because: {}", projectId, reason);
      return bootSet;
    }
  }

  /**
   * The merge itself, split out so {@link #readProject} can wrap exactly this in one {@code catch}
   * and nothing else — {@code data.keepsAnything()} and the layer construction above it must never
   * be caught, since a fault there is a wiring bug and not a project's own.
   */
  private AgentRegistry build(CacheKey key, DefinitionSource tier) {
    Map<String, String> refused = new LinkedHashMap<>();
    Map<String, AgentDefinition> merged = new LinkedHashMap<>(bootSet.byName());

    // No `required` predicate is passed to the tier's own read: nothing a
    // project directory holds may abort this resolution, so a bad
    // definition there is disabled and never a throw. `bootSet.names()` is
    // passed as `alsoDefined` so a `calls:` naming an inherited agent is
    // not read as a typo and withheld before the merge below ever sees it
    // -- see AgentRegistry's four-Set `read` overload and this class's own
    // javadoc.
    AgentRegistry.Loaded read =
        AgentRegistry.read(tier, knownTools(key.projectId()), Set.of(), bootSet.names());
    // The questions the registry cannot answer for itself -- is this model
    // served, does it see, and what sampling will it actually send -- asked
    // of a project's own definitions with the same lambda AgentsConfig asks
    // them of the boot set. Without this the same file means two different
    // things depending on which directory it sits in: profile-resolved
    // sampling under global/agents/ and raw declared sampling under
    // projects/7/agents/, with an unserved model fatal in one and silent in
    // the other. DefinitionChecks' own javadoc carries the argument; what
    // matters here is that its only move is to disable, so nothing it finds
    // can take this resolution down.
    AgentRegistry.Loaded loaded = checks.applyTo(read, tier.describe());
    for (Map.Entry<String, String> late : loaded.disabled().entrySet()) {
      if (!read.disabled().containsKey(late.getKey())) {
        // Logged here because AgentRegistry.read's own WARN loop has
        // already run by the time the checks disable anything, so these
        // are the disablements nothing else would ever say out loud.
        log.warn(
            "The agent '{}' is DISABLED and will not be served, because: {}",
            late.getKey(),
            late.getValue());
      }
    }
    // Every way the tier's own read could have withheld something, folded
    // in: nothing this class withholds may be withheld without being
    // named in refusalsFor.
    loaded.disabled().forEach(refused::put);
    loaded.withheldEdges().forEach(refused::put);
    loaded.withheldTools().forEach(refused::put);
    for (Map.Entry<String, AgentDefinition> entry : loaded.enabled().entrySet()) {
      if (required.contains(entry.getKey())) {
        // Spec §4: a project may not override one of these. The
        // inherited definition stays and the refusal is named, because
        // an agent silently not being the one an operator wrote is the
        // invisible-fact failure this repository designs against.
        String reason =
            "'"
                + entry.getKey()
                + "' is required by this server and cannot be"
                + " overridden by a project. The inherited definition is running.";
        refused.put(entry.getKey(), reason);
        // AND SAID OUT LOUD. The refusal is the whole of what stops
        // this being the invisible-fact failure -- an operator wrote a
        // file, the server is not running it, and the agent that
        // answers to that name is somebody else's. refusalsFor is the
        // structured form of the same sentence; an operator must not
        // have to make an API call to learn that the file they just
        // wrote is inert.
        log.warn(
            "The project definition of '{}' in {} is REFUSED, because: {}",
            entry.getKey(),
            tier.describe(),
            reason);
        continue;
      }
      merged.put(entry.getKey(), entry.getValue());
    }
    // Built before the map write below: if this throws (a set-level fault
    // only visible once merged with the boot set), readProject's catch
    // must find no refusalsFor entry from this attempt -- the two maps
    // must never disagree, and the whole-project fallback it records is
    // the only account of what happened.
    AgentRegistry registry = bootSet.replacing(merged);
    refusals.put(key, Map.copyOf(refused));
    return registry;
  }

  private java.nio.file.Path agentsDirectory(Long id) {
    return applicationResources.directory(id, "agents").orElseGet(() -> data.agentsFor(id));
  }

  private java.nio.file.Path botsDirectory(Long id) {
    return applicationResources.directory(id, "bots").orElseGet(() -> data.botsFor(id));
  }
}
