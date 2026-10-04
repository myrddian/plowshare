package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The layer above the seam: which filesystem a path belongs to.
 *
 * <h2>Fakes here, and a real provider in {@code FileToolsTest}</h2>
 *
 * <p>Every rule in this file is about <em>the router's</em> arithmetic over whatever a provider
 * says — how many cover, which could not be asked — and a fake is the only way to put a provider
 * into a state {@link LocalProvider} reaches only against a real disk and a real database. The one
 * thing a fake cannot hold is that {@code LocalProvider}'s actual sentences arrive: {@code
 * FileToolsTest} drives the whole path against a real store for that, which is the split {@code
 * LocalProviderTest} argues for from the other side.
 *
 * <h2>What is measured here rather than assumed — an index, not a second copy</h2>
 *
 * <ul>
 *   <li>{@code Path.startsWith} is by path element, so a root's name is not a string prefix of a
 *       sibling's &mdash; {@code a_root_is_not_a_string_prefix_of_its_siblings_name}, argued in
 *       {@code ProviderRouter.covers};
 *   <li>a root advertised real-pathed is not matched by a candidate spelled through a symlink
 *       &mdash; {@code a_path_spelled_through_a_link_routes_to_the_root_it_is_really_under}, argued
 *       at the {@code canonical} call in {@code ProviderRouter.providerFor}.
 * </ul>
 */
class ProviderRouterTest {

  private static final Home PAYMENTS = Home.of("payments");

  /**
   * Detached after each test, so a test that asserts on WARN lines cannot be reading an appender an
   * earlier test left attached.
   */
  private final List<Runnable> appenders = new ArrayList<>();

  @AfterEach
  void detachAppenders() {
    appenders.forEach(Runnable::run);
    appenders.clear();
  }

  @TempDir Path tmp;

  /**
   * {@link #tmp}, spelled the way it really is.
   *
   * <p>Every root a fake advertises below is built under this rather than under {@code tmp},
   * because <b>a provider advertises canonical roots</b> — {@code FileProvider.roots()} says so and
   * {@code LocalProviderTest}'s {@code a_projects_workspace_is_the_root_its_jobs_reach} holds
   * {@code LocalProvider} to it — while {@code @TempDir} hands out {@code /var/folders/…}, and
   * {@code /var} is a symlink to {@code /private/var} on this host. A fixture that skipped this
   * step advertised a root no canonicalised candidate could ever match, so four tests in the first
   * draft of this file passed while measuring nothing: the ambiguity refusal never fired, and the
   * string-prefix test was satisfied by a mismatch that had nothing to do with prefixes. Trap 4,
   * caught by running it.
   *
   * <p>{@code a_path_spelled_through_a_link_routes_to_the_root_it_is_really_under} is where the
   * other spelling still appears, because that is the one test the difference is the subject of.
   */
  private Path real;

  @BeforeEach
  void canonicalTree() throws IOException {
    real = tmp.toRealPath();
  }

  @Test
  void a_router_asked_about_nothing_says_so_where_the_bug_is() {
    // A null here is the wiring's fault, not a model's, so it fails at the
    // frame that made it rather than several frames down inside a file tool.
    assertThrows(NullPointerException.class, () -> new ProviderRouter(null));
    ProviderRouter router = over();
    assertThrows(NullPointerException.class, () -> router.providerFor(null, Path.of("/a")));
    assertThrows(NullPointerException.class, () -> router.providerFor(PAYMENTS, null));
    assertThrows(NullPointerException.class, () -> router.providersFor(null));
    assertThrows(NullPointerException.class, () -> ProviderRouter.absence(null));
  }

  // --- the fake ------------------------------------------------------------

