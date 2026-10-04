package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.LocalHookSetStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.SpeakerHandles;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.LogOpen;
import io.aeyer.plowshare.server.orchestrations.Delivery;
import io.aeyer.plowshare.server.orchestrations.Orchestrations;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The log stages bean is built in a real context and handed to every door that uses it: {@link
 * Compaction}, whose {@code logFor} every machine log — {@link JobStore}'s among them — opens
 * through, the orchestration engine, and the orchestration and event deliveries (spec
 * 2026-09-28-hooks-reach-the-log, slice 1).
 */
class LogStagesConfigTest {

  private final ApplicationContextRunner archive =
      new ApplicationContextRunner()
          .withUserConfiguration(LogStagesConfig.class)
          .withBean(ConversationStore.class, () -> mock(ConversationStore.class))
          .withBean(TurnStore.class, () -> mock(TurnStore.class))
          .withBean(EntryStore.class, () -> mock(EntryStore.class));

  private static final LogStages.LogOpened OPENED =
      new LogStages.LogOpened(
          "cnv_1", Origin.SUBMISSION, Home.of("payments"), "scribe", false, null);

  @Test
  void the_stages_run_the_project_s_hooks_and_are_handed_to_every_door_that_uses_them() {
    Hooks project = mock(Hooks.class);
    when(project.logOpen(any(), any())).thenReturn(LogOpen.NOTHING);
    Compaction compaction = mock(Compaction.class);
    Orchestrations orchestrations = mock(Orchestrations.class);
    Delivery delivery = mock(Delivery.class);
    Dispatcher dispatcher = mock(Dispatcher.class);
    io.aeyer.plowshare.server.ws.ApprovalFrames approvalFrames =
        mock(io.aeyer.plowshare.server.ws.ApprovalFrames.class);

    archive
        .withBean("projectHooks", Hooks.class, () -> project)
        .withBean(Compaction.class, () -> compaction)
        .withBean(Orchestrations.class, () -> orchestrations)
        .withBean(Delivery.class, () -> delivery)
        .withBean(Dispatcher.class, () -> dispatcher)
        .withBean(io.aeyer.plowshare.server.ws.ApprovalFrames.class, () -> approvalFrames)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              LogStages stages = context.getBean(LogStages.class);
              assertInstanceOf(HookedLogStages.class, stages);
              verify(compaction).useLogStages(stages);
              verify(orchestrations).useLogStages(stages);
              verify(delivery).useLogStages(stages);
              verify(dispatcher).useLogStages(stages);
              verify(approvalFrames).useLogStages(stages);

              stages.opened(OPENED);

              verify(project).logOpen(any(), any());
            });
  }

  /**
   * No hooks, compaction, orchestrations, turn registry, inbox or agent registry: it still boots
   * and runs.
   */
  @Test
  void a_context_with_only_the_archive_still_builds_the_stages() {
    archive.run(
        context -> {
          assertNull(context.getStartupFailure());
          LogStages stages = context.getBean(LogStages.class);
          assertDoesNotThrow(() -> stages.opened(OPENED));
          verify(context.getBean(TurnStore.class))
              .remember(
                  argThat(
                      opening ->
                          opening.contains("Current date:")
                              && opening.contains("captured when the log opened")
                              && opening.contains("use get_date")));
          assertDoesNotThrow(() -> stages.closed("cnv_1", "answered"));
        });
  }

  /** Spec 2026-09-30-local-hooks-are-served decision 5: harness, project, then local. */
  @Test
  void the_stages_run_the_local_layer_after_the_project_s() {
    Hooks project = mock(Hooks.class);
    Hooks local = mock(Hooks.class);
    when(project.logOpen(any(), any())).thenReturn(LogOpen.NOTHING);
    when(local.logOpen(any(), any())).thenReturn(LogOpen.NOTHING);

    archive
        .withBean("projectHooks", Hooks.class, () -> project)
        .withBean("localHooks", Hooks.class, () -> local)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              context.getBean(LogStages.class).opened(OPENED);
              InOrder order = inOrder(project, local);
              order.verify(project).logOpen(any(), any());
              order.verify(local).logOpen(any(), any());
            });
  }

  /**
   * No file channel in this context: no session can serve hooks, the stages still boot, and the
   * server says once which collaborator it lacks.
   */
  @Test
  void a_context_without_a_file_channel_pins_nothing_and_says_so() {
    ListAppender<ILoggingEvent> said = new ListAppender<>();
    Logger logger = (Logger) LoggerFactory.getLogger(LogStagesConfig.class);
    said.start();
    logger.addAppender(said);
    try {
      archive.run(context -> assertSame(LocalHooks.NONE, context.getBean(LocalHooks.class)));
    } finally {
      logger.detachAppender(said);
    }

    List<ILoggingEvent> warnings =
        said.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(
        warnings.get(0).getFormattedMessage().contains("no file channel"),
        warnings.get(0).getFormattedMessage());
  }

  /**
   * Spec 2026-09-30-local-hooks-are-served: the production pin asks the three real lookups — the
   * registry's bound account, the listener's handle and the file channel's. A session held by the
   * log's owner on every count is read; one where any single count names another account is not, so
   * a lookup wired to the wrong bean, or stubbed, fails here.
   */
  @Test
  void
      the_pin_reads_only_a_session_the_owner_holds_on_the_registry_the_listener_and_the_file_channel() {
    Pinned all = pinWith("enzo", "enzo", "enzo");
    assertEquals(1, all.records().size(), all.records().toString());
    assertEquals(
        HookRecord.FAILED,
        all.records().get(0).decision(),
        "held by the owner on all three counts, the session is read, and this one's disk"
            + " cannot answer");
    assertEquals(1, all.reads());

    for (Pinned other :
        List.of(
            pinWith("mallory", "enzo", "enzo"),
            pinWith("enzo", "mallory", "enzo"),
            pinWith("enzo", "enzo", "mallory"))) {
      assertEquals(List.of(), other.records(), "nothing is read or said");
      assertEquals(0, other.reads());
    }
  }

  private record Pinned(List<HookRecord> records, int reads) {}

  /** One pin of cnv_1, owned by enzo, from session s1, in a context wired as production is. */
  private Pinned pinWith(String bound, String listener, String provider) {
    ConversationStore conversations = mock(ConversationStore.class);
    when(conversations.ownerOf("cnv_1")).thenReturn(Optional.of("enzo"));
    ProjectStore projects = mock(ProjectStore.class);
    when(projects.id("payments")).thenReturn(7L);
    io.aeyer.plowshare.server.ws.FileChannelHandler files =
        mock(io.aeyer.plowshare.server.ws.FileChannelHandler.class);
    when(files.handleOf("s1")).thenReturn(Optional.of(provider));
    when(files.ask(any(), any(), any())).thenThrow(new IllegalStateException("no disk here"));
    SpeakerHandles speakers = mock(SpeakerHandles.class);
    when(speakers.handleOf("s1")).thenReturn(Optional.of(listener));
    SessionRegistry sessions = new SessionRegistry();
    sessions.attach("s1", Role.FILE_PROVIDER, new Object(), bound);
    PresenceRegistry presences = new PresenceRegistry();
    presences.declare(new Presence("s1", "laptop", "/home/example/payments", "payments"));
    LogStages.LogOpened opened =
        new LogStages.LogOpened(
            "cnv_1", Origin.SUBMISSION, Home.of("payments"), "scribe", false, null, "s1", null);
    AtomicReference<Pinned> pinned = new AtomicReference<>();

    new ApplicationContextRunner()
        .withUserConfiguration(LogStagesConfig.class)
        .withBean(ConversationStore.class, () -> conversations)
        .withBean(TurnStore.class, () -> mock(TurnStore.class))
        .withBean(EntryStore.class, () -> mock(EntryStore.class))
        .withBean(ProjectStore.class, () -> projects)
        .withBean(LocalHookSetStore.class, () -> mock(LocalHookSetStore.class))
        .withBean(io.aeyer.plowshare.server.ws.FileChannelHandler.class, () -> files)
        .withBean(
            io.aeyer.plowshare.server.ws.SocketAuthorization.class,
            () -> mock(io.aeyer.plowshare.server.ws.SocketAuthorization.class))
        .withBean(SpeakerHandles.class, () -> speakers)
        .withBean(SessionRegistry.class, () -> sessions)
        .withBean(PresenceRegistry.class, () -> presences)
        .run(
            context -> {
              LocalHooks pins =
                  assertInstanceOf(PinnedLocalHooks.class, context.getBean(LocalHooks.class));
              List<HookRecord> records = pins.pin(opened);
              pinned.set(
                  new Pinned(
                      records,
                      mockingDetails(files).getInvocations().stream()
                          .filter(call -> call.getMethod().getName().equals("ask"))
                          .toList()
                          .size()));
            });
    return pinned.get();
  }
}
