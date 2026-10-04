package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.union.UnionRouting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project is served by the one live session that roots it, not by whoever asked.
 *
 * <p>{@code RemoteWiringTest} measured the rule this replaces — a run reaching the machine that
 * <em>submitted</em> it — and it still measures everything underneath: the role gate, the ambiguity
 * refusal, the real socket. What is here is the one thing that changed, which is <b>which session
 * the remote provider is built from</b>.
 *
 * <h2>Three answers, and only one of them is new</h2>
 *
 * <ul>
 *   <li><b>a named project with a presence</b> — the presence's session, whoever submitted. This is
 *       the feature;
 *   <li><b>a named project with no presence</b> — the server's own disk and a provider that exists
 *       to say the project is rooted nowhere. {@code RunProviders.forRun}'s rule extended: a
 *       smaller set, not an empty capability, and the refusal names the project rather than
 *       reporting a missing file;
 *   <li><b>the global tier</b> — the caller's own session, exactly as before. <b>Global is the
 *       absence of a project</b>, so there is no place to root and no presence to ask; a run that
 *       is not place-bound can only mean the machine it was submitted from.
 * </ul>
 *
 * <h2>The fixture holds two machines throughout</h2>
 *
 * <p>Deliberately, and for {@code RemoteWiringTest}'s reason one step further along: with one
 * client attached, "it picked the machine that roots the project" and "it picked the only machine
 * there was" are the same observation. Every routing assertion here is made with a second, equally
 * live client present and holding a file provider of its own.
 */
class PresenceRoutingTest {

  private static final Home PAYMENTS = Home.of("payments");

  private static final List<Grant> READ = List.of(new Grant(Scope.WORKSPACE, Mode.READ));

  /**
   * The server's own side of {@code payments}: a real, empty directory.
   *
   * <p><b>Real and not a name, and the difference was measured.</b> With a path that does not
   * exist, {@code LocalProvider.roots()} raises {@code WorkspaceUnavailableException} — the
   * workspace vanished — and {@code ProviderRouter} rethrows it, because no provider that could be
   * asked covers the path. That is the router's rule working correctly and it hides the sentence
   * this file is about. A project whose server-side workspace is simply absent is the ordinary
   * shape and it refuses rather than failing, so an empty directory is the fixture that reaches the
   * account.
   *
   * <p><b>That measurement is now a bug with a name and a test</b> — {@link
   * #an_absent_presence_is_not_pre_empted_by_a_workspace_the_server_does_not_have} — and this field
   * stays a real directory because it is the <em>other</em> half of the pair: the case where this
   * server genuinely does root the project, and where nothing about the routing changes.
   */
  @TempDir Path serverSide;

  private final SessionRegistry sessions = new SessionRegistry();

  private final PresenceRegistry presences = new PresenceRegistry();

  private final Recording channel = new Recording();

  // --- the feature ---------------------------------------------------------

  @Test
  void a_run_reaches_the_machine_that_roots_the_project_and_not_the_one_that_asked() {
    attached("bench");
    attached("desk");
    presences.declare(new Presence("bench", "bench.local", "/srv/payments", "payments"));

    remoteOf(wiring().forRun(PAYMENTS, READ, "desk", null)).roots();

    assertEquals(
        List.of("bench"),
        channel.asked(),
        "the request went to the session that roots 'payments', although the run was"
            + " submitted from the other one");
  }

  @Test
  void the_machine_that_roots_a_project_serves_a_run_submitted_with_no_session_at_all() {
    attached("bench");
    presences.declare(new Presence("bench", "bench.local", "/srv/payments", "payments"));

    remoteOf(wiring().forRun(PAYMENTS, READ, null, null)).roots();

    assertEquals(
        List.of("bench"),
        channel.asked(),
        "a curator's pass or a scheduled tick has no client machine of its own, and"
            + " under presence that is no longer a reason for it to have no"
            + " filesystem but the server's");
  }

  @Test
  void a_run_in_a_project_this_session_does_not_root_does_not_reach_this_session() {
    attached("desk");

    assertEquals(
        List.of("local"),
        names(wiring().forRun(PAYMENTS, READ, "desk", null)),
        "holding a disk is not rooting a project: the caller's own machine is reached"
            + " because it roots the project, and never because it asked");
  }

