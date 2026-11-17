package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.transport.PacketBudget;
import io.aeyer.plowshare.protocol.transport.SegmentedMessages;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One segmented connection's bounded dispatcher. Audited independent reads may finish out of order;
 * every other frame is a barrier that waits for prior reads and completes before later work starts.
 * Packet credits stay on the socket reader. Close discards unstarted work without interrupting or
 * replaying a running operation. Authority is checked by the route callback at actual execution.
 */
final class EventRequests {
  private static final class Request {
    private final String frame;
    private final long bytes;
    private boolean released;
    private final QueuedExternalConversationRequest external;

    Request(String frame, long bytes, QueuedExternalConversationRequest external) {
      this.frame = frame;
      this.bytes = bytes;
      this.external = external;
    }
  }

  private final PacketBudget budget;
  private final BiConsumer<String, QueuedExternalConversationRequest> route;
  private final EventReadCapacity reads;
  private final ArrayDeque<Request> waiting = new ArrayDeque<>();
  private Request selected;
  private boolean open = true;
  private boolean started;
  private int retained;
  private int runningReads;
  private long held;

  EventRequests(PacketBudget budget, Consumer<String> route) {
    this(budget, route, new EventReadCapacity(1, 1));
  }

  EventRequests(PacketBudget budget, Consumer<String> route, EventReadCapacity reads) {
    this(budget, (frame, external) -> route.accept(frame), reads);
  }

  EventRequests(
      PacketBudget budget,
      BiConsumer<String, QueuedExternalConversationRequest> route,
      EventReadCapacity reads) {
    this.budget = budget;
    this.route = route;
    this.reads = reads;
  }

  synchronized void offer(String frame) {
    offer(frame, null);
  }

  synchronized boolean offer(String frame, QueuedExternalConversationRequest external) {
    if (!open) return false;
    SegmentedMessages.utf8Length(frame);
    long bytes = 2L * frame.length();
    // Count and bytes include running and globally waiting operations, not just queue membership.
    if (retained >= EventChannelHandler.PENDING
        || bytes > 2L * SegmentedMessages.MAX_MESSAGE_BYTES - held)
      throw new IllegalArgumentException("event request queue capacity exceeded");
    budget.acquire(bytes);
    held += bytes;
    retained++;
    waiting.addLast(new Request(frame, bytes, external));
    notifyAll();
    return true;
  }

  synchronized void start(String socketId) {
    if (started || !open) return;
    started = true;
    Thread.ofVirtual().name("event-requests-" + socketId).start(this::drain);
  }

  void close() {
    var discarded = new ArrayList<Request>();
    synchronized (this) {
      open = false;
      discarded.addAll(waiting);
      for (var request : waiting) release(request);
      waiting.clear();
      if (selected != null) {
        release(selected);
        discarded.add(selected);
      }
      notifyAll();
    }
    for (var request : discarded) if (request.external != null) request.external.close();
  }

  private synchronized boolean isOpen() {
    return open;
  }

  private synchronized void release(Request request) {
    if (request.released) return;
    request.released = true;
    held -= request.bytes;
    retained--;
    budget.release(request.bytes);
    notifyAll();
  }

  private void drain() {
    try {
      dispatch();
    } catch (InterruptedException stopping) {
      Thread.currentThread().interrupt();
    } finally {
      close();
    }
  }

  private void dispatch() throws InterruptedException {
    while (true) {
      Request next;
      synchronized (this) {
        while (open && waiting.isEmpty()) wait();
        if (!open) return;
        next = waiting.removeFirst();
        selected = next;
      }
      boolean transferred = false;
      try {
        boolean independent = EventReadPolicy.independent(next.frame);
        synchronized (this) {
          while (open && runningReads >= (independent ? reads.perConnection() : 1)) wait();
          if (!open) return;
          if (!independent) selected = null;
        }
        if (!independent) {
          // The coordinator itself executes ordered work, so it cannot start subsequent reads.
          route.accept(next.frame, next.external);
        } else if (reads.acquire(this::isOpen)) {
          boolean launched = false;
          try {
            synchronized (this) {
              if (!open) return;
              selected = null;
              runningReads++;
              try {
                Thread.ofVirtual().name("event-read").start(() -> read(next));
                transferred = true;
                launched = true;
              } finally {
                if (!launched) runningReads--;
              }
            }
          } finally {
            if (!launched) reads.release();
          }
        }
      } finally {
        if (!transferred) release(next);
        synchronized (this) {
          if (selected == next) selected = null;
        }
      }
    }
  }

  private void read(Request request) {
    try {
      route.accept(request.frame, request.external);
    } catch (RuntimeException | Error failure) {
      close();
      throw failure;
    } finally {
      reads.release();
      synchronized (this) {
        runningReads--;
        release(request);
      }
    }
  }
}
