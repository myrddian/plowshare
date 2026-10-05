package io.aeyer.plowshare.testpeer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

/** Invoke the current TS stdio protocol adapter with a real SDK/WebSocket backend. */
public record NodeMcp(String origin, String token) {
  public String exchange(String request) throws IOException {
    return NodeBridge.exchange(
        new ObjectMapper()
            .writeValueAsString(
                Map.of("mode", "mcp", "origin", origin, "token", token, "request", request)));
  }
}
