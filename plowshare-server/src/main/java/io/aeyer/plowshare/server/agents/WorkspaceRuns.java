package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.files.ClientWork;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.SessionOwners;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Retains one execution stack across a workspace outage. A dependency pins the serving session and
 * location, independently of the viewer. Delegates on the same virtual thread share the root's
 * fences. No model slot, transaction or command permit may be held at a boundary.
 */
public final class WorkspaceRuns implements ClientWork, WorkspaceExecution {
  /** Existing execution owner validates retained authority before restoring an original root. */
  public interface RecoveryOwner {
    boolean busy(String root);

    void require(WorkspacePauseRepository.Snapshot snapshot);

    void resume(BudgetCheckpoint checkpoint);

    Origin origin(String conversation);
  }

  private record Restoring(WorkspacePauseRepository.Retained retained, int index) {}

  private final ConcurrentMap<String, Restoring> restoring = new ConcurrentHashMap<>();
  private volatile boolean stopping;
  private final Set<String> draining = ConcurrentHashMap.newKeySet();
  private final Set<String> retainedJobs = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<String, Execution> live = new ConcurrentHashMap<>();
  private WorkspacePauseRepository repository;
  private RecoveryOwner owner;
  private SessionOwners sessions = SessionOwners.NONE;
  private final String processOwner = UUID.randomUUID().toString();
  private final AtomicBoolean recovering = new AtomicBoolean();
  private String recoveryCursor = "";

  public void usePersistence(WorkspacePauseRepository repository, RecoveryOwner owner) {
    this.repository = Objects.requireNonNull(repository);
    this.owner = Objects.requireNonNull(owner);
  }

  public void useSessions(SessionOwners sessions) {
    this.sessions = Objects.requireNonNull(sessions);
  }

  /** Pin execution to its logical attachment before capturing callbacks or durable frames. */
  public String logicalSession(String session) {
    return sessions.canonical(session);
  }

  private record Dependency(Presence location) {}

  private static final class Execution {
    final Map<String, Presence> lostGenerations = new LinkedHashMap<>();
    Long readDeadline;
    int readRetries;
    final Map<String, Dependency> dependencies = new LinkedHashMap<>();
    final BooleanSupplier cancelled;
    final BiConsumer<Job.State, String> changed;
    final String job;
    final List<String> definitions = new ArrayList<>();
    final List<Integer> modelCalls = new ArrayList<>();
    String completedIn;
    Outcome completed;
    final List<Runnable> validators = new ArrayList<>();
    Supplier<List<BudgetCheckpoint.Frame>> frames;
    String session;
    String executor;
    String allowance;
    volatile boolean persisted;
    boolean recoveryClaimConsumed;
    long renewAfter;
    boolean validating;
    WorkspacePauseRepository.Uncertain uncertain;
    String blocked;

    Execution(String job, BooleanSupplier cancelled, BiConsumer<Job.State, String> changed) {
      this.job = job;
      this.cancelled = cancelled;
      this.changed = changed;
    }
  }

  private final Function<String, Optional<Presence>> available;
  private final long cancellationCheckNanos;
  private final Duration snapshotTimeout;
  private final long renewNanos;
  private final ThreadLocal<Execution> current = new ThreadLocal<>();

  public WorkspaceRuns(Function<String, Optional<Presence>> available, Duration cancellationCheck) {
    this(available, cancellationCheck, Duration.ofSeconds(30), Duration.ofSeconds(30));
  }

  public WorkspaceRuns(
      Function<String, Optional<Presence>> available,
      Duration cancellationCheck,
      Duration snapshotTimeout,
      Duration ownerLease) {
    this.available = Objects.requireNonNull(available);
    if (snapshotTimeout.isNegative()
        || snapshotTimeout.isZero()
        || ownerLease.compareTo(Duration.ofSeconds(3)) < 0)
      throw new IllegalArgumentException(
          "Finite workspace snapshot and lease budgets are required");
    this.snapshotTimeout = snapshotTimeout;
    this.renewNanos = ownerLease.toNanos() / 3;
    if (cancellationCheck.isNegative()
        || cancellationCheck.isZero()
        || cancellationCheck.compareTo(Duration.ofSeconds(2)) > 0)
      throw new IllegalArgumentException(
          "Workspace cancellation checks must be within two seconds");
    cancellationCheckNanos = cancellationCheck.toNanos();
  }

