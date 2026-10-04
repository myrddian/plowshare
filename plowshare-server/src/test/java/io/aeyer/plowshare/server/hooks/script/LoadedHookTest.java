package io.aeyer.plowshare.server.hooks.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.Stage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class LoadedHookTest {

  private static final HookEngine ENGINE = new HookEngine();

  @AfterAll
  static void closeEngine() {
    ENGINE.close();
  }

  static final String SECRETS =
      """
            import type { Hook, ToolPreEvent, ToolPreDecision } from '@plowshare/hooks'

            const PRIVATE_KEY: RegExp = /BEGIN (RSA|OPENSSH) PRIVATE KEY/

            export default {
                name: 'no-secrets-in-writes',
                stages: {
                    'tool.pre': {
                        tools: ['file_write', 'probe_*'],
                        handle(event: ToolPreEvent): ToolPreDecision {
                            if (PRIVATE_KEY.test(String(event.args['content'] ?? ''))) {
                                return { deny: 'this looks like a private key; it was not written' }
                            }
                            return { allow: true }
                        },
                    },
                    'prompt.pre': {
                        handle() {
                            return { add: 'house rule', mode: 'volatile' }
                        },
                    },
                },
            } satisfies Hook
            """;

  private static LoadedHook load(String fileName, String typescript) throws HookFailure {
    return LoadedHook.load(ENGINE, fileName, Stripping.javascript(fileName, typescript));
  }

  @Test
  void manual_examples_load_in_the_real_sandbox_and_return_valid_decisions() throws Exception {
    Path examples = Path.of("../docs/examples/hooks");
    try (LoadedHook policy =
        load("10-write-policy.ts", Files.readString(examples.resolve("10-write-policy.ts")))) {
      assertEquals(List.of("file_edit"), policy.stages().get(Stage.TOOL_PRE));
      assertTrue(
          Decision.read(
                  Stage.TOOL_PRE,
                  policy.call(
                      Stage.TOOL_PRE,
                      "{\"context\":{},\"tool\":\"file_edit\",\"args\":{\"content\":\"-----BEGIN PRIVATE KEY-----\"}}"))
              instanceof Decision.Deny);
      assertTrue(
          Decision.read(
                  Stage.TOOL_PRE,
                  policy.call(
                      Stage.TOOL_PRE,
                      "{\"context\":{},\"tool\":\"file_edit\",\"args\":{\"new\":\"ordinary replacement text\"}}"))
              instanceof Decision.Nothing);
    }
    try (LoadedHook notice =
        load("20-event-notice.js", Files.readString(examples.resolve("20-event-notice.js")))) {
      assertEquals(List.of("event"), notice.stages().get(Stage.LOG_OPEN));
      assertEquals(List.of("event"), notice.stages().get(Stage.LOG_CLOSE));
      assertTrue(
          Decision.read(
                  Stage.LOG_OPEN,
                  notice.call(
                      Stage.LOG_OPEN,
                      "{\"context\":{\"origin\":\"event\",\"log\":\"fixture-log\"}}"))
              instanceof Decision.Add);
      assertEquals(
          new Decision.Notify("Event work fixture-log ended answered."),
          Decision.read(
              Stage.LOG_CLOSE,
              notice.call(
                  Stage.LOG_CLOSE,
                  "{\"context\":{\"origin\":\"event\",\"log\":\"fixture-log\"},\"ending\":\"answered\",\"turns\":1}")));
    }
  }

  @Test
  void a_typescript_hook_loads_and_says_what_it_hooks() throws Exception {
    try (LoadedHook hook = load("10-secrets.ts", SECRETS)) {
      assertEquals("no-secrets-in-writes", hook.name());
      assertEquals(
          Map.of(Stage.TOOL_PRE, List.of("file_write", "probe_*"), Stage.PROMPT_PRE, List.of()),
          hook.stages());
    }
  }

  @Test
  void a_call_hands_over_json_and_returns_the_decision_as_json() throws Exception {
    try (LoadedHook hook = load("10-secrets.ts", SECRETS)) {
      String denied =
          hook.call(
              Stage.TOOL_PRE,
              "{\"context\":{\"agent\":\"a\",\"bot\":false,\"side\":\"server\"},"
                  + "\"tool\":\"file_write\",\"args\":{\"content\":\"-----BEGIN OPENSSH PRIVATE KEY-----\"}}");
      assertEquals("{\"deny\":\"this looks like a private key; it was not written\"}", denied);
      assertEquals(
          "{\"allow\":true}",
          hook.call(
              Stage.TOOL_PRE,
              "{\"context\":{},\"tool\":\"file_write\",\"args\":{\"content\":\"hello\"}}"));
    }
  }

  @Test
  void a_hook_can_reach_nothing_of_the_host() throws Exception {
    // `typeof Java` is 'object' even under HostAccess.NONE: GraalJS always
    // defines the language builtin, access-controlled or not. What actually
    // moves with host access is whether Java.type(...) can reach a class, so
    // that call, not typeof, is the probe that detects widened access.
    String probe =
        """
                export default {
                    name: 'probe',
                    stages: {
                        'prompt.pre': {
                            handle() {
                                let javaType
                                try {
                                    Java.type('java.lang.System')
                                    javaType = 'reachable'
                                } catch (e) {
                                    javaType = 'blocked'
                                }
                                return { add: [javaType, typeof process, typeof require,
                                        typeof Polyglot, typeof load].join('/'), mode: 'volatile' }
                            },
                        },
                    },
                }
                """;
    try (LoadedHook hook = load("probe.js", probe)) {
      assertEquals(
          "{\"add\":\"blocked/undefined/undefined/undefined/undefined\",\"mode\":\"volatile\"}",
          hook.call(Stage.PROMPT_PRE, "{\"context\":{},\"utterance\":\"hi\"}"));
    }
  }

  @Test
  void a_hook_writing_to_console_reaches_neither_stdout_nor_stderr() throws Exception {
    // A fresh HookEngine, not the class's shared ENGINE: the underlying GraalVM
    // Engine is built lazily on its first context, and if that build ever reads
    // System.out/System.err by default, it captures whichever PrintStream was
    // current AT THAT MOMENT — permanently, for every context this engine ever
    // makes. The shared ENGINE may already have been built by an earlier test in
    // this class before System.out is swapped below, which would make the swap
    // capture nothing regardless of whether the hook's output is actually
    // reachable. A fresh engine, built only after the swap, is what makes this
    // test sensitive to the real behavior either way.
    String noisy =
        """
                export default {
                    name: 'noisy',
                    stages: {
                        'prompt.pre': {
                            handle() {
                                console.log('to stdout, if it could')
                                console.error('to stderr, if it could')
                                print('also to stdout, if it could')
                                return { add: 'done', mode: 'volatile' }
                            },
                        },
                    },
                }
                """;
    PrintStream realOut = System.out;
    PrintStream realErr = System.err;
    ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    try (HookEngine freshEngine = new HookEngine()) {
      System.setOut(new PrintStream(capturedOut));
      System.setErr(new PrintStream(capturedErr));
      try (LoadedHook hook = LoadedHook.load(freshEngine, "noisy.js", noisy)) {
        assertEquals(
            "{\"add\":\"done\",\"mode\":\"volatile\"}",
            hook.call(Stage.PROMPT_PRE, "{\"context\":{},\"utterance\":\"hi\"}"));
      }
    } finally {
      System.setOut(realOut);
      System.setErr(realErr);
    }
    assertEquals("", capturedOut.toString());
    assertEquals("", capturedErr.toString());
  }

  @Test
  void a_value_import_is_a_load_failure_that_says_hooks_cannot_import() {
    // Refused by Stripping's erasable-syntax check (StrippingTest covers that
    // check directly, including the unused-import case swc4j's own transpile
    // would otherwise silently elide); this end-to-end test only needs the
    // failure to surface through load() with a sentence naming what happened.
    HookFailure refused =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "importer.ts",
                    "import { helper } from './helper.ts'\nexport default { name: 'x', stages: {} }\n"));
    assertTrue(refused.getMessage().contains("import"), refused.getMessage());
  }

  @Test
  void a_top_level_throw_is_reported_as_a_failure_and_not_misread_as_an_import() {
    // Both messages contain the substring "import" for reasons that have
    // nothing to do with ES module imports ("important", and an error message
    // that happens to use the word) — neither source has an import declaration
    // at all, so neither may be reported as one.
    HookFailure first =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "thrower1.js",
                    "throw new Error('important')\nexport default { name: 'x', stages: {} }\n"));
    assertFalse(first.getMessage().contains("imports"), first.getMessage());
    assertTrue(first.getMessage().contains("could not be evaluated"), first.getMessage());

    HookFailure second =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "thrower2.js",
                    "throw new Error('import failed')\nexport default { name: 'x', stages: {} }\n"));
    assertFalse(second.getMessage().contains("imports"), second.getMessage());
    assertTrue(second.getMessage().contains("could not be evaluated"), second.getMessage());
  }

  @Test
  void a_plain_javascript_hooks_value_import_is_still_reported_as_an_import() throws Exception {
    // .js files skip Stripping's erasable-syntax check entirely (only .ts is
    // checked there), so a real value import in a .js hook must still be
    // caught here, by LoadedHook itself — the fix for the false-positive above
    // must not have thrown out the true positive.
    HookFailure refused =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "importer.js",
                    "import { helper } from './helper.js'\nhelper()\nexport default { name: 'x', stages: {} }\n"));
    assertTrue(refused.getMessage().contains("imports"), refused.getMessage());
  }

  @Test
  void a_file_that_does_not_describe_a_hook_is_a_load_failure_naming_what_is_missing() {
    assertThrows(HookFailure.class, () -> load("nameless.js", "export default { stages: {} }"));
    assertThrows(
        HookFailure.class,
        () ->
            load(
                "toolless.js",
                "export default { name: 'x', stages: { 'tool.pre': { handle() {} } } }"));
    assertThrows(
        HookFailure.class,
        () ->
            load(
                "stageless.js",
                "export default { name: 'x', stages: { 'tool.during': { tools: ['a'], handle() {} } } }"));
    assertThrows(HookFailure.class, () -> load("broken.ts", "export default { name: 'x', "));
  }

  @Test
  void an_empty_stages_object_is_a_load_failure_naming_that_none_are_declared() {
    HookFailure refused =
        assertThrows(
            HookFailure.class,
            () -> load("empty-stages.js", "export default { name: 'x', stages: {} }"));
    assertTrue(refused.getMessage().contains("stages"), refused.getMessage());
  }

  @Test
  void a_stage_that_is_not_an_object_is_a_load_failure_naming_the_stage() {
    HookFailure refused =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "primitive-stage.js",
                    "export default { name: 'x', stages: { 'tool.pre': 5 } } "));
    assertTrue(refused.getMessage().contains("tool.pre"), refused.getMessage());
  }

  @Test
  void an_async_handle_is_a_failure_that_says_promises_are_not_awaited() throws Exception {
    try (LoadedHook hook =
        load(
            "async.js",
            """
                export default {
                    name: 'async',
                    stages: { 'prompt.pre': { async handle() { return { add: 'x', mode: 'volatile' } } } },
                }
                """)) {
      HookFailure failed =
          assertThrows(
              HookFailure.class,
              () -> hook.call(Stage.PROMPT_PRE, "{\"context\":{},\"utterance\":\"hi\"}"));
      assertTrue(failed.getMessage().toLowerCase().contains("promise"), failed.getMessage());
      assertTrue(failed.getMessage().toLowerCase().contains("await"), failed.getMessage());
    }
  }

  @Test
  void a_runaway_is_stopped_by_cancel_and_the_hook_is_no_longer_alive() throws Exception {
    LoadedHook hook =
        load(
            "spin.js",
            """
                export default {
                    name: 'spin',
                    stages: { 'prompt.pre': { handle() { while (true) {} } } },
                }
                """);
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    try {
      timer.schedule(hook::cancel, 150, TimeUnit.MILLISECONDS);
      long started = System.nanoTime();
      assertThrows(
          HookFailure.class,
          () -> hook.call(Stage.PROMPT_PRE, "{\"context\":{},\"utterance\":\"hi\"}"));
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
      assertFalse(hook.isAlive());
    } finally {
      timer.shutdownNow();
      hook.close();
    }
  }

  /**
   * A module's top level runs at load, outside any {@code handle}, so no call's time limit covers
   * it: without a limit of its own, {@code while (true) {}} there would hang the first fire of that
   * project forever.
   */
  @Test
  void a_top_level_that_never_finishes_is_stopped_at_the_load_limit() {
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    try {
      long started = System.nanoTime();
      HookFailure failed =
          assertThrows(
              HookFailure.class,
              () ->
                  LoadedHook.load(
                      ENGINE,
                      "spin-at-load.js",
                      "while (true) {}\nexport default { name: 'never', stages: { 'prompt.pre': { handle() {} } } }",
                      java.time.Duration.ofMillis(200),
                      timer));
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
      assertEquals("spin-at-load.js did not finish loading within 200 ms", failed.getMessage());
    } finally {
      timer.shutdownNow();
    }
  }

  @Test
  void a_bounded_load_that_finishes_in_time_is_an_ordinary_hook() throws Exception {
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    try (LoadedHook hook =
        LoadedHook.load(
            ENGINE,
            "10-secrets.ts",
            Stripping.javascript("10-secrets.ts", SECRETS),
            java.time.Duration.ofSeconds(5),
            timer)) {
      assertEquals("no-secrets-in-writes", hook.name());
      assertTrue(hook.isAlive());
      assertEquals(
          "{\"allow\":true}",
          hook.call(
              Stage.TOOL_PRE,
              "{\"context\":{},\"tool\":\"file_write\",\"args\":{\"content\":\"hello\"}}"));
    } finally {
      timer.shutdownNow();
    }
  }

  /**
   * A timer's {@code cancel} and a caller's {@code close} can reach one context at once; whichever
   * comes second must not throw, above all on the timer thread, where nothing would ever see it.
   */
  @Test
  void cancel_and_close_racing_on_one_context_never_throw() throws Exception {
    for (int round = 0; round < 30; round++) {
      LoadedHook hook =
          load(
              "spin.js",
              """
                    export default { name: 'spin', stages: { 'prompt.pre': { handle() { while (true) {} } } } }
                    """);
      Thread caller =
          new Thread(
              () -> {
                try {
                  hook.call(Stage.PROMPT_PRE, "{\"context\":{},\"utterance\":\"hi\"}");
                } catch (HookFailure expected) {
                  // stopped, as intended
                }
              });
      caller.start();
      Thread.sleep(20);
      java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
          new java.util.concurrent.atomic.AtomicReference<>();
      Thread canceller =
          new Thread(
              () -> {
                try {
                  go.await();
                  hook.cancel();
                } catch (Throwable t) {
                  thrown.compareAndSet(null, t);
                }
              });
      Thread closer =
          new Thread(
              () -> {
                try {
                  go.await();
                  hook.close();
                } catch (Throwable t) {
                  thrown.compareAndSet(null, t);
                }
              });
      canceller.start();
      closer.start();
      go.countDown();
      canceller.join(5_000);
      closer.join(5_000);
      caller.join(5_000);
      assertEquals(null, thrown.get(), "round " + round);
      assertFalse(hook.isAlive());
    }
  }

  @Test
  void a_hook_that_throws_is_a_failure_and_the_context_survives_it() throws Exception {
    try (LoadedHook hook =
        load(
            "thrower.js",
            """
                let calls = 0
                export default {
                    name: 'thrower',
                    stages: { 'prompt.pre': { handle() { calls += 1; if (calls === 1) throw new Error('first'); return undefined } } },
                }
                """)) {
      assertThrows(
          HookFailure.class,
          () -> hook.call(Stage.PROMPT_PRE, "{\"context\":{},\"utterance\":\"a\"}"));
      assertTrue(hook.isAlive());
      assertEquals("null", hook.call(Stage.PROMPT_PRE, "{\"context\":{},\"utterance\":\"b\"}"));
    }
  }

  @Test
  void a_handle_that_returns_something_json_cannot_carry_is_a_failure_not_a_null()
      throws Exception {
    // A function is neither null nor undefined, so `?? null` does not catch
    // it; JSON.stringify(a function) is the JS value undefined, and
    // Value.asString() on that silently returns Java null rather than
    // throwing — which would otherwise surface as call() returning the
    // string "null" (a legitimate Nothing decision) for a hook bug that
    // returned no decision at all.
    try (LoadedHook hook =
        load(
            "fn.js",
            """
                export default {
                    name: 'fn',
                    stages: { 'prompt.pre': { handle() { return () => 1 } } },
                }
                """)) {
      HookFailure failed =
          assertThrows(
              HookFailure.class,
              () -> hook.call(Stage.PROMPT_PRE, "{\"context\":{},\"utterance\":\"hi\"}"));
      assertTrue(failed.getMessage().contains("fn"), failed.getMessage());
    }
  }

  @Test
  void a_log_stage_reads_its_origins_and_naming_none_is_every_origin() throws Exception {
    try (LoadedHook hook =
        load(
            "openers.js",
            """
                export default { name: 'openers', stages: {
                    'log.open': { origins: ['turn', 'delegation'], handle() { return { add: 'x' } } },
                    'log.close': { handle() { return undefined } },
                } }
                """)) {
      assertEquals(
          Map.of(Stage.LOG_OPEN, List.of("turn", "delegation"), Stage.LOG_CLOSE, List.of()),
          hook.stages());
    }
  }

  @Test
  void origins_on_a_run_stage_are_refused() {
    HookFailure refused =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "run.js",
                    """
                export default { name: 'run', stages: {
                    'prompt.pre': { origins: ['turn'], handle() { return undefined } } } }
                """));
    assertTrue(
        refused.getMessage().contains("only a log stage is filtered by origin"),
        refused.getMessage());
  }

  @Test
  void tools_on_a_log_stage_are_refused() {
    HookFailure refused =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "log.js",
                    """
                export default { name: 'log', stages: {
                    'delivery.pre': { tools: ['run'], handle() { return undefined } } } }
                """));
    assertTrue(
        refused.getMessage().contains("a log stage is filtered by origins"), refused.getMessage());
  }

  @Test
  void an_origin_the_server_does_not_know_is_refused_rather_than_never_firing() {
    HookFailure refused =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "typo.js",
                    """
                export default { name: 'typo', stages: {
                    'log.open': { origins: ['turns'], handle() { return undefined } } } }
                """));
    assertTrue(refused.getMessage().contains("'turns'"), refused.getMessage());
    assertTrue(refused.getMessage().contains("delegation"), "the refusal names the origins");
  }

  @Test
  void an_empty_origins_list_is_refused_because_it_would_fire_for_nothing() {
    assertThrows(
        HookFailure.class,
        () ->
            load(
                "empty.js",
                """
                export default { name: 'empty', stages: {
                    'log.close': { origins: [], handle() { return undefined } } } }
                """));
  }

  @Test
  void a_single_origin_not_in_a_list_is_refused_as_not_a_list_rather_than_as_empty() {
    HookFailure refused =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "single.js",
                    """
                export default { name: 'single', stages: {
                    'log.open': { origins: 'turn', handle() { return undefined } } } }
                """));
    assertTrue(refused.getMessage().contains("must be a list of names"), refused.getMessage());
    assertFalse(refused.getMessage().contains("at least one"), refused.getMessage());
  }

  /**
   * Spec 2026-09-28-hooks-reach-the-log, amended 2026-09-29: a file written for fold.pre does not
   * load, and its author is told the keep moved to fold.post, which sees the summary.
   */
  @Test
  void a_file_that_still_names_fold_pre_does_not_load_and_is_told_where_its_keep_went() {
    HookFailure refused =
        assertThrows(
            HookFailure.class,
            () ->
                load(
                    "marker.js",
                    """
                export default { name: 'marker', stages: {
                    'fold.pre': { handle() { return { keep: 'marker' } } } } }
                """));
    assertTrue(refused.getMessage().startsWith("marker.js: "), refused.getMessage());
    assertTrue(refused.getMessage().contains("'fold.pre' was removed"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("fold.post now sees the summary"), refused.getMessage());
  }

  @Test
  void fold_post_loads_as_a_log_stage_filtered_by_origins() throws Exception {
    try (LoadedHook hook =
        load(
            "marker.js",
            """
                export default { name: 'marker', stages: {
                    'fold.post': { origins: ['turn'], handle() { return { keep: 'marker' } } } } }
                """)) {
      assertEquals(Map.of(Stage.FOLD_POST, List.of("turn")), hook.stages());
    }
  }
}
