package io.aeyer.plowshare.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aeyer.plowshare.protocol.Incoming;
import io.aeyer.plowshare.sdk.IncomingClient;
import io.aeyer.plowshare.sdk.Plowshare;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import okhttp3.HttpUrl;

/** A2A HTTP terminates here. All Plowshare messages, receipts and reads use the SDK's WS. */
public final class Receiver implements AutoCloseable {
  public interface Bridge {
    Incoming.Task receive(Incoming.Receive request) throws IOException;

    Incoming.Task status(Incoming.Id id) throws IOException;

    Incoming.Task cancel(Incoming.Id id) throws IOException;

    JsonNode catalog() throws IOException;
  }

  public record Config(
      String bind,
      int port,
      String publicUrl,
      String project,
      String agent,
      Map<String, String> clients,
      long waitMillis) {
    public Config {
      if (clients != null) clients = Map.copyOf(clients);
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  public static final String COMMAND_EXTENSION = "urn:plowshare:a2a:commands:v1";
  private final Config config;
  private final Bridge bridge;
  private final HttpServer server;
  private final java.util.concurrent.ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();
  private final String rpcPath;
  private volatile JsonNode card;
  private volatile long cardExpires;
  private volatile Set<String> commands = Set.of();

  public Receiver(Config config, Plowshare sdk) throws IOException {
    this(
        config,
        new Bridge() {
          private final IncomingClient incoming = new IncomingClient(sdk);

          public Incoming.Task receive(Incoming.Receive request) throws IOException {
            return incoming.receive(request);
          }

          public Incoming.Task status(Incoming.Id id) throws IOException {
            return incoming.status(id);
          }

          public Incoming.Task cancel(Incoming.Id id) throws IOException {
            return incoming.cancel(id);
          }

          public JsonNode catalog() throws IOException {
            return sdk.request(
                    "incoming.catalog",
                    Map.of("project", config.project(), "agent", config.agent()))
                .requirePayload();
          }
        });
  }

  public Receiver(Config config, Bridge bridge) throws IOException {
    this.config = Objects.requireNonNull(config);
    this.bridge = Objects.requireNonNull(bridge);
    HttpUrl url = HttpUrl.parse(config.publicUrl());
    if (url == null
        || !url.username().isEmpty()
        || !url.password().isEmpty()
        || url.query() != null
        || url.fragment() != null
        || url.encodedPath().equals("/"))
      throw new IllegalArgumentException(
          "receiver publicUrl must name the exact HTTP(S) RPC path without credentials, query or fragment");
    if (config.project() == null
        || config.project().isBlank()
        || config.agent() == null
        || config.agent().isBlank()
        || config.clients() == null
        || config.clients().isEmpty()
        || config.waitMillis() < 1
        || config.waitMillis() > 300000)
      throw new IllegalArgumentException(
          "receiver requires project, agent, configured clients and a 1..300000 ms blocking deadline");
    for (var client : config.clients().entrySet())
      if (!client.getKey().matches("[a-zA-Z0-9_.-]{1,64}")
          || client.getValue() == null
          || client.getValue().isBlank())
        throw new IllegalArgumentException(
            "receiver client names and bearer credentials must be nonblank");
    if (new HashSet<>(config.clients().values()).size() != config.clients().size())
      throw new IllegalArgumentException(
          "each receiver client requires a distinct bearer credential");
    rpcPath = url.encodedPath();
    refreshCard();
    server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 32);
    server.setExecutor(executor);
    server.createContext("/.well-known/agent-card.json", this::handleCard);
    server.createContext(rpcPath, this::handleRpc);
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private synchronized JsonNode refreshCard() throws IOException {
    if (card != null && System.currentTimeMillis() < cardExpires) return card;
    JsonNode catalog = bridge.catalog();
    JsonNode row = catalog.isObject() ? catalog : null;
    if (catalog.isArray())
      for (JsonNode candidate : catalog)
        if (config.agent().equals(candidate.path("name").asText())
            && candidate.path("served").asBoolean()) {
          row = candidate;
          break;
        }
    if (row == null
        || !row.path("served").asBoolean()
        || !config.agent().equals(row.path("name").asText()))
      throw new IOException("receiver agent is not served in the configured project");
    var skills = JSON.createArrayNode();
    skills
        .addObject()
        .put("id", "message")
        .put("name", config.agent())
        .put("description", row.path("description").asText())
        .putArray("tags")
        .add("message");
    var allowed = new HashSet<String>();
    for (JsonNode command : row.path("commands")) {
      String name = command.path("command").asText();
      if (!name.startsWith("/skill:") && !name.startsWith("/orchestration:")) continue;
      allowed.add(name);
      var skill =
          skills
              .addObject()
              .put("id", name)
              .put("name", command.path("name").asText())
              .put("description", command.path("description").asText());
      skill.putArray("tags").add(command.path("kind").asText());
      skill.putObject("metadata").set("plowshareCommand", command.deepCopy());
    }
    var value =
        JSON.createObjectNode()
            .put("name", config.agent())
            .put("description", row.path("description").asText())
            .put("version", "0.1.0");
    value
        .putArray("supportedInterfaces")
        .addObject()
        .put("url", config.publicUrl())
        .put("protocolBinding", "JSONRPC")
        .put("protocolVersion", "1.0");
    value
        .putObject("capabilities")
        .put("streaming", false)
        .put("pushNotifications", false)
        .put("extendedAgentCard", false)
        .putArray("extensions")
        .addObject()
        .put("uri", COMMAND_EXTENSION)
        .put("required", false)
        .put(
            "description",
            "Explicit granted Plowshare commands via message.metadata.plowshareCommand; plain text invokes no skill automatically.");
    value
        .putObject("securitySchemes")
        .putObject("clientBearer")
        .putObject("httpAuthSecurityScheme")
        .put("scheme", "Bearer");
    value
        .putArray("securityRequirements")
        .addObject()
        .putObject("schemes")
        .putObject("clientBearer")
        .putArray("list");
    value.putArray("defaultInputModes").add("text/plain");
    value.putArray("defaultOutputModes").add("text/plain");
    value.set("skills", skills);
    if (JSON.writeValueAsBytes(value).length > 64 * 1024)
      throw new IOException("Receiving Agent Card exceeds the supported 64 KiB discovery limit");
    commands = Set.copyOf(allowed);
    card = value;
    cardExpires = System.currentTimeMillis() + 60000;
    return card;
  }

  private void handleCard(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!exchange.getRequestURI().getPath().equals("/.well-known/agent-card.json")
          || !exchange.getRequestMethod().equals("GET")) {
        respond(exchange, 404, Map.of("error", "Not found"));
        return;
      }
      try {
        exchange.getResponseHeaders().set("Cache-Control", "max-age=60");
        respond(exchange, 200, refreshCard());
      } catch (IOException unavailable) {
        respond(exchange, 503, Map.of("error", "Agent discovery unavailable"));
      }
    }
  }

