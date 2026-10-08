package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * {@link DefinitionResolver} is the layer in front of the boot {@link AgentRegistry}: what one
 * caller, asking on behalf of one project, sees on top of it. These tests exercise what is not
 * visible in the boot registry's own suite: a project tier can shadow the boot set but never a
 * {@code required} agent, a bad project file is disabled rather than fatal, a deployment with no
 * data directory has no project tier at all, and — spec §5, the guarantee this whole slice exists
 * for — a definition dropped into a project's own directory resolves on the next lookup, without
 * the resolver being rebuilt and without a restart.
 */
class DefinitionResolverTest {

  /**
   * {@code AgentRegistryTest}'s own fixture, for the same reason: it is the smallest set every
   * shipped {@code REQUIRED} agent parses against.
   */
  private static final Set<String> TOOLS = Set.of("memory_recall", "memory_read", "agent_run");

  private static void write(Path dir, String name, String body) throws Exception {
    write(dir, name, "", body);
  }

  /**
   * {@link #write(Path, String, String)} with room for extra frontmatter lines -- {@code
   * tools:}/{@code calls:} for the fixtures that need a definition which actually delegates.
   */
  private static void write(Path dir, String name, String extraFrontmatter, String body)
      throws Exception {
    Files.createDirectories(dir);
    // max-turns and max-model-calls are required frontmatter keys --
    // AgentRegistry.requirePositiveInt has no default for either -- so a
    // fixture that omits them parses as a disabled definition rather than
    // an override, which would defeat every test below that wants a
    // project file to actually take effect.
    Files.writeString(
        dir.resolve(name + ".md"),
        "---\nname: "
            + name
            + "\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\n"
            + extraFrontmatter
            + "---\n"
            + body
            + "\n");
  }

  /**
   * A resolver over the real shipped seed, exactly as {@code AgentRegistry.of(dir, knownTools,
   * required)} builds a strict registry from a directory -- here from {@link ClasspathDefinitions}
   * instead, since there is no directory to name. Every project id "exists" for this overload --
   * none of the tests it feeds are about the database's say in the matter, so a predicate that
   * always answers true is the one that keeps them exercising what they always exercised. No test
   * using this overload ever passes a non-null {@code sessionId}, so the channel behind it is a
   * {@link FakeFiles} that is never asked anything.
   */
  private static DefinitionResolver resolverOver(DataLayout layout) {
    return resolverOver(layout, id -> true, new FakeFiles());
  }

  /**
   * {@link #resolverOver(DataLayout)} with the database's own word on which projects exist made
   * explicit, for a test that is about exactly that.
   */
  private static DefinitionResolver resolverOver(DataLayout layout, LongPredicate projectExists) {
    return resolverOver(layout, projectExists, new FakeFiles());
  }

  /**
   * {@link #resolverOver(DataLayout)} with the client-side channel made explicit, for the one test
   * that is about what it contributes.
   */
  private static DefinitionResolver resolverOver(DataLayout layout, FakeFiles channel) {
    return resolverOver(layout, id -> true, channel);
  }

  private static DefinitionResolver resolverOver(
      DataLayout layout, LongPredicate projectExists, SessionChannel channel) {
    return resolverOver(layout, projectExists, channel, DefinitionChecks.NONE);
  }

  /**
   * {@link #resolverOver(DataLayout)} with the checks a fleet would make spelled out, for the tests
   * that are about a tier being judged by the same rules the boot set is. {@link
   * DefinitionChecks#NONE} everywhere else: there is no dispatcher and no sampling profile in this
   * suite, and a fake that answered for one would be measuring itself.
   */
  private static DefinitionResolver resolverOver(
      DataLayout layout,
      LongPredicate projectExists,
      SessionChannel channel,
      DefinitionChecks checks) {
    // Every session is live for this overload, for the same reason every
    // project exists for the ones above it: the tests it feeds are not
    // about whether a session is attached, and a predicate that says yes
    // is the one that keeps them exercising what they always exercised.
    // The tests that ARE about it state the answer themselves, through a
    // real SessionRegistry -- see `liveIn` below.
    return resolverOver(layout, projectExists, channel, session -> true, checks);
  }

  /**
   * {@link #resolverOver(DataLayout)} with the one bit {@code DefinitionResolver} asks about a
   * session made explicit, for the tests that are about exactly that.
   */
  private static DefinitionResolver resolverOver(
      DataLayout layout,
      LongPredicate projectExists,
      SessionChannel channel,
      Predicate<String> sessionLive,
      DefinitionChecks checks) {
    AgentRegistry bootSet =
        new AgentRegistry(
            AgentRegistry.read(new ClasspathDefinitions(), TOOLS, AgentsConfig.REQUIRED));
    return new DefinitionResolver(
        bootSet, layout, projectExists, TOOLS, AgentsConfig.REQUIRED, channel, sessionLive, checks);
  }

  /**
   * The production predicate, spelled exactly as {@code AgentsConfig.definitionResolver} spells it,
   * over a registry a test attaches to and detaches from itself. Written once here rather than per
   * test, so no test can accidentally measure a weaker question than the one production asks.
   */
  private static Predicate<String> liveIn(SessionRegistry sessions) {
    return id -> sessions.find(id).filter(live -> live.has(Role.FILE_PROVIDER)).isPresent();
  }

  /**
   * A definition a client offers over its own channel, so a test can tell a resolution that read
   * the session tier from one that did not.
   */
  private static FakeFiles clientOffering(String name) {
    return new FakeFiles()
        .withListing(".plowshare/bots", List.of(name + ".md"))
        .withFile(
            ".plowshare/bots/" + name + ".md",
            "---\nname: "
                + name
                + "\ndescription: d\nmodel: m\nmax-turns: 2\n"
                + "max-model-calls: 4\n---\nonly on the laptop\n");
  }