  /** Enter only on the root job thread. Nested delegates retain its context. */
  public Scope enter(String job, BooleanSupplier cancelled, BiConsumer<Job.State, String> changed) {
    if (current.get() != null) return () -> {};
    Execution execution = new Execution(job, cancelled, changed);
    Restoring recovered =
        restoring.values().stream()
            .filter(entry -> entry.retained().snapshot().job().equals(job))
            .findFirst()
            .orElse(null);
    if (recovered != null) {
      execution.completedIn = recovered.retained().snapshot().completedIn();
      execution.completed = recovered.retained().snapshot().completed();
      execution.uncertain = recovered.retained().snapshot().uncertain();
      for (Presence presence : recovered.retained().snapshot().dependencies())
        execution.dependencies.put(presence.session(), new Dependency(presence));
    }
    if (live.putIfAbsent(job, execution) != null)
      throw new IllegalStateException("Workspace job already has a worker");
    current.set(execution);
    return () -> {
      current.remove();
      live.remove(job, execution);
    };
  }

  @Override
  public void requires(String session) {
    Execution execution = current.get();
    if (execution == null || session == null) return;
    available
        .apply(session)
        .ifPresent(
            presence -> execution.dependencies.putIfAbsent(session, new Dependency(presence)));
  }

  @Override
  public void beforeRequest(String session) {
    requires(session);
    boundary();
    Execution execution = current.get();
    if (execution != null && execution.cancelled.getAsBoolean())
      throw new SessionGoneException("The paused workspace execution was cancelled");
  }

  /**
   * Wait without active execution resources. Connection identity and location are checked again
   * after every wake; a replacement folder never satisfies the retained dependency. Cancellation is
   * checked with a finite interval even if no transport event arrives.
   */
  public void boundary() {
    Execution execution = current.get();
    if (execution == null || execution.validating) return;
    boolean paused = false;
    Long suspendedReadBudget = null;
    int suspendedReadRetries = 0;
    try {
      while (!execution.cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
        if (draining.contains(execution.job))
          throw new IllegalStateException("Workspace checkpoint retained for server restart");
        String reason = execution.blocked;
        boolean replaced = false;
        if (reason == null) {
          for (var entry : execution.dependencies.entrySet()) {
            var now = available.apply(entry.getKey());
            Presence expected = entry.getValue().location();
            if (now.isEmpty()
                || !expected.project().equals(now.get().project())
                || !expected.sameLocation(now.get())
                || execution.lostGenerations.get(entry.getKey()) == now.get()) {
              reason = "Waiting for the required client workspace to reconnect";
              break;
            }
            replaced |= now.get() != expected;
          }
        }
        if ((reason != null || replaced)
            && suspendedReadBudget == null
            && execution.readDeadline != null) {
          long remaining = execution.readDeadline - System.nanoTime();
          if (remaining <= 0)
            throw new WorkspaceUnavailableException("Workspace snapshot deadline expired");
          suspendedReadBudget = remaining;
          suspendedReadRetries = execution.readRetries;
          execution.readDeadline = null;
        }
        if (reason == null) {
          paused |= replaced;
          if (paused) {
            execution.changed.accept(Job.State.RESUMING, null);
            execution.validating = true;
            try {
              for (Runnable validator : List.copyOf(execution.validators)) validator.run();
            } catch (RuntimeException refused) {
              execution.blocked =
                  "Workspace recovery is blocked because its authority or execution inputs could not be validated";
              persist(execution, execution.blocked);
              execution.changed.accept(Job.State.PAUSED, execution.blocked);
              continue;
            } finally {
              execution.validating = false;
            }
            if (stopping && execution.persisted) {
              draining.add(execution.job);
              throw new IllegalStateException("Workspace checkpoint retained for server restart");
            }
            if (repository != null
                && execution.persisted
                && !repository.resumed(execution.job, processOwner))
              throw new IllegalStateException(
                  "The workspace continuation was claimed by another execution owner");
            if (stopping && execution.persisted) {
              persist(execution, "Waiting for the required client workspace to reconnect");
              draining.add(execution.job);
              throw new IllegalStateException("Workspace checkpoint retained for server restart");
            }
            execution.persisted = false;
            // A second outage during restoration can publish a newer checkpoint before the
            // original recovery reaches its first effect. The owner-fenced retirement above
            // consumes that claim too; its older generation must not be started afterwards.
            if (restoring.values().stream()
                .anyMatch(saved -> saved.retained().snapshot().job().equals(execution.job)))
              execution.recoveryClaimConsumed = true;
            if (restoring.values().stream()
                .noneMatch(saved -> saved.retained().snapshot().job().equals(execution.job)))
              retainedJobs.remove(execution.job);
            execution.dependencies.replaceAll(
                (session, dependency) ->
                    available.apply(session).map(Dependency::new).orElse(dependency));
            execution.lostGenerations.clear();
            execution.changed.accept(Job.State.RUNNING, null);
          }
          return;
        }
        if (!paused) {
          execution.changed.accept(Job.State.PAUSING, reason);
          if (persist(execution, reason)) execution.changed.accept(Job.State.PAUSED, reason);
          paused = true;
        }
        if (repository != null
            && execution.persisted
            && System.nanoTime() - execution.renewAfter >= 0) {
          if (!repository.renew(execution.job, processOwner))
            throw new IllegalStateException("The workspace suspension lease was withdrawn");
          execution.renewAfter = System.nanoTime() + renewNanos;
        }
        LockSupport.parkNanos(this, cancellationCheckNanos);
      }
      if (Thread.currentThread().isInterrupted())
        throw new SessionGoneException("The paused workspace execution was interrupted");
    } finally {
      if (suspendedReadBudget != null) {
        execution.readDeadline = System.nanoTime() + suspendedReadBudget;
        execution.readRetries = suspendedReadRetries;
      }
      if (paused && execution.cancelled.getAsBoolean())
        execution.changed.accept(Job.State.RUNNING, null);
    }
  }