  /**
   * A provider whose four answers are set per test.
   *
   * <p>{@code roots} and {@code glob} are settable independently and neither is derived from the
   * other, because the state this file is mostly about is exactly the one where they disagree:
   * {@link FileProvider#roots()} answers empty and {@code glob} raises the sentence saying why. A
   * fake that synthesised the refusal from an empty root list would be blind in that dimension — it
   * could not express a provider that answers a glob with no roots at all, which is the branch
   * {@code a_provider_that_will_not_say_why_it_has_no_root_is_still_reported} exists for.
   */
  private static final class Fake implements FileProvider {

    private final String name;
    private List<Path> roots = List.of();
    private RuntimeException rootsFail;
    private RuntimeException globFail;
    private int rootsCalls;

    Fake(String name) {
      this.name = name;
    }

    Fake covering(Path... paths) {
      this.roots = List.of(paths);
      return this;
    }

    /**
     * A provider nothing can be asked of, which is <b>every</b> call and not only {@code roots()}.
     *
     * <p>The first draft failed one call and left {@code glob} answering normally, which made
     * {@code a_provider_that_cannot_be_asked_at_all_is_not_an_absence} measure nothing at all: the
     * probe it is about goes through {@code glob}. Trap 2 — a helper blind in the dimension the
     * argument is about.
     */
    Fake unreachable(String why) {
      this.rootsFail = new WorkspaceUnavailableException(why);
      this.globFail = new WorkspaceUnavailableException(why);
      return this;
    }

    /**
     * Unreachable in the one way that has an ending of its own. Same shape as {@link #unreachable},
     * and every call for the same reason.
     */
    Fake gone(String why) {
      this.rootsFail = new SessionGoneException(why);
      this.globFail = new SessionGoneException(why);
      return this;
    }

    Fake explaining(String absence) {
      this.globFail = new WorkspaceRefusedException(absence);
      return this;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<Path> roots() {
      rootsCalls++;
      if (rootsFail != null) {
        throw rootsFail;
      }
      return roots;
    }

    @Override
    public Span read(Path path, Window window) {
      throw new UnsupportedOperationException("this file never routes as far as a read");
    }

    @Override
    public Span stat(Path path) {
      throw new UnsupportedOperationException("this file never routes as far as a stat");
    }

    @Override
    public List<Path> glob(String pattern) {
      if (globFail != null) {
        throw globFail;
      }
      return List.of();
    }

    @Override
    public Found grep(Needle needle, Path path) {
      throw new UnsupportedOperationException("this file never routes as far as a search");
    }

    @Override
    public Changed write(Path path, String content) {
      throw new UnsupportedOperationException("this file never routes as far as a write");
    }

    @Override
    public Changed create(Path path, String content) {
      throw new UnsupportedOperationException("this file never routes as far as a write");
    }

    @Override
    public Changed edit(Path path, String old, String replacement) {
      throw new UnsupportedOperationException("this file never routes as far as a write");
    }

    @Override
    public Changed delete(Path path) {
      throw new UnsupportedOperationException("this file never routes as far as a write");
    }

    @Override
    public Changed move(Path from, Path to) {
      throw new UnsupportedOperationException("this file never routes as far as a write");
    }

    @Override
    public io.aeyer.plowshare.protocol.CommandRunner.Outcome run(
        Path cwd,
        List<String> argv,
        io.aeyer.plowshare.protocol.EnvironmentFile.Side side,
        java.time.Duration timeout,
        java.util.function.BooleanSupplier cancelled) {
      throw new UnsupportedOperationException("no test here runs a command through a fake");
    }
  }

  private static ProviderRouter over(FileProvider... providers) {
    return new ProviderRouter(home -> List.of(providers));
  }

  // --- the refusal that carries the design ---------------------------------

  @Test
  void two_providers_covering_one_path_is_refused_with_both_named() {
    // The same string on two machines is two different files. A tiebreak
    // here would silently pick one, and the failure mode is reading the
    // wrong file and never knowing.
    ProviderRouter router =
        over(new Fake("local").covering(real), new Fake("remote").covering(real));

    AmbiguousPathException refused =
        assertThrows(
            AmbiguousPathException.class,
            () -> router.providerFor(PAYMENTS, real.resolve("a.java")));

    assertTrue(
        refused.getMessage().contains("local") && refused.getMessage().contains("remote"),
        "both providers are named: a refusal that says 'ambiguous' without saying"
            + " between what cannot be acted on — "
            + refused.getMessage());
  }

