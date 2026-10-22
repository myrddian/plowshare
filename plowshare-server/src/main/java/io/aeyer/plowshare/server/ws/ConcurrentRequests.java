package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.transport.ConcurrentDispatch;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import io.aeyer.plowshare.protocol.transport.SegmentedMessages;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded internal Role coordination. Turns select work under one short state lock; blocking
 * operations and completion callbacks always run outside it. Resource identity is process-wide,
 * while PostgreSQL remains authoritative across processes. No framework runtime is required.
 */
final class ConcurrentRequests implements AutoCloseable {
  interface Delivery extends Runnable {
    /** One physical writer owns at most one handoff, including its final settlement turn. */
    void started();

    CompletionStage<Void> executionFinished();
  }

  @FunctionalInterface
  interface Handler {
    /** Delivery acknowledgement is distinct from handler execution completion. */
    CompletionStage<Void> run(String frame, Delivery delivered);
  }

  private static final int GLOBAL_PENDING = 1024;
  private static final int RESOURCE_KEYS = 2048;
  private static final long CONNECTION_BYTES = 2L * SegmentedMessages.MAX_MESSAGE_BYTES;
  private final ReentrantLock state = new ReentrantLock();
  private final Semaphore changed = new Semaphore(0);
  private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentLinkedQueue<Request> waiting =
      new java.util.concurrent.ConcurrentLinkedQueue<>();
  private final ConcurrentMap<DispatchPolicy.Resource, Integer> references =
      new ConcurrentHashMap<>();
  private final Set<DispatchPolicy.Resource> occupied = ConcurrentHashMap.newKeySet();
  private final PacketBudget budget;
  private final DispatchTelemetry telemetry;
  private final int finiteLimit;
  private final int connectionLimit;
  private final int observerLimit;
  private final CompletableFuture<Void> drained = new CompletableFuture<>();
  private final Thread coordinator;
  private boolean open = true;
  private int retained;
  private int finite;
  private int observers;

  ConcurrentRequests(PacketBudget budget, int perConnection, int global) {
    this(budget, perConnection, global, new DispatchTelemetry());
  }

  ConcurrentRequests(
      PacketBudget budget, int perConnection, int global, DispatchTelemetry telemetry) {
    if (perConnection < 1 || global < perConnection)
      throw new IllegalArgumentException("Invalid request execution bounds");
    this.budget = budget;
    this.telemetry = telemetry;
    connectionLimit = perConnection;
    finiteLimit = global;
    observerLimit = global;
    coordinator = Thread.ofVirtual().name("request-coordination").start(this::coordinate);
  }

  /**
   * Registers a connection whose in-flight window is {@code depth}, the value the server advertised
   * to that client. Admission past it is congestion, answered per request, rather than a protocol
   * violation.
   */
  Connection connect(String id, Handler handler, Runnable failed, int depth) {
    if (depth < 1 || depth > ConcurrentDispatch.MAX_PENDING)
      throw new IllegalArgumentException("Invalid connection request depth");
    var connection = new Connection(id, handler, failed, depth);
    state.lock();
    try {
      if (!open || connections.putIfAbsent(id, connection) != null)
        throw new IllegalStateException("Request connection unavailable");
    } finally {
      state.unlock();
    }
    return connection;
  }

  /**
   * Admission was refused because the dispatch queue is full, not because the request is invalid.
   * Nothing was registered, reserved or started, so the same request id may be offered again once
   * capacity frees. Callers answer it per request; every other {@link IllegalArgumentException}
   * from {@code offer} is a protocol violation.
   */
  static final class CapacityExceededException extends IllegalArgumentException {
    CapacityExceededException() {
      super("Request admission capacity exceeded");
    }
  }

  final class Connection {
    private final String id;
    private final int depth;
    private final Handler handler;
    private final Runnable failed;
    private final ConcurrentMap<String, Request> active = new ConcurrentHashMap<>();
    private boolean open = true;
    private long bytes;
    private int running;
    private int observing;
    private boolean barrier;
    private Request handingOff;

    private Connection(String id, Handler handler, Runnable failed, int depth) {
      this.id = id;
      this.depth = depth;
      this.handler = handler;
      this.failed = failed;
    }