  @Test
  void deployed_application_needs_no_data_or_client_and_release_switch_invalidates_cache(
      @TempDir Path root) throws Exception {
    Path first = root.resolve("first"), second = root.resolve("second");
    write(first.resolve("bots"), "worker", "first prompt");
    write(second.resolve("bots"), "worker", "other prompt");
    var mtime = Files.getLastModifiedTime(first.resolve("bots/worker.md"));
    Files.setLastModifiedTime(second.resolve("bots/worker.md"), mtime);
    AtomicReference<Path> active = new AtomicReference<>(first);
    FakeFiles client = clientOffering("clientonly");
    var resolver = resolverOver(new DataLayout(null), client);
    resolver.useApplicationResources(
        id -> id == null ? Optional.empty() : Optional.of(active.get()));
    var caller = new DefinitionResolver.Caller(7L, "connected-client");
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> resolver.defaultBot(caller));
    AgentRegistry initial = resolver.forCaller(caller);
    assertTrue(initial.get("worker").prompt().contains("first prompt"));
    assertFalse(initial.names().contains("clientonly"));
    active.set(second);
    AgentRegistry updated = resolver.forCaller(caller);
    assertNotSame(initial, updated);
    assertTrue(updated.get("worker").prompt().contains("other prompt"));
    assertSame(updated, resolver.forCaller(new DefinitionResolver.Caller(7L, null)));
  }

  @Test
  void a_project_definition_shadows_the_boot_set(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "librarian", "the project's own");

    DefinitionResolver resolver = resolverOver(layout);

    assertTrue(
        resolver
            .forCaller(new DefinitionResolver.Caller(7L, null))
            .get("librarian")
            .prompt()
            .contains("the project's own"));
  }

  @Test
  void another_project_does_not_see_it(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "mine", "only sevens");

    DefinitionResolver resolver = resolverOver(layout);

    assertTrue(
        resolver.forCaller(new DefinitionResolver.Caller(7L, null)).find("mine").isPresent());
    assertFalse(
        resolver.forCaller(new DefinitionResolver.Caller(8L, null)).find("mine").isPresent());
  }

  @Test
  void a_project_may_not_override_a_required_agent(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.agentsFor(7L), "scribe", "a project's own scribe");

    DefinitionResolver resolver = resolverOver(layout);
    AgentRegistry seven = resolver.forCaller(new DefinitionResolver.Caller(7L, null));

    // The inherited one runs -- the exact same definition, not merely one
    // whose prompt happens not to match the project's -- and the refusal
    // is named in refusalsFor rather than left for a caller to infer from
    // seven.disabled(), which the Map(byName) constructor always leaves
    // empty: it is refusalsFor's job to say this, and only its job.
    assertEquals(resolver.bootSet().get("scribe"), seven.get("scribe"));
    assertFalse(seven.get("scribe").prompt().contains("a project's own scribe"));
    DefinitionResolver.Caller sevenNoSession = new DefinitionResolver.Caller(7L, null);
    String reason = resolver.refusalsFor(sevenNoSession).get("scribe");
    assertTrue(
        reason != null && !reason.isBlank(),
        "the refusal was not named in refusalsFor(7L): " + resolver.refusalsFor(sevenNoSession));
  }

  @Test
  void a_bad_project_definition_falls_back_and_never_throws(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Files.createDirectories(layout.botsFor(7L));
    Files.writeString(layout.botsFor(7L).resolve("librarian.md"), "not frontmatter at all");

    DefinitionResolver resolver = resolverOver(layout);
    AgentRegistry seven = resolver.forCaller(new DefinitionResolver.Caller(7L, null));

    // Not merely "some librarian is present" -- which the boot set alone
    // would already satisfy even with every refusal path deleted -- but
    // the exact inherited prompt, and the failed file named by reason.
    assertEquals(resolver.bootSet().get("librarian").prompt(), seven.get("librarian").prompt());
    DefinitionResolver.Caller sevenNoSession = new DefinitionResolver.Caller(7L, null);
    String reason = resolver.refusalsFor(sevenNoSession).get("librarian");
    assertTrue(
        reason != null && !reason.isBlank(),
        "'librarian' was not named in refusalsFor(7L): " + resolver.refusalsFor(sevenNoSession));
  }

  /**
   * Finding 1: {@code helper} calls {@code broken}, and {@code broken}'s own file fails to parse.
   * The tier's own read keeps that edge alive -- {@code broken} is still a name the tier's disable
   * rule considers defined, on the promise that the callee exists somewhere even though its own
   * file was refused -- but {@code broken} is never added to the merged map, because only {@code
   * loaded.enabled()} is merged and {@code broken} was never enabled. So the promise is broken
   * exactly at the merge, {@code helper -> broken} becomes a genuinely unreachable callee only
   * then, and building the merged registry throws. One project's typo must not surface as an
   * exception out of {@code forCaller} mid-run: the whole project falls back to the unmodified boot
   * set, and the fault is named in {@code refusalsFor} rather than swallowed.
   */
  @Test
  void a_callee_unreachable_only_after_the_merge_falls_back_and_is_named(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(
        layout.botsFor(7L),
        "helper",
        "tools: [agent_run]\ncalls: [broken]\n",
        "delegates to broken");
    Files.writeString(layout.botsFor(7L).resolve("broken.md"), "not frontmatter at all");

    DefinitionResolver resolver = resolverOver(layout);

    AgentRegistry seven = resolver.forCaller(new DefinitionResolver.Caller(7L, null));

    assertEquals(
        resolver.bootSet().names(),
        seven.names(),
        "a set-level fault must fall the whole project back to the boot set, not throw");
    DefinitionResolver.Caller sevenNoSession = new DefinitionResolver.Caller(7L, null);
    String failure = String.join(" ", resolver.refusalsFor(sevenNoSession).values());
    assertTrue(
        failure.contains("broken"),
        "the fault was not named anywhere in refusalsFor(7L): "
            + resolver.refusalsFor(sevenNoSession));
  }

  /**
   * Finding 2: a project agent may declare {@code calls:} naming an agent the <em>boot set</em>
   * defines and keep that edge. Before {@code AgentRegistry}'s four-{@code Set} {@code read}
   * overload was threaded through, the tier's own read only ever knew its own names, so an edge
   * like this one read as a typo -- "no file in this directory defines it" -- and was silently
   * withheld before the merge ever saw it.
   */
  @Test
  void a_project_agent_can_delegate_to_an_inherited_agent(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(
        layout.botsFor(9L),
        "delegator",
        "tools: [agent_run]\ncalls: [librarian]\n",
        "delegates to the inherited librarian");

    DefinitionResolver resolver = resolverOver(layout);
    AgentRegistry nine = resolver.forCaller(new DefinitionResolver.Caller(9L, null));

    assertEquals(List.of("librarian"), nine.get("delegator").calls());
    assertTrue(nine.withheldEdges().isEmpty(), nine.withheldEdges().toString());
    DefinitionResolver.Caller nineNoSession = new DefinitionResolver.Caller(9L, null);
    assertTrue(
        resolver.refusalsFor(nineNoSession).isEmpty(),
        resolver.refusalsFor(nineNoSession).toString());
  }

  @Test
  void a_caller_with_no_project_gets_the_boot_set_itself(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    DefinitionResolver resolver = resolverOver(layout);

    assertEquals(
        resolver.bootSet().names(), resolver.forCaller(DefinitionResolver.Caller.server()).names());
  }

  /**
   * Ruling 3: {@code DataLayout.agentsFor}/{@code botsFor} throw on a {@code null} root, and {@code
   * DataLayout.NONE} is exactly the deployment that never configured a data directory. Without the
   * {@code keepsAnything()} guard in {@code DefinitionResolver.forCaller}, the first project lookup
   * on such a deployment would throw instead of falling through to the boot set -- there is no tree
   * to hold a project tier, so every caller resolves to the boot set unchanged.
   */
  @Test
  void a_resolver_with_no_data_directory_falls_back_to_the_boot_set_for_a_project() {
    DefinitionResolver resolver = resolverOver(DataLayout.NONE);

    assertEquals(
        resolver.bootSet().names(),
        resolver.forCaller(new DefinitionResolver.Caller(99L, null)).names());
  }

  /**
   * Spec §9.6: the database is the authority on which projects exist and the filesystem is never
   * asked first. A {@code projects/7/bots/} directory left behind by a deleted project is not an
   * error and nothing evicts it -- it is simply never resolved for, because {@code projectExists}
   * says no before {@link DefinitionResolver#forCaller} ever reaches the cache or the disk.
   */
  @Test
  void a_directory_whose_project_no_longer_exists_is_never_read(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "ghost", "left behind by a deleted project");

    DefinitionResolver resolver = resolverOver(layout, id -> id != 7L);

    assertFalse(
        resolver.forCaller(new DefinitionResolver.Caller(7L, null)).find("ghost").isPresent());
  }

  /**
   * The other half of the ruling above, and the reason it is worth its own test rather than
   * trusting the negative one: a predicate that hid the ghost because it hides <em>everything</em>
   * would pass {@link #a_directory_whose_project_no_longer_exists_is_never_read} too. The exact
   * same directory, read by the exact same resolver, is ordinarily resolvable the moment the
   * database says the project is real.
   */
  @Test
  void the_same_directory_resolves_once_the_project_exists(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "ghost", "left behind by a deleted project");

    DefinitionResolver resolver = resolverOver(layout, id -> id == 7L);

    assertTrue(
        resolver.forCaller(new DefinitionResolver.Caller(7L, null)).find("ghost").isPresent());
  }

  /**
   * Not merely that a non-existent project's definitions are absent from the answer -- {@link
   * FilesystemDefinitions} is never asked for them at all.
   *
   * <p>Proven rather than assumed: {@code layout.botsFor(7L)} is stripped of every permission after
   * the ghost file is written, so a resolution that reached {@link FilesystemDefinitions#list()}
   * for project 7 would have {@code Files.list} throw on the unreadable directory, and {@code
   * DefinitionResolver.readProject}'s own catch would record that fault under {@code
   * refusalsFor(7L)}, keyed on {@code "(the project tier)"} -- see {@code
   * a_callee_unreachable_only_after_the_merge_falls_back_and_is_named} for the same mechanism
   * measured on a different fault. An empty {@code refusalsFor(7L)} here is therefore not silence
   * about a read that succeeded; {@link #the_same_directory_resolves_once_the_project_exists}
   * already covers a directory that can be read. It is the absence of the one event a filesystem
   * consultation of this directory could not avoid leaving behind.
   *
   * <p><b>{@link #assertTheSealHolds} is checked first, inside the {@code try}, on {@code
   * LocalProviderTest}'s own convention and not a new one.</b> The chain above is only a proof on a
   * host where POSIX mode bits actually deny this process; root is not denied, so under root-run CI
   * this test would pass whether or not the short-circuit it exists to measure was still there.
   * That is precisely the failure {@code LocalProviderTest} measured from an earlier {@code
   * Assumptions.assumeFalse} version of this same pattern, and its own comment says why skipping is
   * not the fix: "a guard that passes because it could not run is worse than no guard."
   */
  @Test
  void a_predicate_that_says_no_short_circuits_before_any_filesystem_read(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Path ghostBots = layout.botsFor(7L);
    write(ghostBots, "ghost", "left behind by a deleted project");
    Files.setPosixFilePermissions(ghostBots, PosixFilePermissions.fromString("---------"));
    try {
      // Inside the try, so a host where the seal does not hold gets its
      // permissions restored like any other exit from this method.
      assertTheSealHolds(ghostBots, "listing it");

      DefinitionResolver resolver = resolverOver(layout, id -> id != 7L);

      assertFalse(
          resolver.forCaller(new DefinitionResolver.Caller(7L, null)).find("ghost").isPresent());
      DefinitionResolver.Caller sevenNoSession = new DefinitionResolver.Caller(7L, null);
      assertTrue(
          resolver.refusalsFor(sevenNoSession).isEmpty(),
          "the unreadable directory was named as a refusal, which means"
              + " forCaller reached the filesystem despite projectExists saying"
              + " no: "
              + resolver.refusalsFor(sevenNoSession));
    } finally {
      // Restored so @TempDir's own cleanup can delete the directory.
      Files.setPosixFilePermissions(ghostBots, PosixFilePermissions.fromString("rwx------"));
    }
  }

  /**
   * Spec §2, wired all the way through: for a caller whose {@code sessionId} is non-null, {@code
   * forCaller} adds a {@link ChannelDefinitions} layer behind the project's own {@code agents/} and
   * {@code bots/} -- and behind is the point. The server's project copy is read first and therefore
   * wins any name the two share; a connected client cannot silently change what an operator put in
   * {@code projects/7/bots/}.
   */
  @Test
  void the_servers_project_copy_wins_a_clash_with_the_clients(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "shared", "the server's");
    FakeFiles client =
        new FakeFiles()
            .withListing(".plowshare/bots", List.of("shared.md"))
            .withFile(
                ".plowshare/bots/shared.md",
                "---\nname: shared\ndescription: d\nmodel: m\nmax-turns: 2\n"
                    + "max-model-calls: 4\n---\nthe client's\n");

    DefinitionResolver resolver = resolverOver(layout, client);
    AgentRegistry seven = resolver.forCaller(new DefinitionResolver.Caller(7L, "session-1"));

    assertTrue(seven.get("shared").prompt().contains("the server's"));
  }

  /**
   * The other half of the clash test: a name only the client defines is not merely shadowed by the
   * layering -- it is served, because {@link LayeredDefinitions} only narrows a clash and never
   * drops a name neither competing layer claims.
   */
  @Test
  void a_name_only_the_client_defines_is_still_served(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    FakeFiles client =
        new FakeFiles()
            .withListing(".plowshare/bots", List.of("clientonly.md"))
            .withFile(
                ".plowshare/bots/clientonly.md",
                "---\nname: clientonly\ndescription: d\nmodel: m\nmax-turns: 2\n"
                    + "max-model-calls: 4\n---\nonly on the laptop\n");

    DefinitionResolver resolver = resolverOver(layout, client);
    AgentRegistry seven = resolver.forCaller(new DefinitionResolver.Caller(7L, "session-1"));

    assertTrue(seven.get("clientonly").prompt().contains("only on the laptop"));
  }

  /**
   * A caller with no session sees no channel tier at all -- {@code forCaller} never builds a {@link
   * ChannelDefinitions} for one, so a name the same client would have offered over a session is
   * simply absent rather than silently served anyway.
   */
  @Test
  void a_caller_with_no_session_never_sees_the_channel_tier(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    FakeFiles client =
        new FakeFiles()
            .withListing(".plowshare/bots", List.of("clientonly.md"))
            .withFile(
                ".plowshare/bots/clientonly.md",
                "---\nname: clientonly\ndescription: d\nmodel: m\nmax-turns: 2\n"
                    + "max-model-calls: 4\n---\nonly on the laptop\n");

    DefinitionResolver resolver = resolverOver(layout, client);
    AgentRegistry seven = resolver.forCaller(new DefinitionResolver.Caller(7L, null));

    assertFalse(seven.find("clientonly").isPresent());
  }

  /**
   * I2: {@link DefinitionResolver#refusalsFor} is keyed by session as well as project, because a
   * channel-tier refusal's message carries {@code ChannelDefinitions}' own origin format -- {@code
   * "session <id>: <path>"} -- which names a path on one operator's own laptop. Keying only by
   * project would let session B's build silently replace session A's refusals wholesale, and would
   * surface A's own local file path to whoever reads B's report under the same project id: a
   * privacy leak on top of the stale-report problem the class's own javadoc used to flag.
   *
   * <p>Both sessions answer with a file broken the same way -- a client-side {@code .md} with no
   * frontmatter fence at all, which {@link FilesystemDefinitions}' own sibling rule disables rather
   * than aborts -- so each session's tier build genuinely does produce its own refusal naming its
   * own path (the origin embeds the {@code sessionId} {@link ChannelDefinitions} was built with,
   * not anything {@link FakeFiles} itself distinguishes between sessions), and the two must not
   * collide.
   */
  @Test
  void refusals_are_kept_apart_by_session_and_do_not_leak_between_them(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    FakeFiles client =
        new FakeFiles()
            .withListing(".plowshare/bots", List.of("broken.md"))
            .withFile(".plowshare/bots/broken.md", "not frontmatter at all");

    DefinitionResolver.Caller callerA = new DefinitionResolver.Caller(7L, "session-A");
    DefinitionResolver.Caller callerB = new DefinitionResolver.Caller(7L, "session-B");

    // One resolver, one channel, asked about the same project through two
    // different sessions -- each gets its own ChannelDefinitions (built
    // with its own sessionId, in readProject), and both land in this one
    // resolver's maps under different CacheKeys, which is exactly what
    // could collide.
    DefinitionResolver resolver = resolverOver(layout, client);
    resolver.forCaller(callerA);
    resolver.forCaller(callerB);

    String reasonA = String.join(" ", resolver.refusalsFor(callerA).values());
    String reasonB = String.join(" ", resolver.refusalsFor(callerB).values());
    assertTrue(reasonA.contains("session-A"), reasonA);
    assertFalse(reasonA.contains("session-B"), reasonA);
    assertTrue(reasonB.contains("session-B"), reasonB);
    assertFalse(reasonB.contains("session-A"), reasonB);
  }

  /**
   * C4: a client chooses its own session id ({@link
   * io.aeyer.plowshare.server.files.SessionChannel#ask}'s own javadoc says so), so nothing here may
   * let a session pin a cache entry forever merely by being asked about once. {@link
   * DefinitionResolver#sessionClosed} is the eviction {@code
   * FileChannelHandler.afterConnectionClosed} calls; this proves it drops both the registry cache
   * and the refusals for that session, without touching a different session on the very same
   * project.
   */
  @Test
  void a_closed_sessions_cache_entry_and_refusals_are_dropped_and_no_others_are(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    FakeFiles clientA =
        new FakeFiles()
            .withListing(".plowshare/bots", List.of("broken.md"))
            .withFile(".plowshare/bots/broken.md", "not frontmatter at all");

    DefinitionResolver resolver = resolverOver(layout, clientA);
    DefinitionResolver.Caller callerA = new DefinitionResolver.Caller(7L, "session-A");
    DefinitionResolver.Caller callerNoSession = new DefinitionResolver.Caller(7L, null);
    resolver.forCaller(callerA);
    resolver.forCaller(callerNoSession);
    assertFalse(resolver.refusalsFor(callerA).isEmpty());

    resolver.sessionClosed("session-A");

    // The session's own refusals are gone...
    assertTrue(resolver.refusalsFor(callerA).isEmpty(), resolver.refusalsFor(callerA).toString());
    // ...and the same project with no session, which was never keyed to
    // "session-A", is completely unaffected -- eviction is scoped to the
    // session and not to the project it happened to be asked about.
    assertEquals(resolver.bootSet().names(), resolver.forCaller(callerNoSession).names());
  }

  /**
   * <b>Spec §5, and the whole reason this slice exists</b>: "a bot dropped into {@code
   * projects/&lt;id&gt;/bots/} resolves on the next lookup with no restart". The resolver is built
   * ONCE, before the file exists, and is never rebuilt — this test would pass trivially against a
   * fresh resolver per lookup, which is exactly the shape it exists to rule out.
   *
   * <p>The caller has no session, on purpose. That is the console's and the server's own case, and
   * it was the case the shipped cache never invalidated for: the only removal from the cache was
   * keyed by session id, so a caller that never had one held its first reading for the life of the
   * process. A client session was getting fresh reads only incidentally, because reconnecting
   * changed its cache key.
   */
  @Test
  void a_bot_dropped_in_afterwards_resolves_on_the_next_lookup(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    DefinitionResolver resolver = resolverOver(layout);
    DefinitionResolver.Caller seven = new DefinitionResolver.Caller(7L, null);

    assertFalse(
        resolver.forCaller(seven).find("dropped_in").isPresent(),
        "the fixture is wrong: this name must not resolve before the file exists");

    write(layout.botsFor(7L), "dropped_in", "added while the server was running");

    assertTrue(
        resolver.forCaller(seven).find("dropped_in").isPresent(),
        "a bot dropped into projects/7/bots/ did not resolve on the next lookup, which is"
            + " spec 5's whole promise -- the only remedy left is a restart");
    assertTrue(
        resolver
            .forCaller(seven)
            .get("dropped_in")
            .prompt()
            .contains("added while the server was running"),
        "the name resolved but not to the file that was just written");
  }

  /**
   * The other half of the same rule, and the one that keeps it from being "re-read everything on
   * every call" — which {@code AgentRegistry}'s own javadoc excludes in as many words: "asking the
   * map on a second call would mean re-parsing and re-validating every definition per delegation".
   *
   * <p>Identity is the instrument, because it is the only assertion a re-parse cannot fake: two
   * lookups over an untouched tier must hand back the very same {@link AgentRegistry}, and a
   * resolver that rebuilt would hand back an equal one.
   */
  @Test
  void an_untouched_tier_is_served_from_the_cache_and_not_read_again(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "mine", "unchanged between the two lookups");

    DefinitionResolver resolver = resolverOver(layout);
    DefinitionResolver.Caller seven = new DefinitionResolver.Caller(7L, null);

    AgentRegistry first = resolver.forCaller(seven);
    AgentRegistry second = resolver.forCaller(seven);

    assertSame(
        first,
        second,
        "an untouched project tier was read a second time; the cache is doing nothing and"
            + " every delegation now re-parses and re-validates the whole tier");
  }

  /**
   * A definition edited in place, with no file added or removed. The directory's own modification
   * time does not move for this on POSIX, which is why the stamp is taken per entry — name, size
   * and modification time — rather than over the directory alone. An operator who fixes a prompt
   * and sees the old one served would have no way to tell that from the fix not having worked.
   */
  @Test
  void an_edited_definition_is_re_read_although_nothing_was_added(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "mine", "the first prompt");

    DefinitionResolver resolver = resolverOver(layout);
    DefinitionResolver.Caller seven = new DefinitionResolver.Caller(7L, null);
    assertTrue(resolver.forCaller(seven).get("mine").prompt().contains("the first prompt"));

    write(layout.botsFor(7L), "mine", "the second prompt, which is a different length entirely");

    assertTrue(
        resolver.forCaller(seven).get("mine").prompt().contains("the second prompt"),
        "an edit to a definition already in the tier was never picked up: the stamp is"
            + " watching the directory and not what is in it");
  }

  /**
   * <b>The refusal is NAMED, and not only into a map nothing reads.</b> Spec §4 says a project file
   * shadowing a {@code required} agent is refused rather than silently substituted, and {@code
   * refusalsFor} is the structured form of that. It answers only a caller that already suspects;
   * the operator looking at the file they just wrote gets the log line or gets nothing.
   */
  @Test
  void a_refused_required_override_is_logged_and_not_only_recorded(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.agentsFor(7L), "scribe", "a project's own scribe");

    List<ILoggingEvent> warnings =
        whileCapturing(
            () -> {
              DefinitionResolver resolver = resolverOver(layout);
              resolver.forCaller(new DefinitionResolver.Caller(7L, null));
            });

    String said = rendered(warnings);
    assertTrue(said.contains("scribe"), "no WARN named the refused definition: " + said);
    assertTrue(
        said.contains(layout.agentsFor(7L).toString()) || said.contains("projects"),
        "no WARN named where the refused file is: " + said);
  }

  /**
   * The same debt on the loudest refusal there is: Ruling 99 accepted the whole-project fallback —
   * one bad cross-reference costs every override that project has — specifically BECAUSE it fails
   * visibly rather than silently. A map with no reader is not visible, and this is also the one
   * refusal with no per-agent row on the agents surface to carry it, since the key it is filed
   * under is not a name anything lists.
   */
  @Test
  void a_dropped_project_tier_is_logged_and_not_only_recorded(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(
        layout.botsFor(7L),
        "helper",
        "tools: [agent_run]\ncalls: [broken]\n",
        "delegates to broken");
    Files.writeString(layout.botsFor(7L).resolve("broken.md"), "not frontmatter at all");

    List<ILoggingEvent> warnings =
        whileCapturing(
            () -> {
              DefinitionResolver resolver = resolverOver(layout);
              resolver.forCaller(new DefinitionResolver.Caller(7L, null));
            });

    String said = rendered(warnings);
    assertTrue(said.contains("7"), "no WARN named the project whose tier was dropped: " + said);
    assertTrue(said.contains("broken"), "no WARN named the fault: " + said);
  }

  /**
   * The checks {@code AgentsConfig} makes of the boot set — is this model served, does it see, what
   * sampling does it actually send — reach a project tier too, which they did not when this class
   * was first built. A fake stands in for the fleet here on purpose: what this suite can measure is
   * the plumbing, which is that a check's disablement leaves the registry, lands in {@code
   * refusalsFor}, and is asked with the tier's own source so the sentence names the directory an
   * operator has to edit.
   */
  @Test
  void a_tier_check_can_disable_a_project_definition_and_it_is_named(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "unservable", "names a model no pool serves");
    List<String> sources = new ArrayList<>();

    DefinitionResolver resolver =
        resolverOver(
            layout,
            id -> true,
            new FakeFiles(),
            (loaded, source) -> {
              sources.add(source);
              return loaded.without("unservable", "no pool serves 'm', per " + source);
            });
    DefinitionResolver.Caller seven = new DefinitionResolver.Caller(7L, null);
    AgentRegistry registry = resolver.forCaller(seven);

    assertFalse(
        registry.find("unservable").isPresent(),
        "a definition the checks disabled is still being served");
    String reason = resolver.refusalsFor(seven).get("unservable");
    assertTrue(
        reason != null && reason.contains("no pool serves"),
        "a check's disablement was not named in refusalsFor: " + resolver.refusalsFor(seven));
    assertTrue(
        String.join(" ", sources).contains(layout.botsFor(7L).toString()),
        "the checks were asked about a source that does not name the project's own"
            + " directory, so their refusals cannot say which file to edit: "
            + sources);
  }

  /**
   * A definition the checks disable must not take the tier with it. Nothing hot-loaded may abort a
   * resolution, and a check that threw would otherwise cost the project every override it has
   * rather than the one definition the fleet cannot answer for.
   */
  @Test
  void a_tier_check_that_throws_never_escapes_and_costs_only_that_project(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "mine", "a perfectly good definition");

    DefinitionResolver resolver =
        resolverOver(
            layout,
            id -> true,
            new FakeFiles(),
            (loaded, source) -> {
              throw new IllegalStateException("the fleet blew up");
            });
    DefinitionResolver.Caller seven = new DefinitionResolver.Caller(7L, null);

    AgentRegistry registry = resolver.forCaller(seven);

    assertEquals(
        resolver.bootSet().names(),
        registry.names(),
        "a throwing check must fall this project back to the boot set, not out of" + " forCaller");
    assertTrue(
        String.join(" ", resolver.refusalsFor(seven).values()).contains("blew up"),
        "the failure was swallowed rather than named: " + resolver.refusalsFor(seven));
  }

  /**
   * C4's remaining half: {@link DefinitionResolver#sessionClosed} sweeps the cache, but a
   * resolution already in flight writes its entry <em>after</em> that sweep has passed — leaving an
   * entry keyed by a dead, client-chosen session id, which is the leak the sweep was added to
   * close.
   *
   * <p>The close is triggered from inside the channel call, which is exactly where it happens in
   * production, <b>and in the order production does the two halves in</b>: {@code
   * FileChannelHandler.afterConnectionClosed} detaches the file channel from {@link
   * SessionRegistry} and only then calls its close listeners. That order is what the resolver's
   * install-time question rests on, so a fixture that fired the listener without the detach would
   * be measuring a mechanism production does not have.
   *
   * <p>Identity is again the instrument. A second lookup for the same caller must build a second
   * registry, because nothing may still be cached under a session that has gone.
   */
  @Test
  void a_close_landing_during_a_resolution_leaves_nothing_cached_for_that_session(
      @TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "mine", "so the tier resolves to something of its own");
    SessionRegistry sessions = new SessionRegistry();
    Object socket = new Object();
    sessions.attach("session-A", Role.FILE_PROVIDER, socket);
    AtomicReference<DefinitionResolver> holder = new AtomicReference<>();
    AtomicBoolean fired = new AtomicBoolean();
    FakeFiles client = new FakeFiles();
    SessionChannel closingMidRead =
        (session, request) -> {
          if (fired.compareAndSet(false, true)) {
            // The close arrives while this very resolution is reading, and
            // its sweep therefore runs before the entry exists.
            sessions.detach(session, Role.FILE_PROVIDER, socket);
            holder.get().sessionClosed(session);
          }
          return client.ask(session, request);
        };
    DefinitionResolver resolver =
        resolverOver(layout, id -> true, closingMidRead, liveIn(sessions), DefinitionChecks.NONE);
    holder.set(resolver);
    DefinitionResolver.Caller caller = new DefinitionResolver.Caller(7L, "session-A");

    AgentRegistry first = resolver.forCaller(caller);
    AgentRegistry second = resolver.forCaller(caller);

    assertTrue(
        first.find("mine").isPresent(),
        "the fixture is wrong: this resolution must actually have built a project tier");
    assertTrue(
        fired.get(),
        "the fixture is wrong: the channel was never asked, so no close landed mid-read");
    assertNotSame(
        first,
        second,
        "a registry keyed by a session that closed mid-resolution stayed in the cache;"
            + " the session id is chosen by the client, so that entry can never be"
            + " asked for again and nothing will ever remove it");
  }

  /**
   * <b>The leak that needed no race at all.</b> {@code AgentController} does not check that the
   * session a request names was ever attached — a body naming a session nothing has attached to
   * submits, deliberately — so before the install-time question existed, an authenticated caller
   * could mint one permanent cache entry per made-up id, per project: keyed by a session that will
   * never close, so nothing would ever sweep it.
   *
   * <p>Two instruments, because either alone is weak. <b>Identity</b> says the resolution was filed
   * under the sessionless key and not under one carrying an id no session answers to — a
   * session-keyed entry would be a separately built registry, and this one is the very object the
   * sessionless caller is served. <b>The count of channel calls</b> says the session tier was
   * declined rather than read-and-discarded: a client that nothing is attached to has nothing to
   * answer with, and asking it costs a round trip that can only fail.
   *
   * <p>And the caller is still <em>served</em>. It gets its project's own definitions, because a
   * session id nobody recognises is not an error — {@code SessionRegistry.find}'s own javadoc says
   * a miss there is the ordinary shape of a client that has not connected, not a fault.
   */
  @Test
  void a_session_the_registry_does_not_know_is_resolved_as_a_sessionless_caller(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "mine", "the server's own copy");
    FakeFiles client = clientOffering("clientonly");
    AtomicInteger asked = new AtomicInteger();
    SessionChannel counted =
        (session, request) -> {
          asked.incrementAndGet();
          return client.ask(session, request);
        };
    // Nothing is ever attached to it, which is the whole fixture.
    SessionRegistry sessions = new SessionRegistry();

    DefinitionResolver resolver =
        resolverOver(layout, id -> true, counted, liveIn(sessions), DefinitionChecks.NONE);
    AgentRegistry ghost = resolver.forCaller(new DefinitionResolver.Caller(7L, "never-attached"));
    AgentRegistry sessionless = resolver.forCaller(new DefinitionResolver.Caller(7L, null));

    assertTrue(
        ghost.find("mine").isPresent(),
        "a caller naming a session nothing has attached to must still be served its"
            + " project's own tier -- declining to cache is not declining to answer");
    assertFalse(
        ghost.find("clientonly").isPresent(),
        "a definition was served from the channel of a session nothing is attached to");
    assertEquals(
        0,
        asked.get(),
        "the channel was asked about a session nothing is attached to, which can only"
            + " ever answer 'gone': "
            + asked.get()
            + " round trips");
    assertSame(
        sessionless,
        ghost,
        "a cache entry was installed under a session id nothing has ever attached to."
            + " Nothing will ever close that session, so nothing will ever sweep that"
            + " entry, and an authenticated caller can mint one per id it invents");
  }

  /**
   * The other half of the rule above, and the reason it is worth its own test: a resolver that
   * declined to cache <em>every</em> session would pass {@link
   * #a_session_the_registry_does_not_know_is_resolved_as_a_sessionless_caller} too, and would have
   * thrown away the whole reason {@code CacheKey} carries a session. A session with a file channel
   * actually attached gets its own entry, keeps it across lookups, and is served the definitions
   * that channel offers.
   */
  @Test
  void a_session_with_a_live_file_channel_is_cached_under_its_own_key(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "mine", "the server's own copy");
    SessionRegistry sessions = new SessionRegistry();
    sessions.attach("session-A", Role.FILE_PROVIDER, new Object());
    DefinitionResolver resolver =
        resolverOver(
            layout,
            id -> true,
            clientOffering("clientonly"),
            liveIn(sessions),
            DefinitionChecks.NONE);
    DefinitionResolver.Caller callerA = new DefinitionResolver.Caller(7L, "session-A");

    AgentRegistry first = resolver.forCaller(callerA);
    AgentRegistry second = resolver.forCaller(callerA);

    assertTrue(
        first.find("clientonly").isPresent(),
        "a live session's own .plowshare/ was not read at all");
    assertSame(
        first,
        second,
        "a live session's tier was rebuilt on the second lookup; the cache is doing"
            + " nothing for exactly the caller it exists for");
    assertNotSame(
        first,
        resolver.forCaller(new DefinitionResolver.Caller(7L, null)),
        "a live session's registry was handed to the sessionless caller as well, which"
            + " is the collision CacheKey carries a session to prevent");
  }

  @Test
  void refreshing_a_roster_rereads_local_bots_without_invalidating_other_sessions(
      @TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    FakeFiles laptop = clientOffering("cathy");
    FakeFiles other = clientOffering("otherbot");
    DefinitionResolver resolver =
        resolverOver(
            layout,
            id -> true,
            (session, request) ->
                (session.equals("session-A") ? laptop : other).ask(session, request));
    var caller = new DefinitionResolver.Caller(7L, "session-A");
    var another = new DefinitionResolver.Caller(7L, "session-B");
    AgentRegistry original = resolver.forCaller(caller);
    AgentRegistry otherRegistry = resolver.forCaller(another);
    AgentRegistry sessionless = resolver.forCaller(new DefinitionResolver.Caller(7L, null));
    assertTrue(original.find("cathy").isPresent());

    laptop.withFile(".plowshare/bots/cathy.md", "not frontmatter anymore");
    assertSame(original, resolver.forCaller(caller));
    AgentRegistry changed = resolver.refreshForCaller(caller);
    assertTrue(changed.find("cathy").isEmpty(), "the listing kept a removed local definition");
    assertFalse(
        resolver.refusalsFor(caller).isEmpty(), "the changed definition's refusal was lost");
    assertSame(otherRegistry, resolver.forCaller(another));
    assertTrue(otherRegistry.find("otherbot").isPresent());
    assertTrue(otherRegistry.find("cathy").isEmpty());
    assertSame(sessionless, resolver.forCaller(new DefinitionResolver.Caller(7L, null)));

    laptop.withFile(
        ".plowshare/bots/cathy.md",
        "---\nname: cathy\ndescription: repaired\nmodel: m\nbot: true\nexported: true\n"
            + "max-turns: 2\nmax-model-calls: 4\n---\nupdated local bot\n");
    AgentRegistry repaired = resolver.refreshForCaller(caller);
    assertTrue(repaired.find("cathy").isPresent());
    assertEquals("repaired", repaired.find("cathy").orElseThrow().description());
    assertTrue(resolver.refusalsFor(caller).isEmpty());
    assertSame(otherRegistry, resolver.forCaller(another));
  }

  /**
   * And the entry a live session earned is gone the moment its channel is. The two halves are done
   * in production's own order — {@code FileChannelHandler.afterConnectionClosed} detaches from
   * {@link SessionRegistry} first and calls its close listeners second — so this measures the pair
   * rather than either half's own opinion.
   */
  @Test
  void a_close_drops_the_entry_that_session_earned_while_it_was_live(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(7L), "mine", "the server's own copy");
    SessionRegistry sessions = new SessionRegistry();
    Object socket = new Object();
    sessions.attach("session-A", Role.FILE_PROVIDER, socket);
    DefinitionResolver resolver =
        resolverOver(
            layout,
            id -> true,
            clientOffering("clientonly"),
            liveIn(sessions),
            DefinitionChecks.NONE);
    DefinitionResolver.Caller callerA = new DefinitionResolver.Caller(7L, "session-A");
    AgentRegistry whileLive = resolver.forCaller(callerA);
    assertTrue(
        whileLive.find("clientonly").isPresent(),
        "the fixture is wrong: this resolution never read the session tier");

    sessions.detach("session-A", Role.FILE_PROVIDER, socket);
    resolver.sessionClosed("session-A");

    AgentRegistry afterClose = resolver.forCaller(callerA);
    assertNotSame(
        whileLive,
        afterClose,
        "the closed session's entry was served again; nothing will ever ask for it under"
            + " that id and nothing else will ever remove it");
    assertFalse(
        afterClose.find("clientonly").isPresent(),
        "the client tier of a session that has gone was read again");
  }

  // --- the client tier belongs to the project the session roots --------------

  /**
   * A resolver whose one live session, {@code session-1}, roots project 7 and nothing else — {@code
   * AgentsConfig}'s predicate, stated as the answer.
   */
  private static DefinitionResolver rootingSeven(DataLayout layout, FakeFiles channel) {
    AgentRegistry bootSet =
        new AgentRegistry(
            AgentRegistry.read(new ClasspathDefinitions(), TOOLS, AgentsConfig.REQUIRED));
    return new DefinitionResolver(
        bootSet,
        layout,
        id -> true,
        TOOLS,
        AgentsConfig.REQUIRED,
        channel,
        session -> true,
        (project, session) -> Long.valueOf(7L).equals(project) && "session-1".equals(session),
        DefinitionChecks.NONE);
  }

  /**
   * A session's {@code .plowshare/} is a project directory, and the project it belongs to is the
   * one that session roots (spec §5).
   *
   * <p><b>The bug this pins.</b> A client that roots A and then moves its tier to B — {@code
   * /project B}, which lends nothing — still holds A's file channel, so its session is live, and
   * B's {@code agent.list} used to layer A's own agents and bots over B's. The session is not what
   * makes a tier; the rooting is.
   */
  @Test
  void a_session_lends_its_definitions_only_to_the_project_it_roots(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    DefinitionResolver resolver = rootingSeven(layout, clientOffering("clientonly"));

    assertTrue(
        resolver
            .forCaller(new DefinitionResolver.Caller(7L, "session-1"))
            .find("clientonly")
            .isPresent(),
        "the project it roots sees its definitions");
    assertFalse(
        resolver
            .forCaller(new DefinitionResolver.Caller(8L, "session-1"))
            .find("clientonly")
            .isPresent(),
        "a project the same session merely asks about must not see them");
  }

  @Test
  void a_session_names_no_default_for_a_project_it_does_not_root(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    defaults(layout.botsFor(null), "aristoxenus");
    FakeFiles client = new FakeFiles().withFile(".plowshare/bots/default", "hermippus");
    DefinitionResolver resolver = rootingSeven(layout, client);

    assertEquals(
        "hermippus",
        resolver.defaultBot(new DefinitionResolver.Caller(7L, "session-1")).orElseThrow().name());
    assertEquals(
        "aristoxenus",
        resolver.defaultBot(new DefinitionResolver.Caller(8L, "session-1")).orElseThrow().name(),
        "the rooted project's bots/default must not pick who answers in another one");
  }

  @Test
  void json_default_bots_preserve_server_precedence_and_rooted_session_scope(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    defaults(layout.botsFor(null), "aristoxenus");
    FakeFiles files =
        new FakeFiles()
            .withFile(
                "plowshare", "{\"version\":1,\"name\":\"house\",\"defaultBot\":\"hermippus\"}")
            .withFile(".plowshare/bots/default", "other");
    var resolver = rootingSeven(layout, files);
    assertEquals(
        "hermippus",
        resolver.defaultBot(new DefinitionResolver.Caller(7L, "session-1")).orElseThrow().name());
    assertEquals(
        "aristoxenus",
        resolver.defaultBot(new DefinitionResolver.Caller(8L, "session-1")).orElseThrow().name());
    resolver.useProjectConfiguration(
        id ->
            ProjectConfiguration.parse(
                "{\"version\":1,\"name\":\"house\",\"defaultBot\":\"aristoxenus\"}",
                "house",
                "server manifest"));
    assertEquals(
        "aristoxenus",
        resolver.defaultBot(new DefinitionResolver.Caller(7L, "session-1")).orElseThrow().name());
  }

  // --- bots/default ---------------------------------------------------------

  private static void defaults(Path bots, String line) throws Exception {
    Files.createDirectories(bots);
    Files.writeString(bots.resolve(DefinitionResolver.DEFAULT_FILE), line);
  }

  @Test
  void a_projects_own_default_is_preferred_over_the_clients_and_the_global_one(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    defaults(layout.botsFor(7L), "sophron\n");
    defaults(layout.botsFor(null), "aristoxenus\n");
    FakeFiles client = new FakeFiles().withFile(".plowshare/bots/default", "hermippus");

    Optional<DefinitionResolver.DefaultBot> named =
        resolverOver(layout, client).defaultBot(new DefinitionResolver.Caller(7L, "session-1"));

    assertEquals("sophron", named.orElseThrow().name());
  }

  @Test
  void the_clients_default_answers_for_a_project_that_names_none(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    defaults(layout.botsFor(null), "aristoxenus");
    FakeFiles client = new FakeFiles().withFile(".plowshare/bots/default", "hermippus");

    assertEquals(
        "hermippus",
        resolverOver(layout, client)
            .defaultBot(new DefinitionResolver.Caller(7L, "session-1"))
            .orElseThrow()
            .name());
  }

  @Test
  void the_global_default_answers_when_no_nearer_tier_names_one(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    defaults(layout.botsFor(null), "  aristoxenus  \nignored\n");

    assertEquals(
        "aristoxenus",
        resolverOver(layout, new FakeFiles())
            .defaultBot(new DefinitionResolver.Caller(7L, "session-1"))
            .orElseThrow()
            .name(),
        "the first line, trimmed, and nothing after it");
    assertEquals(
        "aristoxenus",
        resolverOver(layout, new FakeFiles())
            .defaultBot(DefinitionResolver.Caller.server())
            .orElseThrow()
            .name(),
        "and the global tier is its own answer");
  }

  @Test
  void a_blank_default_is_no_default_rather_than_a_bot_called_nothing(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    defaults(layout.botsFor(7L), "   \n");
    defaults(layout.botsFor(null), "aristoxenus");

    assertEquals(
        "aristoxenus",
        resolverOver(layout, new FakeFiles())
            .defaultBot(new DefinitionResolver.Caller(7L, null))
            .orElseThrow()
            .name());
  }

  @Test
  void a_session_that_is_gone_is_not_asked_for_its_default(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    FakeFiles closed =
        new FakeFiles().withFile(".plowshare/bots/default", "hermippus").thatIsClosed();

    Optional<DefinitionResolver.DefaultBot> named =
        resolverOver(layout, id -> true, closed, session -> false, DefinitionChecks.NONE)
            .defaultBot(new DefinitionResolver.Caller(7L, "session-1"));

    assertEquals(
        "farnsworth",
        named.orElseThrow().name(),
        "the closed session contributes nothing, so the shipped floor answers");
  }

  @Test
  void the_shipped_default_answers_when_a_deployment_keeps_no_data() {
    DefinitionResolver.DefaultBot named =
        resolverOver(DataLayout.NONE, new FakeFiles())
            .defaultBot(new DefinitionResolver.Caller(7L, "session-1"))
            .orElseThrow();

    assertEquals("farnsworth", named.name());
    assertEquals("the shipped bots/default", named.where());
  }

  @Test
  void the_shipped_default_answers_when_no_writable_tier_names_one(@TempDir Path data) {
    DataLayout layout = new DataLayout(data).initialise();

    assertEquals(
        "farnsworth",
        resolverOver(layout, new FakeFiles())
            .defaultBot(DefinitionResolver.Caller.server())
            .orElseThrow()
            .name());
  }

  /**
   * Everything logged at WARN by {@link DefinitionResolver} while {@code work} runs, on {@code
   * LlmProviderTest}'s own {@link ListAppender} convention rather than a new one.
   */
  private static List<ILoggingEvent> whileCapturing(Runnable work) {
    Logger resolverLog = (Logger) LoggerFactory.getLogger(DefinitionResolver.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    resolverLog.addAppender(captured);
    try {
      work.run();
    } finally {
      resolverLog.detachAppender(captured);
      captured.stop();
    }
    return List.copyOf(captured.list);
  }

  /**
   * The WARN lines of a capture, rendered — anything below WARN is diagnostic and is not what these
   * tests are asserting about.
   */
  private static String rendered(List<ILoggingEvent> events) {
    StringBuilder said = new StringBuilder();
    for (ILoggingEvent event : events) {
      if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
        said.append(event.getFormattedMessage()).append('\n');
      }
    }
    return said.toString();
  }

  /**
   * {@code LocalProviderTest.assertTheSealHolds}'s own instrument, reused rather than re-invented:
   * the same host-dependent premise, so the same check and the same refusal to let a strong host
   * mean a silently-weaker test. See the javadoc on {@link
   * #a_predicate_that_says_no_short_circuits_before_any_filesystem_read}, this file's only caller,
   * for why the proof needs it.
   */
  private static void assertTheSealHolds(Path sealed, String deniedOperation) {
    assertFalse(
        Files.isReadable(sealed),
        sealed.getFileName()
            + " was set to mode 000 and this process can still read it,"
            + " so the exception that "
            + deniedOperation
            + " has to raise cannot"
            + " occur and this test cannot do its job on this host. The likely cause"
            + " is that the suite is running as root, which POSIX mode bits do not"
            + " constrain: check with `id -u`, which prints 0. An ACL granting what"
            + " the mode bits deny, or a filesystem mounted without permission"
            + " support, has the same effect. Run the suite as an unprivileged user"
            + " -- in a container image, add a non-root USER -- and fix the host"
            + " rather than this assertion: a guard that passes because it could not"
            + " run is worse than no guard.");
  }

  @Test
  void personal_bots_are_private_in_a_shared_project_and_edits_refresh_the_account_cache(
      @TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(8L), "privatebot", "Alice private bot");
    DefinitionResolver resolver = resolverOver(layout);
    resolver.usePersonalResources(caller -> "alice".equals(caller.handle()) ? 8L : 9L);
    var alice = new DefinitionResolver.Caller(7L, null, "alice");
    var bob = new DefinitionResolver.Caller(7L, null, "bob");
    assertTrue(resolver.forCaller(alice).find("privatebot").isPresent());
    assertFalse(resolver.forCaller(bob).find("privatebot").isPresent());
    write(layout.botsFor(8L), "privatebot", "Alice revised private bot with a longer prompt");
    assertTrue(resolver.forCaller(alice).get("privatebot").prompt().contains("revised"));
    write(layout.botsFor(7L), "privatebot", "Project override");
    assertTrue(resolver.forCaller(alice).get("privatebot").prompt().contains("Project override"));
  }
}
