package io.aeyer.plowshare.server.ws;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

/**
 * Idle sockets get one native ping at a time. Only its matching pong satisfies the deadline.
 * Bookkeeping never performs I/O while locked. Probes use nonblocking write-lock admission and at
 * most one probe/close task per live socket, so a slow peer cannot block the shared scheduler.
 */
final class ScheduledWebSocketKeepAlive implements WebSocketKeepAlive {
  private static final Logger log = LoggerFactory.getLogger(ScheduledWebSocketKeepAlive.class);
  private static final String ATTACHMENT = ScheduledWebSocketKeepAlive.class.getName();
  private final ConcurrentHashMap<String, Live> connections = new ConcurrentHashMap<>();
  private final WebSocketKeepAliveProperties properties;
  private final LongSupplier clock;
  private final Executor tasks;
  private volatile boolean stopped;

  ScheduledWebSocketKeepAlive(WebSocketKeepAliveProperties properties) {
    this(
        properties,
        System::nanoTime,
        action -> Thread.ofVirtual().name("websocket-keepalive").start(action));
  }

  ScheduledWebSocketKeepAlive(
      WebSocketKeepAliveProperties properties, LongSupplier clock, Executor tasks) {
    this.properties = Objects.requireNonNull(properties);
    this.clock = Objects.requireNonNull(clock);
    this.tasks = Objects.requireNonNull(tasks);
  }

  @Override
  public WebSocketHandler decorate(WebSocketHandler handler) {
    return properties.enabled() ? new Handler(handler) : handler;
  }

  /** Spring owns the timer; shutdown fences it before disposing transport state. */
  @Scheduled(fixedDelayString = "${plowshare.transport.keepalive.check-interval:PT5S}")
  public void check() {
    if (!stopped) connections.values().forEach(Live::check);
  }

  @Override
  public void close() {
    stopped = true;
    for (var live : connections.values()) {
      if (live.retire()) tasks.execute(() -> live.closeTransport(CloseStatus.GOING_AWAY));
    }
  }

  private final class Handler extends WebSocketHandlerDecorator implements SubProtocolCapable {
    Handler(WebSocketHandler delegate) {
      super(delegate);
    }

    @Override
    public List<String> getSubProtocols() {
      return getDelegate() instanceof SubProtocolCapable capable
          ? capable.getSubProtocols()
          : List.of();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
      var live = new Live(session);
      session.getAttributes().put(ATTACHMENT, live);
      connections.put(session.getId(), live);
      try {
        if (stopped) {
          live.close(CloseStatus.GOING_AWAY);
          return;
        }
        super.afterConnectionEstablished(live);
      } catch (Exception failure) {
        live.retire();
        throw failure;
      }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message)
        throws Exception {
      var live = attached(session);
      if (live == null) return;
      live.received(message);
      super.handleMessage(live, message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception)
        throws Exception {
      var live = attached(session);
      super.handleTransportError(live == null ? session : live, exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
        throws Exception {
      var live = attached(session);
      if (live != null) live.retire();
      try {
        super.afterConnectionClosed(live == null ? session : live, status);
      } finally {
        session.getAttributes().remove(ATTACHMENT);
      }
    }

    private Live attached(WebSocketSession session) {
      return session.getAttributes().get(ATTACHMENT) instanceof Live live ? live : null;
    }
  }

  private final class Live extends WebSocketSessionDecorator {
    // Ordinary event writes, packet credits and file writes all pass this same wrapper.
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Object state = new Object();
    private long activity = clock.getAsLong();
    private long sequence;
    private long sentAt;
    private Long outstanding;
    private boolean probeQueued;
    private boolean retired;

    Live(WebSocketSession delegate) {
      super(delegate);
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
      writeLock.lock();
      try {
        super.sendMessage(message);
        synchronized (state) {
          activity = clock.getAsLong();
        }
      } finally {
        writeLock.unlock();
      }
    }

    void received(WebSocketMessage<?> message) {
      synchronized (state) {
        if (retired) return;
        if (message instanceof PongMessage pong) {
          var bytes = pong.getPayload().duplicate();
          if (outstanding != null
              && bytes.remaining() == Long.BYTES
              && bytes.getLong() == outstanding) {
            outstanding = null;
            activity = clock.getAsLong();
          }
        } else {
          activity = clock.getAsLong();
        }
      }
    }

    void check() {
      boolean expired;
      synchronized (state) {
        if (retired) return;
        long now = clock.getAsLong();
        expired = outstanding != null && now - sentAt >= properties.pongTimeout().toNanos();
        if (!expired) {
          if (outstanding != null
              || probeQueued
              || now - activity < properties.interval().toNanos()) return;
          probeQueued = true;
        }
      }
      if (expired) {
        if (retire())
          tasks.execute(
              () ->
                  closeTransport(
                      CloseStatus.SESSION_NOT_RELIABLE.withReason(
                          "WebSocket heartbeat pong deadline expired")));
      } else {
        tasks.execute(this::probe);
      }
    }

    private void probe() {
      boolean locked = writeLock.tryLock();
      try {
        if (!locked) return; // An ordinary write keeps the path active; never queue behind it.
        long nonce;
        synchronized (state) {
          if (retired
              || stopped
              || outstanding != null
              || clock.getAsLong() - activity < properties.interval().toNanos()) return;
          nonce = ++sequence;
          outstanding = nonce;
          sentAt = clock.getAsLong();
        }
        super.sendMessage(new PingMessage(ByteBuffer.allocate(Long.BYTES).putLong(0, nonce)));
      } catch (IOException | RuntimeException failure) {
        if (retire())
          closeTransport(
              CloseStatus.SESSION_NOT_RELIABLE.withReason("WebSocket heartbeat write failed"));
      } finally {
        if (locked) writeLock.unlock();
        synchronized (state) {
          probeQueued = false;
        }
      }
    }

    boolean retire() {
      synchronized (state) {
        if (retired) return false;
        retired = true;
      }
      connections.remove(getId(), this);
      return true;
    }

    @Override
    public void close() throws IOException {
      close(CloseStatus.NORMAL);
    }

    @Override
    public void close(CloseStatus status) throws IOException {
      retire();
      super.close(status);
    }

    private void closeTransport(CloseStatus status) {
      try {
        super.close(status);
      } catch (IOException | IllegalStateException alreadyClosed) {
        log.debug("WebSocket keepalive cleanup found an already closed transport");
      }
    }
  }
}
