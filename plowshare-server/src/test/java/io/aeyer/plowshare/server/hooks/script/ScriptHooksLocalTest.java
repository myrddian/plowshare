package io.aeyer.plowshare.server.hooks.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.Addition;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookFile;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.PromptPre;
import io.aeyer.plowshare.server.hooks.Tier;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec 2026-09-30-local-hooks-are-served decisions 7, 9 and 10, and plan choices 11 and 12 (as
 * amended in review), over real swc4j and GraalJS.
 */
class ScriptHooksLocalTest {

  private static final String GUARD =
      """
            import type { Hook } from '@plowshare/hooks'
            export default {
                name: 'guard',
                stages: {
                    'tool.pre': {
                        tools: ['file_write'],
                        handle(e) {
                            return String(e.args.content ?? '').includes('TODO')
                                ? { deny: 'no TODOs' } : { allow: true }
                        },
                    },
                },
            } satisfies Hook
            """;

  /** Module state that persists within a context: each call counts up. */
  private static final HookFile COUNTER =
      new HookFile(
          "10-counter.js",
          """
            let seen = 0
            export default { name: 'counter', stages: {
                'prompt.pre': { handle() { seen += 1; return { add: 'seen ' + seen, mode: 'durable' } } },
            } }
            """);

  private static final HookContext WRITER =
      new HookContext("scribe", false, Set.of("file_write"), "ledger", "cnv_a", HookContext.SERVER);
  private static final String TODO = "{\"content\":\"TODO later\"}";

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-30T09:00:00Z"));
  private final Map<String, String> pins = new ConcurrentHashMap<>();

  /** A log's owner; a log not named here is enzo's. */
  private final Map<String, String> owners = new ConcurrentHashMap<>();

  private final AtomicInteger ownerFailures = new AtomicInteger();
  private final Map<String, List<HookFile>> stored = new ConcurrentHashMap<>();
  private final AtomicInteger reads = new AtomicInteger();
  private final AtomicInteger lookups = new AtomicInteger();
  private final AtomicInteger pinFailures = new AtomicInteger();
  private final AtomicInteger storeFailures = new AtomicInteger();
  private final List<AutoCloseable> opened = new ArrayList<>();
  private HookEngine engine;
  private HooksProperties properties;

  @BeforeEach
  void setUp() {
    engine = new HookEngine();
    properties = new HooksProperties();
    properties.setTimeout(Duration.ofMillis(500));
    properties.setIdle(Duration.ofMinutes(1));
  }

  @AfterEach
  void tearDown() throws Exception {
    for (AutoCloseable one : opened) {
      one.close();
    }
    engine.close();
  }

  private ScriptHooks local() {
    ScriptHooks hooks =
        ScriptHooks.local(
            conversation -> {
              lookups.incrementAndGet();
              if (pinFailures.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new IllegalStateException("the archive is down");
              }
              return Optional.ofNullable(pins.get(conversation));
            },
            this::ownerOf,
            hash -> {
              reads.incrementAndGet();
              if (storeFailures.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new IllegalStateException("the archive hiccuped");
              }
              return Optional.ofNullable(stored.get(hash));
            },
            (log, session) -> false,
            engine,
            properties,
            now::get);
    opened.add(hooks);
    return hooks;
  }

  private Optional<String> ownerOf(String conversation) {
    if (ownerFailures.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
      throw new IllegalStateException("the archive is down");
    }
    String owner = owners.getOrDefault(conversation, "enzo");
    return owner.isEmpty() ? Optional.empty() : Optional.of(owner);
  }

  private String served(String conversation, HookFile... files) {
    String hash = HookFile.hashOf(List.of(files));
    stored.put(hash, HookFile.ordered(List.of(files)));
    pins.put(conversation, hash);
    return hash;
  }

  private static String counted(PromptPre pre) {
    assertEquals(1, pre.additions().size(), pre.records().toString());
    return pre.additions().get(0).text();
  }

  private static HookContext in(String conversation) {
    return new HookContext(
        "scribe", false, Set.of("file_write"), "ledger", conversation, HookContext.SERVER);
  }

