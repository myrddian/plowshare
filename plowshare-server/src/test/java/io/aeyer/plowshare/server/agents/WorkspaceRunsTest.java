package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.session.Presence;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;

/** Controlled transport availability; no database, providers or unbounded sleeps. */
@Timeout(10)
@SuppressWarnings("try")
class WorkspaceRunsTest {
  private final Presence original = new Presence("files", "machine", "/work/project", "project");
  private final Map<String, Presence> available =
      new ConcurrentHashMap<>(Map.of("files", original));
  private final WorkspaceRuns runs =
      new WorkspaceRuns(id -> Optional.ofNullable(available.get(id)), Duration.ofMillis(5));
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicReference<Job.State> state = new AtomicReference<>(Job.State.RUNNING);
  private final CountDownLatch paused = new CountDownLatch(1);
  private final AgentDefinition agent =
      new AgentDefinition(
          "worker",
          "worker",
          "model",
          List.of(),
          List.of(),
          List.of(),
          8,
          12,
          "Complete the original task",
          true,
          true);

  private WorkspaceRuns.Scope enter() {
    return runs.enter(
        "job_test",
        cancelled::get,
        (next, reason) -> {
          state.set(next);
          if (next == Job.State.PAUSED) paused.countDown();
        });
  }

  @Test
  void a_viewer_or_server_only_personal_run_has_no_file_dependency() {
    available.clear();
    try (var scope = enter()) {
      runs.boundary();
    }
    assertEquals(Job.State.RUNNING, state.get());
  }