  @Override
  public boolean interrupted(String session, FileRequest request) {
    Execution execution = current.get();
    if (execution == null) return false;
    requires(session);
    if (ClientWork.readOnly(request)) disconnected(session);
    if (!ClientWork.readOnly(request)) {
      execution.uncertain =
          new WorkspacePauseRepository.Uncertain(session, request.id(), request.op());
      execution.blocked =
          "A client operation has an unknown outcome. Inspect its original receipt; "
              + "the operation will not be replayed. Cancel this run after resolving the outcome.";
    }
    boundary();
    return !execution.cancelled.getAsBoolean();
  }

  @Override
  public void disconnected(String session) {
    Execution execution = current.get();
    if (execution == null) return;
    Dependency dependency = execution.dependencies.get(session);
    if (dependency != null) execution.lostGenerations.putIfAbsent(session, dependency.location());
  }

  @Override
  public Duration requestBudget(Duration requested) {
    Execution execution = current.get();
    if (execution == null || execution.readDeadline == null) return requested;
    long remaining = execution.readDeadline - System.nanoTime();
    if (remaining <= 0)
      throw new WorkspaceUnavailableException("Workspace snapshot deadline expired");
    return Duration.ofNanos(Math.min(remaining, requested.toNanos()));
  }

  /** Rebuild one incomplete pure source snapshot, preserving its original remaining active time. */
  public <T> T read(Supplier<T> read) {
    Execution execution = current.get();
    if (execution == null) return read.get();
    boolean ownsDeadline = execution.readDeadline == null;
    if (ownsDeadline) {
      execution.readDeadline = System.nanoTime() + snapshotTimeout.toNanos();
      execution.readRetries = 0;
    }
    try {
      try {
        return read.get();
      } catch (SessionGoneException lost) {
        if (execution.readDeadline == null) throw lost;
        long remaining = execution.readDeadline - System.nanoTime();
        if (remaining <= 0 || execution.readRetries != 0) throw lost;
        // Recovery validation has its own bounded reads. It must not inherit the interrupted
        // snapshot's deadline, which may have expired during an arbitrarily long offline period.
        // Nested readers share the restored deadline and one retry for the entire snapshot.
        execution.readDeadline = null;
        boundary();
        if (execution.cancelled.getAsBoolean()) throw lost;
        execution.readDeadline = System.nanoTime() + remaining;
        execution.readRetries = 1;
        return read.get();
      }
    } finally {
      if (ownsDeadline) execution.readDeadline = null;
    }
  }

  /** Register the current typed stack supplier; it is read only when a suspension occurs. */
  public void checkpoint(
      String session,
      String executor,
      String allowance,
      Supplier<List<BudgetCheckpoint.Frame>> frames) {
    Execution execution = current.get();
    if (execution == null) return;
    execution.session = session;
    execution.executor = executor;
    execution.allowance = allowance;
    execution.frames = frames;
  }

  /**
   * Definition snapshots are compared at recovery; validators recheck current authority and rules.
   */
  public Scope definition(AgentDefinition definition, Runnable validate) {
    Execution execution = current.get();
    if (execution == null) return () -> {};
    String fingerprint = fingerprint(definition);
    Restoring saved =
        restoring.isEmpty() || execution.frames == null
            ? null
            : restoring.get(execution.frames.get().getLast().conversation());
    execution.definitions.add(
        saved == null ? fingerprint : saved.retained().snapshot().definitions().get(saved.index()));
    execution.modelCalls.add(
        saved == null ? 0 : saved.retained().snapshot().modelCalls().get(saved.index()));
    execution.validators.add(validate);
    if (saved != null
        && !saved.retained().snapshot().definitions().get(saved.index()).equals(fingerprint)) {
      execution.blocked =
          "Workspace recovery is blocked because its agent definition or filesystem rules changed";
      boundary();
    }
    return () -> {
      execution.definitions.removeLast();
      execution.modelCalls.removeLast();
      execution.validators.removeLast();
    };
  }