  @Test
  void a_served_hook_fires_as_the_local_tier_naming_its_file() {
    served("cnv_a", new HookFile("10-guard.ts", GUARD));

    ToolPre denied = local().toolPre(WRITER, "file_write", TODO);

    assertEquals("'guard': no TODOs", denied.denied());
    HookRecord record = denied.records().get(0);
    assertEquals(Tier.LOCAL, record.tier());
    assertEquals("10-guard.ts", record.file());
    assertEquals(HookRecord.DENY, record.decision());
    assertTrue(record.json().contains("\"tier\":\"local\""), record.json());
  }

  @Test
  void a_log_with_no_snapshot_and_a_run_with_no_log_have_no_local_tier() {
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    ScriptHooks hooks = local();

    assertEquals(ToolPre.allowed(TODO), hooks.toolPre(in("cnv_b"), "file_write", TODO));
    assertEquals(ToolPre.allowed(TODO), hooks.toolPre(in(null), "file_write", TODO));
  }

  @Test
  void one_owners_logs_with_the_same_snapshot_share_one_loaded_set() {
    String hash = served("cnv_a", new HookFile("10-guard.ts", GUARD));
    pins.put("cnv_b", hash);
    ScriptHooks hooks = local();

    assertTrue(hooks.toolPre(in("cnv_a"), "file_write", TODO).isDenied());
    assertTrue(hooks.toolPre(in("cnv_b"), "file_write", TODO).isDenied());

    assertEquals(1, reads.get());
    assertEquals(1, hooks.loadedLocalSets());
  }

  /**
   * Spec decision 9 as amended 2026-09-30: a loaded set is shared by owner and hash, never by hash
   * alone. Two accounts with the same committed hooks each get their own contexts, so no module
   * state one account's calls leave behind is read in the other's logs.
   */
  @Test
  void two_owners_with_the_same_snapshot_load_two_sets_and_share_no_module_state() {
    String hash = served("cnv_enzo", COUNTER);
    pins.put("cnv_mallory", hash);
    owners.put("cnv_mallory", "mallory");
    ScriptHooks hooks = local();

    List<String> mallory =
        List.of(
            counted(hooks.promptPre(in("cnv_mallory"), "hi")),
            counted(hooks.promptPre(in("cnv_mallory"), "hi")));
    String enzo = counted(hooks.promptPre(in("cnv_enzo"), "hi"));

    assertEquals(List.of("seen 1", "seen 2"), mallory, "one owner's calls share a context");
    assertEquals("seen 1", enzo, "another owner's calls start from their own module state");
    assertEquals(2, hooks.loadedLocalSets());
    assertEquals(2, reads.get(), "each owner's set is read from the one stored snapshot");
  }

  @Test
  void an_owner_that_could_not_be_looked_up_is_asked_again_at_the_next_fire() {
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    ownerFailures.set(1);
    ScriptHooks hooks = local();

    ToolPre first = hooks.toolPre(WRITER, "file_write", TODO);
    ToolPre second = hooks.toolPre(WRITER, "file_write", TODO);

    assertFalse(first.isDenied(), "never a failed run");
    assertTrue(
        first.records().get(0).reason().contains("could not be looked up"),
        first.records().toString());
    assertTrue(second.isDenied(), "the lookup that worked runs the log's hooks");
  }

  @Test
  void a_pinned_log_with_no_owner_on_record_runs_no_local_hooks_said_once() {
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    owners.put("cnv_a", "");
    ScriptHooks hooks = local();

    ToolPre first = hooks.toolPre(WRITER, "file_write", TODO);
    ToolPre second = hooks.toolPre(WRITER, "file_write", TODO);

    assertFalse(first.isDenied(), "never a failed run");
    assertEquals(HookFile.WHOLE_SET, first.records().get(0).hook());
    assertTrue(
        first.records().get(0).reason().contains("no owner"), first.records().get(0).reason());
    assertEquals(List.of(), second.records());
    assertEquals(0, reads.get(), "nothing is loaded for no one");
  }

  @Test
  void served_files_run_in_name_order_and_other_names_are_not_hooks() {
    served(
        "cnv_a",
        new HookFile(
            "20-second.js",
            "export default { name: 'second', stages: {"
                + " 'prompt.pre': { handle() { return { add: 'second', mode: 'durable' } }"
                + " } } }"),
        new HookFile(
            "10-first.js",
            "export default { name: 'first', stages: {"
                + " 'prompt.pre': { handle() { return { add: 'first', mode: 'durable' } }"
                + " } } }"),
        new HookFile("notes.md", "not code at all {"));

    PromptPre pre = local().promptPre(WRITER, "hi");

    assertEquals(List.of("first", "second"), pre.additions().stream().map(Addition::text).toList());
    assertEquals(
        List.of(HookRecord.ADD, HookRecord.ADD),
        pre.records().stream().map(HookRecord::decision).toList(),
        pre.records().toString());
  }

