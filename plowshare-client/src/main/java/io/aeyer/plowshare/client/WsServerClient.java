package io.aeyer.plowshare.client;

/** Legacy CLI/MCP binding to the reusable Java WS SDK. */
public final class WsServerClient extends io.aeyer.plowshare.sdk.WsServerClient
    implements ServerClient {
  public WsServerClient(String origin, String bearer) {
    super(origin, bearer);
  }
}
