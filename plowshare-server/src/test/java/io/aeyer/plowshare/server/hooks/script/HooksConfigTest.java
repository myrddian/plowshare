package io.aeyer.plowshare.server.hooks.script;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataConfig;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The {@code projectHooks} bean {@code AgentsConfig.runHooks} looks up by name: real hooks when
 * there is a tree to read them from and nobody switched them off, {@link Hooks#NONE} otherwise —
 * and a context that built them shuts down without a destroy step failing.
 *
 * <p>No slice elsewhere loads {@link HooksConfig}, on the brief's rule that one is added only where
 * a slice already stands {@code AgentsConfig} and a layout up and fails without it; none did. So
 * the bean's three answers are measured here.
 *
 * <p><b>How a failed shutdown is seen.</b> Spring does not throw from a destroy method that fails;
 * {@code DisposableBeanAdapter} logs it at WARN and carries on. So the shutdown check listens to
 * that logger, and {@link #the_listener_does_see_a_destroy_method_that_throws} proves the listener
 * is not deaf — without it, "nothing was logged" would pass for a check that could never fail.
 */
class HooksConfigTest {

  private final ListAppender<ILoggingEvent> destroyLog = new ListAppender<>();
  // By name: the class is package-private in Spring, and its logger is named for it.
  private final Logger adapterLogger =
      (Logger)
          LoggerFactory.getLogger(
              "org.springframework.beans.factory.support.DisposableBeanAdapter");

  @BeforeEach
  void listen() {
    destroyLog.start();
    adapterLogger.addAppender(destroyLog);
  }

  @AfterEach
  void stopListening() {
    adapterLogger.detachAppender(destroyLog);
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(HooksConfig.class, DataConfig.class)
        .withBean(ProjectStore.class, () -> mock(ProjectStore.class));
  }

  private List<ILoggingEvent> warnings() {
    return destroyLog.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
  }

  @Test
  void a_server_that_keeps_a_data_directory_runs_its_projects_hooks(@TempDir Path data) {
    runner()
        .withPropertyValues("plowshare.data.dir=" + data.resolve("owned").toAbsolutePath())
        .run(
            context -> {
              ScriptHooks hooks =
                  assertInstanceOf(ScriptHooks.class, context.getBean("projectHooks"));
              HookEngine engine = context.getBean(HookEngine.class);

              // The shutdown Spring will run, twice over: the destroy step's own
              // log, and the two closes called directly, in the order they run.
              assertDoesNotThrow(context::close);
              assertEquals(List.of(), warnings(), "a destroy method failed on shutdown");
              assertDoesNotThrow(hooks::close);
              assertDoesNotThrow(engine::close);
            });
  }

  @Test
  void the_listener_does_see_a_destroy_method_that_throws() {
    new ApplicationContextRunner()
        .withBean(
            "failsOnClose",
            AutoCloseable.class,
            () ->
                () -> {
                  throw new IllegalStateException("closing failed");
                })
        .run(context -> context.close());

    assertFalse(warnings().isEmpty(), "the shutdown check above would be blind");
  }

  @Test
  void a_server_that_keeps_nothing_has_no_project_hooks() {
    runner().run(context -> assertSame(Hooks.NONE, context.getBean("projectHooks")));
  }

  @Test
  void switched_off_there_are_none_even_with_a_tree(@TempDir Path data) {
    runner()
        .withPropertyValues(
            "plowshare.data.dir=" + data.resolve("owned").toAbsolutePath(),
            "plowshare.hooks.enabled=false")
        .run(context -> assertSame(Hooks.NONE, context.getBean("projectHooks")));
  }

  /** Spec 2026-09-30-local-hooks-are-served: the local layer needs the log's pin and the sets. */
  @Test
  void the_local_layer_is_script_hooks_over_the_archive_and_nothing_without_it(@TempDir Path data) {
    runner()
        .withPropertyValues("plowshare.data.dir=" + data.toAbsolutePath())
        .run(context -> assertSame(Hooks.NONE, context.getBean("localHooks")));
    runner()
        .withPropertyValues("plowshare.data.dir=" + data.toAbsolutePath())
        .withBean(
            io.aeyer.plowshare.server.archive.ConversationStore.class,
            () -> mock(io.aeyer.plowshare.server.archive.ConversationStore.class))
        .withBean(
            io.aeyer.plowshare.server.archive.LocalHookSetStore.class,
            () -> mock(io.aeyer.plowshare.server.archive.LocalHookSetStore.class))
        .run(
            context -> {
              assertInstanceOf(ScriptHooks.class, context.getBean("localHooks"));
              assertDoesNotThrow(context::close);
              assertEquals(List.of(), warnings(), "a destroy method failed on shutdown");
            });
  }

  /**
   * Spec decision 9 as amended 2026-09-30: the bean keys a loaded set by the owner the archive
   * names for the log, so two accounts with the same files never share one.
   */
  @Test
  void the_local_layer_loads_one_set_per_owner_of_the_same_files(@TempDir Path data) {
    List<io.aeyer.plowshare.server.hooks.HookFile> files =
        List.of(
            new io.aeyer.plowshare.server.hooks.HookFile(
                "10-add.js",
                "export default { name:"
                    + " 'add', stages: { 'prompt.pre': { handle() { return { add: 'x', mode:"
                    + " 'durable' } } } } }"));
    String hash = io.aeyer.plowshare.server.hooks.HookFile.hashOf(files);
    io.aeyer.plowshare.server.archive.ConversationStore rows =
        mock(io.aeyer.plowshare.server.archive.ConversationStore.class);
    for (String log : List.of("cnv_enzo", "cnv_enzo_too", "cnv_mallory")) {
      when(rows.localHooksOf(log)).thenReturn(Optional.of(hash));
    }
    when(rows.ownerOf("cnv_enzo")).thenReturn(Optional.of("enzo"));
    when(rows.ownerOf("cnv_enzo_too")).thenReturn(Optional.of("enzo"));
    when(rows.ownerOf("cnv_mallory")).thenReturn(Optional.of("mallory"));
    io.aeyer.plowshare.server.archive.LocalHookSetStore stored =
        mock(io.aeyer.plowshare.server.archive.LocalHookSetStore.class);
    when(stored.find(hash)).thenReturn(Optional.of(files));
    runner()
        .withPropertyValues("plowshare.data.dir=" + data.toAbsolutePath())
        .withBean(io.aeyer.plowshare.server.archive.ConversationStore.class, () -> rows)
        .withBean(io.aeyer.plowshare.server.archive.LocalHookSetStore.class, () -> stored)
        .run(
            context -> {
              ScriptHooks local = (ScriptHooks) context.getBean("localHooks");
              for (String log : List.of("cnv_enzo", "cnv_enzo_too")) {
                local.promptPre(in(log), "hi");
              }
              assertEquals(1, local.loadedLocalSets(), "one owner's logs share a set");
              local.promptPre(in("cnv_mallory"), "hi");
              assertEquals(2, local.loadedLocalSets(), "another owner loads their own");
            });
  }

  private static io.aeyer.plowshare.server.hooks.HookContext in(String conversation) {
    return new io.aeyer.plowshare.server.hooks.HookContext(
        "scribe",
        false,
        java.util.Set.of(),
        "ledger",
        conversation,
        io.aeyer.plowshare.server.hooks.HookContext.SERVER);
  }

  /**
   * Fix round 1, as ruled: a local allow counts only on the log owner's own machine — the serving
   * session held, in the registry, by the account that owns the log.
   */
  @Test
  void the_owner_s_machine_is_a_session_the_registry_binds_to_the_log_s_owner() {
    io.aeyer.plowshare.server.archive.ConversationStore rows =
        mock(io.aeyer.plowshare.server.archive.ConversationStore.class);
    when(rows.ownerOf("cnv_1")).thenReturn(Optional.of("enzo"));
    when(rows.ownerOf("cnv_nobody")).thenReturn(Optional.empty());
    SessionRegistry sessions = new SessionRegistry();
    sessions.attach("s_mine", Role.FILE_PROVIDER, new Object(), "enzo");
    sessions.attach("s_theirs", Role.FILE_PROVIDER, new Object(), "mallory");
    sessions.attach("s_anyone", Role.FILE_PROVIDER, new Object(), null);

    assertTrue(HooksConfig.ownersMachine(rows, sessions, "cnv_1", "s_mine"));
    assertFalse(HooksConfig.ownersMachine(rows, sessions, "cnv_1", "s_theirs"));
    assertFalse(HooksConfig.ownersMachine(rows, sessions, "cnv_1", "s_anyone"));
    assertFalse(HooksConfig.ownersMachine(rows, sessions, "cnv_1", "s_unknown"));
    assertFalse(HooksConfig.ownersMachine(rows, sessions, "cnv_nobody", "s_anyone"));
    assertFalse(HooksConfig.ownersMachine(rows, null, "cnv_1", "s_mine"));
  }
}