  @Test
  void a_broken_served_file_closes_tool_stages_in_this_log_and_opens_prompt_stages() {
    served("cnv_a", new HookFile("30-broken.ts", "export default { name: 'broken', "));
    ScriptHooks hooks = local();

    ToolPre tool = hooks.toolPre(WRITER, "file_write", "{}");
    PromptPre prompt = hooks.promptPre(WRITER, "hi");

    assertTrue(
        tool.denied()
            .startsWith(
                "a local hook file did not load, so tool calls in this"
                    + " log are refused, and a fix reaches only a log opened after it"),
        tool.denied());
    assertTrue(tool.denied().contains("30-broken.ts"), tool.denied());
    assertEquals(List.of(), prompt.additions());
    assertEquals(HookRecord.FAILED, prompt.records().get(0).decision());
    assertEquals(Tier.LOCAL, prompt.records().get(0).tier());
  }

  @Test
  void a_snapshot_that_cannot_be_loaded_is_an_empty_tier_said_once() {
    pins.put("cnv_a", "sha256:" + "0".repeat(64));
    ScriptHooks hooks = local();

    ToolPre first = hooks.toolPre(WRITER, "file_write", TODO);
    ToolPre second = hooks.toolPre(WRITER, "file_write", TODO);

    assertFalse(first.isDenied(), "never a failed run");
    assertEquals(1, first.records().size(), first.records().toString());
    HookRecord said = first.records().get(0);
    assertEquals(HookFile.WHOLE_SET, said.hook());
    assertEquals(Tier.LOCAL, said.tier());
    assertEquals(HookRecord.FAILED, said.decision());
    assertTrue(said.reason().contains("no longer stored"), said.reason());
    assertEquals(List.of(), second.records());
    assertEquals(1, reads.get(), "withheld for the rest of the log, not read again");
  }

  @Test
  void a_store_that_fails_withholds_the_log_for_good_said_once() {
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    storeFailures.set(1);
    ScriptHooks hooks = local();

    ToolPre first = hooks.toolPre(WRITER, "file_write", TODO);
    ToolPre second = hooks.toolPre(WRITER, "file_write", TODO);

    assertFalse(first.isDenied(), "never a failed run");
    assertEquals(1, first.records().size(), first.records().toString());
    assertEquals(HookFile.WHOLE_SET, first.records().get(0).hook());
    assertTrue(
        first.records().get(0).reason().contains("could not be read"),
        first.records().get(0).reason());
    assertFalse(second.isDenied(), "no hooks start running partway through the log");
    assertEquals(List.of(), second.records());
    assertEquals(1, reads.get());
  }

  @Test
  void a_snapshot_that_throws_while_loading_is_an_empty_tier_said_once() {
    String hash = served("cnv_a", new HookFile("10-guard.ts", GUARD));
    stored.put(hash, Arrays.asList(new HookFile("10-guard.ts", GUARD), null));
    ScriptHooks hooks = local();

    ToolPre first = hooks.toolPre(WRITER, "file_write", TODO);
    ToolPre second = hooks.toolPre(WRITER, "file_write", TODO);

    assertFalse(first.isDenied(), first.denied());
    assertEquals(HookRecord.FAILED, first.records().get(0).decision());
    assertTrue(
        first.records().get(0).reason().contains("could not be loaded"),
        first.records().get(0).reason());
    assertEquals(List.of(), second.records());
    assertEquals(0, hooks.loadedLocalSets());
  }

  @Test
  void a_pin_that_could_not_be_looked_up_is_asked_again_at_the_next_fire() {
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    pinFailures.set(2);
    ScriptHooks hooks = local();

    ToolPre first = hooks.toolPre(WRITER, "file_write", TODO);
    ToolPre second = hooks.toolPre(WRITER, "file_write", TODO);
    ToolPre third = hooks.toolPre(WRITER, "file_write", TODO);

    for (ToolPre failed : List.of(first, second)) {
      assertFalse(failed.isDenied(), "never a failed run");
      assertEquals(1, failed.records().size(), failed.records().toString());
      assertEquals(Tier.LOCAL, failed.records().get(0).tier());
      assertTrue(
          failed.records().get(0).reason().contains("could not be looked up"),
          failed.records().get(0).reason());
    }
    assertTrue(third.isDenied(), "the lookup that worked runs the log's hooks");
  }

