package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Incoming;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Adapter-facing durable message receipt; HTTP protocols are outside the SDK. */
public final class IncomingClient {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  public static final class Refused extends IOException {
    private final String code;

    public Refused(String code) {
      super("Incoming operation refused: " + code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private final Plowshare sdk;

  public IncomingClient(Plowshare sdk) {
    this.sdk = Objects.requireNonNull(sdk);
  }

  public Incoming.Task receive(Incoming.Receive request) throws IOException {
    return call("incoming.receive", request);
  }

  public Incoming.Task status(Incoming.Id id) throws IOException {
    return call("incoming.status", id);
  }

  public Incoming.Task cancel(Incoming.Id id) throws IOException {
    return call("incoming.cancel", id);
  }

  private Incoming.Task call(String type, Object value) throws IOException {
    Map<String, Object> payload =
        JSON.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    var reply = sdk.request(type, payload);
    if (!reply.successful()) throw new Refused(reply.code());
    var node = reply.requirePayload();
    var task = JSON.treeToValue(node, Incoming.Task.class);
    if (task.id() == null
        || task.context() == null
        || task.createdAt() == null
        || task.replies() == null)
      throw new IOException("Malformed incoming task; outcome is unresolved");
    if (value instanceof Incoming.Id expected && !expected.id().equals(task.id()))
      throw new IOException("Foreign incoming task");
    return task;
  }
}