  @Test
  void an_ambiguous_path_is_something_the_model_reads_and_never_an_ending() {
    // On the correctable side of the split, so a file tool renders it and
    // the run goes on: everything is reachable and every other path on this
    // run still works. Asserted as a type relation rather than as prose,
    // because the tools catch WorkspaceRefusedException and nothing else.
    ProviderRouter router =
        over(new Fake("local").covering(real), new Fake("remote").covering(real));

    RuntimeException refused =
        assertThrows(
            AmbiguousPathException.class,
            () -> router.providerFor(PAYMENTS, real.resolve("a.java")));

    assertTrue(
        refused instanceof WorkspaceRefusedException,
        "an ambiguity is a refusal a tool renders, not an outage that ends the run");
    assertFalse(
        refused instanceof WorkspaceUnavailableException,
        "and not an outage: both machines answered, we simply will not guess");
  }

  // --- ordinary routing ----------------------------------------------------

  @Test
  void the_provider_whose_root_covers_the_path_is_the_one_returned() throws IOException {
    Path mine = Files.createDirectory(real.resolve("mine"));
    Path yours = Files.createDirectory(real.resolve("yours"));
    Fake local = new Fake("local").covering(mine);
    Fake remote = new Fake("remote").covering(yours);
    ProviderRouter router = over(local, remote);

    assertSame(local, router.providerFor(PAYMENTS, mine.resolve("a.java")));
    assertSame(
        remote,
        router.providerFor(PAYMENTS, yours.resolve("a.java")),
        "two providers with two roots: a router answering by list order would"
            + " pass the first of these and fail this one");
  }

  @Test
  void a_root_is_not_a_string_prefix_of_its_siblings_name() throws IOException {
    // Measured on JDK 21 and the reason `covers` uses startsWith rather than
    // comparing strings: startsWith is by path element, so /w/repo does not
    // contain /w/repository. A textual prefix test would route a sibling's
    // files into a workspace that was never granted them.
    Path repo = Files.createDirectory(real.resolve("repo"));
    Path repository = Files.createDirectory(real.resolve("repository"));
    ProviderRouter router = over(new Fake("local").covering(repo));

    assertThrows(
        WorkspaceRefusedException.class,
        () -> router.providerFor(PAYMENTS, repository.resolve("a.java")));
  }

  @Test
  void a_root_covers_itself() throws IOException {
    // startsWith is reflexive, measured, and the root itself is a path a
    // tool is asked about — file_glob over the root, file_write of a file
    // directly in it.
    Path repo = Files.createDirectory(real.resolve("repo"));
    Fake local = new Fake("local").covering(repo);

    assertSame(local, over(local).providerFor(PAYMENTS, repo));
  }

  @Test
  void a_path_spelled_through_a_link_routes_to_the_root_it_is_really_under() throws IOException {
    // A provider advertises its roots canonical — LocalProvider's come out
    // of FileAccess, which real-paths them — so a candidate compared as it
    // was typed misses every root on any host where the tree is reached
    // through a link. Measured in task 1 and again in task 2: /tmp is a
    // symlink to /private/tmp on this host, which is why every other test in
    // this file builds under a real-pathed tree and this one builds a second
    // spelling of its own on top of it.
    Path granted = Files.createDirectory(real.resolve("granted"));
    Path link = Files.createSymbolicLink(real.resolve("link"), granted);
    Fake local = new Fake("local").covering(granted);

    assertSame(
        local,
        over(local).providerFor(PAYMENTS, link.resolve("a.java")),
        "the candidate is canonicalised before it is compared, or a path spelled"
            + " through a link is outside a root it is plainly inside");
  }