  // --- no presence ---------------------------------------------------------

  @Test
  void a_project_nothing_roots_is_named_in_the_refusal_rather_than_reported_as_a_missing_file() {
    FileProvider absent =
        rootedOn("bench.local", serverSide).forRun(PAYMENTS, READ, null, null).get(0);

    WorkspaceRefusedException said =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> absent.read(Path.of("/srv/payments/Main.java"), Window.of(0, 50)));

    assertTrue(
        said.getMessage().contains("payments"), "the project is named: " + said.getMessage());
    assertTrue(
        said.getMessage().contains("presence"),
        "and what is missing is a presence rather than a file: " + said.getMessage());
  }

  @Test
  void the_absent_presence_advertises_no_root_and_explains_itself_when_asked_why() {
    FileProvider absent =
        rootedOn("bench.local", serverSide).forRun(PAYMENTS, READ, null, null).get(0);

    assertEquals(List.of(), absent.roots(), "no root, because there is no machine to have one on");
    assertTrue(
        ProviderRouter.absence(absent).contains("payments"),
        "and file_roots reaches the sentence through the same door LocalProvider's"
            + " absences use: "
            + ProviderRouter.absence(absent));
  }

  @Test
  void a_path_in_an_unrooted_project_is_refused_by_a_sentence_that_says_which_project() {
    ProviderRouter router =
        new ProviderRouter(
            home -> rootedOn("bench.local", serverSide).forRun(home, READ, "desk", null));
    attached("desk");

    WorkspaceRefusedException said =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> router.providerFor(PAYMENTS, Path.of("/srv/payments/Main.java")));

    assertTrue(
        said.getMessage().contains("payments"),
        "the account the router assembles carries the presence's sentence, so a model"
            + " is told what to fix: "
            + said.getMessage());
  }

  @Test
  void a_server_owned_root_is_readable_without_a_client_or_a_presence_warning() throws Exception {
    Files.writeString(serverSide.resolve("probe.txt"), "server-root-ok");
    RunProviders providers = wiring();
    ProviderRouter router = new ProviderRouter(home -> providers.forRun(home, READ, null, null));
    String roots = new FileTools.Roots(router).run("{}", PAYMENTS);
    assertTrue(roots.contains(serverSide.toString()), roots);
    assertFalse(roots.contains("presence"), roots);
    String listing = new FileTools.Glob(router).run("{\"pattern\":\"**/*\"}", PAYMENTS);
    assertTrue(listing.contains("probe.txt"), listing);
    assertFalse(listing.contains("presence"), listing);
    assertEquals(
        List.of("server-root-ok"),
        router
            .providerFor(PAYMENTS, serverSide.resolve("probe.txt"))
            .read(serverSide.resolve("probe.txt"), Window.of(0, 50))
            .lines());
  }

  // --- no presence, and no workspace on this server either ------------------

  /**
   * The bug the {@link #serverSide} field's javadoc recorded as a fixture choice, promoted to the
   * thing under test.
   *
   * <p><b>Presence makes this the common case rather than an edge.</b> A project whose files are on
   * somebody's laptop has no directory of that name on the server, so a {@code projects} row
   * pointed at one names a path this machine does not have — and {@code LocalProvider.roots()} then
   * raises rather than answering. {@code AbsentPresence} covers no path, so {@link
   * ProviderRouter}'s rule — a provider that could not be asked is fatal only when no provider that
   * could be asked covers the path — makes it fatal, the run ends {@code UNAVAILABLE}, and <b>the
   * model is never told that no presence serves the project</b>. The local outage pre-empts the
   * diagnosis.
   *
   * <p>The rule is right and is not what changes. What changes is upstream of it: a server that
   * does not hold the project's workspace is not the machine that roots the project, so it has no
   * business being in the set as a provider whose failure can end the run.
   */
  @Test
  void an_absent_presence_is_not_pre_empted_by_a_workspace_the_server_does_not_have() {
    Path notHere = serverSide.resolve("only-on-the-laptop");
    ProviderRouter router =
        new ProviderRouter(home -> wiring(notHere).forRun(home, READ, "desk", null));
    attached("desk");

    WorkspaceRefusedException said =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> router.providerFor(PAYMENTS, Path.of("/srv/payments/Main.java")),
            "a refusal the model can act on, and never an unavailability that ends the run"
                + " before it is read");

    assertTrue(
        said.getMessage().contains("payments"), "the project is named: " + said.getMessage());
    assertTrue(
        said.getMessage().contains("presence"),
        "and the diagnosis is that nothing roots it: " + said.getMessage());
  }

  /**
   * Both facts, because an operator needs both and they have different fixes.
   *
   * <p>The two states are byte-identical in the row — a project that lives on a laptop and a
   * project that lived here until somebody deleted the directory are one {@code projects} row with
   * one workspace this server cannot reach — so the honest answer names the server's own failure
   * alongside the absence rather than choosing between them.
   */
  @Test
  void the_refusal_says_the_server_does_not_hold_the_workspace_either() {
    Path notHere = serverSide.resolve("only-on-the-laptop");

    FileProvider absent = wiring(notHere).forRun(PAYMENTS, READ, null, null).get(0);

    String said = ProviderRouter.absence(absent);
    assertTrue(
        said.contains(notHere.toString()),
        "the directory the server was told to look in is named, because re-pointing it"
            + " is the other fix: "
            + said);
  }

  // --- and no presence, because the project lives on another machine ---------

  /**
   * <b>The two states §13.3 recorded as byte-identical, separated.</b>
   *
   * <p>That section could only say the thing it could not fix: "a project that lives on a laptop
   * and one that lived here until somebody deleted the directory are the same {@code workspace}
   * this server cannot reach", so the account named both possible fixes because neither could be
   * ruled out. V15 stores the discriminator instead of inferring it, and a project whose row names
   * another machine is not an outage of anything — <b>nothing is broken</b>, the files are simply
   * somewhere else and a client there is the only thing that can serve them.
   *
   * <p>So the account must not tell an operator to point the project at a directory on this server.
   * That is §13.4's complaint verbatim — "advice that cannot work for it" — and it is the half a
   * probe could never get right.
   */
  @Test
  void a_project_whose_row_names_another_machine_says_so_rather_than_offering_this_disk() {
    FileProvider absent = rootedOn("bench.local").forRun(PAYMENTS, READ, null, null).get(0);

    String said = ProviderRouter.absence(absent);
    assertTrue(
        said.contains("bench.local"),
        "the machine that holds the project is named, which is the fact a probe could"
            + " never recover: "
            + said);
    assertFalse(
        said.contains("pointed at one this server has"),
        "and the server does not offer a directory of its own for a project whose files"
            + " are not on it: "
            + said);
  }

  /**
   * The local provider is left out for the reason it is left out of the other degraded case: the
   * only thing it could do from here is raise, and under {@code ProviderRouter}'s rule a provider
   * that raises while nothing else covers the path ends the run before the absence beside it is
   * ever read.
   */
  @Test
  void a_project_rooted_elsewhere_gets_the_absence_alone() {
    assertEquals(
        List.of("presence"),
        names(rootedOn("bench.local").forRun(PAYMENTS, READ, null, null)),
        "a provider with no claim to serve the project is not in the set");
  }

  /**
   * <b>The ordinary shape of a laptop project, and a measured bug.</b> `/here` writes the row with
   * the laptop's machine and the laptop stays attached, so the set used to be {@code local} and
   * {@code remote}. {@code file_roots} then answered "local: no workspace is defined for project
   * 'character-storyteller'; whoever runs this server points a project at a directory" above the
   * one root there was, and the model read the first line and told the person it had no project
   * (cnv_313AE867D7AEA6EE). The local provider is left out here for the same reason it is left out
   * when nothing is attached: it has no claim to serve a project whose files are on another
   * machine, and all it can say about one is untrue.
   */
  @Test
  void a_project_rooted_elsewhere_and_served_gets_the_remote_alone() {
    attached("bench");
    presences.declare(new Presence("bench", "bench.local", "/srv/payments", "payments"));

    assertEquals(
        List.of("remote"),
        names(rootedOn("bench.local").forRun(PAYMENTS, READ, "desk", null)),
        "the machine that holds the files is the only filesystem the project has");
  }

  /**
   * And it is decided by the column rather than by whether a directory happens to be here.
   *
   * <p><b>The fixture is the trap.</b> This server does hold a directory at the path the row names
   * — the two machines have the same layout, which is the ordinary case when somebody clones one
   * repository on both — so the probe that {@code serverCannotServe} used to be answers "the server
   * can serve this" and hands an agent the wrong tree under a leash that looks right.
   */
  @Test
  void a_directory_of_the_same_name_on_this_server_is_not_the_projects_files() {
    assertEquals(
        List.of("presence"),
        names(rootedOn("bench.local", serverSide).forRun(PAYMENTS, READ, null, null)),
        "the same string on two machines is two different files, which is"
            + " ProviderRouter's own sentence one layer up");
  }

  /**
   * The half that must not regress: <b>a project this server genuinely does root is served exactly
   * as before</b>, and its workspace going away is still an outage that ends the run rather than a
   * sentence about a presence.
   *
   * <p>Which of the two applies is decided by whether this server holds the project's workspace,
   * and that is asked once, in the seam, rather than discovered when a provider raises.
   */
  @Test
  void a_workspace_this_server_does_hold_is_still_the_servers_to_serve_and_to_lose() {
    assertEquals(
        List.of("local"),
        names(wiring().forRun(PAYMENTS, READ, null, null)),
        "the server's own disk is in the set for a project it holds the workspace for");
  }

  /**
   * And the rule itself is untouched. With a presence rooting the project the set is {@code local}
   * and {@code remote} exactly as before, so a local workspace that has gone is still remembered by
   * the router and still ends the run when nothing that answered covers the path.
   */
  @Test
  void a_rooted_project_keeps_the_routers_rule_over_a_local_workspace_that_has_gone() {
    Path notHere = serverSide.resolve("only-on-the-laptop");
    attached("bench");
    presences.declare(new Presence("bench", "bench.local", "/srv/payments", "payments"));
    ProviderRouter router =
        new ProviderRouter(home -> wiring(notHere).forRun(home, READ, "desk", null));

    assertThrows(
        WorkspaceUnavailableException.class,
        () -> router.providerFor(PAYMENTS, Path.of("/srv/payments/Main.java")),
        "the presence answered and covers nothing, the server could not be asked, and"
            + " nothing can be concluded — which is the router's rule and not this"
            + " task's to weaken");
  }

  @Test
  void a_presence_whose_session_has_no_file_provider_is_not_a_machine() {
    sessions.attach("bench", Role.LISTENER, new Object());
    presences.declare(new Presence("bench", "bench.local", "/srv/payments", "payments"));

    assertEquals(
        List.of("local"),
        names(wiring().forRun(PAYMENTS, READ, "desk", null)),
        "a claim is not a disk. The role gate is unchanged and still the thing that"
            + " decides whether a run reaches a machine at all");
  }

  @Test
  void a_presence_that_withdraws_makes_the_next_ask_smaller_and_ends_nothing() {
    attached("bench");
    presences.declare(new Presence("bench", "bench.local", "/srv/payments", "payments"));
    RunProviders wiring = wiring();
    assertEquals(List.of("local", "remote"), names(wiring.forRun(PAYMENTS, READ, "desk", null)));

    presences.withdraw("bench");

    assertEquals(
        List.of("local"),
        names(wiring.forRun(PAYMENTS, READ, "desk", null)),
        "the set is re-asked per routing call, so a machine that stopped rooting the"
            + " project during a run changes what the rest of that run can see");
  }

  // --- the global tier -----------------------------------------------------

  @Test
  void the_global_tier_is_not_place_bound_so_it_is_served_by_the_session_that_asked() {
    attached("bench");
    attached("desk");
    presences.declare(new Presence("bench", "bench.local", "/srv/payments", "payments"));

    remoteOf(wiring().forRun(Home.global(), READ, "desk", "alice")).roots();

    assertEquals(
        List.of("desk"),
        channel.asked(),
        "global is the absence of a project, so there is no place to root and nothing"
            + " to ask the registry; the only machine such a run can mean is the"
            + " one it came from");
  }

  @Test
  void a_global_run_with_no_session_has_the_servers_own_disk_and_no_absence_beside_it() {
    assertEquals(
        List.of("local"),
        names(wiring().forRun(Home.global(), READ, null, null)),
        "there is no project to say is rooted nowhere, so nothing is added to say it");
  }

  // --- the fixture ---------------------------------------------------------

  private void attached(String session) {
    sessions.attach(session, Role.FILE_PROVIDER, new Object(), "alice");
  }

  /**
   * The production method, with a project store that answers for one workspace.
   *
   * <p>{@code RemoteWiringTest}'s helper and its reasoning: a mocked store rather than a database,
   * because what is under test is which providers the seam builds.
   */
  private RunProviders wiring() {
    return wiring(serverSide);
  }

  /**
   * The same, over a workspace the caller names — so that "this server holds it" and "this server
   * does not" are one line apart in a fixture.
   */
  private RunProviders wiring(Path workspace) {
    ProjectStore projects = mock(ProjectStore.class);
    ProjectRecord row = new ProjectRecord("payments", workspace, List.of(), List.of());
    when(projects.find("payments")).thenReturn(Optional.of(row));
    when(projects.effectiveExclusions(any(ProjectRecord.class))).thenReturn(List.of());
    return new AgentsConfig()
        .runProviders(
            projects,
            channel,
            sessions,
            presences,
            ImageStore.NONE,
            UnionRouting.NONE,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.ProjectMembers.class));
  }

  /**
   * A store answering the way it answers for a project whose files are on another machine: {@code
   * rootedElsewhere} names the machine, and {@code find} is empty because this server holds no
   * leash for it.
   *
   * <p><b>Both halves, because that is what the real store does</b> — {@code ProjectStore.find}
   * carries {@code machine IS NULL} — and a fixture that set only the first would be measuring a
   * store that cannot exist.
   */
  private RunProviders rootedOn(String machine) {
    ProjectStore projects = mock(ProjectStore.class);
    when(projects.rootedElsewhere("payments")).thenReturn(Optional.of(machine));
    when(projects.find("payments")).thenReturn(Optional.empty());
    return new AgentsConfig()
        .runProviders(
            projects,
            channel,
            sessions,
            presences,
            ImageStore.NONE,
            UnionRouting.NONE,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.ProjectMembers.class));
  }

  /**
   * The same, and with a directory on <em>this</em> server at the path the row names — the trap a
   * filesystem probe walks into.
   */
  private RunProviders rootedOn(String machine, Path alsoHere) {
    ProjectStore projects = mock(ProjectStore.class);
    when(projects.rootedElsewhere("payments")).thenReturn(Optional.of(machine));
    when(projects.find("payments")).thenReturn(Optional.empty());
    when(projects.effectiveExclusions(any(ProjectRecord.class))).thenReturn(List.of());
    assertTrue(
        Files.isDirectory(alsoHere),
        "the fixture is only a trap if the directory" + " really is here");
    return new AgentsConfig()
        .runProviders(
            projects,
            channel,
            sessions,
            presences,
            ImageStore.NONE,
            UnionRouting.NONE,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.ProjectMembers.class));
  }

  private static List<String> names(List<FileProvider> providers) {
    return providers.stream().map(FileProvider::name).toList();
  }

  private static FileProvider remoteOf(List<FileProvider> providers) {
    return providers.stream()
        .filter(provider -> "remote".equals(provider.name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no remote provider among " + names(providers)));
  }

  /**
   * A channel that answers every request with an empty list and records which session it was asked
   * about, which is the only question this file puts to it.
   */
  private static final class Recording implements SessionChannel {

    private final List<String> asked = Collections.synchronizedList(new ArrayList<>());

    @Override
    public FileReply ask(String session, FileRequest request) {
      asked.add(session);
      return FileReply.listed(request.id(), List.of());
    }

    List<String> asked() {
      return List.copyOf(asked);
    }
  }
}
