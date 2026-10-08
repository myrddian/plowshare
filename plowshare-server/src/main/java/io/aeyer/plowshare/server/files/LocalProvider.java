package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.CommandIsolation;
import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.FileSearch;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.GlobSpellings;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Replacement;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.images.StoredImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * A {@link FileProvider} over the server's own disk, bounded by the directories its project names.
 *
 * <p><b>Directories and not directory.</b> A project's leash is its {@code workspace} prepended to
 * whatever else is lent at that place — {@code ProjectRecord.roots()} — and this class asks for the
 * leash, never for the place. The distinction is the one V30 exists to hold: {@code workspace} is
 * still the project's single location and still the {@code <PATH>} of its canonical name, and
 * nothing here composes an identity, so nothing here reads it alone.
 *
 * <h2>The leash is read again on every call, and that is the design</h2>
 *
 * <p>{@link FileAccess} is a snapshot by construction — its own javadoc says so, and says that a
 * root which vanishes is a provider's problem to report. This class builds a new one per call
 * instead of holding one, for two reasons that are not the same reason:
 *
 * <ul>
 *   <li><b>a revoked workspace must actually be revoked.</b> Moving or forgetting a project's
 *       workspace is an ordinary write on a server whose project list is not configuration. A
 *       provider that snapshotted at construction would leave a long-running job holding a leash
 *       that had already been cut, and nothing would ever say so;
 *   <li><b>{@link FileProvider#roots()} promises it may change between calls.</b> That promise
 *       exists for the remote provider, whose human moves their workspace mid-run — but a promise
 *       only one implementation keeps is one callers learn to ignore.
 * </ul>
 *
 * <p>The cost is <b>one</b> primary-key lookup per file operation, and the count is load-bearing
 * rather than a saving. Reading the workspace and the exclusions as two calls leaves a window a
 * {@code forget} can land in, and {@code ProjectStore.effectiveExclusions(String)} raises {@code
 * ArchiveException} for a row that has gone — a third exception type out of a seam whose contract
 * is that there are two, on the one path this class's per-call re-read exists to serve. {@code
 * effectiveExclusions(ProjectRecord)} is the overload that closes it; that method says so too.
 *
 * <p>{@code ProjectStore} translates a dead database into {@code ArchiveUnavailableException},
 * which {@code JobRuntime.dependencyFailure} already lists — so an outage under this class ends the
 * run as an outage, with the sentence that names the archive rather than one that blames the disk.
 *
 * <h2>Where the containment actually happens</h2>
 *
 * <p>Nowhere in this class. Every question of <em>where</em> is {@code FileAccess.of(workspaces,
 * excluded)} and {@code permits}, called with every one of the project's roots passed as a
 * <b>workspace</b> — never through {@code withServerOwned}, which keeps a root an exclusion covers
 * and would turn a {@code projects} row pointing inside the definitions tree into a grant over
 * every agent's definition. {@code a_workspace_set_inside_the_agents_directory_grants_nothing} is
 * what proves this caller passed it in the right list; the two-call signature is what makes the
 * wrong one hard to write.
 *
 * <p>The exclusions come from {@code ProjectStore.effectiveExclusions} and never from {@code
 * ProjectRecord.exclusions()}. That store method owns the reason; what matters here is that the
 * record is the row, so a check built from it is a check a {@code projects} row can switch off, and
 * {@code the_config_is_unreachable_from_a_workspace_that_contains_it} is the test that fails when
 * this class reaches for the wrong one.
 *
 * <p><b>And the exclusions are not the whole of what {@code permits} refuses.</b> A path with a
 * dot-prefixed component below its root is refused whatever any list says — {@code
 * FileAccess.hiddenBelow} owns that rule — so a workspace over a home directory does not reach
 * {@code ~/.config/}, and {@code file_glob} and {@code file_grep} do not name what is inside it.
 * That is not this class's to enforce and it is this class's to know about, because it changes what
 * a refusal from {@link #permitted} can mean: {@code
 * the_console_token_is_unreachable_from_a_workspace_over_a_home_directory} and {@code
 * a_glob_does_not_list_what_is_hidden_and_a_grep_does_not_read_it} are the pair that say this class
 * goes through it.
 *
 * <h2>Five states in which there is no root, said five ways</h2>
 *
 * <p>No grant in the agent's definition; the global tier; a project with no row; a project every
 * one of whose roots an exclusion covers; and — the only one that ends the run — a tier whose name
 * can never have a workspace at all. Each is a different fix, made by a different person, and
 * Excalibur split its own {@code NO_ROOTS} and {@code NO_WORKSPACE} apart after collapsing two of
 * them sent readers to fix the wrong thing. {@link Leash#absence} is where the five sentences are.
 */
public final class LocalProvider implements FileProvider {

  /**
   * The most this provider will read into memory as one string.
   *
   * <p>Measured on JDK 21: {@code Files.readString} of an 8 MiB file returns all 8388608
   * characters, so nothing in the platform bounds this. A model asked to look at a log will read
   * the log, and a gigabyte-sized file is a gigabyte of heap held while every other job on the
   * server is also running.
   *
   * <p>The value has to be far above what a tool would ever display — the refusal should be about
   * files no reader wanted whole, not about ordinary source — and far below what a job can afford
   * to hold. It is not a display limit and must not become one: truncating to it would hand back a
   * prefix that reads as the file.
   *
   * <p><b>The accepted side is held absolutely and the refused side is not held at all.</b> {@code
   * a_file_of_four_megabytes_is_ordinary_and_is_read} names four megabytes as a literal, so
   * lowering this constant below that fails; the refusal test builds its file from this constant,
   * so it moves with it and holds nothing on its own. Nothing pins the ceiling — that is a
   * judgement about heap under concurrency, with no instrument — and saying so is better than a
   * test that appears to make it.
   */
  static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

  /**
   * The most paths one {@link #glob} will return.
   *
   * <p>Refused at, never truncated to. Excalibur's {@code file_glob} keeps its first 200 hits and
   * says nothing about the rest, which is the confident empty answer wearing a different hat: a
   * list that stops at a limit reads as a complete one.
   *
   * <p>{@code ProjectStore.define} accepts {@code /} — it is a directory that exists — so "the root
   * is small" is not an assumption available here.
   *
   * <p>Held from below by {@code two_thousand_matches_are_an_ordinary_answer}, which names its
   * count as a literal: a repository-wide {@code **&#47;*.java} must not be refused. As with {@link
   * #MAX_FILE_BYTES}, the refusal test's fixture is derived from this constant and therefore pins
   * only that some limit exists.
   */
  static final int MAX_MATCHES = 10_000;

  /**
   * What this provider is called in a refusal that names two machines.
   *
   * <p><b>Public because a second subsystem has to be kept off this word.</b> "local" here names a
   * <em>kind</em> — the server's own disk as against a client machine's, a real binary with two
   * values and no third. An LLM pool was also called {@code local} until 2026-09-07, where it named
   * a <em>property</em> (where the box is) and so meant something unrelated; one string meaning two
   * things in two subsystems is what a grep cannot tell apart, and it cost a whole afternoon of
   * per-occurrence disambiguation to undo. {@code no_pool_is_named_for_a_property_of_the_pool}
   * reads this constant rather than repeating the string, so a rename here moves the guard with it.
   */
  public static final String NAME = "local";

  /** The empty leash: no root, nothing permitted, used for every absence. */
  private static final FileAccess NOTHING = FileAccess.of(List.of(), List.of());

  private final ProjectWorkspaces projects;
  private final Home home;

  /**
   * Where a picture in one of this project's own files is <b>named</b>, and never where one is
   * stored.
   *
   * <p>{@link ImageStore#NONE} — and any store with no {@link
   * io.aeyer.plowshare.server.images.ImageFence} — names nothing, and this class then behaves
   * exactly as it did before workspace images existed: a PNG is refused as not UTF-8 text. That is
   * what makes the whole change additive rather than a new answer every deployment gets.
   */
  private final ImageStore images;

  private final boolean anyGrant;
  private final boolean writable;
  private final CommandIsolation isolation;

  /**
   * Non-null only for {@link #over}: a leash nobody looks up, because it is not a project's row.
   */
  private final FileAccess fixed;

  /**
   * @param projects where a project's workspace, the directories lent alongside it, and its
   *     exclusions live
   * @param home the tier this job runs in. Its project is what selects the roots, which is how the
   *     leash inherits 3a's non-escalation: a job cannot reach another project's files for the same
   *     reason it cannot read another project's memories
   * @param grants what the agent's definition declared. Empty is an ordinary answer and means this
   *     agent reaches no file at all
   */
  public LocalProvider(ProjectWorkspaces projects, Home home, List<Grant> grants) {
    this(projects, home, grants, ImageStore.NONE);
  }

  /**
   * The same provider, on a deployment that can name a picture it reads.
   *
   * @param images where a named file whose bytes {@link ImageFormat} recognises gets an id. {@link
   *     ImageStore#NONE} for a deployment that names none, which is every one that keeps no data
   *     directory and is the three-argument form above
   */
  public LocalProvider(
      ProjectWorkspaces projects, Home home, List<Grant> grants, ImageStore images) {
    this(projects, home, grants, images, null, CommandIsolation.UNAVAILABLE);
  }

  /** The execution backend is supplied by server composition, never by a project or command. */
  public LocalProvider(
      ProjectWorkspaces projects,
      Home home,
      List<Grant> grants,
      ImageStore images,
      CommandIsolation isolation) {
    this(projects, home, grants, images, null, isolation);
  }

  private LocalProvider(
      ProjectWorkspaces projects,
      Home home,
      List<Grant> grants,
      ImageStore images,
      FileAccess fixed,
      CommandIsolation isolation) {
    this.projects = projects;
    this.home = home;
    this.images = Objects.requireNonNull(images, "images");
    // No comparison against Scope.WORKSPACE. Scope has one value — its own
    // javadoc says why the second one from the source did not port — so
    // every grant is a workspace grant, and a scope check here would be a
    // line no mutant could kill until a second scope exists.
    this.anyGrant = !grants.isEmpty();
    // Grant.allows is the single owner of "write implies read", and says
    // why comparing modes here instead would be the copy that drifts.
    this.writable = grants.stream().anyMatch(grant -> grant.allows(Mode.WRITE));
    this.fixed = fixed;
    this.isolation = Objects.requireNonNull(isolation);
  }

  /**
   * A provider over directories that are not a project row's workspace — a union's server copy.
   * Every containment rule is {@link FileAccess}'s, the same as for a project, so hidden paths,
   * symlinks and {@code ..} are refused exactly as they are here.
   */
  public static LocalProvider over(FileAccess access, List<Grant> grants) {
    return new LocalProvider(
        null,
        null,
        grants,
        ImageStore.NONE,
        Objects.requireNonNull(access, "access"),
        CommandIsolation.UNAVAILABLE);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<Path> roots() {
    return reachable(leash()).access().roots();
  }

  @Override
  public io.aeyer.plowshare.protocol.FileSource fingerprint(Path path) {
    byte[] bytes = codeBytes(path, io.aeyer.plowshare.protocol.FileSource.MAX_BYTES);
    return new io.aeyer.plowshare.protocol.FileSource(
        bytes.length, FileContents.sha256(bytes), null, null);
  }

  @Override
  public byte[] snapshot(Path path, io.aeyer.plowshare.protocol.FileSource metadata, int maxBytes) {
    byte[] bytes = codeBytes(path, maxBytes);
    if (bytes.length != metadata.size() || !FileContents.sha256(bytes).equals(metadata.sha256()))
      throw new WorkspaceUnavailableException("code source changed during snapshot");
    return bytes;
  }

  private byte[] codeBytes(Path path, int maxBytes) {
    if (maxBytes < 0 || maxBytes > io.aeyer.plowshare.protocol.FileSource.MAX_BYTES)
      throw new IllegalArgumentException("invalid source byte bound");
    try {
      Leash leash = reachable(leash());
      Path target = permitted(leash, path, "read");
      BasicFileAttributes before =
          Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile())
        throw refused(FileResult.refused(FileRequest.READ, notRegular(before), path.toString()));
      if (before.size() > maxBytes)
        throw refused(
            FileResult.tooLarge(FileRequest.READ, path.toString(), before.size(), maxBytes));
      byte[] bytes;
      try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(maxBytes + 1);
      }
      BasicFileAttributes after =
          Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (bytes.length > maxBytes
          || bytes.length != before.size()
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !java.util.Objects.equals(before.fileKey(), after.fileKey())
          || !target.equals(permitted(reachable(leash()), path, "read")))
        throw new WorkspaceUnavailableException("code source changed during snapshot");
      return bytes;
    } catch (IOException failed) {
      throw new WorkspaceUnavailableException("code source could not be read: " + path, failed);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>The translation of {@link Window.LineTooWide} is the only thing here besides the cut, and it
   * is {@link #matchers}' arrangement: the protocol module has neither half's refusal type and can
   * raise nothing but an unchecked exception, and left alone this one would reach {@code
   * JobRuntime} as "the tool failed" rather than as something a model can act on — which for a file
   * this shape is the whole point of the change, since the alternative it replaced was a dead
   * session.
   *
   * <p><b>The message is carried unchanged</b>, as {@code GlobSpellings}' is and for the same test:
   * nothing in it is a fact about this machine. The line number, its size and {@link
   * Window#MAX_WINDOW_BYTES} are the file's and the wire's, so the client's copy of this catch says
   * the same words, and a model reading a refusal cannot tell — and must not have to — which side
   * of the wire refused.
   */
  @Override
  public Span read(Path path, Window window) {
    // The cut belongs to Window and not to this class. FileProvider.read
    // says why: a provider that worked out its own range would make a
    // file's contents depend on which machine the job ran on, and both
    // answers would look like answers. What is this class's is everything
    // above the cut — which file may be opened at all, and how its bytes
    // become the lines handed over.
    try {
      return window.cut(lines(path, FileRequest.READ));
    } catch (Window.LineTooWide unreadable) {
      throw refused(
          FileResult.lineTooWide(
              FileRequest.READ,
              path.toString(),
              unreadable.offset(),
              unreadable.bytes(),
              Window.MAX_WINDOW_BYTES));
    }
  }

  @Override
  public Span stat(Path path) {
    int total = lines(path, FileRequest.STAT).size();
    // `more` follows the invariant Window.cut establishes and this method
    // has to keep, since both answers reach a caller through the same
    // type: stoppedBy is END exactly when nothing follows. A stat of a file
    // with lines in it carries none of them, so something does follow, and
    // END there would tell a caller it had already seen the file.
    //
    // LINES rather than BYTES for the one that remains. BYTES means "a
    // wider limit would not have helped", which is the opposite of what is
    // true here — asking for lines is precisely what a caller stats in
    // order to plan — and an empty file is the ordinary END, the same
    // answer a read of it gives.
    return new Span(List.of(), 0, total, total > 0, total > 0 ? Span.LINES : Span.END);
  }

  /**
   * Every line of one file, behind every guard {@link #read} and {@link #stat} share.
   *
   * <h2>The whole file is read and then cut, not the window read</h2>
   *
   * <p>Seeking to the window and decoding only its bytes would be the obvious saving and is not
   * available, for two reasons that are separately fatal.
   *
   * <p><b>The strict decoder's promise is about the file and not about the range.</b> Measured on
   * JDK 21: a REPORTing UTF-8 decoder over a byte prefix that splits a multi-byte character raises
   * {@code MalformedInputException} — the same exception the {@code catch} below turns into "this
   * file is not UTF-8 text". A provider that decoded a byte range would refuse an ordinary file for
   * where its window happened to fall, and the refusal would be about the file, which would be a
   * lie.
   *
   * <p><b>{@link Span#totalLines()} has to be true.</b> It is what makes paging plannable rather
   * than exploratory, and there is no way to know how many lines a file has without looking at all
   * of it.
   *
   * <p>So the window bounds the wire and not the heap, and {@link #MAX_FILE_BYTES} is still the
   * only thing bounding the heap. It is unchanged and still reachable: a file above it is refused
   * whatever window is asked of it, which is the same refusal as before this change.
   *
   * <h2>What a line is here, measured rather than assumed</h2>
   *
   * <p>{@code String.lines}, whose behaviour on JDK 21 was run before this was written:
   *
   * <ul>
   *   <li><b>a terminator is not part of the line it ends</b>, and {@code \n}, {@code \r\n} and a
   *       lone {@code \r} all end one. So a CRLF file's lines arrive without their carriage
   *       returns, and since {@code FileReply} no longer carries the file's text beside them,
   *       <b>what is not in a line is not delivered</b>: a window of a CRLF file reads as a window
   *       of an LF file. That is the right answer for the reader these windows exist for — a model
   *       that would otherwise be shown a trailing {@code \r} on every line and charged a byte for
   *       each — and it is why nothing reconstructs a file from a window;
   *   <li><b>a trailing terminator does not add an empty last line.</b> {@code "a\nb"} and {@code
   *       "a\nb\n"} are both two lines, which is the other end of the byte {@link Window#cut}
   *       over-charges a last line that has no newline: this method cannot tell those two files
   *       apart and that method does not try to;
   *   <li><b>an empty file is zero lines</b>, so its window is the empty {@link Span} at {@link
   *       Span#END} — the same answer as an offset past the end, and an ordinary one rather than a
   *       refusal.
   * </ul>
   */
  private List<String> lines(Path path, String op) {
    Leash leash = reachable(leash());
    return decode(permitted(leash, path, "read"), path, NAMING, op);
  }

  /**
   * Whether {@link #decode} may hand a file to {@link ImageStore} before deciding it is not text.
   *
   * <p><b>Two named constants and not a bare boolean at three call sites</b>, because the
   * difference between them is a real decision and one of them is the one somebody will want to
   * change. {@code ClientEnforcer.CONVERTING} and {@code AS_IT_LIES} are the same pair one module
   * over, drawn on the same line for the same argument, and this is deliberately not a second rule:
   * a file the caller named is treated differently from a file a walk happened to reach, whatever
   * the treatment is.
   *
   * <p>A read, a stat and a search of <em>one named file</em> name a picture; a <em>walk</em> does
   * not. The line is not which operation it is — it is whether the caller named the file. Naming
   * costs a hash and a sidecar write per file, and a {@code file_grep} across a workspace holding
   * two hundred PNGs would turn one search into two hundred records nobody asked for, on the socket
   * a job is blocked on. <b>A file the caller named is a cost the caller chose.</b>
   *
   * <p>It is also the containment half, and it is the same sentence read the other way: an agent
   * that could make a walk mint ids would have something close to an enumeration of the pictures in
   * a tree — which is precisely what {@code NothingEnumeratesImagesTest} exists to keep out of
   * reach. Nothing is minted that a model did not name a path for.
   *
   * <p><b>The consequence is stated rather than hidden: a walk does not find what a search of the
   * same file by name would.</b> That is a real seam and it is the asymmetry the client already
   * accepts. It is also exactly today's behaviour for these files, which the walk skips as
   * unreadable, so nothing that worked has changed shape.
   */
  private static final boolean NAMING = true;

  /**
   * @see #NAMING
   */
  private static final boolean AS_IT_LIES = false;

  /**
   * One file's lines, once it is settled that this job may open it.
   *
   * <p>Split out of {@link #lines} so that {@link #grep}'s walk can read a file {@code
   * FileSearch.eachFile} has already checked, without asking {@link #permitted} a second question
   * it has already answered — and <b>without the walk being able to skip the check</b>, which is
   * why this takes the target rather than a raw path and is private.
   *
   * @param target the canonical, permitted file to open
   *     <h2>The one place a file stops being text on its way in</h2>
   *     <p>This is the only method here that turns a file into lines, so it is the only place a
   *     picture can be recognised before the strict decoder turns it away. {@link ImageStore} is
   *     asked <b>after</b> the size ceiling and the regular-file check and <b>before</b> the
   *     decode: after, because a file above this server's ceiling is refused whatever it is, and
   *     before, because the decode is what would otherwise refuse it.
   *     <p>Everything downstream is untouched. {@link #read} still calls {@code Window.cut} and
   *     {@link #stat} still counts what a read would return, so the answer is lines the way every
   *     other answer is lines — which is the whole of {@code named}'s "name, do not attach".
   * @param path the path as the caller wrote it, which is what every sentence below quotes back.
   *     The two differ, and quoting the canonical one would answer a model about a path it did not
   *     type
   * @param naming {@link #NAMING} where the caller named this file, {@link #AS_IT_LIES} inside a
   *     walk. That constant owns the argument
   */
  private List<String> decode(Path target, Path path, boolean naming, String op) {
    try {
      // An attribute read and not a failed open. Measured on this host:
      // Files.newInputStream(directory, NOFOLLOW_LINKS) SUCCEEDS, and the
      // failure comes one call later out of readAllBytes, as an IOException
      // whose message is the platform's "Is a directory".
      //
      // So opening first and hoping does not hand a directory to the
      // decoder — an earlier version of this comment said it did, and the
      // review measured it. What it actually costs is the SENTENCE: the
      // read would land in the residue clause below and come back as
      // "could not be read: Is a directory", a platform string this seam
      // has to be able to reproduce over a socket. The explicit check is
      // what makes it one message rather than whatever the far machine's
      // libc says.
      //
      // NOFOLLOW on both calls closes the window ON THE FINAL COMPONENT,
      // and that is the whole of what it does. `target` is canonical, so
      // no legitimate last name is a symlink and refusing to follow one
      // costs nothing; a loop flipping that name between a file and a link
      // to an out-of-root canary is how Excalibur demonstrated the read
      // (tools.py:375-390), and this stops that.
      //
      // It does NOT close the check-to-open window in general, and this
      // comment claimed it did until a review read it. Every DIRECTORY
      // component above the last is still resolved by the OS at open time,
      // so swapping one of those for a symlink between `permits` and here
      // reaches a different file with this flag set.
      //
      // THE DESIGN SPEC ASKED FOR A POST-OPEN IDENTITY CHECK TO CLOSE THE
      // REST, AND NO LONGER DOES -- this comment said it did until slice
      // 3c measured it. Java 21 offers no way to read attributes THROUGH
      // an open handle: no fstat, no attribute accessor on FileChannel, no
      // method of Files taking a descriptor, and SecureDirectoryStream --
      // the one handle-anchored filesystem API in the language -- is
      // unsupported on this platform. So "compare the fileKey of the
      // handle against the path that was checked" cannot be written; it
      // reduces to two PATH stats around one open, whose second stat is as
      // raceable as the first. Measured, in three separate sittings of
      // four rounds each at 200,000 reads a round: THE GUARDED VARIANT
      // STILL ESCAPED IN EVERY ROUND OF EVERY RUN. That is why nothing
      // was shipped -- a guard whose comment could not honestly say the
      // window is closed is worse than a stated absence.
      //
      // How much it narrows the window is deliberately NOT stated here,
      // and this comment quoted "about fivefold" until the whole-slice
      // review sent someone to check it against the evidence. The three
      // sittings measured 5x, 26x and 38x for the same code on the same
      // machine: the ratio is a property of how the two threads happened
      // to interleave that hour, not of the check, so a number for it is
      // a number that means nothing to whoever reads it next.
      //
      // What is left is a boundary rather than a hole. No file tool
      // creates a symlink -- read, write, edit, delete, move, glob and
      // roots are the whole surface, and move refuses a link or a
      // directory as its source -- so the agent, which is the adversary this leash was
      // built against, cannot move an intermediate directory at all.
      // Reaching this needs a concurrent local writer who already holds
      // write access to the workspace's directory structure, and who
      // therefore does not need the window to read the file. The evidence,
      // the race test and the numbers are at
      // implementation rationale
      //
      // No test here reaches either race; a race has no deterministic
      // instrument, so the mutant that drops both flags survives this
      // file, and they are kept on the strength of that demonstration
      // rather than on a green test.
      BasicFileAttributes about =
          Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!about.isRegularFile()) {
        throw refused(FileResult.refused(op, notRegular(about), path.toString()));
      }
      if (about.size() > MAX_FILE_BYTES) {
        throw refused(FileResult.tooLarge(op, path.toString(), about.size(), MAX_FILE_BYTES));
      }
      byte[] bytes;
      try (InputStream open = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
        bytes = open.readAllBytes();
      }
      if (naming) {
        List<String> converted = FileContents.SHARED.pdf(path.toString(), op, bytes, false);
        if (converted != null) return converted;
        List<String> picture = named(target, path, bytes, op);
        if (picture != null) {
          return picture;
        }
      }
      // A decoder that REPORTs, which is the default and is what
      // Files.readString does. `new String(bytes, UTF_8)` substitutes
      // U+FFFD instead — measured — so a provider written that way hands a
      // model a page of replacement characters and calls it the file.
      return StandardCharsets.UTF_8
          .newDecoder()
          .decode(ByteBuffer.wrap(bytes))
          .toString()
          .lines()
          .toList();
    } catch (NoSuchFileException absent) {
      throw refused(FileResult.noFile(op, path.toString()));
    } catch (AccessDeniedException forbidden) {
      // Its own clause because it is the one certain refusal in this
      // family: a file this server may not open is not the workspace
      // becoming unreachable, and the model can name another path.
      // Measured: a mode-000 file opens as AccessDeniedException from
      // newInputStream — readAttributes on it succeeds, so the type
      // arrives at the open and not at the check. Naming it structurally
      // leaves the untyped residue below visibly separate, rather than
      // resting the whole clause on a claim about which errno is common.
      throw refused(
          FileResult.failed(op, FileResult.DENIED, path.toString(), null, forbidden.getMessage()));
    } catch (CharacterCodingException notText) {
      throw refused(FileResult.notText(op, path.toString(), FileResult.NOT_UTF8));
    } catch (IOException failed) {
      // THE RESIDUE, admitted rather than hidden. ENOSPC and EIO land here
      // and are not the caller's mistake at all, yet they are refused with
      // everything else.
      //
      // There is no portable *type* that separates them: NIO names
      // ENOENT, EACCES, EEXIST and ELOOP, and everything past that —
      // ENOTDIR included, which is the case a test in this file covers —
      // is a plain FileSystemException carrying a platform reason string.
      // Matching that string is the locale-dependent guess this project's
      // rules forbid, and splitting on the types that ARE named would be
      // worse than the residue: it would put ENOTDIR, an ordinary
      // caller's mistake, on the outage side.
      //
      // FileStore.getUsableSpace() was considered and rejected. It is
      // portable and needs no string, but it is racy — space can be freed
      // between the failure and the probe — it says nothing about EIO, it
      // misses EDQUOT, and it fails in the dangerous direction, since a
      // false positive ends a run. The reason is carried into the message
      // instead, where a reader sees it.
      throw refused(
          FileResult.failed(op, FileResult.FAILED, path.toString(), null, failed.getMessage()));
    }
  }

  /**
   * The lines that <b>name</b> the picture in these bytes, or {@code null} if they are not a
   * picture this server can name.
   *
   * <h2>Name, do not attach</h2>
   *
   * <p>The answer is an id and never the image. The design note's §4 is the argument and volume is
   * the whole of it: a glob-then-read loop over a directory of two hundred PNGs would otherwise
   * attach two hundred pictures to one context, on a system whose compaction reasons in tokens it
   * cannot count for an image — {@code LlmConfig}'s "no tokenizer emits more tokens than its input
   * has bytes" is true of text and false of a picture, and that gap is recorded and unfixed. Naming
   * also leaves the <em>decision to look</em> with the model, where it becomes a delegation with
   * its own budget and its own record, which is what {@code image_reader} is for.
   *
   * <p>So this returns lines, exactly as a file's text does, and everything downstream stays
   * ignorant: {@code Window.cut} cuts them, {@code file_stat} counts them, and no tool in this
   * server has learned to answer with bytes.
   *
   * <h2>{@link ImageFormat} is the allow-list, and nothing is sniffed past it</h2>
   *
   * <p>The four formats a vision endpoint takes. A BMP, a TIFF, an SVG or a HEIC is not named,
   * falls through to the strict decoder, and is refused with the sentence it has always been
   * refused with — a fixed set that refuses the rest is the one idea worth taking from Codex here.
   * Naming a format no model in this fleet can be shown would produce an id whose only possible use
   * fails at the vision call, one hop away from anything that could explain it.
   *
   * <p><b>Null and not a refusal</b> for everything it declines, on {@code
   * ClientEnforcer.converted}'s reasoning: an executable, a UTF-16 document and a TIFF all keep the
   * answer they had. A refusal invented here would be a new answer for every binary in every
   * workspace, for no gain.
   *
   * <h2>Nothing is copied</h2>
   *
   * <p>{@link ImageStore#note} writes the record and not the bytes: the file is already on this
   * server's disk, inside a project it roots, so a second copy would be bytes retention had to
   * answer for and nobody needed. What that record buys is resolution — {@code agent_run} can turn
   * the id back into a picture — and resolution re-reads this very path and re-asks {@code
   * FileAccess.permits}, so the id does not outlive the permission that produced it.
   *
   * @param target the canonical, permitted file — what the record points at, because it is the
   *     spelling the fence was asked about
   * @param path the path as the caller wrote it, which is what the sentence quotes back
   */
  private List<String> named(Path target, Path path, byte[] bytes, String op) {
    if (ImageFormat.of(bytes) == null) {
      return null;
    }
    StoredImage picture = images.note(home, target, bytes);
    if (picture == null) {
      // This deployment names none -- no data directory, or no fence to
      // re-ask. The file goes back to being not UTF-8 text, which is the
      // answer it had before any of this existed.
      return null;
    }
    // ONE LINE, and it is the whole answer. `file_stat` therefore says this
    // file has one line, which is true of what a reader is handed and is the
    // same shape a converted document already has: what `stat` counts is
    // what `read` would return, and neither has ever been the file's own
    // structure.
    // Worded by FileWords, which words a client's picture too.
    return List.of(
        FileWords.named(
            FileResult.named(op, path.toString(), picture.format().declared(), picture.id()),
            false));
  }

  @Override
  public List<Path> glob(String pattern) {
    Leash leash = reachable(leash());
    // Before the pattern is even looked at: a job with nowhere to search has
    // a state to be told about, and a syntax complaint about the pattern
    // would send it rewriting the one thing that was not wrong.
    refuseIfNothingIsReachable(leash);
    if (pattern.isBlank()) {
      throw refused(FileResult.pattern(FileResult.NO_PATTERN, pattern, null));
    }
    if (pattern.startsWith("/")) {
      // Measured on JDK 21: an absolute glob compiles and simply matches
      // no relative path, so leaving it alone produces a confident empty
      // answer rather than an error. Python raises NotImplementedError,
      // which is why Excalibur could answer this without a check.
      throw refused(FileResult.pattern(FileResult.ABSOLUTE_PATTERN, pattern, null));
    }
    List<PathMatcher> matchers = matchers(pattern);
    List<Path> hits = new ArrayList<>();
    for (Path root : leash.access().roots()) {
      walk(root, matchers, leash.access(), hits);
    }
    return List.copyOf(hits);
  }

  /**
   * {@inheritDoc}
   *
   * <h2>Two shapes, and only one of them walks</h2>
   *
   * <p>A named path is the read path with a different consumer: {@link #permitted} refuses it
   * exactly as a read would, and a file that is not text is a refusal rather than an empty answer,
   * because the model asked about <em>that</em> file. A named directory and an absent path are the
   * same walk, differing only in where it starts.
   *
   * <p>The walk skips what it cannot read, and the class-level rule about empty answers is not
   * violated by that: {@link FileProvider#grep} argues it, and the short of it is that a file which
   * cannot be searched has no matches to be missing from the answer. What is <em>not</em> skipped
   * is a directory this server cannot list — {@link #walk} refuses that for {@code glob} and this
   * refuses it here, because a tree half searched and reported as searched is the confident empty
   * answer.
   */
  @Override
  public Found grep(Needle needle, Path path) {
    Leash leash = reachable(leash());
    // Before the disk is touched and before the needle is looked at: a job
    // with nowhere to search has a state to be told about, and the five
    // sentences behind `absence` are five different fixes.
    refuseIfNothingIsReachable(leash);
    List<Found.Match> into = new ArrayList<>();
    boolean capped;
    if (path != null) {
      Path target = permitted(leash, path, "read");
      // A named file is read whatever it is, and a named directory is the
      // same walk an absent path gets. `into` is filled before Found.of
      // copies it, and the two steps are written apart rather than nested
      // so that nothing here depends on the order Java evaluates arguments
      // in.
      capped =
          Files.isDirectory(target)
              ? sweep(target, leash.access(), needle, into)
              : needle.find(
                  target.toString(), decode(target, path, NAMING, FileRequest.GREP), into);
      return Found.of(into, capped);
    }
    capped = false;
    for (Path root : leash.access().roots()) {
      // `capped = sweep(...)` would be reset by a root that held nothing,
      // and only the break would hide it — the hazard `sweep` avoids one
      // level down, and worth avoiding the same way rather than resting on
      // the loop's shape.
      if (sweep(root, leash.access(), needle, into)) {
        capped = true;
        break;
      }
    }
    return Found.of(into, capped);
  }

  /**
   * One root's matching lines, with the refusal that is this server's own.
   *
   * <p>{@code FileSearch.eachFile} owns which files a walk reaches — that a directory is not one,
   * and that containment is checked on the resolved path <b>per file</b>. {@link Needle#find} owns
   * which of their lines match and how many come back. What is left here is this machine's: what it
   * says about a directory of its own it cannot list, and which of its own files it declines to
   * read.
   *
   * @return whether the allowance discarded a match, which is also what stops the walk
   */
  private boolean sweep(Path root, FileAccess access, Needle needle, List<Found.Match> into) {
    boolean[] capped = {false};
    try {
      FileSearch.eachFile(
          root,
          access,
          file -> {
            List<String> lines;
            try {
              // AS_IT_LIES, so a walk never mints an id -- that constant
              // owns the argument, and this is the call the mutation that
              // breaks it would edit.
              lines = decode(file, file, AS_IT_LIES, FileRequest.GREP);
            } catch (WorkspaceRefusedException unreadable) {
              // A binary, a file above MAX_FILE_BYTES, one this server may
              // not open. SKIPPED, and FileProvider.grep argues it: a tree
              // with a PNG in it is every real tree, and refusing the whole
              // search over one of them is a tool that cannot be used
              // without a path — which is the case it exists for.
              //
              // The sentence is dropped rather than collected. Naming every
              // unsearchable file would be a list of the repository's
              // binaries, longer than the answer and about nothing the
              // caller asked.
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
      // Named rather than skipped, exactly as `walk` does for a glob: a
      // tree searched in part and reported as searched in full is the
      // confident empty answer. This is a DIRECTORY that cannot be listed,
      // and not a file that cannot be read — the two are told apart by
      // where they are caught, and only the second is skippable.
      throw refused(
          FileResult.failed(
              FileRequest.GREP, FileResult.UNLISTABLE, root.toString(), null, failed.getMessage()));
    }
    return capped[0];
  }

  @Override
  public Changed write(Path path, String content) {
    return put(path, content, false);
  }

  @Override
  public Changed create(Path path, String content) {
    return put(path, content, true);
  }

  /**
   * A refusal of a change to one of this server's own files, as facts — worded by {@link
   * FileWords}, the renderer every file side's facts go through, with the file placed here rather
   * than on a client's machine.
   */
  private static FileRefusedException refused(FileResult facts) {
    return new FileRefusedException(facts, false);
  }

  /**
   * Which rule a file that is not a regular one breaks: a directory is said to be one, as the
   * clients say it, and anything else is not a regular file.
   */
  private static String notRegular(BasicFileAttributes about) {
    return about.isDirectory() ? FileResult.DIRECTORY : FileResult.NOT_REGULAR;
  }

  private Changed put(Path path, String content, boolean createOnly) {
    String op = FileRequest.WRITE;
    Path target = writable(reachable(leash()), path, "write", "written");
    if (Files.isDirectory(target)) {
      // Checked rather than left to the open, which reports it as a
      // FileSystemException whose message is the path and the words "Is a
      // directory" — a sentence assembled by the platform, in a place this
      // seam has to be able to say the same thing over a socket.
      throw refused(FileResult.refused(op, FileResult.DIRECTORY, path.toString()));
    }
    try {
      createParents(target);
      // TRUNCATE_EXISTING, or a shorter write over a longer file leaves a
      // tail behind and produces a file neither version ever had.
      // NOFOLLOW for the reason `read` gives, and with the same admission:
      // dropping it survives every test here, and measured, it is not
      // decorative — writing to a link with the flag raises rather than
      // following it, and the file at the far end was left untouched.
      //
      // CREATE_NEW for a create, and not an exists() check first: the
      // check and the open are one system call, so nothing can create the
      // file between them.
      if (createOnly) {
        try {
          Files.writeString(
              target,
              content,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException there) {
          // Caught here and not around the whole block: createParents
          // raises the same type for a FILE standing where a directory
          // above the target should be, and that is "could not be
          // written", not "already exists".
          throw refused(FileResult.refused(op, FileResult.EXISTS, path.toString()));
        }
      } else {
        Files.writeString(
            target,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS);
      }
    } catch (IOException failed) {
      // Refused, with `read`'s residue and its argument: a full disk lands
      // here too and is not a caller's mistake, and no portable type
      // separates it from the write-below-a-file case in the same clause.
      throw refused(
          FileResult.failed(op, FileResult.FAILED, path.toString(), null, failed.getMessage()));
    }
    // Counted from what was sent, as a client counts it: the bytes as UTF-8
    // encodes them, and the lines as String.lines() splits them.
    return Changed.of(
        FileResult.written(
            path.toString(),
            content.getBytes(StandardCharsets.UTF_8).length,
            (int) content.lines().count()));
  }

  /**
   * {@inheritDoc}
   *
   * <p>The file's bytes are read, decoded strictly and re-encoded, so every byte the replacement
   * does not touch is written back as it was — CRLFs, a missing last newline and a byte-order mark
   * included. {@link Replacement#decode} owns why strict.
   */
  @Override
  public Changed edit(Path path, String old, String replacement) {
    String op = FileRequest.EDIT;
    String named = path.toString();
    Path target = writable(reachable(leash()), path, "edit", "edited");
    refuseIfLink(path, op);
    try {
      BasicFileAttributes about =
          Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!about.isRegularFile()) {
        throw refused(FileResult.refused(op, notRegular(about), named));
      }
      if (about.size() > MAX_FILE_BYTES) {
        throw refused(FileResult.tooLarge(op, named, about.size(), MAX_FILE_BYTES));
      }
      byte[] bytes;
      try (InputStream open = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
        bytes = open.readAllBytes();
      }
      String text = Replacement.decode(bytes);
      if (text == null) {
        throw refused(FileResult.notText(op, named, FileResult.NOT_UTF8));
      }
      Replacement.Edited edited;
      try {
        edited = Replacement.edit(text, old, replacement);
      } catch (Replacement.Refused notMade) {
        throw refused(notMade.result(named));
      }
      Files.writeString(
          target,
          edited.text(),
          StandardCharsets.UTF_8,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING,
          LinkOption.NOFOLLOW_LINKS);
      return Changed.of(edited.result(named));
    } catch (NoSuchFileException absent) {
      // An edit of nothing is, measured, a model trying to create a file
      // with old and new; the answer says how to create one.
      throw refused(FileResult.noFile(op, named));
    } catch (IOException failed) {
      // `write`'s residue, for `write`'s reason.
      throw refused(FileResult.failed(op, FileResult.FAILED, named, null, failed.getMessage()));
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Answered with how big the file was, and how many lines when it is small enough to load —
   * counted from its bytes, as a read counts them.
   */
  @Override
  public Changed delete(Path path) {
    String op = FileRequest.DELETE;
    String named = path.toString();
    Path target = writable(reachable(leash()), path, "delete", "deleted");
    refuseIfLink(path, op);
    try {
      BasicFileAttributes about =
          Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!about.isRegularFile()) {
        // One file, and never a tree: a directory removed by a model is
        // the change with the widest reach and the least review, and
        // nothing in this slice needs it.
        throw refused(FileResult.refused(op, notRegular(about), named));
      }
      Integer lines = null;
      if (about.size() <= MAX_FILE_BYTES) {
        try (InputStream open = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
          lines = FileResult.lineCount(open.readAllBytes());
        }
      }
      Files.delete(target);
      return Changed.of(FileResult.deleted(named, about.size(), lines));
    } catch (NoSuchFileException absent) {
      throw refused(FileResult.noFile(op, named));
    } catch (IOException failed) {
      throw refused(FileResult.failed(op, FileResult.FAILED, named, null, failed.getMessage()));
    }
  }

  @Override
  public Changed move(Path from, Path to) {
    String op = FileRequest.MOVE;
    // One leash for both paths: `leash` reads the projects row, and two reads
    // are a window a `forget` can land in between the source's check and
    // the destination's.
    Leash leash = reachable(leash());
    Path source = writable(leash, from, "move", "moved");
    // The destination is contained by the same rule as a write, and checked
    // before anything is touched: a move out of the fence is a copy of the
    // file to wherever the model named.
    Path destination = writable(leash, to, "move to", "moved to");
    refuseIfLink(from, op);
    try {
      BasicFileAttributes about =
          Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!about.isRegularFile()) {
        throw refused(FileResult.refused(op, notRegular(about), from.toString()));
      }
      if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
        throw refused(FileResult.destinationExists(from.toString(), to.toString()));
      }
      createParents(destination);
      try {
        // No REPLACE_EXISTING, so a file that appears at the destination
        // after the look above is still not overwritten: the move raises.
        Files.move(source, destination);
      } catch (FileAlreadyExistsException there) {
        // Around the move alone, for `put`'s reason: createParents
        // raises this type for a file in the way of a directory.
        throw refused(FileResult.destinationExists(from.toString(), to.toString()));
      }
      return Changed.of(FileResult.moved(from.toString(), to.toString(), about.size()));
    } catch (NoSuchFileException absent) {
      throw refused(FileResult.noFile(op, from.toString()));
    } catch (IOException failed) {
      throw refused(
          FileResult.failed(
              op, FileResult.FAILED, from.toString(), to.toString(), failed.getMessage()));
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Server command modes still default to {@code off}. An admitted {@code bubblewrap} command
   * uses the current read/write fence through the installed isolation backend. An admitted {@code
   * none} command runs as the server OS user and cannot honor restricted writable areas.
   */
  @Override
  public CommandRunner.Outcome run(
      Path cwd,
      List<String> argv,
      EnvironmentFile.Side side,
      Duration timeout,
      BooleanSupplier cancelled) {
    return run(cwd, argv, side, timeout, null, cancelled);
  }

  /** {@inheritDoc} On this server, as this server's OS user, with {@code stdin} written to it. */
  @Override
  public CommandRunner.Outcome run(
      Path cwd,
      List<String> argv,
      EnvironmentFile.Side side,
      Duration timeout,
      String stdin,
      BooleanSupplier cancelled) {
    Leash leash = reachable(leash());
    boolean isolated = "bubblewrap".equals(side.isolation());
    if (!isolated && !"none".equals(side.isolation())) {
      throw new WorkspaceRefusedException("unsupported command isolation");
    }
    Path directory =
        isolated
            ? permitted(leash, cwd, "run a command in")
            : writable(leash, cwd, "run a command in", "run in");
    if (!writable)
      throw new WorkspaceRefusedException("command execution requires a workspace write grant");
    if (isolated && leash.writeAccess().roots().isEmpty()) {
      throw new WorkspaceRefusedException("command execution requires a configured writable area");
    }
    if (!Files.isDirectory(directory)) {
      throw new WorkspaceRefusedException(
          "path " + cwd + " is not a directory, so a command" + " cannot run in it");
    }
    try {
      var command =
          new CommandRunner.Command(
              argv, directory, side.env(), side.inherit(), timeout, side.outputBytes(), stdin);
      if (isolated)
        return isolation.run(
            command, leash.access(), leash.writeAccess(), System.getenv(), cancelled);
      return CommandRunner.run(command, System.getenv(), cancelled);
    } catch (CommandRunner.Refused refused) {
      throw new WorkspaceRefusedException(refused.getMessage());
    }
  }

  /**
   * The canonical path a change may be made to, or the refusal saying why not.
   *
   * <p>Containment before the mode, and the order is the message. "The workspace is read-only to
   * you" said about a path that was never in the workspace points a reader at the agent's scopes
   * when the thing to fix is the path — Excalibur recorded hearing exactly that and calling it
   * technically true and useless.
   */
  private Path writable(Leash leash, Path path, String verb, String done) {
    Path target = permitted(leash, path, verb);
    if (leash.access().roots().stream()
        .anyMatch(root -> !io.aeyer.plowshare.protocol.ProjectFile.modelMayChange(root, target)))
      throw new WorkspaceRefusedException(
          "Project configuration is changed by the operator, not through an agent file tool");
    if (!leash.writeAccess().permits(target)) {
      throw new WorkspaceRefusedException(
          "This path is outside the project's writable areas: " + path);
    }
    if (verb.equals("run a command in") && leash.restrictedWrites()) {
      throw new WorkspaceRefusedException(
          "Commands are disabled for projects with restricted writable areas; a working directory cannot contain command writes");
    }
    if (!writable) {
      throw new WorkspaceRefusedException(
          "the workspace is read-only to this agent, so "
              + path
              + " cannot be "
              + done
              + "; its definition declares 'scopes:"
              + " [workspace:read]'");
    }
    return target;
  }

  /**
   * A link named as the thing to change is refused, on the path as it was named.
   *
   * <p>Not on the canonical path, which has already resolved the link: deleting or editing that
   * would change the file the link points at, which is not the file the model named.
   */
  private static void refuseIfLink(Path path, String op) {
    if (Files.isSymbolicLink(path)) {
      throw refused(FileResult.refused(op, FileResult.LINK, path.toString()));
    }
  }

  private static void createParents(Path target) throws IOException {
    Path parent = target.getParent();
    if (parent != null) {
      // The directories above a permitted target are inside the same
      // root or are the root itself, which already exists — `reachable`
      // has just said so — so this creates nothing outside the leash:
      // an exclusion covering an ancestor of the target would cover
      // the target too, and `permitted` has already refused that.
      //
      // NOT a guard, and no test reaches it. `parent` is null only for
      // a filesystem root, which is a directory and refused before this
      // runs. It is here for the reason FileAccess.canonical keeps its
      // own `parent == null` branch: the alternative is a
      // NullPointerException inside a file tool, taking a job down over a
      // path it could have refused.
      Files.createDirectories(parent);
    }
  }

  // --- the leash -----------------------------------------------------------

  /**
   * What this job may reach right now, and — when that is nothing — which of the five states it is
   * in.
   *
   * @param absence null when at least one root survived, and otherwise the sentence saying why none
   *     did. Every refusal that is not about a particular path is this string: it names the state
   *     rather than letting it pass as an out-of-scope path, because the five states are five
   *     different fixes and only one of them is the model's
   */
  private record Leash(
      FileAccess access, String absence, FileAccess writeAccess, boolean restrictedWrites) {
    private Leash(FileAccess access, String absence) {
      this(access, absence, access, false);
    }
  }

  private Leash leash() {
    if (!anyGrant) {
      // First, because it is true whatever tier the job runs in and it is
      // the only one of the five whose fix is the agent's own file.
      // Grant.noneDeclared and not a literal: RemoteProvider reaches the
      // same state before a byte goes on the wire, and the sentence that
      // sends a reader to the agent's own file is the one this slice can
      // least afford to have two of.
      return new Leash(NOTHING, Grant.noneDeclared());
    }
    if (fixed != null) {
      return new Leash(
          fixed,
          fixed.roots().isEmpty() ? "the server's copy of this project holds nothing yet" : null);
    }
    if (home.isGlobal()) {
      return new Leash(
          NOTHING,
          "this job runs in the global tier, which is every project"
              + " at once and so names no single filesystem; a job that needs files runs"
              + " in a project");
    }
    String project = home.project();
    Optional<ProjectRecord> row;
    try {
      row = projects.find(project);
    } catch (ValidationException unusable) {
      // Home.of does not strip (HomeTest holds that) and ProjectStore
      // refuses a name that is not already stripped on every way in, so
      // this tier is one for which a workspace can never be defined —
      // ProjectStore.find's javadoc leaves the caller to decide what a job
      // there gets, and this is the decision. Unavailable and not refused:
      // no turn the model takes can fix a project name, so this is the
      // type that ends the run — which it now does, since
      // JobRuntime.dependencyFailure names it. The exception itself owns
      // that argument.
      throw new WorkspaceUnavailableException(
          "no workspace can ever be defined for project"
              + " '"
              + project
              + "': "
              + unusable.getMessage(),
          unusable);
    }
    if (row.isEmpty()) {
      return new Leash(
          NOTHING,
          "no workspace is defined for project '"
              + project
              + "'; whoever runs this server points a project at a directory");
    }
    ProjectRecord defined = row.get();
    // of(workspaces, excluded) and never withServerOwned: the class javadoc
    // says what the difference costs. effectiveExclusions and never
    // row.exclusions(): ProjectStore.effectiveExclusions says why.
    //
    // The overload taking the row, so this reads `projects` ONCE. Asking by
    // name a second time is a window a `forget` can land in — the very
    // operation the per-call re-read exists to honour — and it raises
    // ArchiveException, which is a third exit from a seam whose whole
    // contract is that there are two.
    //
    // defined.roots() and never List.of(defined.workspace()). The singleton
    // was correct while a project had one directory, and it is now the
    // mutation that makes every lent root unreachable while every fixture
    // about the workspace stays green -- which is what
    // `a_second_lent_root_makes_a_hidden_directory_readable` is here to
    // catch. It is also the one line the whole lending feature is: the
    // leash is plural everywhere below this call and was collapsed here.
    // defined.reach(...) and not FileAccess.of(...) assembled here: that
    // method is the one expression a project's containment is, and image
    // resolution asks the same one. Two copies of it is the arrangement
    // where an id outlives the permission that produced it.
    FileAccess access = defined.reach(projects.effectiveExclusions(defined));
    if (access.roots().isEmpty()) {
      // Derived rather than recomputed. Asking which exclusion covers
      // which root would mean a second copy of `covering` living here, and
      // the two copies that drift are the pair deciding what an agent may
      // read. FileAccess.of dropping every root it was given is the same
      // fact, observed where it is already true.
      //
      // EVERY root is named, not "the workspace". With one root the
      // sentence is what it always was; with several, saying "the
      // workspace" would name the one directory an operator is least
      // likely to have got wrong -- the lent root they just added is the
      // suspect, and a refusal that hides it sends them to re-check a path
      // that was fine. `defined.roots()` and not `access.roots()`: the
      // whole point of this branch is that the latter is empty.
      String covered = String.join(", ", defined.roots().stream().map(Path::toString).toList());
      return new Leash(
          access,
          "project '"
              + project
              + "' reaches "
              + covered
              + ", but every one of those lies inside a path no project may reach, so"
              + " nothing in them is readable");
    }
    if (defined.serverProject()) {
      var writeExclusions = new java.util.ArrayList<>(projects.effectiveExclusions(defined));
      boolean application = Files.isRegularFile(defined.workspace().resolve("plowshare.json"));
      if (application) {
        writeExclusions.add(defined.workspace().resolve("server"));
        writeExclusions.add(defined.workspace().resolve("plowshare.json"));
      }
      FileAccess writes = FileAccess.of(defined.writeRoots(), writeExclusions);
      return new Leash(access, null, writes, defined.restrictedCommands() || application);
    }
    return new Leash(access, null);
  }

  /**
   * The same leash, once it is known that its roots are still on the disk.
   *
   * <p>Before containment, not after: a job whose workspace has disappeared is over, and telling it
   * that a path is "outside every root" would be true of a leash that no longer exists. {@link
   * FileAccess} cannot notice this — its roots are resolved when it is built and it compares a
   * deleted directory exactly as it compared it before — which is why its javadoc hands the
   * question here.
   *
   * <p>Two states and two sentences, because measured they are not the same state: {@code
   * Files.isDirectory} is false for both a deleted root and one replaced by a regular file, while
   * {@code Files.exists} tells them apart. Reporting a file that is sitting right there as absent
   * sends an operator looking for it — {@code ProjectStore.validWorkspace} refuses a dangling
   * symlink on the same argument.
   */
  private static Leash reachable(Leash leash) {
    for (Path root : leash.access().roots()) {
      if (!Files.exists(root)) {
        throw new WorkspaceUnavailableException("the root " + root + " is no longer there");
      }
      if (!Files.isDirectory(root)) {
        throw new WorkspaceUnavailableException("the root " + root + " is no longer a directory");
      }
    }
    return leash;
  }

  private static void refuseIfNothingIsReachable(Leash leash) {
    if (leash.absence() != null) {
      throw new WorkspaceRefusedException(leash.absence());
    }
  }

  /** The canonical path this operation may act on, or the refusal saying why not. */
  private static Path permitted(Leash leash, Path path, String verb) {
    refuseIfNothingIsReachable(leash);
    // Canonicalised once and handed to `permits`, so the path that is
    // checked is the path that is opened. FileAccess.canonical is public
    // precisely so that more than one thing can mean the same thing by it.
    Path target = FileAccess.canonical(path);
    if (!leash.access().permits(target)) {
      throw new WorkspaceRefusedException(
          "path "
              + path
              + " is outside every root this job"
              + " may "
              + verb
              + "; ask for the roots you have rather than guessing at"
              + " paths");
    }
    return target;
  }

  // --- globbing ------------------------------------------------------------

  /**
   * The matchers one pattern means, in the spelling a model writes.
   *
   * <p>{@link GlobSpellings} owns the whole of that — Java's {@code **} is not Python's, each
   * {@code **&#47;} expands into both readings, and there is a cap on how many. It lives in {@code
   * plowshare-protocol} because the client module searches a tree of its own now, and two
   * expansions of one pattern would return two different file sets into one {@code file_glob}
   * listing with nothing to say which reading each half used.
   *
   * <p>What is here is the translation into this seam's vocabulary: {@code GlobSpellings} raises
   * {@link IllegalArgumentException}, since {@code plowshare-protocol} has no refusal type and the
   * client answers over a wire. Its message is carried unchanged — it names the pattern and is
   * written to be true on either machine — and only the type changes, because an unchecked {@code
   * IllegalArgumentException} out of this seam would reach {@code JobRuntime} as "the tool failed"
   * rather than as something the model can correct.
   */
  private static List<PathMatcher> matchers(String pattern) {
    try {
      return GlobSpellings.matchers(pattern);
    } catch (GlobSpellings.Unusable unusable) {
      throw refused(unusable.result());
    }
  }

  /**
   * The hits under one root, with the two refusals that are this server's own.
   *
   * <p>{@link FileSearch#matching} owns the search: which candidates a pattern names, that a
   * directory is not a hit, and that the containment filter runs on the resolved path. Those three
   * change an <em>answer</em>, and a client that read any of them differently would put a different
   * file set into the same {@code file_glob} listing with nothing to say so — {@code FileSearch}
   * argues it at length, and it is {@code GlobSpellings}' rule one level down.
   *
   * <p>What is left here is the pair that produces a <em>refusal</em>, and both are properly this
   * machine's: how many hits it will return, and what it says about a directory of its own it
   * cannot read. A refusal is a sentence the model reads, so a laptop and a server may differ.
   */
  private static void walk(
      Path root, List<PathMatcher> matchers, FileAccess access, List<Path> hits) {
    try {
      FileSearch.matching(root, matchers, access, hits, MAX_MATCHES);
    } catch (FileSearch.TooManyMatches tooMany) {
      throw refused(FileResult.tooManyMatches(FileRequest.GLOB, tooMany.limit()));
    } catch (IOException failed) {
      // Named rather than skipped — a partial listing returned as a
      // complete one is the confident empty answer in miniature — and
      // refused rather than fatal, because one directory the server cannot
      // open is a pattern the model can narrow.
      throw refused(
          FileResult.failed(
              FileRequest.GLOB, FileResult.UNLISTABLE, root.toString(), null, failed.getMessage()));
    }
  }
}