  @Test
  void the_home_is_what_selects_the_providers() throws IOException {
    // A router is one object for every job on this server, so the tier is
    // not decoration: a source that ignored it would hand a job in one
    // project another project's filesystem, which is the containment the
    // whole slice is about.
    Path mine = Files.createDirectory(real.resolve("mine"));
    Path yours = Files.createDirectory(real.resolve("yours"));
    Fake payments = new Fake("local").covering(mine);
    Fake billing = new Fake("local").covering(yours);
    Map<Home, List<FileProvider>> byHome =
        Map.of(PAYMENTS, List.of(payments), Home.of("billing"), List.of(billing));
    ProviderRouter router = new ProviderRouter(home -> byHome.get(home));

    assertSame(payments, router.providerFor(PAYMENTS, mine.resolve("a.java")));
    assertThrows(
        WorkspaceRefusedException.class,
        () -> router.providerFor(Home.of("billing"), mine.resolve("a.java")),
        "billing's provider does not cover payments' workspace, and the router"
            + " asked for billing's providers because it was told billing");
  }

  @Test
  void the_roots_are_asked_for_again_on_every_call() throws IOException {
    // FileProvider.roots() says it may shrink or grow between calls and that
    // no caller may cache it. A router that cached would route on a leash
    // that had already been cut — the same failure LocalProvider re-reads
    // the projects table on every call to avoid.
    Path mine = Files.createDirectory(real.resolve("mine"));
    Fake local = new Fake("local").covering(mine);
    ProviderRouter router = over(local);

    router.providerFor(PAYMENTS, mine.resolve("a.java"));
    router.providerFor(PAYMENTS, mine.resolve("b.java"));

    assertEquals(2, local.rootsCalls, "the roots are re-read, not remembered");
  }

  // --- a provider that could not be asked ----------------------------------

  @Test
  void a_dead_provider_does_not_end_a_run_over_a_path_that_belongs_to_another() {
    // The problem task 3 handed this task by name. roots() raises when a
    // root has vanished, which is right for a job whose own workspace is
    // gone and wrong for a router: asking every provider in order to route
    // one path would end the run over a dead LOCAL workspace even for a path
    // that is plainly the remote's.
    //
    // The rule: a provider that could not be asked cannot serve anything, so
    // its candidacy is moot as long as a provider that COULD be asked covers
    // the path.
    Fake local = new Fake("local").unreachable("the workspace /gone is no longer there");
    Fake remote = new Fake("remote").covering(real);
    ProviderRouter router = over(local, remote);

    assertSame(remote, router.providerFor(PAYMENTS, real.resolve("a.java")));
  }

  @Test
  void a_provider_dropped_from_candidacy_leaves_a_trace() {
    // THE DEBT TASK 4 RECORDED, PAID. The rule above is right and it is not
    // free: when one provider covers the path and another could not be
    // asked, the remembered unavailability is discarded — so if the
    // unreachable one WOULD also have covered it, the model gets one
    // machine's file where the spec says both should have been named.
    //
    // That case cannot be refused, because an unreachable provider's
    // coverage is unknowable — that is what unreachable means — and it
    // cannot be tested either, for the same reason: the fake makes it
    // unknowable by construction, which is the thing itself rather than a
    // gap in the fixture.
    //
    // WHAT CAN BE TESTED IS THAT IT LEAVES A RECORD, and this is the
    // instrument for it. Without the record, a job that read the wrong
    // machine's file finishes looking perfectly healthy and nothing anywhere
    // says a second filesystem was never consulted.
    ListAppender<ILoggingEvent> recorded = recording();
    Fake local = new Fake("local").unreachable("the workspace /gone is no longer there");
    Fake remote = new Fake("remote").covering(real);

    over(local, remote).providerFor(PAYMENTS, real.resolve("a.java"));

    List<ILoggingEvent> warnings =
        recorded.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    assertEquals(1, warnings.size(), "one routing decision, one record — " + recorded.list);
    String said = warnings.get(0).getFormattedMessage();
    assertTrue(said.contains("a.java"), "the path it routed — " + said);
    assertTrue(said.contains("remote"), "where it routed it — " + said);
    assertTrue(
        said.contains("/gone is no longer there"),
        "and what could not be asked, which is the half an operator has to go and"
            + " look at — "
            + said);
  }