  private String client(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
    byte[] supplied = header.substring(7).getBytes(StandardCharsets.UTF_8);
    for (var client : config.clients().entrySet())
      if (MessageDigest.isEqual(supplied, client.getValue().getBytes(StandardCharsets.UTF_8)))
        return client.getKey();
    return null;
  }

  private void handleRpc(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!exchange.getRequestURI().getPath().equals(rpcPath)
          || !exchange.getRequestMethod().equals("POST")) {
        respond(exchange, 404, Map.of("error", "Not found"));
        return;
      }
      String client = client(exchange);
      if (client == null) {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        respond(exchange, 401, Map.of("error", "Authentication required"));
        return;
      }
      if (!"1.0".equals(exchange.getRequestHeaders().getFirst("A2A-Version"))) {
        respond(exchange, 400, Map.of("error", "A2A-Version: 1.0 required"));
        return;
      }
      String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
      String media =
          contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
      if (!Set.of("application/json", "application/a2a+json").contains(media)) {
        respond(exchange, 415, Map.of("error", "JSON content type required"));
        return;
      }
      JsonNode id = JSON.nullNode();
      try {
        byte[] bytes = exchange.getRequestBody().readNBytes(128 * 1024 + 1);
        if (bytes.length > 128 * 1024) {
          respond(exchange, 413, Map.of("error", "Request too large"));
          return;
        }
        JsonNode frame;
        try {
          frame = JSON.readTree(bytes);
        } catch (IOException invalid) {
          throw new RpcFault(-32700, "Invalid JSON");
        }
        if (frame == null
            || !frame.isObject()
            || !"2.0".equals(frame.path("jsonrpc").asText())
            || !frame.has("id")
            || !(frame.get("id").isTextual() || frame.get("id").isIntegralNumber())
            || !frame.path("method").isTextual())
          throw new RpcFault(-32600, "A JSON-RPC request with an id is required");
        id = frame.get("id");
        JsonNode params = frame.path("params");
        if (!params.isObject()) throw new RpcFault(-32602, "Object params required");
        Object result =
            switch (frame.path("method").asText()) {
              case "SendMessage" ->
                  send(client, params, exchange.getRequestHeaders().getFirst("A2A-Extensions"));
              case "GetTask" -> task(status(client, params), historyLength(params));
              case "CancelTask" -> {
                int history = historyLength(params);
                yield task(cancel(client, params), history);
              }
              case "SendStreamingMessage",
                      "SubscribeToTask",
                      "ListTasks",
                      "GetExtendedAgentCard",
                      "CreateTaskPushNotificationConfig",
                      "GetTaskPushNotificationConfig",
                      "ListTaskPushNotificationConfigs",
                      "DeleteTaskPushNotificationConfig" ->
                  throw new RpcFault(-32004, "Operation not supported by this receiver");
              default -> throw new RpcFault(-32601, "Unknown method");
            };
        var response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", JSON.valueToTree(result));
        if (JSON.writeValueAsBytes(response).length > 1024 * 1024)
          throw new RpcFault(
              -32602, "Requested history exceeds 1 MiB; use a smaller historyLength");
        if (exchange.getRequestHeaders().getFirst("A2A-Extensions") != null
            && Arrays.stream(exchange.getRequestHeaders().getFirst("A2A-Extensions").split(","))
                .map(String::trim)
                .anyMatch(COMMAND_EXTENSION::equals))
          exchange.getResponseHeaders().set("A2A-Extensions", COMMAND_EXTENSION);
        respond(exchange, 200, response);
      } catch (RpcFault fault) {
        respond(exchange, 200, error(id, fault.code, fault.getMessage()));
      } catch (BlockingDeadline deadline) {
        respond(
            exchange,
            504,
            error(
                id,
                -32603,
                "Task accepted; blocking deadline expired. Recover explicitly with the same messageId and payload."));
      } catch (IOException | RuntimeException unresolved) {
        respond(
            exchange,
            503,
            error(
                id,
                -32603,
                "Plowshare operation outcome is unresolved; do not replay with a new messageId."));
      }
    }
  }

  private Object send(String client, JsonNode params, String extensions) throws IOException {
    JsonNode message = params.path("message");
    if (!message.isObject()
        || !"ROLE_USER".equals(message.path("role").asText())
        || !message.path("messageId").isTextual()
        || message.path("messageId").asText().isBlank()
        || message.path("messageId").asText().length() > 256
        || !message.path("parts").isArray()
        || message.path("parts").isEmpty())
      throw new RpcFault(
          -32602, "A ROLE_USER message with messageId and nonempty parts is required");
    var texts = new ArrayList<String>();
    for (JsonNode part : message.path("parts")) {
      if (!part.isObject()
          || !part.path("text").isTextual()
          || part.has("url")
          || part.has("raw")
          || part.has("data")
          || part.has("mediaType") && !"text/plain".equals(part.path("mediaType").asText()))
        throw new RpcFault(-32005, "This receiver accepts text/plain parts only");
      texts.add(part.path("text").asText());
    }
    String body = String.join("\n", texts);
    if (body.isBlank() || body.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new RpcFault(-32602, "Nonblank text within 64 KiB required");
    UUID context = message.has("contextId") ? identity(message.path("contextId").asText()) : null;
    if (message.has("taskId")) {
      var known = read(client, identity(message.path("taskId").asText()));
      if (context != null && !known.context().equals(context))
        throw new RpcFault(-32001, "No accessible task in that context");
      throw new RpcFault(
          -32004,
          "Task follow-ups are not supported; approval waits require the Plowshare operator. Use contextId without taskId for a new task.");
    }
    JsonNode configuration = params.path("configuration");
    if (!configuration.isMissingNode() && !configuration.isObject())
      throw new RpcFault(-32602, "Object configuration required");
    if (configuration.has("returnImmediately")
        && !configuration.path("returnImmediately").isBoolean())
      throw new RpcFault(-32602, "returnImmediately must be boolean");
    if (configuration.has("acceptedOutputModes")) {
      JsonNode modes = configuration.path("acceptedOutputModes");
      if (!modes.isArray() || modes.isEmpty())
        throw new RpcFault(-32602, "acceptedOutputModes must be a nonempty media type array");
      for (JsonNode mode : modes)
        if (!mode.isTextual())
          throw new RpcFault(-32602, "acceptedOutputModes must contain media type strings");
      if (!java.util.stream.StreamSupport.stream(modes.spliterator(), false)
          .anyMatch(mode -> mode.asText().equals("text/plain")))
        throw new RpcFault(-32005, "Only text/plain output is supported");
    }
    if (configuration.has("taskPushNotificationConfig"))
      throw new RpcFault(-32003, "Push notifications are not supported");
    int history = historyLength(configuration);
    String command = null;
    if (message.path("metadata").has("plowshareCommand")) {
      if (extensions == null
          || Arrays.stream(extensions.split(","))
              .map(String::trim)
              .noneMatch(COMMAND_EXTENSION::equals))
        throw new RpcFault(
            -32602, "Explicit commands require the Plowshare command extension header");
      JsonNode selected = message.path("metadata").path("plowshareCommand");
      if (!selected.isTextual())
        throw new RpcFault(-32602, "plowshareCommand must be a qualified command string");
      command = selected.asText();
      refreshCard();
      String name = command.split(" ", 2)[0];
      if (!commands.contains(name))
        throw new RpcFault(-32602, "Command is unavailable or not granted to the receiving agent");
    }
    UUID request =
        UUID.nameUUIDFromBytes(
            JSON.writeValueAsBytes(
                List.of(
                    config.project(), config.agent(), client, message.path("messageId").asText())));
    Map<String, Object> source =
        JSON.convertValue(message, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    Incoming.Task accepted;
    try {
      accepted =
          bridge.receive(
              new Incoming.Receive(
                  config.project(),
                  client,
                  config.agent(),
                  request,
                  context,
                  body,
                  command,
                  source));
    } catch (IncomingClient.Refused refused) {
      throw new RpcFault(
          -32602,
          "Message refused: scope, receipt identity, limits or command arguments are invalid");
    }
    if (!configuration.path("returnImmediately").asBoolean()) {
      long deadline = System.nanoTime() + config.waitMillis() * 1_000_000L;
      while (List.of("SUBMITTED", "WORKING").contains(accepted.state())) {
        if (System.nanoTime() >= deadline) throw new BlockingDeadline();
        try {
          Thread.sleep(200);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Receiver wait interrupted");
        }
        accepted = read(client, accepted.id());
      }
    }
    return Map.of("task", task(accepted, history));
  }

  private Incoming.Task status(String client, JsonNode params) throws IOException {
    return read(client, identity(params.path("id").asText()));
  }

  private Incoming.Task read(String client, UUID id) throws IOException {
    try {
      return bridge.status(new Incoming.Id(config.project(), client, id));
    } catch (IncomingClient.Refused inaccessible) {
      throw new RpcFault(-32001, "Task not found or not accessible");
    }
  }

  private Incoming.Task cancel(String client, JsonNode params) throws IOException {
    UUID id = identity(params.path("id").asText());
    Incoming.Task known = read(client, id);
    if (List.of("COMPLETED", "FAILED", "CANCELED").contains(known.state()))
      throw new RpcFault(-32002, "Task is not cancelable");
    try {
      return bridge.cancel(new Incoming.Id(config.project(), client, id));
    } catch (IncomingClient.Refused refused) {
      throw new RpcFault(-32002, "Task is not cancelable");
    }
  }

  private static UUID identity(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new RpcFault(-32001, "Task or context not found");
    }
  }

  private static int historyLength(JsonNode params) {
    if (!params.has("historyLength")) return 200;
    JsonNode length = params.path("historyLength");
    if (!length.isIntegralNumber()
        || !length.canConvertToInt()
        || length.asInt() < 0
        || length.asInt() > 200) throw new RpcFault(-32602, "historyLength must be from 0 to 200");
    return length.asInt();
  }

  private static Map<String, Object> task(Incoming.Task task, int historyLength) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", task.id().toString());
    result.put("contextId", task.context().toString());
    var status = new LinkedHashMap<String, Object>();
    status.put("state", "TASK_STATE_" + task.state());
    var history = new ArrayList<Map<String, Object>>();
    var user = new LinkedHashMap<>(task.source());
    user.put("taskId", task.id().toString());
    user.put("contextId", task.context().toString());
    history.add(user);
    for (var reply : task.replies()) {
      var message =
          Map.<String, Object>of(
              "messageId",
              reply.id(),
              "role",
              "ROLE_AGENT",
              "taskId",
              task.id().toString(),
              "contextId",
              task.context().toString(),
              "parts",
              List.of(Map.of("text", reply.body())),
              "metadata",
              Map.of(
                  "plowshareGenerated",
                  reply.generated(),
                  "plowshareEnding",
                  Objects.toString(reply.ending(), ""),
                  "plowshareFinal",
                  reply.finalReply()));
      history.add(message);
      status.put("message", message);
    }
    if (task.state().equals("INPUT_REQUIRED") && task.replies().isEmpty())
      status.put(
          "message",
          Map.of(
              "messageId",
              task.id() + ":approval",
              "role",
              "ROLE_AGENT",
              "taskId",
              task.id().toString(),
              "contextId",
              task.context().toString(),
              "parts",
              List.of(
                  Map.of(
                      "text",
                      "Awaiting approval in Plowshare. An operator must answer the original approval; A2A text does not approve it."))));
    result.put("status", status);
    result.put("metadata", Map.of("plowshareEnding", Objects.toString(task.ending(), "")));
    if (historyLength > 0)
      result.put(
          "history", history.subList(Math.max(0, history.size() - historyLength), history.size()));
    return result;
  }

  private static JsonNode error(JsonNode id, int code, String message) {
    var response = JSON.createObjectNode().put("jsonrpc", "2.0");
    response.set("id", id);
    response.putObject("error").put("code", code).put("message", message);
    return response;
  }

  private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] body = JSON.writeValueAsBytes(value);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("A2A-Version", "1.0");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  private static final class RpcFault extends RuntimeException {
    final int code;

    RpcFault(int code, String message) {
      super(message);
      this.code = code;
    }
  }

  private static final class BlockingDeadline extends RuntimeException {}

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
