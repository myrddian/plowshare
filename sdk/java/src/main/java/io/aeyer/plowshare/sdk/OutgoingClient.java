package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Outgoing;
import java.io.IOException;
import java.util.*;

/** Typed external-work operations over the same SDK connection. */
public final class OutgoingClient {
  private static final ObjectMapper JSON =
      SdkJson.mapper()
          .registerModule(ExternalTimeCodec.module())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
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
    Objects.requireNonNull(send, "send");
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
        || !Objects.equals(receipt.conversation(), send.conversation())
        || !receipt.message().equals(send.message()))
      throw new IOException("foreign outgoing send receipt; outcome is unknown");
    return receipt;
  }

  public Outgoing.Work status(UUID id) throws IOException {
    return work(
        connection
            .request("outgoing.status", Map.of("id", new Outgoing.Id(id).id()))
            .requirePayload(),
        id);
  }

  public Outgoing.Work cancel(UUID id) throws IOException {
    return work(
        connection
            .request("outgoing.cancel", Map.of("id", new Outgoing.Id(id).id()))
            .requirePayload(),
        id);
  }

  public Outgoing.Peers peers(String project) throws IOException {
    var query = new Outgoing.PeerQuery(project);
    JsonNode result =
        connection.request("outgoing.peers", fields("project", query.project())).requirePayload();
    if (!result.path("peers").isArray()) throw new IOException("unreadable outgoing peers");
    for (JsonNode peer : result.path("peers"))
      if (!peer.isTextual() || peer.asText().isBlank())
        throw new IOException("unreadable outgoing peer");
    return SdkJson.decode(
        JSON.copy()
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
        result,
        Outgoing.Peers.class);
  }

  public void advertise(String project, List<String> peers) throws IOException {
    advertise(project, peers, null);
  }

  public void advertise(
      String project,
      List<String> peers,
      Map<String, io.aeyer.plowshare.protocol.AgentCard> agentCards)
      throws IOException {
    var request = new Outgoing.Advertise(project, peers, agentCards);
    var answer =
        connection.request(
            "outgoing.advertise",
            fields(
                "project",
                request.project(),
                "peers",
                request.peers(),
                "agentCards",
                request.agentCards()));
    if (!answer.successful()) throw new IOException("outgoing advertise refused: " + answer.code());
  }

  public Outgoing.Claimed claim(String project, List<String> peers) throws IOException {
    var request = new Outgoing.Claim(project, peers);
    JsonNode result =
        connection
            .request(
                "outgoing.claim", fields("project", request.project(), "peers", request.peers()))
            .requirePayload();
    if (!result.has("work")) throw new IOException("unreadable outgoing claim");
    if (result.get("work").isNull()) {
      if (result.has("action") && !result.get("action").isNull())
        throw new IOException("empty outgoing claim cannot have an action");
      return new Outgoing.Claimed(null, null);
    }
    Outgoing.Work work = work(result.get("work"), null);
    if (!Objects.equals(work.project(), request.project())
        || !request.peers().contains(work.peer()))
      throw new IOException("foreign outgoing claim scope; delivery remains unknown");
    String action = result.path("action").asText();
    if (!Set.of("send", "observe", "cancel").contains(action)
        || action.equals("send")
            && (!work.state().equals("DISPATCHED") || work.remoteTask() != null)
        || !action.equals("send") && (work.remoteTask() == null || work.remoteTask().isBlank()))
      throw new IOException("unreadable outgoing claim action");
    return new Outgoing.Claimed(work, action);
  }

  public Outgoing.Work report(Outgoing.Report report) throws IOException {
    Objects.requireNonNull(report, "report");
    Outgoing.Work receipt =
        work(
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
    if (receipt.revision() != report.revision() + 1
        || !receipt.state().equals(report.state())
        || !Objects.equals(receipt.remoteTask(), report.remoteTask())
        || !Objects.equals(receipt.remoteContext(), report.remoteContext())
        || !Objects.equals(receipt.result(), report.result())
        || !Objects.equals(receipt.error(), report.error()))
      throw new IOException("foreign outgoing report receipt; delivery remains unknown");
    return receipt;
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
      Outgoing.Work work = SdkJson.decode(JSON, value, Outgoing.Work.class);
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
