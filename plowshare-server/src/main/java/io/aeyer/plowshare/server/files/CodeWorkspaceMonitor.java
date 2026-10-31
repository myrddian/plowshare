package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.CodeTrackingStatus;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.documents.CodeProjection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Background reconciliation and gated syntax indexing of durable subscriptions. */
public final class CodeWorkspaceMonitor implements AutoCloseable, WorkspaceChanges {
  @FunctionalInterface
  public interface Access {
    ProviderRouter router(CodeWorkspaceStore.Scope scope);
  }

  private final CodeWorkspaceStore store;
  private final Access access;
  private final boolean enabled;
  private java.util.function.Supplier<CodeIndexes> indexes = () -> null;

  public CodeWorkspaceMonitor indexing(java.util.function.Supplier<CodeIndexes> indexes) {
    this.indexes = Objects.requireNonNull(indexes);
    return this;
  }

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "code-tracking-timer");
            thread.setDaemon(true);
            return thread;
          });
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          2,
          r -> {
            Thread thread = new Thread(r, "code-tracking-scan");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicInteger running = new AtomicInteger();
  private volatile boolean closed;
  private boolean started;
  private io.aeyer.plowshare.server.reads.ReadAdmission admission =
      io.aeyer.plowshare.server.reads.ReadAdmission.direct();
  private java.util.function.Consumer<Change> changeAuthority;

  private record ChangeKey(Home home, String owner, String personalSession) {}

  private final ConcurrentMap<ChangeKey, Change> changes = new ConcurrentHashMap<>();
  private final Object changeAdmission = new Object();

  /**
   * Composition supplies live session/project checks; hints never retain those checks as grants.
   */
  public CodeWorkspaceMonitor changes(
      java.util.function.Consumer<Change> authority,
      io.aeyer.plowshare.server.reads.ReadAdmission admission) {
    this.changeAuthority = Objects.requireNonNull(authority);
    this.admission = Objects.requireNonNull(admission);
    return this;
  }

  @Override
  public boolean enabled() {
    return enabled && !closed && changeAuthority != null;
  }

  @Override
  public void changed(Change change) {
    Objects.requireNonNull(change);
    if (!enabled()) return;
    var key =
        new ChangeKey(
            change.home(), change.owner(), change.home().isGlobal() ? change.session() : null);
    synchronized (changeAdmission) {
      // Keep the latest live socket for this principal/workspace. Overflow is recovered by the
      // existing periodic hash scan; a hostile client cannot create an unbounded hint queue.
      if (changes.containsKey(key) || changes.size() < 64) changes.put(key, change);
    }
  }

  private void flushChanges() {
    if (!enabled() || changes.isEmpty()) return;
    var batch = new ArrayList<Change>();
    synchronized (changeAdmission) {
      for (var entry : changes.entrySet()) {
        if (changes.remove(entry.getKey(), entry.getValue())) batch.add(entry.getValue());
      }
    }
    try {
      admission.read(
          "code-change-hints",
          io.aeyer.plowshare.server.reads.ReadAdmission.Work.MAINTENANCE,
          () -> {
            var authorized = new ArrayList<Change>();
            for (var change : batch) {
              try {
                changeAuthority.accept(change);
                authorized.add(change);
              } catch (io.aeyer.plowshare.server.faults.CallerFault
                  | WorkspaceRefusedException denied) {
                // Revoked or departed sessions cannot wake retained registrations.
              }
            }
            if (!authorized.isEmpty()) store.changed(List.copyOf(authorized));
            return null;
          });
    } catch (io.aeyer.plowshare.server.reads.ReadUnavailableException capacity) {
      synchronized (changeAdmission) {
        for (var change : batch) {
          var key =
              new ChangeKey(
                  change.home(),
                  change.owner(),
                  change.home().isGlobal() ? change.session() : null);
          // A reconnect arriving during admission supersedes this batch's socket identity.
          if (changes.size() < 64) changes.putIfAbsent(key, change);
        }
      }
    }
  }

  public CodeWorkspaceMonitor(CodeWorkspaceStore store, Access access, boolean enabled) {
    this.store = Objects.requireNonNull(store);
    this.access = Objects.requireNonNull(access);
    this.enabled = enabled;
  }

  public synchronized void start() {
    if (started || closed || !enabled) return;
    started = true;
    scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
  }

  private void tick() {
    try {
      flushChanges();
      while (!closed && running.get() < 2) {
        var scan = store.claim();
        if (scan.isEmpty()) return;
        running.incrementAndGet();
        try {
          workers.execute(
              () -> {
                try {
                  reconcile(scan.get());
                } finally {
                  running.decrementAndGet();
                }
              });
        } catch (RejectedExecutionException stopped) {
          running.decrementAndGet();
          store.unavailable(scan.get().ticket(), "monitor_stopped");
          return;
        }
      }
    } catch (RuntimeException unavailable) {
      // A database outage leaves the existing lease/observation intact; the next tick retries
      // discovery.
      // Requests expose their own storage failures through tracking_status rather than claiming
      // durability.
    }
  }

  /** Deterministic one-scan entry point, also useful to a maintenance caller. */
  public boolean pollOnce() {
    if (closed || !enabled) return false;
    flushChanges();
    var scan = store.claim();
    if (scan.isEmpty()) return false;
    reconcile(scan.get());
    return true;
  }

  private void reconcile(CodeWorkspaceStore.Scan scan) {
    try {
      var router =
          access.router(scan.scope()); // Fresh membership, definition, grants and session checks.
      var index = indexes.get();
      var map = new WorkspaceCodeMap(router, () -> closed);
      if (index == null) map.fingerprintsOnly();
      else map.metadataOnly().observing(indexObservations(scan.scope(), index));
      var view = map.reconcile(scan.scope().home(), scan.pattern());
      if (closed) store.unavailable(scan.ticket(), "monitor_stopped");
      else {
        // Metadata admission is batched, not a permission lease. Recheck the live definition and
        // provider/session boundary after physical I/O, before generation-fenced publication.
        access.router(scan.scope());
        store.publish(scan.ticket(), view);
      }
    } catch (RuntimeException unavailable) {
      if (unavailable instanceof io.aeyer.plowshare.server.reads.ReadUnavailableException)
        store.defer(scan.ticket());
      else store.unavailable(scan.ticket(), "workspace_access_or_scan_unavailable");
    }
  }

  public CodeMapObservations observations(String agent, String session, String owner) {
    if (!enabled) return CodeMapObservations.NONE;
    boolean attributed = owner != null && !owner.isBlank();
    // Unattributed project writers can dirty existing observations, but cannot enroll or read a
    // registration.
    String writerOwner = attributed ? owner : "@unattributed";
    return new CodeMapObservations() {
      private volatile CodeTrackingStatus failure;
      private volatile boolean watching = true;

      record Mutation(CodeWorkspaceStore.Scope scope, String token) {}

      private final Queue<Mutation> mutations = new ConcurrentLinkedQueue<>();

      private CodeWorkspaceStore.Scope scope(Home home) {
        return new CodeWorkspaceStore.Scope(home, writerOwner, agent, session);
      }

      public boolean supportsIndexes() {
        return attributed && watching && !closed && indexes.get() != null;
      }

      public CodeProjection cached(Home home, CodeWorkspaceStore.IndexSource source) {
        if (!attributed || !watching || closed || failure != null) return null;
        access.router(scope(home));
        var index = indexes.get();
        return index == null ? null : index.cached(scope(home), source);
      }

      public CodeProjection retain(Home home, WorkspaceCodeMap.Entry file, byte[] source) {
        if (!attributed || !watching || closed || failure != null) return null;
        access.router(scope(home));
        var index = indexes.get();
        if (index == null) return null;
        var retained = index.retain(scope(home), file, source);
        if (retained == null)
          throw new WorkspaceRefusedException(
              "syntax processing pending or blocked; inspect information status");
        return retained;
      }

      public Ticket begin(Home home, String pattern) {
        if (!attributed || !watching || closed) return null;
        try {
          access.router(
              scope(home)); // Do not persist a registration after authorization was revoked.
          var ticket = store.begin(scope(home), pattern);
          failure = null;
          return ticket;
        } catch (RuntimeException refused) {
          boolean capacity = refused instanceof CodeWorkspaceStore.CapacityReached;
          failure =
              CodeTrackingStatus.failure(
                  capacity ? "limited" : "unavailable",
                  capacity ? "registration_capacity" : "tracking_registration_unavailable");
          return null;
        }
      }

      public void publish(Ticket ticket, WorkspaceCodeMap.View view) {
        if (ticket == null) return;
        try {
          store.publish(ticket, view);
          failure = null;
        } catch (RuntimeException unavailable) {
          failure = CodeTrackingStatus.failure("unavailable", "tracking_storage_unavailable");
        }
      }

      public void invalidate(Home home) {
        if (!attributed && home.isGlobal()) return;
        try {
          mutations.add(new Mutation(scope(home), store.mutationStarted(scope(home))));
        } catch (RuntimeException unavailable) {
          failure = CodeTrackingStatus.failure("unavailable", "tracking_storage_unavailable");
        }
      }

      public void completed(Home home) {
        if (!attributed && home.isGlobal()) return;
        var mutation = mutations.poll();
        try {
          if (mutation != null) store.mutationFinished(mutation.scope(), mutation.token());
          else store.invalidate(scope(home));
        } catch (RuntimeException unavailable) {
          failure = CodeTrackingStatus.failure("unavailable", "tracking_storage_unavailable");
        }
      }

      public CodeTrackingStatus status(Home home) {
        if (!attributed) return CodeTrackingStatus.failure("disabled", "unattributed");
        if (failure != null) return failure;
        try {
          return store.status(scope(home));
        } catch (RuntimeException unavailable) {
          return CodeTrackingStatus.failure("unavailable", "tracking_storage_unavailable");
        }
      }

      public void stop(Home home) {
        if (!attributed) return;
        try {
          store.forget(scope(home));
          watching = false;
          failure = null;
        } catch (RuntimeException unavailable) {
          failure = CodeTrackingStatus.failure("unavailable", "tracking_storage_unavailable");
        }
      }
    };
  }

  private CodeMapObservations indexObservations(CodeWorkspaceStore.Scope scope, CodeIndexes index) {
    return new CodeMapObservations() {
      public Map<String, IndexState> indexes(
          Home home, List<CodeWorkspaceStore.IndexSource> sources) {
        if (!scope.home().equals(home))
          throw new WorkspaceRefusedException("code tracking scope changed");
        return index.states(scope, sources);
      }

      public boolean supportsIndexes() {
        return true;
      }

      public Ticket begin(Home home, String pattern) {
        return null;
      }

      public void publish(Ticket ticket, WorkspaceCodeMap.View view) {}

      public void invalidate(Home home) {}

      public CodeTrackingStatus status(Home home) {
        return CodeTrackingStatus.state("background");
      }

      public CodeProjection cached(Home home, CodeWorkspaceStore.IndexSource source) {
        access.router(scope);
        return index.cached(scope, source);
      }

      public CodeProjection retain(Home home, WorkspaceCodeMap.Entry file, byte[] source) {
        access.router(scope);
        var retained = index.retain(scope, file, source);
        if (retained == null)
          throw new WorkspaceRefusedException("syntax processing pending or blocked");
        return retained;
      }
    };
  }

  @Override
  public void close() {
    closed = true;
    changes.clear();
    scheduler.shutdownNow();
    workers.shutdownNow();
  }
}
