package io.aeyer.plowshare.testpeer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.sdk.ServerClient;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;

/** Test socket pair for server routing/events; no executable Java session client is retained. */
public final class SessionPeer implements Closeable {
  public static final String EVENTS_PATH = "v1/events";
  private final String id = UUID.randomUUID().toString();
  private final ServerClient server;
  private final SocketPeer files;
  private final ArrayBlockingQueue<JobEvent> events = new ArrayBlockingQueue<>(256);
  private final AtomicInteger dropped = new AtomicInteger();
  private final CountDownLatch opened = new CountDownLatch(1);
  private volatile boolean listening;
  private volatile String failure;
  private WebSocket listener;

  public SessionPeer(ServerClient server, TestWorkspace workspace) {
    this(server, workspace, null, null);
  }

  public SessionPeer(
      ServerClient server, TestWorkspace workspace, String bearer, TestRooting rooted) {
    this.server = server;
    this.files = new SocketPeer(server.baseUrl(), id, new NodeFiles(workspace), bearer, rooted);
  }

  public String id() {
    return id;
  }

  public void attach(Duration timeout) throws IOException {
    files.open();
    listener =
        files.dial(
            EVENTS_PATH,
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket ws, Response response) {
                listening = true;
                opened.countDown();
              }

              @Override
              public void onMessage(WebSocket ws, String text) {
                try {
                  if (!events.offer(new ObjectMapper().readValue(text, JobEvent.class)))
                    dropped.incrementAndGet();
                } catch (IOException invalid) {
                  failure = "invalid job event";
                  ws.close(1011, failure);
                }
              }

              @Override
              public void onFailure(WebSocket ws, Throwable problem, Response response) {
                failure = response == null ? "upgrade failed" : Integer.toString(response.code());
                listening = false;
                opened.countDown();
              }

              @Override
              public void onClosing(WebSocket ws, int code, String reason) {
                listening = false;
                ws.close(code, reason);
              }

              @Override
              public void onClosed(WebSocket ws, int code, String reason) {
                listening = false;
              }
            });
    try {
      if (!files.awaitOpen(timeout)
          || !opened.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
          || !listening) {
        close();
        throw new IOException(
            "session fixture could not attach: " + files.refusal() + " " + failure);
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      close();
      throw new IOException("attach interrupted", stopped);
    }
  }

  public boolean providing() {
    return files.isOpen();
  }

  public boolean listening() {
    return listening;
  }

  public int dropped() {
    return dropped.get();
  }

  public String submit(String agent, String task, String project, String conversation)
      throws IOException {
    if (!providing()) throw new IOException("file peer disconnected");
    return server.run(agent, task, project, id, conversation).id();
  }

  public ServerClient.JobStatus job(String job) throws IOException {
    return server.job(job);
  }

  public JobEvent nextEvent(Duration wait) throws InterruptedException {
    return events.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    listening = false;
    if (listener != null) listener.close(1000, "test complete");
    files.close();
    if (server instanceof AutoCloseable closing)
      try {
        closing.close();
      } catch (Exception problem) {
        throw new IllegalStateException("fixture close failed", problem);
      }
  }
}