  private static String fingerprint(AgentDefinition definition) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(definition.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private boolean persist(Execution execution, String reason) {
    if (repository == null) return true;
    if (execution.frames == null || execution.executor == null || execution.dependencies.isEmpty())
      return false;
    List<BudgetCheckpoint.Frame> frames = execution.frames.get();
    if (frames.size() != execution.definitions.size()) return false;
    repository.suspended(
        new WorkspacePauseRepository.Snapshot(
            execution.job,
            new BudgetCheckpoint(execution.session, execution.executor, frames),
            execution.allowance,
            execution.dependencies.values().stream().map(Dependency::location).toList(),
            execution.definitions,
            execution.modelCalls,
            reason,
            execution.blocked != null,
            execution.completedIn,
            execution.completed,
            execution.uncertain),
        processOwner);
    retainedJobs.add(execution.job);
    execution.persisted = true;
    execution.renewAfter = System.nanoTime() + renewNanos;
    return true;
  }

  public void modelCalls(int calls) {
    Execution execution = current.get();
    if (execution != null && !execution.modelCalls.isEmpty())
      execution.modelCalls.set(execution.modelCalls.size() - 1, calls);
  }

  @Override
  public int modelCalls(String conversation) {
    Restoring saved = conversation == null ? null : restoring.get(conversation);
    return saved == null ? 0 : saved.retained().snapshot().modelCalls().get(saved.index());
  }

  public Optional<Outcome> completedOutcome(String conversation) {
    Restoring saved = conversation == null ? null : restoring.get(conversation);
    if (saved == null || !conversation.equals(saved.retained().snapshot().completedIn()))
      return Optional.empty();
    return Optional.of(saved.retained().snapshot().completed());
  }

  /**
   * A paid completion is retained before waiting; recovery returns it without another model call.
   */
  public void completing(String conversation, Outcome outcome) {
    Execution execution = current.get();
    if (execution == null || outcome.ending() != Outcome.Ending.ANSWERED) return;
    execution.completedIn = conversation;
    execution.completed = outcome;
    try {
      boundary();
    } finally {
      execution.completed = null;
      execution.completedIn = null;
    }
  }

  /** Stop safely paused workers without fabricating a cancellation or retiring their checkpoint. */
  public void shutdown() {
    stopping = true;
    live.forEach(
        (job, execution) -> {
          if (execution.persisted) draining.add(job);
        });
  }

  public boolean draining(String job) {
    return draining.contains(job);
  }

  public boolean dependent() {
    Execution execution = current.get();
    return execution != null && !execution.dependencies.isEmpty();
  }

  public boolean retained(String job) {
    return retainedJobs.contains(job);
  }

  public boolean live(String job) {
    return live.containsKey(job);
  }

  /** A persisted cancellation wins over late completion, including from another server process. */
  public Outcome finished(String job, Outcome outcome) {
    if (repository == null) return outcome;
    if (retainedJobs.contains(job)) {
      var saved = repository.find(job);
      if (saved.isPresent() && "cancelled".equals(saved.get().state()))
        outcome = cancelled(saved.get().snapshot());
      if (outcome.ending() == Outcome.Ending.ANSWERED
          || outcome.ending() == Outcome.Ending.CANCELLED) repository.completed(job, processOwner);
      else if (restoring.values().stream()
          .anyMatch(entry -> entry.retained().snapshot().job().equals(job)))
        repository.blocked(
            job, "Workspace restoration stopped; inspect the original work before retrying");
    }
    restoring
        .entrySet()
        .removeIf(entry -> entry.getValue().retained().snapshot().job().equals(job));
    retainedJobs.remove(job);
    return outcome;
  }

  public Optional<Outcome> cancelRetained(String job) {
    if (repository == null) return Optional.empty();
    return repository.cancel(job).map(WorkspaceRuns::cancelled);
  }

  private static Outcome cancelled(WorkspacePauseRepository.Snapshot snapshot) {
    var frame = snapshot.checkpoint().frames().getFirst();
    return new Outcome(
        Outcome.Ending.CANCELLED,
        "The retained paused run was cancelled.",
        frame.steps(),
        snapshot.modelCalls().getFirst(),
        "");
  }

  public Optional<String> restoringJob(String conversation) {
    Restoring saved = conversation == null ? null : restoring.get(conversation);
    return saved == null ? Optional.empty() : Optional.of(saved.retained().snapshot().job());
  }

  /** Durable status for a safely paused worker after server restart, before files reconnect. */
  public Optional<Job> retainedJob(String id) {
    if (repository == null) return Optional.empty();
    return repository
        .find(id)
        .filter(saved -> !"done".equals(saved.state()))
        .map(
            saved -> {
              var snapshot = saved.snapshot();
              var frame = snapshot.checkpoint().frames().getFirst();
              Job job =
                  new Job(
                      id,
                      frame.agent(),
                      null,
                      0,
                      frame.conversation(),
                      owner.origin(frame.conversation()));
              if ("cancelled".equals(saved.state())) job.finish(cancelled(snapshot));
              else {
                retainedJobs.add(id);
                job.transition(Job.State.PAUSED, snapshot.reason());
              }
              return job;
            });
  }

  @Override
  public boolean awaiting(String conversation) {
    return conversation != null && repository != null && repository.awaiting(conversation);
  }

  @Override
  public Optional<BudgetCheckpoint.Frame> restoring(String conversation) {
    Restoring saved = conversation == null ? null : restoring.get(conversation);
    return saved == null
        ? Optional.empty()
        : Optional.of(saved.retained().snapshot().checkpoint().frames().get(saved.index()));
  }

  @Override
  public Optional<BudgetCheckpoint.Frame> restoringChild(String conversation) {
    Restoring saved = conversation == null ? null : restoring.get(conversation);
    if (saved == null) return Optional.empty();
    var frames = saved.retained().snapshot().checkpoint().frames();
    return saved.index() + 1 == frames.size()
        ? Optional.empty()
        : Optional.of(frames.get(saved.index() + 1));
  }

  @Override
  public Optional<String> restorationDecision(String conversation) {
    return restoring(conversation)
        .map(
            frame ->
                "The required workspace reconnected. Continue the same task with its retained results and allowance.");
  }

  @Override
  public void recoveryStarted(String conversation) {
    Restoring saved = conversation == null ? null : restoring.get(conversation);
    Execution execution = current.get();
    if (saved != null
        && saved.index() == 0
        && (execution == null || !execution.recoveryClaimConsumed)) {
      repository.started(
          saved.retained().snapshot().job(), saved.retained().generation(), processOwner);
      if (execution != null) execution.recoveryClaimConsumed = true;
    }
  }

  @Override
  public void restored(String conversation) {
    // Keep the frame map until the root finishes, so a nested continuation cannot lose its owner.
  }

  /**
   * One bounded page per activation. A cursor prevents offline earlier jobs starving later work.
   */
  public void recover() {
    if (repository == null || stopping || !recovering.compareAndSet(false, true)) return;
    try {
      List<WorkspacePauseRepository.Retained> candidates = repository.pending(recoveryCursor, 100);
      recoveryCursor = candidates.isEmpty() ? "" : candidates.getLast().snapshot().job();
      for (var saved : candidates) {
        var snapshot = saved.snapshot();
        if (live(snapshot.job()) || restoring.containsKey(snapshot.root())) continue;
        if (!"paused".equals(saved.state())) {
          repository.blocked(
              snapshot.job(),
              "Server interruption during restoration; no uncertain effects were replayed");
          continue;
        }
        if (snapshot.blocked()
            || snapshot.dependencies().stream()
                .anyMatch(
                    expected ->
                        available
                            .apply(expected.session())
                            .filter(
                                now ->
                                    expected.project().equals(now.project())
                                        && expected.sameLocation(now))
                            .isEmpty())) continue;
        if (owner.busy(snapshot.root())) continue;
        try {
          owner.require(snapshot);
          if (!repository.claim(snapshot.job(), saved.generation(), processOwner)) continue;
          retainedJobs.add(snapshot.job());
          for (int index = 0; index < snapshot.checkpoint().frames().size(); index++)
            restoring.put(
                snapshot.checkpoint().frames().get(index).conversation(),
                new Restoring(saved, index));
          owner.resume(snapshot.checkpoint());
        } catch (RuntimeException failed) {
          restoring
              .entrySet()
              .removeIf(
                  entry -> entry.getValue().retained().snapshot().job().equals(snapshot.job()));
          repository.blocked(
              snapshot.job(), "Workspace recovery could not revalidate the original execution");
        }
      }
    } finally {
      recovering.set(false);
    }
  }
}