  @Test
  void a_failed_pin_lookup_does_not_spend_the_logs_one_notice() {
    pins.put("cnv_a", "sha256:" + "0".repeat(64));
    pinFailures.set(1);
    ScriptHooks hooks = local();

    ToolPre lookup = hooks.toolPre(WRITER, "file_write", TODO);
    ToolPre missing = hooks.toolPre(WRITER, "file_write", TODO);
    ToolPre after = hooks.toolPre(WRITER, "file_write", TODO);

    assertTrue(lookup.records().get(0).reason().contains("could not be looked up"));
    assertEquals(1, missing.records().size(), missing.records().toString());
    assertTrue(
        missing.records().get(0).reason().contains("no longer stored"),
        missing.records().get(0).reason());
    assertEquals(List.of(), after.records());
  }

  /**
   * Plan choice 13 as amended in Task 6's review: a cancel can reach {@code log.close} before the
   * pin lands, so a miss is believed only for twice the read's deadline, then asked again.
   */
  @Test
  void a_missed_pin_is_believed_for_a_minute_and_a_pin_that_landed_since_is_seen_after_it() {
    ScriptHooks hooks = local();
    assertEquals(
        ToolPre.allowed(TODO),
        hooks.toolPre(WRITER, "file_write", TODO),
        "looked up before the pin landed");
    served("cnv_a", new HookFile("10-guard.ts", GUARD));

    now.set(now.get().plus(Duration.ofSeconds(60)).minusMillis(1));
    ToolPre within = hooks.toolPre(WRITER, "file_write", TODO);
    now.set(now.get().plusMillis(1));
    ToolPre after = hooks.toolPre(WRITER, "file_write", TODO);

    assertEquals(ToolPre.allowed(TODO), within, "the miss is still believed");
    assertEquals(List.of(), within.records(), "and it is not a withheld log");
    assertTrue(after.isDenied(), "the pin that landed since is seen");
    assertEquals(2, lookups.get());
  }

  /** A pin is written once, so a lookup that found one is never asked again. */
  @Test
  void a_found_pin_is_believed_for_the_life_of_the_log() {
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    ScriptHooks hooks = local();

    assertTrue(hooks.toolPre(WRITER, "file_write", TODO).isDenied());
    now.set(now.get().plus(Duration.ofMinutes(10)));
    assertTrue(hooks.toolPre(WRITER, "file_write", TODO).isDenied());

    assertEquals(1, lookups.get());
  }

  @Test
  void a_restart_loads_the_snapshot_from_the_store() {
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    ScriptHooks before = local();
    assertTrue(before.toolPre(WRITER, "file_write", TODO).isDenied());
    before.close();

    assertTrue(local().toolPre(WRITER, "file_write", TODO).isDenied());
    assertEquals(2, reads.get());
  }

  @Test
  void a_set_nobody_fired_for_is_retired_and_reloaded_on_the_next_fire() {
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    ScriptHooks hooks = local();
    hooks.toolPre(WRITER, "file_write", TODO);

    now.set(now.get().plus(Duration.ofMinutes(1)));
    hooks.sweepLocalSets();
    assertEquals(1, hooks.loadedLocalSets(), "not yet idle for longer than idle");

    now.set(now.get().plus(Duration.ofMinutes(2)));
    hooks.sweepLocalSets();
    assertEquals(0, hooks.loadedLocalSets());

    assertTrue(hooks.toolPre(WRITER, "file_write", TODO).isDenied());
    assertEquals(2, reads.get());
  }

  @Test
  void a_stage_holding_its_set_is_never_retired_and_is_once_it_lets_go() {
    properties.setIdle(Duration.ZERO);
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    LocalHookSets sets = sets(hash -> Optional.ofNullable(stored.get(hash)));

    LocalHookSets.Lease stage = sets.acquire(WRITER);
    ProjectHookSet held = stage.set;
    now.set(now.get().plus(Duration.ofMinutes(5)));
    sets.sweep();
    assertEquals(1, sets.loaded(), "held by a stage in flight");
    assertEquals(1, held.working.get(0).pool().live());

    stage.close();
    now.set(now.get().plus(Duration.ofMillis(1)));
    sets.sweep();
    assertEquals(0, sets.loaded());
    assertEquals(0, held.working.get(0).pool().live(), "retired and closed");
  }