  @Test
  void an_ordinary_routing_call_leaves_no_trace_at_all() {
    // The other half of the pair, and the reason the level is warn. A line
    // written on every file operation would be noise an operator filters
    // out, and filtering it out is filtering out the one case it exists for.
    ListAppender<ILoggingEvent> recorded = recording();

    over(new Fake("local").covering(real)).providerFor(PAYMENTS, real.resolve("a.java"));

    assertEquals(
        List.of(), recorded.list.stream().filter(event -> event.getLevel() == Level.WARN).toList());
  }

  /**
   * A logback appender on {@link ProviderRouter}'s own logger, detached when the test method ends.
   * Measured rather than assumed that it records anything: {@code
   * an_ordinary_routing_call_leaves_no_trace_at_all} would pass against an appender that captured
   * nothing, and {@code a_provider_dropped_from_candidacy_leaves_a_trace} would not.
   */
  private ListAppender<ILoggingEvent> recording() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProviderRouter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    appenders.add(() -> logger.detachAppender(appender));
    return appender;
  }

  @Test
  void a_dead_provider_ends_the_run_when_nothing_that_answered_covers_the_path() {
    // The other half, and it is why the rule above is not "ignore a dead
    // provider". Its roots are unknown, so "outside every root" would be a
    // claim about roots nobody could read — the confident wrong answer this
    // project exists to avoid. The unavailability is what propagates, and it
    // carries its own sentence rather than a new one invented here.
    Fake local = new Fake("local").unreachable("the workspace /gone is no longer there");
    Fake remote = new Fake("remote").covering(real.resolve("elsewhere"));
    ProviderRouter router = over(local, remote);

    WorkspaceUnavailableException gone =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> router.providerFor(PAYMENTS, real.resolve("a.java")));

