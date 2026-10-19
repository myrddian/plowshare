package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.transport.PacketBudget;
import io.aeyer.plowshare.protocol.transport.SegmentedMessages;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * One segmented connection's bounded, serial operation dispatcher. Packet acknowledgments stay on
 * the socket reader even while a domain operation waits on persistence. Closing discards queued
 * operations; an operation already running keeps its existing completion semantics and is never
 * replayed or interrupted by this dispatcher.
 */
final class EventRequests {
  private record Request(String frame, long bytes) {}

  private final PacketBudget budget;
  private final Consumer<String> route;
  private final ArrayDeque<Request> waiting = new ArrayDeque<>();
  private boolean open = true;
  private long held;

  EventRequests(PacketBudget budget, Consumer<String> route) {
    this.budget = budget;
    this.route = route;
  }

  synchronized void offer(String frame) {
    if (!open) return;
    // Bound both count and bytes, including the operation in flight. Retained UTF-16
    // uses the same process-wide budget as packet assembly, after assembly releases its own.
    SegmentedMessages.utf8Length(frame);
    long bytes = 2L * frame.length();
    if (waiting.size() >= EventChannelHandler.PENDING
        || bytes > 2L * SegmentedMessages.MAX_MESSAGE_BYTES - held)
      throw new IllegalArgumentException("event request queue capacity exceeded");
    budget.acquire(bytes);
    held += bytes;
    waiting.addLast(new Request(frame, bytes));
    notifyAll();
  }

  void start(String socketId) {
    Thread.ofVirtual().name("event-requests-" + socketId).start(this::drain);
  }

  synchronized void close() {
    open = false;
    for (var request : waiting) release(request);
    waiting.clear();
    notifyAll();
  }

  private synchronized void release(Request request) {
    held -= request.bytes();
    budget.release(request.bytes());
  }

  private void drain() {
    try {
      dispatch();
    } finally {
      close();
    }
  }

  private void dispatch() {
    while (true) {
      Request next;
      synchronized (this) {
        while (open && waiting.isEmpty()) {
          try {
            wait();
          } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            close();
            return;
          }
        }
        if (!open) return;
        next = waiting.removeFirst();
      }
      try {
        route.accept(next.frame());
      } finally {
        release(next);
      }
    }
  }
}
