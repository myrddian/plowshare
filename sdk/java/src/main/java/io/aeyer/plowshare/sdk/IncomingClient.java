package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Incoming;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Adapter-facing durable message receipt; HTTP protocols are outside the SDK. */
public final class IncomingClient {
  private static final ObjectMapper JSON = SdkJson.mapper();

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

  /** Read the receiving agent's public command catalogue through the authenticated socket. */
  public Incoming.Catalog catalog(Incoming.CatalogQuery query) throws IOException {
    Objects.requireNonNull(query, "query");
    var catalog = sdk.exchange("incoming.catalog", query, Incoming.Catalog.class);
    if (!catalog.name().equals(query.agent()) || !catalog.served())
      throw new IOException("The configured receiving agent is not served");
    return catalog;
  }

  public Incoming.Task receive(Incoming.Receive request) throws IOException {
    return call("incoming.receive", Objects.requireNonNull(request, "request"));
  }

  public Incoming.Task status(Incoming.Id id) throws IOException {
    return call("incoming.status", Objects.requireNonNull(id, "id"));
  }

  public Incoming.Task cancel(Incoming.Id id) throws IOException {
    return call("incoming.cancel", Objects.requireNonNull(id, "id"));
  }

  private Incoming.Task call(String type, Object value) throws IOException {
    Map<String, Object> payload =
        JSON.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    var reply = sdk.request(type, payload);
    if (!reply.successful()) throw new Refused(reply.code());
    var node = reply.requirePayload();
    var task = SdkJson.decode(JSON, node, Incoming.Task.class);
    if (task.id() == null
        || task.context() == null
        || task.createdAt() == null
        || task.replies() == null)
      throw new IOException("Malformed incoming task; outcome is unresolved");
    if (value instanceof Incoming.Receive expected
        && (!expected.agent().equals(task.agent())
            || expected.context() != null && !expected.context().equals(task.context())
            || !Objects.equals(expected.source(), task.source())))
      throw new IOException("Foreign incoming receipt; outcome is unresolved");
    if (value instanceof Incoming.Id expected && !expected.id().equals(task.id()))
      throw new IOException("Foreign incoming task");
    return task;
  }
}
