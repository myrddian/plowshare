package io.aeyer.plowshare.server.llm;

import okhttp3.HttpUrl;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Opens a websocket on the pool's own HTTP client.
 *
 * <p><b>This exists for the containment invariant, and it is the websocket counterpart of {@code
 * OpenAiTransport.metadata(String)}, which {@code OpenAiCompatible.probe(String)} forwards to.</b>
 * {@code InvariantsTest} names the files in {@code main} allowed to hold an {@code OkHttpClient},
 * and a vendor transport that opened its own would be another place configuring timeouts and a
 * connection pool for a box that already has one configured. So the same division applies as for
 * {@code probe}: the vendor knows <em>which</em> URL to open, and the pool's transport knows
 * <em>how</em> — whose client, whose dispatcher, whose connect timeout.
 *
 * <p>Authentication is deliberately not this interface's business. {@code /v1} authenticates with a
 * header on every request, and LM Studio's websocket namespace authenticates with a frame sent
 * after the socket is open; only the caller knows which it needs, so this opens the connection and
 * nothing more.
 */
public interface WebSocketOpener {

  /**
   * Opens a websocket to {@code url}, delivering events to {@code listener}.
   *
   * <p>Returns immediately: the connection is established on the client's own threads, and a
   * failure to connect arrives as {@code onFailure} rather than as an exception from here.
   */
  WebSocket openWebSocket(HttpUrl url, WebSocketListener listener);
}
