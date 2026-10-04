package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.FileAccess;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One row of {@code projects}: a project's name, the directory it <em>is</em>, the further
 * directories lent at that place, and the paths inside them its jobs may not reach.
 *
 * <p>{@code workspace} and every element of {@code lent} are absolute and normalised, never as the
 * caller spelled them. A relative path is resolved against the server's working directory
 * <em>once</em>, when it is written, so the leash cannot move later because the process was started
 * somewhere else.
 *
 * <h2>Two components, two jobs, and that is why there are two</h2>
 *
 * <p><b>{@code workspace} is the identity; {@link #roots()} is the leash.</b> {@code workspace} is
 * the {@code <PATH>} of {@code Presence.canonicalName()} — {@code <MACHINE>/<PATH>/<PROJ_NAME>} —
 * and the column {@code projects_machine_has_a_place} keys on. {@code lent} is the rest of what the
 * project may read at that place, and <b>nothing composing an identity may read it</b>: a canonical
 * name built from a list renames the project whenever the list reorders, and V14 exists so that a
 * rename is survivable rather than routine. V30 carries the whole argument.
 *
 * <p>So a caller wanting "where is this project" asks {@link #workspace()}, and a caller wanting
 * "what may its jobs read" asks {@link #roots()}. Neither question is answered by the other
 * component, and the accessor for the leash is a method rather than a component precisely so there
 * is no third list anybody can build by hand.
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
    List<String> writePaths) {

  public ProjectRecord(String name, Path workspace, List<Path> lent, List<Path> exclusions) {
    this(name, workspace, lent, exclusions, "STANDARD", List.of("."));
  }

  public boolean readOnly() {
    return writePaths.isEmpty();
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
    return List.copyOf(roots);
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
