package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Outgoing;
import java.io.IOException;
import java.util.*;

/** Typed external-work operations over the same SDK connection. */
public final class OutgoingClient {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private final Plowshare connection;

  public OutgoingClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  private static Map<String, Object> fields(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public Outgoing.Work send(Outgoing.Send send) throws IOException {
    Outgoing.Work receipt =
        work(
            connection
                .request(
                    "outgoing.send",
                    fields(
                        "requestId",
                        send.requestId(),
                        "peer",
                        send.peer(),
                        "message",
                        send.message(),
                        "project",
                        send.project(),
                        "conversation",
                        send.conversation()))
                .requirePayload(),
            null);
    if (!receipt.requestId().equals(send.requestId())
        || !receipt.peer().equals(send.peer())
        || !Objects.equals(receipt.project(), send.project())
        || !Objects.equals(receipt.conversation(), send.conversation()))
      throw new IOException("foreign outgoing send receipt; outcome is unknown");
    return receipt;
  }

  public Outgoing.Work status(UUID id) throws IOException {
    return work(connection.request("outgoing.status", Map.of("id", id)).requirePayload(), id);
  }

  public Outgoing.Work cancel(UUID id) throws IOException {
    return work(connection.request("outgoing.cancel", Map.of("id", id)).requirePayload(), id);
  }

  public Outgoing.Peers peers(String project) throws IOException {
    JsonNode result =
        connection.request("outgoing.peers", fields("project", project)).requirePayload();
    if (!result.path("peers").isArray()) throw new IOException("unreadable outgoing peers");
    for (JsonNode peer : result.path("peers"))
      if (!peer.isTextual() || peer.asText().isBlank())
        throw new IOException("unreadable outgoing peer");
    return JSON.treeToValue(result, Outgoing.Peers.class);
  }

  public void advertise(String project, List<String> peers) throws IOException {
    advertise(project, peers, null);
  }

  public void advertise(
      String project, List<String> peers, Map<String, Map<String, Object>> agentCards)
      throws IOException {
    var answer =
        connection.request(
            "outgoing.advertise",
            fields("project", project, "peers", peers, "agentCards", agentCards));
    if (!answer.successful()) throw new IOException("outgoing advertise refused: " + answer.code());
  }

  public Outgoing.Claimed claim(String project, List<String> peers) throws IOException {
    JsonNode result =
        connection
            .request("outgoing.claim", fields("project", project, "peers", peers))
            .requirePayload();
    if (!result.has("work")) throw new IOException("unreadable outgoing claim");
    if (result.get("work").isNull()) return new Outgoing.Claimed(null, null);
    Outgoing.Work work = work(result.get("work"), null);
    String action = result.path("action").asText();
    if (!Set.of("send", "observe", "cancel").contains(action)
        || action.equals("send")
            && (!work.state().equals("DISPATCHED") || work.remoteTask() != null)
        || !action.equals("send") && (work.remoteTask() == null || work.remoteTask().isBlank()))
      throw new IOException("unreadable outgoing claim action");
    return new Outgoing.Claimed(work, action);
  }

  public Outgoing.Work report(Outgoing.Report report) throws IOException {
    return work(
        connection
            .request(
                "outgoing.report",
                fields(
                    "id",
                    report.id(),
                    "revision",
                    report.revision(),
                    "state",
                    report.state(),
                    "remoteTask",
                    report.remoteTask(),
                    "remoteContext",
                    report.remoteContext(),
                    "result",
                    report.result(),
                    "error",
                    report.error()))
            .requirePayload(),
        report.id());
  }

  private static Outgoing.Work work(JsonNode value, UUID expected) throws IOException {
    if (!value.isObject()
        || !value.path("id").isTextual()
        || !value.path("requestId").isTextual()
        || !value.path("peer").isTextual()
        || value.path("peer").asText().isBlank()
        || !value.path("message").isObject()
        || !value.path("createdAt").isTextual()
        || !value.path("cancelRequested").isBoolean()
        || !value.path("revision").canConvertToLong()
        || !value.path("revision").isIntegralNumber()
        || value.path("revision").asLong() < 0
        || !Set.of(
                "QUEUED",
                "DISPATCHED",
                "WORKING",
                "INPUT_REQUIRED",
                "AUTH_REQUIRED",
                "COMPLETED",
                "FAILED",
                "CANCELED",
                "REJECTED",
                "UNKNOWN")
            .contains(value.path("state").asText()))
      throw new IOException("unreadable outgoing work; completion remains unknown");
    try {
      Outgoing.Work work = JSON.treeToValue(value, Outgoing.Work.class);
      if (work.id() == null
          || work.requestId() == null
          || work.createdAt() == null
          || expected != null && !expected.equals(work.id()))
        throw new IOException("foreign outgoing work identity");
      return work;
    } catch (IllegalArgumentException invalid) {
      throw new IOException("unreadable outgoing work identity", invalid);
    }
  }
}