    void offer(String frame, Envelope envelope, String account) {
      ConcurrentDispatch.requireId(envelope.id());
      SegmentedMessages.utf8Length(frame);
      long bytes = 2L * frame.length();
      var request =
          new Request(
              this, envelope.id(), frame, bytes, DispatchPolicy.decode(envelope, account, id));
      state.lock();
      try {
        if (!open || !ConcurrentRequests.this.open)
          throw new IllegalStateException("Request admission closed");
        if (active.containsKey(request.id)) {
          telemetry.duplicateIds.increment();
          telemetry.rejected.increment();
          throw new IllegalArgumentException("Duplicate active request ID");
        }
        long newKeys =
            request.policy.resources().stream().filter(key -> !references.containsKey(key)).count();
        int transferring = handingOff == null ? 0 : 1;
        // Congestion is checked after every validity check above, and before any state is touched:
        // a refusal leaves no active entry, byte reservation or resource reference behind.
        if (active.size() - transferring >= depth
            || retained >= GLOBAL_PENDING
            || bytes > CONNECTION_BYTES - this.bytes
            || newKeys > RESOURCE_KEYS - references.size()) {
          telemetry.rejected.increment();
          throw new CapacityExceededException();
        }
        try {
          budget.acquire(bytes);
        } catch (IllegalArgumentException exhausted) {
          // Shared packet memory is full across all connections: congestion, and retryable. The
          // acquire failed before any state below changed, so there is nothing to unwind.
          telemetry.rejected.increment();
          throw new CapacityExceededException();
        }
        active.put(request.id, request);
        this.bytes += bytes;
        retained++;
        telemetry.admitted.increment();
        telemetry.queued.increment();
        telemetry.requestBytes.add(bytes);
        request
            .policy
            .resources()
            .forEach(
                key -> {
                  if (!references.containsKey(key)) telemetry.resourceKeys.increment();
                  references.merge(key, 1, Integer::sum);
                });
        waiting.add(request);
      } finally {
        state.unlock();
      }
      signal();
    }

    void close() {
      List<Request> discarded = new ArrayList<>();
      state.lock();
      try {
        if (!open) return;
        open = false;
        connections.remove(id, this);
        waiting.removeIf(
            request -> {
              if (request.connection != this) return false;
              discarded.add(request);
              return true;
            });
        for (Request request : discarded) release(request);
        for (Request request : active.values()) {
          request.delivered = true;
          if (request.executed) release(request);
        }
      } finally {
        state.unlock();
      }
      signal();
    }
  }

  private static final class Request {
    final Connection connection;
    final String id;
    final String frame;
    final long bytes;
    final DispatchPolicy policy;
    boolean executed;
    boolean delivered;
    boolean released;
    boolean contended;
    final long admittedAt = System.nanoTime();
    long startedAt;
    final CompletableFuture<Void> executionFinished = new CompletableFuture<>();

    Request(Connection connection, String id, String frame, long bytes, DispatchPolicy policy) {
      this.connection = connection;
      this.id = id;
      this.frame = frame;
      this.bytes = bytes;
      this.policy = policy;
    }
  }

  private void signal() {
    // At most one wake hint is needed: all state transitions are visible under the state lock.
    if (changed.availablePermits() == 0) changed.release();
  }

  private void coordinate() {
    try {
      while (true) {
        changed.acquire();
        List<Request> ready = new ArrayList<>();
        boolean done;
        state.lock();
        try {
          Set<DispatchPolicy.Resource> earlierKeys = new HashSet<>();
          Set<Connection> earlierBarriers = new HashSet<>();
          Set<Connection> earlierWork = new HashSet<>();
          for (var iterator = waiting.iterator(); iterator.hasNext(); ) {
            Request request = iterator.next();
            Connection connection = request.connection;
            boolean observer = request.policy.kind() == DispatchPolicy.Kind.OBSERVER;
            boolean barrier = request.policy.kind() == DispatchPolicy.Kind.BARRIER;
            boolean conflict =
                request.policy.resources().stream()
                    .anyMatch(key -> occupied.contains(key) || earlierKeys.contains(key));
            boolean eligible =
                !connection.barrier
                    && !earlierBarriers.contains(connection)
                    && !conflict
                    && (observer
                        ? observers < observerLimit && connection.observing < connectionLimit
                        : finite < finiteLimit && connection.running < connectionLimit)
                    && (!barrier
                        || connection.running + connection.observing == 0
                            && !earlierWork.contains(connection));
            if (eligible) {
              iterator.remove();
              request.startedAt = System.nanoTime();
              telemetry.queued.decrement();
              telemetry.active.increment();
              if (observer) telemetry.observers.increment();
              telemetry.queue.record(request.startedAt - request.admittedAt);
              if (observer) {
                observers++;
                connection.observing++;
              } else {
                finite++;
                connection.running++;
              }
              if (barrier) connection.barrier = true;
              occupied.addAll(request.policy.resources());
              ready.add(request);
            } else {
              if (conflict && !request.contended) {
                request.contended = true;
                telemetry.contentions.increment();
              }
              earlierKeys.addAll(request.policy.resources());
              earlierWork.add(connection);
              if (barrier) earlierBarriers.add(connection);
            }
          }
          done = !open && retained == 0;
        } finally {
          state.unlock();
        }
        for (Request request : ready)
          Thread.ofVirtual().name("request-operation").start(() -> execute(request));
        if (done) {
          drained.complete(null);
          return;
        }
      }
    } catch (InterruptedException stopping) {
      Thread.currentThread().interrupt();
      drained.completeExceptionally(stopping);
    }
  }

