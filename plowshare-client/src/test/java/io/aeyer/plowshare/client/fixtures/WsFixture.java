package io.aeyer.plowshare.client.fixtures;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.frames.Code;
import java.net.Socket;
import java.util.List;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okhttp3.mockwebserver.Dispatcher;
import okio.Buffer;

/**
 * Reuses legacy output fixtures through real WS. Logical HTTP-shaped requests below exist only in
 * the fixture; no HTTP application call leaves the migrated client.
 */
public final class WsFixture {
  private static final ObjectMapper JSON = new ObjectMapper();

  private WsFixture() {}

  public static Dispatcher wrap(Dispatcher fixture) {
    return new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest upgrade) throws InterruptedException {
        if (!"plowshare-sdk".equals(upgrade.getHeader("User-Agent")))
          return fixture.dispatch(upgrade);
        return new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  @Override
                  public void onMessage(WebSocket ws, String text) {
                    try {
                      JsonNode frame = JSON.readTree(text);
                      MockResponse answer = fixture.dispatch(logical(frame));
                      int status = Integer.parseInt(answer.getStatus().split(" ")[1]);
                      Code code =
                          java.util.Arrays.stream(Code.values())
                              .filter(c -> c.httpStatus() == status)
                              .findFirst()
                              .orElse(Code.INTERNAL_ERROR);
                      ObjectNode result = JSON.createObjectNode();
                      result.put("code", code.name());
                      var body = answer.getBody();
                      if (body != null && body.size() > 0) {
                        JsonNode value = JSON.readTree(body.clone().readUtf8());
                        if (status < 400) result.set("payload", value);
                        else
                          result.put(
                              "said",
                              value
                                  .path("detail")
                                  .asText(value.path("message").asText("fixture refused")));
                      }
                      ObjectNode reply = JSON.createObjectNode();
                      reply.set("id", frame.get("id"));
                      reply.set("type", frame.get("type"));
                      reply.put("protocol_version", "plowshare-v1");
                      reply.set("payload", result);
                      ws.send(JSON.writeValueAsString(reply));
                    } catch (Exception failure) {
                      ws.cancel();
                      throw new AssertionError(failure);
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                });
      }
    };
  }

  private static RecordedRequest logical(JsonNode frame) throws Exception {
    String type = frame.path("type").asText();
    ObjectNode p = (ObjectNode) frame.path("payload").deepCopy();
    String method = "GET", path;
    switch (type) {
      case "memory.write" -> {
        method = "POST";
        path = "/v1/memories";
      }
      case "memory.recall", "memory.navigate", "memory.digest" -> {
        method = "POST";
        path = "/v1/memories/" + type.substring(7);
      }
      case "memory.read" -> path = "/v1/memories/" + p.remove("memory").asText();
      case "memory.index" -> path = "/v1/memories/index";
      case "web.search", "web.fetch" -> {
        method = "POST";
        path = "/v1/" + type.substring(4);
      }
      case "agent.run" -> {
        method = "POST";
        path = "/v1/agents/" + p.remove("agent").asText() + "/runs";
      }
      case "agent.curate" -> {
        method = "POST";
        path = "/v1/curate";
      }
      case "job.status" -> path = "/v1/jobs/" + p.remove("job").asText();
      case "job.cancel" -> {
        method = "POST";
        path = "/v1/jobs/" + p.remove("job").asText() + "/cancel";
      }
      case "conversation.open" -> {
        method = "POST";
        path = "/v1/conversations";
      }
      case "conversation.list" -> path = "/v1/conversations";
      case "conversation.search" -> path = "/v1/entries/search";
      case "conversation.chat",
              "conversation.trajectory",
              "conversation.context",
              "conversation.compactions" ->
          path =
              "/v1/conversations/" + p.remove("conversation").asText() + "/" + type.substring(13);
      case "project.define" -> {
        method = "POST";
        path = "/v1/projects";
      }
      case "project.forget" -> {
        method = "DELETE";
        path = "/v1/projects/" + p.remove("project").asText();
      }
      case "project.lend", "project.unlend", "project.workspace", "project.move" -> {
        method = "POST";
        path = "/v1/projects/" + p.remove("project").asText() + "/" + type.substring(8);
      }
      case "proposal.list" -> path = "/v1/proposals";
      case "proposal.resolve" -> {
        method = "POST";
        path = "/v1/proposals/" + p.remove("proposal").asText() + "/resolve";
      }
      case "document.list" -> path = "/v1/documents";
      case "document.detail" -> path = "/v1/documents/" + p.remove("document").asText();
      case "document.citations" -> path = "/v1/documents/citations";
      case "document.stance", "document.ask" -> {
        method = "POST";
        path = "/v1/documents/" + p.remove("document").asText() + "/" + type.substring(9);
      }
      case "document.search", "document.rank", "document.retrieve" -> {
        method = "POST";
        path = "/v1/documents/" + type.substring(9);
      }
      default -> throw new AssertionError("fixture needs " + type);
    }
    var url = HttpUrl.get("http://fixture.invalid" + path).newBuilder();
    if (method.equals("GET")) {
      var fields = p.fields();
      while (fields.hasNext()) {
        var pair = fields.next();
        if (!pair.getValue().isNull())
          url.addQueryParameter(pair.getKey(), pair.getValue().asText());
      }
    }
    var body = new Buffer();
    if (!method.equals("GET")) body.writeUtf8(JSON.writeValueAsString(p));
    HttpUrl target = url.build();
    String requestPath =
        target.encodedPath() + (target.encodedQuery() == null ? "" : "?" + target.encodedQuery());
    try (var socket = new Socket()) {
      return new RecordedRequest(
          method + " " + requestPath + " HTTP/1.1",
          new Headers.Builder().build(),
          List.of(),
          body.size(),
          body,
          0,
          socket);
    }
  }
}
