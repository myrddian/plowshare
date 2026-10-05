package io.aeyer.plowshare.testpeer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileRequest;
import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Raw test socket for injecting protocol conditions; filesystem answers come from Node. */
public final class SocketPeer implements Closeable {
  public static final String PATH = "v1/files";
  public static final String SESSION_PARAM = "session";
  private final OkHttpClient http = new OkHttpClient.Builder().readTimeout(Duration.ZERO).build();
  private final ObjectMapper json = new ObjectMapper();
  private final String origin, session, bearer;
  private final NodeFiles answers;
  private final TestRooting rooting;
  private final CountDownLatch opened = new CountDownLatch(1);
  private volatile boolean connected;
  private volatile String refusal;
  private WebSocket socket;

  public SocketPeer(String origin, String session, NodeFiles answers) {
    this(origin, session, answers, null, null);
  }

  public SocketPeer(
      String origin, String session, NodeFiles answers, String bearer, TestRooting rooting) {
    this.origin = origin;
    this.session = session;
    this.answers = answers;
    this.bearer = bearer;
    this.rooting = rooting;
  }

  public WebSocket dial(String path, WebSocketListener listener) {
    var url =
        HttpUrl.get(origin)
            .newBuilder()
            .addPathSegments(path)
            .addQueryParameter(SESSION_PARAM, session);
    if (path.equals(PATH)) url.addQueryParameter("source", "1");
    if (path.equals(PATH) && rooting != null)
      url.addQueryParameter("machine", rooting.machine())
          .addQueryParameter("root", rooting.root())
          .addQueryParameter("project", rooting.project());
    var request = new Request.Builder().url(url.build());
    if (bearer != null) request.header("Authorization", "Bearer " + bearer);
    return http.newWebSocket(request.build(), listener);
  }

  public void open() {
    socket =
        dial(
            PATH,
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket ws, Response response) {
                connected = true;
                opened.countDown();
              }

              @Override
              public void onMessage(WebSocket ws, String text) {
                Thread.startVirtualThread(
                    () -> {
                      try {
                        var request = json.readValue(text, FileRequest.class);
                        ws.send(json.writeValueAsString(answers.answer(request)));
                      } catch (Exception failure) {
                        ws.close(1011, "test peer failed");
                      }
                    });
              }

              @Override
              public void onFailure(WebSocket ws, Throwable failure, Response response) {
                refusal = response == null ? "upgrade failed" : Integer.toString(response.code());
                connected = false;
                opened.countDown();
              }

              @Override
              public void onClosing(WebSocket ws, int code, String reason) {
                refusal = reason;
                connected = false;
                ws.close(code, reason);
              }

              @Override
              public void onClosed(WebSocket ws, int code, String reason) {
                connected = false;
              }
            });
  }

  public boolean awaitOpen(Duration timeout) throws InterruptedException {
    return opened.await(timeout.toMillis(), TimeUnit.MILLISECONDS) && connected;
  }

  public boolean isOpen() {
    return connected;
  }

  public String refusal() {
    return refusal;
  }

  @Override
  public void close() {
    connected = false;
    if (socket != null) socket.close(1000, "test complete");
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