  @Test
  void an_offline_interval_cannot_expire_revalidation_or_a_nested_snapshot_budget()
      throws Exception {
    var runs =
        new WorkspaceRuns(
            id -> Optional.ofNullable(available.get(id)),
            Duration.ofMillis(5),
            Duration.ofMillis(100),
            Duration.ofSeconds(30));
    var waiting = new CountDownLatch(1);
    var reads = new AtomicInteger();
    var validated = new AtomicInteger();
    var completed = new CompletableFuture<String>();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var scope =
                          runs.enter(
                              "job_reads",
                              cancelled::get,
                              (next, reason) -> {
                                if (next == Job.State.PAUSED) waiting.countDown();
                              });
                      var definition =
                          runs.definition(
                              agent,
                              () ->
                                  runs.read(
                                      () -> {
                                        assertTrue(
                                            runs.requestBudget(Duration.ofSeconds(1)).toNanos()
                                                > 0);
                                        validated.incrementAndGet();
                                        return null;
                                      }))) {
                    runs.requires("files");
                    completed.complete(
                        runs.read(
                            () -> {
                              String value =
                                  runs.read(
                                      () -> {
                                        if (reads.incrementAndGet() == 1) {
                                          available.clear();
                                          // Direct file requests detect loss at their owning
                                          // request boundary, before the
                                          // enclosing source snapshot sees an exception.
                                          runs.disconnected("files");
                                          runs.boundary();
                                          throw new SessionGoneException(
                                              "The incomplete source generation was discarded");
                                        }
                                        return "fresh snapshot";
                                      });
                              assertTrue(runs.requestBudget(Duration.ofSeconds(1)).toNanos() > 0);
                              return value;
                            }));
                  } catch (Throwable failed) {
                    completed.completeExceptionally(failed);
                  }
                });
    try {
      assertTrue(waiting.await(2, TimeUnit.SECONDS));
      Thread.sleep(150); // Longer than the active snapshot budget, entirely while suspended.
      available.put("files", new Presence("files", "machine", "/work/project", "project"));
      assertEquals("fresh snapshot", completed.get(2, TimeUnit.SECONDS));
      assertEquals(2, reads.get());
      assertEquals(1, validated.get());
    } finally {
      cancelled.set(true);
      worker.interrupt();
      worker.join(1000);
    }
  }

  @Test
  void nested_read_wrappers_share_one_snapshot_rebuild_attempt() {
    var reads = new AtomicInteger();
    try (var scope = enter()) {
      assertThrows(
          SessionGoneException.class,
          () ->
              runs.read(
                  () ->
                      runs.read(
                          () -> {
                            reads.incrementAndGet();
                            throw new SessionGoneException("Incomplete snapshot");
                          })));
    }
    assertEquals(2, reads.get());
  }

  @Test
  void required_files_pause_and_the_same_location_resumes_the_original_stack() throws Exception {
    var ready = new CountDownLatch(1);
    var disconnected = new CountDownLatch(1);
    var continued = new AtomicInteger();
    var finished = new CompletableFuture<Void>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (var scope = enter()) {
                runs.requires("files");
                ready.countDown();
                disconnected.await();
                runs.boundary();
                continued.incrementAndGet();
                finished.complete(null);
              } catch (Throwable failed) {
                finished.completeExceptionally(failed);
              }
            });
    assertTrue(ready.await(2, TimeUnit.SECONDS));
    available.clear();
    disconnected.countDown();
    assertTrue(paused.await(2, TimeUnit.SECONDS));
    assertEquals(0, continued.get());
    available.put("files", new Presence("files", "machine", "/work/project", "project"));
    finished.get(2, TimeUnit.SECONDS);
    assertEquals(1, continued.get());
    assertEquals(Job.State.RUNNING, state.get());
    assertFalse(runs.live("job_test"));
  }

  @Test
  void a_fast_replacement_revalidates_inputs_even_without_observing_an_offline_interval() {
    var checked = new AtomicInteger();
    try (var scope = enter()) {
      runs.requires("files");
      try (var definition = runs.definition(agent, checked::incrementAndGet)) {
        available.put("files", new Presence("files", "machine", "/work/project", "project"));
        runs.boundary();
        runs.boundary();
      }
    }
    assertEquals(1, checked.get());
    assertEquals(Job.State.RUNNING, state.get());
  }

  @Test
  void a_different_root_does_not_resume_and_cancellation_releases_the_wait() throws Exception {
    var ready = new CountDownLatch(1);
    var disconnected = new CountDownLatch(1);
    var resumed = new AtomicBoolean();
    var finished = new CompletableFuture<Void>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (var scope = enter()) {
                runs.requires("files");
                ready.countDown();
                disconnected.await();
                runs.boundary();
                resumed.set(!cancelled.get());
                finished.complete(null);
              } catch (Throwable failed) {
                finished.completeExceptionally(failed);
              }
            });
    assertTrue(ready.await(2, TimeUnit.SECONDS));
    available.put("files", new Presence("files", "machine", "/different", "project"));
    disconnected.countDown();
    assertTrue(paused.await(2, TimeUnit.SECONDS));
    assertFalse(finished.isDone());
    cancelled.set(true);
    finished.get(2, TimeUnit.SECONDS);
    assertFalse(resumed.get());
  }

  @Test
  void an_uncertain_command_remains_blocked_even_when_the_workspace_is_available()
      throws Exception {
    var finished = new CompletableFuture<Boolean>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (var scope = enter()) {
                runs.requires("files");
                FileRequest command = mock(FileRequest.class);
                when(command.op()).thenReturn(FileRequest.RUN);
                when(command.id()).thenReturn("original-request");
                finished.complete(runs.interrupted("files", command));
              } catch (Throwable failed) {
                finished.completeExceptionally(failed);
              }
            });
    assertTrue(paused.await(2, TimeUnit.SECONDS));
    assertFalse(finished.isDone());
    cancelled.set(true);
    assertFalse(finished.get(2, TimeUnit.SECONDS));
  }

  @Test
  void safe_suspension_is_persisted_and_recovery_claims_the_same_job_once() throws Exception {
    var repository = mock(WorkspacePauseRepository.class);
    var owner = mock(WorkspaceRuns.RecoveryOwner.class);
    var snapshot = new AtomicReference<WorkspacePauseRepository.Snapshot>();
    doAnswer(
            call -> {
              snapshot.set(call.getArgument(0));
              return null;
            })
        .when(repository)
        .suspended(any(), anyString());
    when(repository.pending(anyString(), anyInt()))
        .thenAnswer(
            call ->
                snapshot.get() == null
                    ? List.of()
                    : List.of(new WorkspacePauseRepository.Retained(snapshot.get(), 1, "paused")));
    when(repository.claim(ArgumentMatchers.eq("job_test"), ArgumentMatchers.eq(1L), anyString()))
        .thenReturn(true);
    runs.usePersistence(repository, owner);
    var stopped = new CompletableFuture<Void>();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var scope = enter()) {
                    runs.requires("files");
                    runs.checkpoint(
                        "files",
                        "alice",
                        UUID.randomUUID().toString(),
                        () ->
                            List.of(
                                new BudgetCheckpoint.Frame(
                                    "cnv_root", "worker", 1, 2, 8, List.of())));
                    try (var definition = runs.definition(agent, () -> {})) {
                      runs.modelCalls(1);
                      available.clear();
                      runs.completing(
                          "cnv_root",
                          new Outcome(Outcome.Ending.ANSWERED, "Retained paid answer", 2, 1, ""));
                    }
                  } catch (RuntimeException interrupted) {
                    stopped.complete(null);
                  }
                });
    assertTrue(paused.await(2, TimeUnit.SECONDS));
    assertEquals(2, snapshot.get().checkpoint().frames().getFirst().steps());
    runs.shutdown();
    stopped.get(2, TimeUnit.SECONDS);
    worker.join(1000);
    assertEquals("Retained paid answer", snapshot.get().completed().text());
    available.put("files", new Presence("files", "machine", "/work/project", "project"));
    var restarted =
        new WorkspaceRuns(id -> Optional.ofNullable(available.get(id)), Duration.ofMillis(5));
    restarted.usePersistence(repository, owner);
    restarted.recover();
    restarted.recover();
    verify(repository).claim(ArgumentMatchers.eq("job_test"), ArgumentMatchers.eq(1L), anyString());
    verify(owner).resume(snapshot.get().checkpoint());
    assertEquals(Optional.of("job_test"), restarted.restoringJob("cnv_root"));
    assertEquals(
        Optional.of(snapshot.get().checkpoint().frames().getFirst()),
        restarted.restoring("cnv_root"));
    assertEquals(
        "Retained paid answer", restarted.completedOutcome("cnv_root").orElseThrow().text());
    assertEquals(1, restarted.modelCalls("cnv_root"));

    // The transport can flap again after a durable claim but before its first effect. Retiring
    // the newer safe point consumes that claim; starting its obsolete generation must be skipped.
    when(repository.resumed(anyString(), anyString())).thenReturn(true);
    var pausedAgain = new CountDownLatch(1);
    var resumedAgain = new CompletableFuture<Void>();
    Thread restoringWorker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var scope =
                      restarted.enter(
                          "job_test",
                          cancelled::get,
                          (next, reason) -> {
                            if (next == Job.State.PAUSED) pausedAgain.countDown();
                          })) {
                    restarted.checkpoint(
                        "files",
                        "alice",
                        snapshot.get().allowance(),
                        () -> snapshot.get().checkpoint().frames());
                    try (var definition = restarted.definition(agent, () -> {})) {
                      available.clear();
                      restarted.boundary();
                      restarted.recoveryStarted("cnv_root");
                    }
                    resumedAgain.complete(null);
                  } catch (Throwable failed) {
                    resumedAgain.completeExceptionally(failed);
                  }
                });
    try {
      assertTrue(pausedAgain.await(2, TimeUnit.SECONDS));
      available.put("files", new Presence("files", "machine", "/work/project", "project"));
      resumedAgain.get(2, TimeUnit.SECONDS);
      verify(repository, never()).started(anyString(), ArgumentMatchers.anyLong(), anyString());
    } finally {
      cancelled.set(true);
      restoringWorker.interrupt();
      restoringWorker.join(1000);
    }
  }

  @Test
  void cancellation_cannot_be_overwritten_by_a_resume_transition() {
    Job job = new Job("job_test", "worker", null);
    job.transition(Job.State.PAUSED, "waiting for files");
    assertTrue(job.active());
    assertTrue(job.requestCancel());
    job.transition(Job.State.RESUMING, null);
    assertTrue(job.cancelRequested());
    assertFalse(job.requestCancel());
  }

  @Test
  void nested_work_waits_for_every_provider_and_revoked_authority_blocks_resume() throws Exception {
    available.put("other", new Presence("other", "machine", "/work/other", "project"));
    var revalidated = new CountDownLatch(1);
    var parentChecks = new AtomicInteger();
    var finished = new CompletableFuture<Void>();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var scope = enter();
                      var parent = runs.definition(agent, parentChecks::incrementAndGet);
                      var child =
                          runs.definition(
                              agent,
                              () -> {
                                revalidated.countDown();
                                throw new IllegalStateException("Original authority was revoked");
                              })) {
                    runs.requires("files");
                    runs.requires("other");
                    available.clear();
                    runs.boundary();
                    finished.complete(null);
                  } catch (Throwable failed) {
                    finished.completeExceptionally(failed);
                  }
                });
    try {
      assertTrue(paused.await(2, TimeUnit.SECONDS));
      available.put("files", new Presence("files", "machine", "/work/project", "project"));
      assertEquals(0, parentChecks.get());
      assertFalse(finished.isDone());
      available.put("other", new Presence("other", "machine", "/work/other", "project"));
      assertTrue(revalidated.await(2, TimeUnit.SECONDS));
      assertEquals(1, parentChecks.get());
      assertFalse(finished.isDone(), "reconnection cannot waive a retained authority check");
      cancelled.set(true);
      finished.get(2, TimeUnit.SECONDS);
    } finally {
      cancelled.set(true);
      worker.interrupt();
      worker.join(1000);
    }
  }
}