    assertTrue(
        gone.getMessage().contains("no longer there"),
        "the provider's own sentence travels with the ending — " + gone.getMessage());
  }

  @Test
  void a_session_that_went_away_is_swallowed_when_another_provider_covers_the_path() {
    // THE COST SIDE OF THE SUBCLASS, PINNED AS INTENDED RATHER THAN LEFT TO
    // LOOK LIKE AN OVERSIGHT. SessionGoneException extends
    // WorkspaceUnavailableException, so it is caught by STRICTLY MORE
    // handlers than a sibling type would have been — and the catch above
    // `continue`s. A client that disconnected is therefore dropped from
    // candidacy silently whenever another provider covers the path, where a
    // sibling would have propagated and ended the run.
    //
    // That is the right answer and it is this class's documented rule, not a
    // special case for this type: the file was reachable, and a provider
    // that could not be asked serves nothing either way. But it is the one
    // place the seventh ending can be lost by design, so what is asserted
    // here is that the run goes on.
    //
    // WHAT IS NOT ASSERTED HERE IS THE TRACE, and this sentence used to
    // claim it was. a_provider_dropped_from_candidacy_leaves_a_trace owns
    // that, over the same return, and duplicating it would be two places
    // holding one fact — but a comment claiming an assertion its body does
    // not make is itself an unmeasured measurement, which is the rule this
    // file applies to everything else.
    Fake laptop =
        new Fake("remote")
            .gone(
                "the session 'laptop' closed while this was"
                    + " waiting, so the files it owned cannot be reached");
    Fake local = new Fake("local").covering(real);
    ProviderRouter router = over(laptop, local);

    assertSame(
        local,
        router.providerFor(PAYMENTS, real.resolve("a.java")),
        "the run goes on over the filesystem that could be asked");
  }

  @Test
  void which_of_two_dead_providers_is_reported_decides_the_ending_as_well() {
    // First-unavailability-wins is this class's rule and it now decides more
    // than a sentence: one of these two types ends the run SESSION_GONE and
    // the other ends it UNAVAILABLE, so ITERATION ORDER PICKS THE ENDING.
    //
    // Both directions, because a router that always reported the first would
    // pass either half alone. The downgrade — a local disk that failed
    // before a remote session went — is safe in direction (the run stops
    // either way, and there really was a local disk that could not be asked)
    // and is the first place to look if a session-gone run ever reports the
    // wrong thing.
    Path elsewhere = real.resolve("elsewhere");

    Fake deadDisk = new Fake("local").unreachable("the workspace /gone is no longer there");
    Fake wentAway =
        new Fake("remote").gone("the session 'laptop' closed while this was" + " waiting");

    WorkspaceUnavailableException diskFirst =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> over(deadDisk, wentAway).providerFor(PAYMENTS, elsewhere.resolve("a.java")));
    assertFalse(
        diskFirst instanceof SessionGoneException,
        "the disk failed first, so its sentence and its ending are the ones reported — "
            + diskFirst.getMessage());
    assertTrue(diskFirst.getMessage().contains("no longer there"), diskFirst.getMessage());

    WorkspaceUnavailableException sessionFirst =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> over(wentAway, deadDisk).providerFor(PAYMENTS, elsewhere.resolve("a.java")));
    assertTrue(
        sessionFirst instanceof SessionGoneException,
        "and the same two providers the other way round reach the seventh ending — "
            + sessionFirst.getMessage());
  }

  @Test
  void the_first_dead_provider_is_the_one_reported_when_two_are_dead() {
    // Deterministic on purpose: two sentences would be a report whose
    // content depended on iteration order, and an operator reading a job's
    // ending needs the same sentence every time it happens.
    // Both are DISKS. The second said "the session that owns /b has gone"
    // until task 8 gave that sentence an ending of its own — a plain
    // WorkspaceUnavailableException wearing a session-gone sentence, in the
    // file that now turns on telling those apart.
    Fake local = new Fake("local").unreachable("the workspace /a is no longer there");
    Fake remote = new Fake("remote").unreachable("the workspace /b is no longer there");
    ProviderRouter router = over(local, remote);

    WorkspaceUnavailableException gone =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> router.providerFor(PAYMENTS, real.resolve("a.java")));

    assertTrue(gone.getMessage().contains("/a"), gone.getMessage());
    assertFalse(
        gone.getMessage().contains("/b"),
        "the first in the list, not both merged into one sentence");
  }

  // --- a path nothing covers -----------------------------------------------

  @Test
  void a_path_no_provider_covers_is_refused_with_the_roots_that_do_exist() throws IOException {
    Path mine = Files.createDirectory(real.resolve("mine"));
    ProviderRouter router = over(new Fake("local").covering(mine));

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> router.providerFor(PAYMENTS, Path.of("/etc/passwd")));

    assertTrue(
        refused.getMessage().contains("outside"),
        "the model is told why, in prose — " + refused.getMessage());
    assertTrue(
        refused.getMessage().contains(mine.toString()),
        "and told where it may look instead, which is the one thing that turns"
            + " the refusal into a next move — "
            + refused.getMessage());
  }

  @Test
  void when_nothing_has_a_root_at_all_the_refusal_is_the_providers_own_sentence() {
    // Trap 7: "outside every root" names a situation that does not hold when
    // there is no root to be outside of. The states behind an empty root
    // list are enumerated by LocalProvider, they are a different person's
    // fix apiece, and only the provider knows which one it is in.
    //
    // Not copied here, and it was — as "five", which is the count of states
    // with no root and NOT the count that reaches this branch: one of them
    // raises instead of answering empty, so four get this far.
    ProviderRouter router =
        over(
            new Fake("local")
                .explaining("this job runs in the global tier, which is every project at once"));

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> router.providerFor(PAYMENTS, Path.of("/etc/passwd")));

    assertTrue(
        refused.getMessage().contains("global tier"),
        "the state is named — " + refused.getMessage());
    assertFalse(
        refused.getMessage().contains("outside"),
        "and not dressed as an out-of-scope path, which would send the model"
            + " guessing at other paths for the rest of its turns");
  }

  @Test
  void a_provider_with_no_root_is_still_accounted_for_beside_one_that_has_them()
      throws IOException {
    // The mixed case, which neither test above reaches: one provider can
    // say where to look while the other says why it is empty, and dropping
    // either half leaves the model with an incomplete picture of its own
    // reach.
    Path mine = Files.createDirectory(real.resolve("mine"));
    ProviderRouter router =
        over(
            new Fake("local").covering(mine),
            new Fake("remote").explaining("no session owns this run, so there is no laptop"));

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> router.providerFor(PAYMENTS, Path.of("/etc/passwd")));

    assertTrue(refused.getMessage().contains(mine.toString()), refused.getMessage());
    assertTrue(refused.getMessage().contains("no laptop"), refused.getMessage());
  }

  @Test
  void a_run_with_no_providers_at_all_says_that_rather_than_naming_none() {
    // Reachable through the wiring rather than through a projects row: a job
    // built with no provider list has no filesystem, and a message listing
    // the roots of nobody would read as an ordinary out-of-scope path.
    ProviderRouter router = over();

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> router.providerFor(PAYMENTS, Path.of("/etc/passwd")));

    assertTrue(refused.getMessage().contains("no filesystem"), refused.getMessage());
  }

  // --- the sentence behind an empty root list ------------------------------

  @Test
  void a_providers_own_sentence_is_what_says_why_it_has_no_root() {
    // How file_roots reaches the absence sentence, and the reason it needs
    // no new method on FileProvider. The interface already promises that a
    // provider with no roots RAISES rather than answering an empty list, so
    // asking it to search is asking it to say which state it is in — of the
    // ones LocalProvider enumerates, minus the one that raises before a root
    // list exists at all. This is the one owner of that trick;
    // ProviderRouter's own refusal and FileTools.Roots both come through
    // here.
    Fake local = new Fake("local").explaining("no workspace is defined for project 'payments'");

    assertEquals("no workspace is defined for project 'payments'", ProviderRouter.absence(local));
  }

  @Test
  void a_provider_that_will_not_say_why_it_has_no_root_is_still_reported() {
    // A provider that answers the probe instead of raising is one breaking
    // FileProvider's contract, and the honest report is that it would not
    // say. Returning null or a blank string here would surface as a
    // file_roots result with a hole in it, which reads to a model as a tool
    // that does not work.
    String said = ProviderRouter.absence(new Fake("local"));

    assertFalse(
        said.isBlank(),
        "never blank: a tool result with a hole in it reads" + " to a model as a broken tool");
    assertTrue(said.contains("no reason"), said);
  }

  @Test
  void a_provider_that_cannot_be_asked_at_all_is_not_an_absence() {
    // The split, at the one place it would be easiest to lose: a probe that
    // caught everything would turn a vanished workspace into a sentence
    // rendered inside a perfectly ordinary file_roots answer.
    Fake local = new Fake("local").unreachable("the workspace /gone is no longer there");

    assertThrows(WorkspaceUnavailableException.class, () -> ProviderRouter.absence(local));
  }

  // --- what the tools ask for ----------------------------------------------

  @Test
  void the_providers_for_a_home_are_listed_for_the_tool_that_asks_all_of_them() {
    // file_roots has no path to route, so it needs the set rather than one
    // member of it.
    Fake local = new Fake("local");
    Fake remote = new Fake("remote");
    List<FileProvider> asked = new ArrayList<>(over(local, remote).providersFor(PAYMENTS));

    assertEquals(List.of(local, remote), asked);
  }
}
