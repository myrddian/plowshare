package io.aeyer.plowshare.server.data;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The tree under {@code plowshare.data.dir}: what is in it, what is reserved in it, and what this
 * server refuses to start on top of.
 *
 * <h2>The tree</h2>
 *
 * <pre>
 *   &lt;data-dir&gt;/
 *       layout.properties          what version this tree is, and what wrote it
 *       projects/
 *           &lt;project-id&gt;/
 *               exports/           ejected conversation payloads
 *               images/            images a model may be shown, by UID
 *               agents/            agent definitions for this project's tier
 *               bots/              bot definitions for this project's tier
 *               orchestrations/    orchestration definitions for this project's tier
 *               hooks/             this project's own hooks (no global form)
 *               environment.yml    whether run may start commands here (no global form)
 *       global/
 *           exports/               the same, for the tier that is not a project
 *           images/                the same
 *           agents/                the same
 *           bots/                  the same
 *           orchestrations/        the same
 * </pre>
 *
 * <p><b>Keyed by the project's surrogate {@code BIGINT} id and never by its name.</b> {@code
 * ProjectStore.rename} exists, {@code V14} exists so that a rename is survivable rather than
 * routine, and a directory named for a name is a directory a rename orphans — silently, since
 * nothing would fail. {@code ProjectIds} is where a name becomes an id, and this is the first thing
 * in this server for which an id leaves the archive and becomes something a person can see.
 *
 * <p><b>{@code global/} is a sibling of {@code projects/} and not a project inside it</b>, because
 * {@code V1} says what the global tier is: "the absence of a project rather than a project named
 * 'global'". A directory {@code projects/global/} would be the other thing, and would sit in the
 * namespace of ids where an operator reading the tree would take it for one. <b>The design this was
 * built from has no place for the global tier at all</b>; its tree is projects and nothing else,
 * and a conversation whose home is global has no id to name a directory with. That is a gap in the
 * spec rather than a decision it took.
 *
 * <h2>What is reserved and deliberately not created</h2>
 *
 * <p>Three things the tree left room for when this section was written, each named here so the room
 * was a decision rather than an accident. Two are built now, and are explained below rather than
 * removed from this history — one is still reserved and absent:
 *
 * <p><b>{@code images/} was one of these and is no longer.</b> The paragraph that stood here said
 * the image store had no producer, so creating the directory would be "a store nothing can write
 * to". {@code POST /v1/images} is that producer, and {@link #imagesFor} is where the tree answers
 * for it. It is still not created at boot, for {@link #exportsFor}'s reason rather than for the old
 * one: a server nobody has uploaded an image to should not leave an empty {@code images/} behind to
 * explain, so {@code ImageStore} makes it on the first write. <b>{@link #VERSION} did not move</b>,
 * and that is the rule that field states — nothing moved, a directory the layout already named was
 * filled.
 *
 * <p><b>{@code projects/&lt;id&gt;/agents/} was one of these too, and {@code bots/} at both tiers
 * and {@code global/agents/} join it now</b> — none of the three are reserved-but-absent any more.
 * The paragraph that used to stand here argued a different question: whether the single boot-time
 * {@code AgentRegistry} bean, read once from one directory with three {@code REQUIRED} agents whose
 * failure aborts startup, should become per-project. That question is still open and these
 * directories do not answer it. {@link #agentsFor} and {@link #botsFor} answer a narrower one —
 * where an agent or bot <em>definition</em> file lives, for a loader that scans both directories
 * into one set and reads {@code exported}/{@code delegable} out of each file's frontmatter to
 * decide what the file is; the split between them carries no meaning of its own, on {@link
 * #imagesFor}'s reasoning against nesting {@code images/} under {@link #exportsFor}. Nothing reads
 * either directory yet, on {@code images/}'s reasoning before {@code POST /v1/images} existed; a
 * later task is that reader. <b>{@link #VERSION} did move this time</b> — see that field's own
 * javadoc for why.
 *
 * <ul>
 *   <li><b>{@code profiles/} at the root.</b> The design puts the operator-input sampling-profile
 *       directory here, and it is <b>not created and not accessible from this class</b>. The reason
 *       is {@code PayloadExport}'s own — "a server that never ejects anything should not leave an
 *       empty directory behind to explain" — applied honestly: {@code application.yml} sets {@code
 *       plowshare.llm.sampling-directory} outright, so on every shipped deployment that directory
 *       is {@code ./sampling} and a {@code profiles/} created in here would be a directory that
 *       says "put profiles here" and is never read. Moving that default belongs in its own commit;
 *       {@link #VERSION} is what lets that commit tell the tree it has to move from the tree it
 *       does not.
 *       <p>{@code agents/} at the root was the other half of this bullet, for the same reason,
 *       until {@code AgentsProperties}' own directory key was retired: there is no longer a default
 *       for that migration to move, and an operator's own definitions live in {@code
 *       global/agents/} and {@code global/bots/} — already built, and nowhere else.
 * </ul>
 *
 * <h2>What is NOT in the tree, and why not</h2>
 *
 * <p><b>The operator token.</b> It stays at {@code ~/.config/plowshare/console-token} because
 * {@code PlowshareClient.consoleTokenFile()} <em>hardcodes that path</em>. The location is a
 * contract between two binaries — the server's handoff to the local operator — rather than server
 * state, and moving it breaks the CLI with no error either binary could raise.
 *
 * <p><b>The configuration file and the working directory.</b> They are fence parameters: paths this
 * server never uses and only protects. {@code WorkspaceProperties}' one key exists so that {@code
 * ProjectStore.mandatoryExclusions} has a path to name.
 *
 * <p><b>The word "workspace" appears nowhere in this tree</b>, and that is load-bearing rather than
 * incidental. A workspace is a place a project may have, on this server's disk or on somebody
 * else's; this is what the <em>server</em> holds about a project, and a project rooted on a laptop
 * or with no place at all has it just the same. A draft of the design put a {@code workspace/}
 * directory in here, which would have meant something neither this system nor its ancestor means.
 *
 * <h2>The layout marker, and the three states it exists to tell apart</h2>
 *
 * <p>{@link #initialise} creates the tree on first start and writes {@code layout.properties} into
 * it. Without that file a later version that moves a subdirectory cannot tell <b>a fresh
 * install</b> from <b>an older layout it should migrate</b> from <b>an operator's deliberate
 * arrangement it must not touch</b> — the three look identical from a directory listing, and a
 * version that guesses between them is a version that eventually moves somebody's files for them.
 *
 * <p>So an unmarked directory that already holds something is a <b>refusal to start</b>, not a
 * silent adoption. That is the only answer that keeps the distinction real: adopting would mark an
 * arrangement this server did not make as one it did, and every later migration would then act on
 * it.
 *
 * <p><b>{@code java.util.Properties} text, and the format is chosen for the hand that will edit
 * it.</b> The refusal above tells an operator to write the file themselves, so the format has to be
 * one a person can produce with {@code echo} and cannot get subtly wrong: {@code Properties.load}
 * tolerates comments, blank lines, any order, and leading whitespace, and it ignores keys it does
 * not know — so an operator's own note beside ours survives being read back. <b>Rejected: JSON
 * Lines</b>, which is {@code PayloadExport}'s manifest format and is right there. A manifest is a
 * growing list of records and streams; this is one fact, so JSON Lines' one property — valid up to
 * its last newline — buys nothing, and it has no comment syntax at all, which means the sentence
 * telling an operator what the file is for cannot live in it. <b>Rejected: a bare integer in a file
 * named for the version</b>, which is smaller and says nothing about what wrote it, which is half
 * of what was asked for.
 *
 * <p>Only {@link #LAYOUT_VERSION} is ever read. {@code written-by} and {@code written-at} are for
 * the person holding a directory they do not recognise, and nothing branches on them — which is why
 * they can be edited or deleted without consequence, and why the file says so in its own text.
 */
public final class DataLayout {

  /** What the marker is called, at the top of the tree. */
  static final String MARKER = "layout.properties";

  /** The one key anything reads. */
  static final String LAYOUT_VERSION = "layout-version";

  /**
   * The shape of the tree this server writes and expects.
   *
   * <p><b>6 reserves {@code accounting/} for the durable inference journal.</b> Layout 5 never
   * owned that name, so migration refuses an existing path there. Project or transcript retention
   * cannot remove this server-level history.
   *
   * <p><b>5, and the fifth move is {@code projects/&lt;id&gt;/environment.yml}</b> — whether {@code
   * run} may start a command for that project, and how ({@link #environmentFor}). It is {@code
   * hooks/}' case and not {@code bots/}': the file decides what runs, so one already there was put
   * there by somebody else and would start deciding on this boot, and the step checks no project
   * has one.
   *
   * <p><b>4, and the fourth move is {@code orchestrations/} at both tiers</b> — a name no earlier
   * layout reserved, where an orchestration definition file lives ({@link #orchestrationsFor}).
   * Like {@code bots/} and {@code global/agents/} before it, it is read as a definition and not as
   * code, so the step this move adds to {@code STEPS} checks nothing — the same reasoning as the 1
   * -&gt; 2 move below, applied to the same kind of name.
   *
   * <p><b>3, and the third move is {@code projects/&lt;id&gt;/hooks/}</b> — a name no earlier
   * layout reserved, where a project's own hook files live ({@link #hooksFor}). Nothing moved and
   * nothing was renamed; a tree marked 2 simply cannot be assumed to agree with this class that
   * {@code hooks/} under a project is read as code, so {@link #initialise} moves it forward only
   * after checking no project already has one. <b>Moving this field now means adding a {@code
   * Step}</b> from the version it leaves behind — see {@code STEPS}. The paragraph below is the 1
   * -> 2 move's own account, and the doctrine it states is the one this move followed.
   *
   * <p><b>2 was only the second time it had a reason to move.</b> It moves when a directory moves,
   * not when a reserved one is filled -- {@code images/} being filled did not move it, because
   * {@code images/} was a name layout 1 already reserved. {@code projects/&lt;id&gt;/agents/} is
   * the same story and, filled on its own, would not have moved this field either. {@code bots/} at
   * both tiers and {@code global/agents/} are different: layout 1 never reserved them, so a tree
   * marked 1 cannot be assumed to agree with this class about what belongs where those names now
   * point -- which is exactly the disagreement the marker exists to catch rather than silently
   * start on top of.
   *
   * <p>Not a build number and not tied to a release: two servers whose trees are identical must
   * agree here whatever else differs between them, or every upgrade becomes a migration of nothing.
   */
  static final int VERSION = 6;

  /**
   * A deployment that keeps no data directory.
   *
   * <p>Not a null {@link DataLayout} at the call sites, on {@code PayloadExport.NONE}'s reasoning:
   * a wiring that had to ask "is there a data directory?" before every derivation is a null check
   * somebody eventually forgets. This one is asked the same questions and answers "nowhere".
   *
   * <p>It is the state of every Spring context in this repository that did not come through {@code
   * PlowshareServerApplication.main} — see {@link DataProperties}, which argues why that is the
   * safe default rather than an omission.
   */
  public static final DataLayout NONE = new DataLayout(null);

  /**
   * The directory that is not a project: {@code V1}'s "absence of a project rather than a project
   * named 'global'", spelled somewhere it cannot be mistaken for an id.
   */
  private static final String GLOBAL = "global";

  private final Path root;

  /**
   * @param root where this server keeps what it owns, or {@code null} for a deployment that keeps
   *     nothing. Absolutised and normalised here and not at each use: the fence in {@code
   *     ProjectStore} and the directory an export is written into have to be the same path, and a
   *     process cannot change its working directory, so resolving once is resolving for the life of
   *     the server
   */
  public DataLayout(Path root) {
    this.root = root == null ? null : root.toAbsolutePath().normalize();
  }

  /**
   * Where the tree is, or {@code null} for {@link #NONE}.
   *
   * <p>Null rather than an {@link java.util.Optional}, because the one caller that is not a
   * derivation is {@code ProjectStore}'s fence, which already takes a nullable {@code Path} for
   * every other entry and means the same thing by it: there is no such directory on this
   * deployment, so there is nothing to fence.
   */
  public Path root() {
    return root;
  }

  /** Whether anything is kept at all. */
  public boolean keepsAnything() {
    return root != null;
  }

  /** Server-owned accounting journal, isolated from project and transcript retention. */
  public Path accounting() {
    if (root == null) {
      throw new IllegalStateException("durable inference accounting requires plowshare.data.dir");
    }
    return root.resolve("accounting");
  }

  /**
   * Where one project's exports go, by id.
   *
   * @param projectId the project's surrogate id, or {@code null} for the global tier
   * @throws IllegalStateException if this deployment keeps no data directory. A caller reaching
   *     here on {@link #NONE} has already decided to write, and answering with a path relative to
   *     nothing would put a person's ejected file body in whatever directory the server happened to
   *     start in
   */
  public Path exportsFor(Long projectId) {
    if (root == null) {
      throw new IllegalStateException(
          "this server keeps no data directory, so there is nowhere to write an export."
              + " Set PLOWSHARE_DATA_DIR, or name a directory in"
              + " plowshare.conversations.retention.export-directory");
    }
    return projectId == null
        ? root.resolve(GLOBAL).resolve("exports")
        : root.resolve("projects").resolve(Long.toString(projectId)).resolve("exports");
  }

  /**
   * Where one project's images go, by id.
   *
   * <p><b>A sibling of {@link #exportsFor} and not a subdirectory of it</b>, and the difference is
   * what each holds. An export is a conversation's ejected payload — bytes taken <em>out</em> of
   * the archive so the row can be emptied — and it is keyed by the tree it came from. An image is
   * bytes a person put <em>in</em>, keyed by nothing but its own id, and it is read back by
   * whatever names that id. Two lifecycles, two questions, two directories; nesting one under the
   * other would make "what is in here" answerable only by knowing which of the two you meant.
   *
   * <p><b>There is no {@code imagesUnder}</b>, and the asymmetry with exports is deliberate rather
   * than unfinished. {@code plowshare.conversations.retention.export-directory} exists because it
   * predates the data directory and demoting it to an override was cheaper than breaking every
   * deployment that set it. Images have no such key and are not given one: a second way to say
   * where they go would be a second place for a UID to resolve to nothing, on the one path where
   * "not found" and "somewhere else" look identical to a caller.
   *
   * @param projectId the project's surrogate id, or {@code null} for the global tier
   * @throws IllegalStateException if this deployment keeps no data directory, on {@link
   *     #exportsFor}'s reasoning: a caller reaching here has bytes in hand, and a path relative to
   *     nothing would put a person's image in whatever directory the server happened to start in
   */
  public Path imagesFor(Long projectId) {
    if (root == null) {
      throw new IllegalStateException(
          "this server keeps no data directory, so there is nowhere to put an image."
              + " Set PLOWSHARE_DATA_DIR. Unlike an export there is no per-feature"
              + " override to fall back on, deliberately -- see DataLayout.imagesFor");
    }
    return projectId == null
        ? root.resolve(GLOBAL).resolve("images")
        : root.resolve("projects").resolve(Long.toString(projectId)).resolve("images");
  }

  /**
   * Where one tier's bot definitions go, by project id.
   *
   * <p>A sibling of {@link #agentsFor} and not a subdirectory of it, because spec §1 makes the
   * split organisational: both are scanned into one set and {@code exported}/{@code delegable} in
   * each file's frontmatter keep deciding what a definition is. Two directories are a filing
   * convenience for an operator and carry no meaning the loader reads.
   *
   * <p><b>{@code bot: true} joined those two on 2026-09-12 and this paragraph is unchanged by
   * it.</b> The word now means something the loader reads, and what it reads is frontmatter: a bot
   * filed under {@code agents/} is a bot, an agent filed here is an agent, and the sentence above
   * stays exactly as true as it was. Spec §2.1 records the choice and §7 records rejecting the
   * other one — teaching the loader to read the path would contradict this javadoc to get a fact
   * the frontmatter already carries.
   *
   * <p><b>What did stop being true is that nothing read this directory.</b> A bot ships from {@code
   * resources/bots/} — {@code ClasspathDefinitions.SHIPPED_BOTS} — so the shipped seed scans a
   * {@code bots} location, and this tier's own is layered over it. Where the definitions come
   * <em>from</em> has changed; what makes one a bot has not.
   *
   * @param projectId the project's surrogate id, or {@code null} for the global tier
   * @throws IllegalStateException if this deployment keeps no data directory, on {@link
   *     #exportsFor}'s reasoning: a path relative to nothing is wrong regardless of who is asking
   *     for it
   */
  public Path botsFor(Long projectId) {
    if (root == null) {
      throw new IllegalStateException(
          "this server keeps no data directory, so there is nowhere to look for a bot"
              + " definition. Set PLOWSHARE_DATA_DIR. Like images, there is no"
              + " per-feature override to fall back on -- see DataLayout.botsFor");
    }
    return tierDirectory(projectId, "bots");
  }

  /**
   * Where one tier's agent definitions go. See {@link #botsFor} for the reasoning shared by both.
   *
   * @param projectId the project's surrogate id, or {@code null} for the global tier
   * @throws IllegalStateException if this deployment keeps no data directory, on {@link #botsFor}'s
   *     reasoning
   */
  public Path agentsFor(Long projectId) {
    if (root == null) {
      throw new IllegalStateException(
          "this server keeps no data directory, so there is nowhere to look for an"
              + " agent definition. Set PLOWSHARE_DATA_DIR. Like images, there is"
              + " no per-feature override to fall back on -- see DataLayout.botsFor");
    }
    return tierDirectory(projectId, "agents");
  }

  /**
   * Where one tier's orchestration definitions go: a sibling of {@link #agentsFor} and {@link
   * #botsFor}, read by {@code OrchestrationResolver}. An absent directory is an empty tier, as
   * theirs is; layout 4 reserved the name.
   *
   * @param projectId the project's surrogate id, or {@code null} for the global tier
   * @throws IllegalStateException if this deployment keeps no data directory, on {@link #botsFor}'s
   *     reasoning
   */
  public Path orchestrationsFor(Long projectId) {
    if (root == null) {
      throw new IllegalStateException(
          "this server keeps no data directory, so there is nowhere to look for an"
              + " orchestration definition. Set PLOWSHARE_DATA_DIR. Like images, there is"
              + " no per-feature override to fall back on -- see DataLayout.botsFor");
    }
    return tierDirectory(projectId, "orchestrations");
  }

  /** Standard skill packages at the same project/global authority tiers as agents. */
  public Path skillsFor(Long projectId) {
    if (root == null) throw new IllegalStateException("this server keeps no data directory");
    return tierDirectory(projectId, "skills");
  }

  /**
   * Where a project's hooks live: {@code projects/<id>/hooks}. There is no global form — the global
   * plugin tier is not loaded (hooks spec §2) — so a project id is required. Not created at boot;
   * nothing is written there by this server.
   */
  public Path hooksFor(long projectId) {
    if (root == null) {
      throw new IllegalStateException(
          "this server keeps no data directory, so there is nowhere to look for a project's"
              + " hooks. Set PLOWSHARE_DATA_DIR.");
    }
    return tierDirectory(projectId, "hooks");
  }

  /**
   * Where a project's environment file lives: {@code projects/<id>/environment.yml}. No global form
   * — the global tier names no filesystem, so nothing runs there. An absent file is the defaults,
   * which run nothing.
   */
  public Path environmentFor(long projectId) {
    if (root == null) {
      throw new IllegalStateException(
          "this server keeps no data directory, so there is nowhere to look for a project's"
              + " environment. Set PLOWSHARE_DATA_DIR.");
    }
    return tierDirectory(projectId, "environment.yml");
  }

  /**
   * Where one tier's swarm lives: {@code projects/<id>/swarm.md}, or {@code global/swarm.md} for
   * {@code null} — spec 2026-09-29 §3. One file, not a directory: the member list of a project's
   * board. An absent file is no swarm at that tier.
   */
  public Path swarmFor(Long projectId) {
    if (root == null) {
      throw new IllegalStateException(
          "this server keeps no data directory, so there is nowhere to look for a swarm"
              + " definition. Set PLOWSHARE_DATA_DIR.");
    }
    return tierDirectory(projectId, "swarm.md");
  }

  private java.util.function.LongPredicate personalProjects = id -> false;

  public void usePersonalProjects(java.util.function.LongPredicate personalProjects) {
    this.personalProjects = java.util.Objects.requireNonNull(personalProjects);
  }

  private Path tierDirectory(Long projectId, String leaf) {
    if (projectId != null
        && personalProjects.test(projectId)
        && java.util.Set.of(
                "agents",
                "bots",
                "skills",
                "orchestrations",
                "hooks",
                "environment.yml",
                "swarm.md")
            .contains(leaf)) {
      return unionFor(projectId)
          .resolve("tree")
          .resolve(leaf.equals("bots") ? "Bots" : "Resources/" + leaf);
    }
    return projectId == null
        ? root.resolve(GLOBAL).resolve(leaf)
        : root.resolve("projects").resolve(Long.toString(projectId)).resolve(leaf);
  }

  /**
   * Where a union project's hub ({@code sync.git}) and working copy ({@code tree}) live: {@code
   * <root>/projects/<id>}. Keyed by id, never by name, like every other per-project directory here.
   */
  public Path unionFor(long projectId) {
    if (root == null) {
      throw new IllegalStateException("this server keeps no data directory");
    }
    return root.resolve("projects").resolve(Long.toString(projectId));
  }

  /**
   * The same question asked of a root an operator named themselves, so that a deployment which
   * overrides {@code plowshare.conversations.retention.export-directory} gets the same shape of
   * answer as one that does not.
   *
   * <p><b>Static, and the argument for its existing is that the alternative was two shapes.</b> The
   * override used to mean "write the trees flat in here", which was the only shape available while
   * nothing knew a conversation's project. Keeping that would mean "which project is this export
   * of" is answerable on one deployment and not on another, and every later question — orphans,
   * retention, what is in here — would have to be asked twice. The cost is stated rather than
   * discovered: <b>on a deployment that already set the key, trees written from now on land one
   * level deeper than the ones already there.</b> The old ones are untouched and still open — the
   * manifest's paths are relative to their own tree, which is what that property was for.
   */
  public static Path exportsUnder(Path named, Long projectId) {
    return named
        .toAbsolutePath()
        .normalize()
        .resolve(projectId == null ? GLOBAL : Long.toString(projectId));
  }

  /**
   * Create the tree if it is not there, adopt it if this server wrote it, and refuse it otherwise.
   *
   * <p>Called from the bean factory rather than from an {@code ApplicationReadyEvent} listener, so
   * that a tree this server cannot account for stops the boot instead of being discovered by the
   * first ejection — {@code AgentsConfig}'s rule that "a directory that is there and wrong stops
   * the boot", applied to the directory the server writes rather than the one it reads.
   *
   * <p>Idempotent: a marked tree at this version has its {@code projects/} remade if somebody
   * deleted it and is otherwise left exactly as it is. A marked tree at an older layout is migrated
   * first, which rewrites only the marker's one key, so an operator's own comments and keys in it
   * survive every restart and every upgrade.
   *
   * @return this, so the bean method reads as one expression
   * @throws IllegalStateException if the path exists and is not a directory, if it holds something
   *     and carries no marker, if it carries a marker this server does not understand, or if a
   *     migration step finds something it will not move past. All are the operator's to resolve and
   *     each says how
   */
  public DataLayout initialise() {
    if (root == null) {
      return this;
    }
    if (Files.exists(root) && !Files.isDirectory(root)) {
      throw new IllegalStateException(
          "plowshare.data.dir is "
              + root
              + ", which exists and is not a directory."
              + " This server keeps a tree of files there, so a path naming one"
              + " file cannot be read as it");
    }
    Path marker = root.resolve(MARKER);
    if (Files.isRegularFile(marker)) {
      adopt(marker);
    } else {
      if (Files.isDirectory(root) && !isEmpty(root)) {
        throw new IllegalStateException(
            "plowshare.data.dir is "
                + root
                + ", which already holds files and has no "
                + MARKER
                + " saying what layout they are in. This server will not"
                + " guess: a directory it did not create may be an older layout to"
                + " migrate or an arrangement somebody made on purpose, and the"
                + " two want opposite treatment. Either point PLOWSHARE_DATA_DIR"
                + " somewhere else, or -- if this tree really is Plowshare's --"
                + " adopt it by writing `"
                + LAYOUT_VERSION
                + "="
                + VERSION
                + "` into "
                + marker);
      }
      write(marker);
    }
    try {
      Files.createDirectories(root.resolve("projects"));
    } catch (IOException unwritable) {
      throw new IllegalStateException(
          "plowshare.data.dir is "
              + root
              + " and its projects directory could not be"
              + " created, so nothing this server owns can be written",
          unwritable);
    }
    return this;
  }

  /**
   * One move from a layout to the next: what it checks before moving, and the sentence the marker
   * records once it has.
   *
   * <p><b>A step may refuse but may not guess.</b> {@link #check} throws when the tree holds
   * something the older layout never wrote and the newer one would read — a person made that, and
   * what happens to it is theirs to decide. A step that has to move a directory does it in {@code
   * check} too, before the marker is rewritten, so a crash between the two leaves a tree the step
   * can run on again.
   */
  private interface Step {
    void check(Path root);
  }

  /**
   * Every move this class has ever made, keyed by the layout it starts from. The next bump adds its
   * step here; {@link #migrate} walks them in order.
   */
  private static final SortedMap<Integer, Step> STEPS =
      new TreeMap<>(
          Map.of(
              // bots/ at both tiers and global/agents/: names layout 1 never had and
              // never wrote, and nothing reads a definition directory as code.
              1, root -> {},
              // hooks/ under a project is read as code, so one that is already there
              // was made by somebody else and would start running on this boot.
              2, DataLayout::noHooksYet,
              // orchestrations/ at both tiers: a name layout 3 never had and never
              // wrote, and nothing reads a definition directory as code -- bots/'s
              // reasoning, not hooks/'s.
              3, root -> {},
              // environment.yml under a project decides whether commands run, so one
              // already there was made by somebody else -- hooks/' reasoning.
              4, DataLayout::noEnvironmentYet,
              5, DataLayout::noAccountingYet));

  private static final Map<Integer, String> ADDED =
      Map.of(
          1, "added bots/ at both tiers and global/agents/",
          2, "added hooks/ under a project",
          3, "added orchestrations/ at both tiers",
          4, "added environment.yml under a project",
          5, "reserved accounting/ for the durable inference journal");

  private static void noAccountingYet(Path root) {
    if (Files.exists(root.resolve("accounting"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(
          "layout 5 did not own accounting/; move that existing path before upgrading the data layout");
    }
  }

  private static void noHooksYet(Path root) {
    Path projects = root.resolve("projects");
    if (!Files.isDirectory(projects)) {
      return;
    }
    try (Stream<Path> each = Files.list(projects)) {
      each.map(project -> project.resolve("hooks"))
          .filter(Files::exists)
          .findFirst()
          .ifPresent(
              hooks -> {
                throw new IllegalStateException(
                    root.resolve(MARKER)
                        + " says "
                        + LAYOUT_VERSION
                        + "=2, and layout 3"
                        + " runs every file under a project's hooks/ as that project's"
                        + " hooks -- but "
                        + hooks
                        + " is already there, and layout 2"
                        + " never wrote one. Move it aside and restart, or write `"
                        + LAYOUT_VERSION
                        + "=3` into the marker yourself if those"
                        + " files are meant to run");
              });
    } catch (IOException | UncheckedIOException unlistable) {
      throw new IllegalStateException(
          projects
              + " could not be listed, so this server cannot tell whether moving"
              + " it to layout 3 would start running files nobody meant as hooks",
          unlistable);
    }
  }

  private static void noEnvironmentYet(Path root) {
    Path projects = root.resolve("projects");
    if (!Files.isDirectory(projects)) {
      return;
    }
    try (Stream<Path> each = Files.list(projects)) {
      each.map(project -> project.resolve("environment.yml"))
          .filter(Files::exists)
          .findFirst()
          .ifPresent(
              file -> {
                throw new IllegalStateException(
                    root.resolve(MARKER)
                        + " says "
                        + LAYOUT_VERSION
                        + "=4, and layout 5"
                        + " reads a project's environment.yml to decide whether"
                        + " commands may run -- but "
                        + file
                        + " is already there,"
                        + " and layout 4 never wrote one. Move it aside and restart,"
                        + " or write `"
                        + LAYOUT_VERSION
                        + "=5` into the marker"
                        + " yourself if it is meant to apply");
              });
    } catch (IOException | UncheckedIOException unlistable) {
      throw new IllegalStateException(
          projects
              + " could not be listed, so this server cannot tell whether moving"
              + " it to layout 5 would let a file nobody meant decide what runs",
          unlistable);
    }
  }

  /**
   * A marker this server already wrote, a tree moved forward to it, or a refusal naming both
   * versions.
   *
   * <p><b>Higher is refused</b>: a later Plowshare wrote this tree and this one does not know where
   * things are in it. <b>Lower is migrated</b>, one {@link Step} at a time, each rewriting the
   * marker as it lands so a boot that stops halfway resumes from where it stopped. Below the first
   * step there is no layout this class ever wrote, so that is refused as a guess.
   */
  private void adopt(Path marker) {
    Properties found = new Properties();
    try (Reader text = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
      found.load(text);
    } catch (IOException unreadable) {
      throw new IllegalStateException(
          marker + " could not be read, so this server cannot tell what layout " + root + " is in",
          unreadable);
    }
    String stated = found.getProperty(LAYOUT_VERSION);
    int version;
    try {
      version = Integer.parseInt(stated == null ? "" : stated.trim());
    } catch (NumberFormatException notANumber) {
      throw new IllegalStateException(
          marker
              + " has no readable "
              + LAYOUT_VERSION
              + " (found "
              + (stated == null ? "no such key" : "'" + stated + "'")
              + "). That key is the only thing in the file this server reads, and"
              + " without it the layout of "
              + root
              + " is unknown. Write `"
              + LAYOUT_VERSION
              + "="
              + VERSION
              + "` if this tree was written by"
              + " this version");
    }
    if (version > VERSION) {
      throw new IllegalStateException(
          marker
              + " says "
              + LAYOUT_VERSION
              + "="
              + version
              + " and this server writes"
              + " layout "
              + VERSION
              + ". A later Plowshare wrote this tree and this"
              + " one does not know where things are in it -- run the version that"
              + " made it, or point PLOWSHARE_DATA_DIR at a directory of your own.");
    }
    if (version < VERSION && !STEPS.containsKey(version)) {
      throw new IllegalStateException(
          marker
              + " says "
              + LAYOUT_VERSION
              + "="
              + version
              + " and this server writes"
              + " layout "
              + VERSION
              + ". No Plowshare wrote layout "
              + version
              + ", so there is no migration from it. If you have confirmed "
              + root
              + " already matches layout "
              + VERSION
              + ", write `"
              + LAYOUT_VERSION
              + "="
              + VERSION
              + "` into "
              + marker
              + "; if not, point"
              + " PLOWSHARE_DATA_DIR at a directory of your own.");
    }
    for (int from = version; from < VERSION; from++) {
      STEPS.get(from).check(root);
      advance(marker, from);
    }
  }

  /**
   * Rewrite the one key to the next layout, in place, and note the move beneath it. Everything else
   * in the file — an operator's comments and keys — is left as it was; the file is replaced whole
   * through a sibling so a crash cannot leave half of it.
   */
  private void advance(Path marker, int from) {
    int to = from + 1;
    Pattern key =
        Pattern.compile("^\\s*" + Pattern.quote(LAYOUT_VERSION) + "\\s*[=:\\s]\\s*\\d+\\s*$");
    try {
      List<String> lines = new ArrayList<>(Files.readAllLines(marker, StandardCharsets.UTF_8));
      int at = -1;
      for (int i = 0; i < lines.size(); i++) {
        if (key.matcher(lines.get(i)).matches()) {
          at = i;
        }
      }
      if (at < 0) {
        throw new IllegalStateException(
            marker
                + " could not be moved to layout "
                + to
                + ": its "
                + LAYOUT_VERSION
                + " is not on a line of its own, so this server cannot rewrite it"
                + " without rewriting what is around it. Write `"
                + LAYOUT_VERSION
                + "="
                + VERSION
                + "` on a line of its own");
      }
      lines.set(at, LAYOUT_VERSION + "=" + to);
      lines.add(
          at + 1,
          "# moved from layout "
              + from
              + " to "
              + to
              + " by plowshare-server at "
              + Instant.now()
              + ": "
              + ADDED.get(from));
      Path next = marker.resolveSibling(MARKER + ".next");
      Files.write(next, lines, StandardCharsets.UTF_8);
      Files.move(next, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException unwritable) {
      throw new IllegalStateException(
          marker
              + " could not be moved from layout "
              + from
              + " to "
              + to
              + ", so this"
              + " server will not start on a tree it has not finished migrating",
          unwritable);
    }
  }

  /**
   * The marker, written once, in the words of somebody who will find it without having gone looking
   * for it.
   *
   * <p>Written by hand rather than through {@link Properties#store}, which emits one comment of its
   * own devising and escapes the text it is given. The prose here is the point: an operator who
   * opens this file is holding a directory they may not recognise, and the file has to say what the
   * directory is, what reads it, and what is safe to change.
   */
  private void write(Path marker) {
    String text =
        """
                # Plowshare's data directory.
                #
                # This file was written when the directory was created. A later
                # Plowshare rewrites only the one key below, when it moves this tree
                # to a newer layout, and notes the move beside it; everything else
                # here is left as you leave it. It exists so that a later version can tell a
                # fresh install from a tree it should migrate from an arrangement you
                # made on purpose -- without it, every future change to this layout
                # would have to guess between the three.
                #
                # Only `%s` is read. The other keys, and anything you add
                # below them, are for whoever opens this file; change or delete them
                # freely. Deleting `%s` makes this server refuse to start
                # rather than guess.
                #
                # The tree:  projects/<project-id>/{exports,images,agents,bots,hooks,orchestrations}/
                # and environment.yml, and the same under global/, which has no hooks/ and no
                # environment.yml
                # A project is named here by its id and never by its name, because a
                # project can be renamed and a directory named for a name would be
                # orphaned by that with nothing failing.
                %s=%d
                written-by=plowshare-server
                written-at=%s
                """
            .formatted(LAYOUT_VERSION, LAYOUT_VERSION, LAYOUT_VERSION, VERSION, Instant.now());
    try {
      Files.createDirectories(root);
      Files.writeString(marker, text, StandardCharsets.UTF_8);
    } catch (IOException unwritable) {
      throw new IllegalStateException(
          "plowshare.data.dir is "
              + root
              + " and its "
              + MARKER
              + " could not be"
              + " written. A tree with no marker is one this server refuses on the"
              + " next boot, so this is refused now instead",
          unwritable);
    }
  }

  private static boolean isEmpty(Path directory) {
    try (Stream<Path> inside = Files.list(directory)) {
      return inside.findAny().isEmpty();
    } catch (IOException unlistable) {
      throw new UncheckedIOException(
          "plowshare.data.dir is "
              + directory
              + " and could not be listed, so this"
              + " server cannot tell whether it is a fresh one",
          unlistable);
    }
  }
}
