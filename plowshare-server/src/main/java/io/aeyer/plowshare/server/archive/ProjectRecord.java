package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.FileAccess;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A project's primary source directory and the filesystem boundaries admitted on this host.
 *
 * <p>For legacy records, {@code workspace} is the stored absolute primary source path and {@code
 * lent} adds readable roots. Their existing meaning is preserved until explicit Application
 * adoption. Client Workspace views are permitted operations over filesystem locations; they are not
 * Application identities.
 *
 * <p>For alias-based Applications, {@code placement} is the durable alias/relative-path contract.
 * The repository resolves it against this host's FileStores on each read, supplying {@code
 * workspace} as the current Application root and {@code areaRoots} as the admitted writable areas.
 * An unavailable alias is refused rather than falling back to the historical absolute path. {@link
 * #roots()} includes source and admitted writable areas; {@link #writeRoots()} includes only
 * writable areas. User FileStore access and agent grants remain separate checks.
 *
 * <p><b>{@link #exclusions()} is the row's own list and is not the containment set.</b> The
 * mandatory exclusions — the server's configuration, the sampling profiles, the operator token, the
 * directory ejected conversation payloads are written to, and the data directory that holds every
 * agent and bot definition — are not stored in the row, precisely so that a row cannot drop them;
 * they are added by {@link ProjectStore#effectiveExclusions}, which is what anything enforcing
 * containment has to ask. A caller that builds a check from this component alone has built one a
 * {@code projects} row can disable, which is the failure the split exists to make impossible. That
 * hazard is <b>not</b> lifted by {@code lent}: a lent root is a root like any other and {@code
 * FileAccess.of} drops it when a mandatory exclusion is an ancestor of it or is it, which is what
 * makes lending the definitions tree reach nothing.
 *
 * <p><b>Ancestor-or-equal, and the other side of that line is worth stating because it is where an
 * operator will stand.</b> An exclusion <em>below</em> a lent root does not drop the root: lending
 * {@code /etc/plowshare}, which holds the configured {@code application.yml}, keeps the root and
 * refuses that one file on {@code permits}' longest match. So the fence survives either way, but
 * the two arrangements answer differently about everything else in the directory, and only one of
 * them is "reaches nothing". {@code
 * LocalProviderTest.a_mandatory_exclusion_still_beats_a_lent_root} measures both halves against a
 * real filesystem.
 *
 * @param lent the further directories lent at this project's place, in the order they were set. May
 *     be empty, which is the ordinary case; may name a directory outside {@code workspace}, which
 *     is the whole feature
 */
public record ProjectRecord(
    String name,
    Path workspace,
    List<Path> lent,
    List<Path> exclusions,
    String type,
    List<String> writePaths,
    ApplicationPlacement placement,
    List<Path> areaRoots) {

  /** Legacy paths retain their source-relative meaning until explicitly adopted. */
  public ProjectRecord(
      String name,
      Path workspace,
      List<Path> lent,
      List<Path> exclusions,
      String type,
      List<String> writePaths) {
    this(name, workspace, lent, exclusions, type, writePaths, null, List.of());
  }

  public ProjectRecord(String name, Path workspace, List<Path> lent, List<Path> exclusions) {
    this(name, workspace, lent, exclusions, "STANDARD", List.of("."));
  }

  public boolean readOnly() {
    return placement == null ? writePaths.isEmpty() : placement.writableAreas().isEmpty();
  }

  public boolean serverProject() {
    return !type.equals("STANDARD");
  }

  /**
   * Copies both lists, because this record is handed to callers that outlive the store call: a
   * mutable exclusions list is a leash the holder can lengthen, and a mutable {@code lent} list is
   * one they can lengthen from the other end.
   */
  public ProjectRecord {
    writePaths = List.copyOf(writePaths);
    lent = List.copyOf(lent);
    exclusions = List.copyOf(exclusions);
    areaRoots = List.copyOf(areaRoots);
    if (placement == null && !areaRoots.isEmpty())
      throw new IllegalArgumentException("Resolved areas need an Application placement");
  }

  /**
   * Everything this project's jobs may reach, before exclusions: the workspace first, then whatever
   * else is lent at that place.
   *
   * <p><b>This, and never {@code List.of(workspace())}, is what a leash is built from.</b> The
   * singleton was correct while a project had one directory and is now the mutation that makes
   * every lent root unreachable while every test about the workspace stays green — which is exactly
   * the shape {@code a_second_lent_root_makes_a_hidden_directory_readable} exists to catch.
   *
   * <p><b>Prepended and not merged, and the order is load-bearing in one place only.</b> {@code
   * FileAccess.permits} resolves by deepest covering root, so order cannot change what is
   * permitted. But {@code file_roots} renders this list to a model, and the project's own place
   * should be the first thing it reads; and {@code Plowshare.rooting} already means "the place" by
   * {@code get(0)} of the roots a client lends, so the two paths agree about what first means.
   *
   * <p>Not deduplicated and not sorted. Sorting would destroy the one property the order carries;
   * deduplicating would be a rule with no failure behind it — a root repeated is a root repeated,
   * {@code permits} answers identically, and {@code file_roots} shows a person the duplicate they
   * wrote.
   */
  public List<Path> roots() {
    List<Path> roots = new ArrayList<>(lent.size() + 1);
    roots.add(workspace);
    roots.addAll(lent);
    roots.addAll(areaRoots);
    return List.copyOf(roots);
  }

  /**
   * Admitted runtime write roots; account grants and mandatory exclusions further restrict them.
   */
  public List<Path> writeRoots() {
    if (placement != null) return areaRoots;
    return writePaths.stream()
        .map(path -> FileAccess.canonical(workspace.resolve(path)))
        .filter(path -> path.startsWith(FileAccess.canonical(workspace)))
        .toList();
  }

  /** A working directory is not a sandbox for writes across multiple FileStores. */
  public boolean restrictedCommands() {
    return placement != null || !writePaths.contains(".");
  }

  /**
   * What this project's jobs may reach, as the one object every containment check is made of.
   *
   * <h2>One expression, because the copy that drifts decides what an agent may read</h2>
   *
   * <p>Two things ask it. {@code LocalProvider} builds one before every file operation, and {@code
   * ImageStore} builds one when a picture named out of one of these files is resolved — an id must
   * not outlive the permission that produced it, so the fence a resolution re-asks has to be the
   * fence the read was allowed through. Two call sites pairing the roots with the exclusions by
   * hand is the arrangement where one of them quietly stops matching the other.
   *
   * <p>It is {@link ProjectStore#effectiveExclusions(ProjectRecord)}'s argument one level up: that
   * method exists so nobody re-adds the mandatory list by hand, and this one exists so nobody
   * re-pairs it with the roots by hand.
   *
   * <p><b>On the record and not on the store</b>, and that is not where it started. A {@code
   * ProjectStore.fence(row)} was written first and measured wrong: three suites mock the store, so
   * the rule became something a mock could answer for, and a containment expression a test can
   * replace is not a containment expression. A record is final — there is no mock of this — so
   * every caller gets the real pairing or does not compile.
   *
   * <p><b>Never {@code withServerOwned}, and the roots go in as <em>workspaces</em>.</b> {@code
   * LocalProvider}'s class javadoc says what the other spelling costs: a {@code projects} row
   * pointing inside the data directory would become a grant over every agent's definition.
   *
   * @param excluded what {@link ProjectStore#effectiveExclusions(ProjectRecord)} answers for this
   *     row — the row's own list <em>and</em> the ones no row may override. Passed in rather than
   *     reached for, because this record is the row and the rule is the store's
   */
  public FileAccess reach(List<Path> excluded) {
    return FileAccess.of(roots(), excluded);
  }
}