  @Test
  void a_fire_still_loading_is_not_retired_by_a_sweep_with_no_idle_time() throws Exception {
    properties.setIdle(Duration.ZERO);
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    ScriptHooks hooks =
        ScriptHooks.local(
            conversation -> Optional.ofNullable(pins.get(conversation)),
            this::ownerOf,
            hash -> {
              reading.countDown();
              awaitQuietly(go);
              return Optional.ofNullable(stored.get(hash));
            },
            (log, session) -> false,
            engine,
            properties,
            now::get);
    opened.add(hooks);
    AtomicReference<ToolPre> fired = new AtomicReference<>();
    Thread fire = new Thread(() -> fired.set(hooks.toolPre(WRITER, "file_write", TODO)));
    fire.start();
    assertTrue(reading.await(5, TimeUnit.SECONDS));

    now.set(now.get().plus(Duration.ofMinutes(5)));
    hooks.sweepLocalSets();
    go.countDown();
    fire.join(5_000);

    assertTrue(fired.get().isDenied(), String.valueOf(fired.get()));
    assertEquals(1, hooks.loadedLocalSets(), "the sweep left the fire's set in place");
    now.set(now.get().plus(Duration.ofMillis(1)));
    hooks.sweepLocalSets();
    assertEquals(0, hooks.loadedLocalSets());
  }

  @Test
  void a_load_that_won_after_a_failed_read_is_the_one_set_and_the_sweep_retires_it()
      throws Exception {
    Raced raced = race();

    now.set(now.get().plus(properties.getIdle()).plusMillis(1));
    raced.sets().sweep();

    assertEquals(0, raced.sets().loaded());
    assertEquals(0, raced.loaded().working.get(0).pool().live(), "retired and closed");
  }

  @Test
  void a_load_that_won_after_a_failed_read_is_closed_by_close() throws Exception {
    Raced raced = race();

    raced.sets().close();

    assertEquals(0, raced.sets().loaded());
    assertEquals(0, raced.loaded().working.get(0).pool().live(), "closed");
  }

  /**
   * Final review M3: the withheld logs are an LRU by use, not by insertion. A log still firing
   * stays withheld while newer ones push the oldest out; before the fix it was evicted first, and
   * its next fire read the store again and could start running hooks mid-log.
   */
  @Test
  void a_withheld_log_that_keeps_firing_stays_withheld_as_newer_ones_push_old_ones_out() {
    String gone = "sha256:" + "0".repeat(64);
    pins.put("cnv_first", gone);
    for (int i = 0; i < LocalHookSets.REMEMBERED; i++) {
      pins.put("cnv_" + i, gone);
    }
    LocalHookSets sets =
        sets(
            hash -> {
              reads.incrementAndGet();
              return Optional.empty();
            });
    closing(sets.acquire(in("cnv_first")));
    for (int i = 0; i < LocalHookSets.REMEMBERED - 1; i++) {
      closing(sets.acquire(in("cnv_" + i)));
    }
    closing(sets.acquire(in("cnv_first")));
    closing(sets.acquire(in("cnv_" + (LocalHookSets.REMEMBERED - 1))));
    int before = reads.get();

    LocalHookSets.Lease again = sets.acquire(in("cnv_first"));

    assertEquals(before, reads.get(), "still withheld, so the store is not read again");
    assertNull(again.set.withheld, "and nothing is said again");
    again.close();
  }

  private static void closing(LocalHookSets.Lease lease) {
    lease.close();
  }

  /**
   * Final review M4: a lease closed twice lets go once. Before the fix the second close took
   * another stage's hold, and a sweep retired a set that stage was still firing.
   */
  @Test
  void a_lease_closed_twice_does_not_let_go_of_another_stage_s_hold() {
    properties.setIdle(Duration.ZERO);
    served("cnv_a", new HookFile("10-guard.ts", GUARD));
    LocalHookSets sets = sets(hash -> Optional.ofNullable(stored.get(hash)));
    LocalHookSets.Lease one = sets.acquire(WRITER);
    LocalHookSets.Lease other = sets.acquire(WRITER);

    one.close();
    one.close();
    now.set(now.get().plus(Duration.ofMinutes(5)));
    sets.sweep();

    assertEquals(1, sets.loaded(), "the other stage still holds it");
    assertEquals(1, other.set.working.get(0).pool().live());
    other.close();
  }