  private void execute(Request request) {
    var once = new AtomicBoolean();
    try {
      request
          .connection
          .handler
          .run(
              request.frame,
              new Delivery() {
                @Override
                public void started() {
                  handoff(request);
                }

                @Override
                public void run() {
                  delivered(request);
                }

                @Override
                public CompletionStage<Void> executionFinished() {
                  return request.executionFinished;
                }
              })
          .whenComplete(
              (ignored, failure) -> {
                if (once.compareAndSet(false, true)) {
                  try {
                    finished(request, failure);
                  } finally {
                    Throwable cause = failure;
                    while (cause instanceof java.util.concurrent.CompletionException
                        && cause.getCause() != null) cause = cause.getCause();
                    // Throwing inside whenComplete would only fail an ignored dependent stage.
                    // Explicitly report a deferred JVM error through the completion thread's
                    // uncaught-error owner, after releasing request ownership and closing it.
                    if (cause instanceof Error fatal) {
                      Thread thread = Thread.currentThread();
                      thread.getUncaughtExceptionHandler().uncaughtException(thread, fatal);
                    }
                  }
                }
              });
    } catch (RuntimeException | Error failure) {
      if (once.compareAndSet(false, true)) finished(request, failure);
      if (failure instanceof Error fatal) throw fatal;
    }
  }

  private void delivered(Request request) {
    state.lock();
    try {
      request.delivered = true;
      if (request.executed) release(request);
    } finally {
      state.unlock();
    }
    signal();
  }

  private void handoff(Request request) {
    state.lock();
    try {
      if (request.released || !request.connection.open) return;
      var previous = request.connection.handingOff;
      if (previous != null && previous != request)
        throw new IllegalStateException("A reply handoff is already settling");
      request.connection.handingOff = request;
    } finally {
      state.unlock();
    }
  }

  private void finished(Request request, Throwable failure) {
    state.lock();
    try {
      request.executed = true;
      telemetry.active.decrement();
      if (request.policy.kind() == DispatchPolicy.Kind.OBSERVER) telemetry.observers.decrement();
      telemetry.execution.record(System.nanoTime() - request.startedAt);
      Connection connection = request.connection;
      if (request.policy.kind() == DispatchPolicy.Kind.OBSERVER) {
        observers--;
        connection.observing--;
      } else {
        finite--;
        connection.running--;
      }
      if (request.policy.kind() == DispatchPolicy.Kind.BARRIER) connection.barrier = false;
      occupied.removeAll(request.policy.resources());
      if (request.delivered || failure != null || !connection.open) release(request);
    } finally {
      state.unlock();
    }
    // Only transport waits on this private completion. Never invoke continuations under state.
    request.executionFinished.complete(null);
    if (failure != null) {
      request.connection.close();
      request.connection.failed.run();
    }
    signal();
  }

  /** Caller holds the short state lock; no callbacks or IO occur here. */
  private void release(Request request) {
    if (request.released) return;
    request.released = true;
    request.connection.active.remove(request.id, request);
    if (request.connection.handingOff == request) request.connection.handingOff = null;
    request.connection.bytes -= request.bytes;
    retained--;
    telemetry.requestBytes.add(-request.bytes);
    if (request.startedAt == 0) telemetry.queued.decrement();
    request
        .policy
        .resources()
        .forEach(
            key ->
                references.computeIfPresent(
                    key,
                    (ignored, count) -> {
                      if (count == 1) {
                        telemetry.resourceKeys.decrement();
                        return null;
                      }
                      return count - 1;
                    }));
    budget.release(request.bytes);
  }

  CompletionStage<Void> drained() {
    return drained;
  }

  void stopAdmission() {
    state.lock();
    try {
      open = false;
      for (Request request : waiting) release(request);
      waiting.clear();
    } finally {
      state.unlock();
    }
    signal();
  }

  @Override
  public void close() {
    state.lock();
    try {
      open = false;
    } finally {
      state.unlock();
    }
    // Close connection-owned output/admission, but let running durable work settle naturally.
    List.copyOf(connections.values()).forEach(Connection::close);
    signal();
  }
}