  /**
   * Final review M5: a hook whose first context loaded but whose pool could not be built (here a
   * pool with no room) has that context closed, not left to the engine's shutdown.
   */
  @Test
  void a_loaded_context_whose_pool_cannot_be_built_is_closed() throws Exception {
    properties.setPoolMax(0);
    ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    opened.add(timer::shutdownNow);
    String javascript =
        "export default { name: 'guard', stages: { 'prompt.pre': {"
            + " handle() { return undefined } } } }";
    LoadedHook first =
        LoadedHook.load(engine, "10-guard.js", javascript, properties.getTimeout(), timer);
    List<ProjectHookSet.Working> working = new ArrayList<>();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProjectHookSet.pool(
                "10-guard.js",
                first,
                javascript,
                engine,
                properties,
                now::get,
                timer,
                working,
                new ArrayList<>(),
                new java.util.HashMap<>()));

    assertFalse(first.isAlive(), "the context the pool never took is closed");
    assertEquals(List.of(), working);
    ToolPre served = servedWith(new HookFile("10-guard.ts", GUARD));
    assertFalse(served.isDenied(), "a served set that cannot be pooled is an empty tier");
    assertTrue(
        served.records().get(0).reason().contains("could not be loaded"),
        served.records().toString());
  }

  private ToolPre servedWith(HookFile file) {
    served("cnv_a", file);
    return local().toolPre(WRITER, "file_write", TODO);
  }

  private record Raced(LocalHookSets sets, ProjectHookSet loaded) {}

  /**
   * Two logs with one hash fire together: the first's read of the store throws while the second
   * waits for the set's lock, and the second's read works. Before the fix the failure took the
   * entry out of the map, and the second loaded into an entry nothing could reach.
   */
  private Raced race() throws Exception {
    String hash = served("cnv_a", new HookFile("10-guard.ts", GUARD));
    pins.put("cnv_b", hash);
    AtomicReference<Thread> second = new AtomicReference<>();
    AtomicBoolean overlapped = new AtomicBoolean();
    CountDownLatch firstReading = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    LocalHookSets sets =
        sets(
            key -> {
              if (calls.incrementAndGet() == 1) {
                firstReading.countDown();
                overlapped.set(untilBlocked(second));
                throw new IllegalStateException("the archive hiccuped");
              }
              return Optional.ofNullable(stored.get(key));
            });
    AtomicReference<LocalHookSets.Lease> a = new AtomicReference<>();
    AtomicReference<LocalHookSets.Lease> b = new AtomicReference<>();
    Thread first = new Thread(() -> a.set(sets.acquire(in("cnv_a"))));
    Thread other = new Thread(() -> b.set(sets.acquire(in("cnv_b"))));
    second.set(other);
    first.start();
    assertTrue(firstReading.await(5, TimeUnit.SECONDS));
    other.start();
    first.join(5_000);
    other.join(5_000);

    assertTrue(overlapped.get(), "the second fire waited on the first's load");
    assertTrue(a.get().set.withheld.contains("could not be read"), a.get().set.withheld);
    ProjectHookSet loaded = b.get().set;
    assertEquals(1, loaded.working.size());
    a.get().close();
    b.get().close();
    assertEquals(1, sets.loaded(), "one set, in the map");
    assertEquals(1, loaded.working.get(0).pool().live());
    return new Raced(sets, loaded);
  }

  private LocalHookSets sets(Function<String, Optional<List<HookFile>>> store) {
    ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    LocalHookSets sets =
        new LocalHookSets(
            conversation -> Optional.ofNullable(pins.get(conversation)),
            this::ownerOf,
            store,
            engine,
            properties,
            now::get,
            timer);
    opened.add(timer::shutdownNow);
    opened.add(sets);
    return sets;
  }

  private static boolean untilBlocked(AtomicReference<Thread> waiting) {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < until) {
      Thread thread = waiting.get();
      if (thread != null && thread.getState() == Thread.State.BLOCKED) {
        return true;
      }
      Thread.onSpinWait();
    }
    return false;
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
