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
    return AsyncSupport.await(connection.defaultOptions(), options -> sendAsync(send, options));
  }

  public Outgoing.Work status(UUID id) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> statusAsync(id, options));
  }

  public Outgoing.Work cancel(UUID id) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> cancelAsync(id, options));
  }

  public Outgoing.Peers peers(String project) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> peersAsync(project, options));
  }

  public void advertise(String project, List<String> peers) throws IOException {
    AsyncSupport.await(
        connection.defaultOptions(), options -> advertiseAsync(project, peers, options));
  }

  public void advertise(
      String project,
      List<String> peers,
      Map<String, io.aeyer.plowshare.protocol.AgentCard> agentCards)
      throws IOException {
    AsyncSupport.await(
        connection.defaultOptions(),
        options -> advertiseAsync(project, peers, agentCards, options));
  }

  public Outgoing.Claimed claim(String project, List<String> peers) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> claimAsync(project, peers, options));
  }

  public Outgoing.Work report(Outgoing.Report report) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> reportAsync(report, options));
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

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Outgoing.Work> sendAsync(
      Outgoing.Send send, RequestOptions options) {

    Objects.requireNonNull(send, "send");
    return AsyncSupport.map(
        connection.requestAsync(
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
                send.conversation()),
            options),
        asyncResult -> {
          Outgoing.Work receipt = work(asyncResult.requirePayload(), null);
          if (!receipt.requestId().equals(send.requestId())
              || !receipt.peer().equals(send.peer())
              || !Objects.equals(receipt.project(), send.project())
              || !Objects.equals(receipt.conversation(), send.conversation())
              || !receipt.message().equals(send.message()))
            throw new IOException("foreign outgoing send receipt; outcome is unknown");
          return receipt;
        });
  }

  public java.util.concurrent.CompletionStage<Outgoing.Work> sendAsync(Outgoing.Send send) {
    return sendAsync(send, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Outgoing.Work> statusAsync(
      UUID id, RequestOptions options) {

    return AsyncSupport.map(
        connection.requestAsync("outgoing.status", Map.of("id", new Outgoing.Id(id).id()), options),
        asyncResult -> {
          return work(asyncResult.requirePayload(), id);
        });
  }

  public java.util.concurrent.CompletionStage<Outgoing.Work> statusAsync(UUID id) {
    return statusAsync(id, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Outgoing.Work> cancelAsync(
      UUID id, RequestOptions options) {

    return AsyncSupport.map(
        connection.requestAsync("outgoing.cancel", Map.of("id", new Outgoing.Id(id).id()), options),
        asyncResult -> {
          return work(asyncResult.requirePayload(), id);
        });
  }

  public java.util.concurrent.CompletionStage<Outgoing.Work> cancelAsync(UUID id) {
    return cancelAsync(id, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Outgoing.Peers> peersAsync(
      String project, RequestOptions options) {

    var query = new Outgoing.PeerQuery(project);
    return AsyncSupport.map(
        connection.requestAsync("outgoing.peers", fields("project", query.project()), options),
        asyncResult -> {
          JsonNode result = asyncResult.requirePayload();
          if (!result.path("peers").isArray()) throw new IOException("unreadable outgoing peers");
          for (JsonNode peer : result.path("peers"))
            if (!peer.isTextual() || peer.asText().isBlank())
              throw new IOException("unreadable outgoing peer");
          return SdkJson.decode(
              JSON.copy()
                  .enable(
                      com.fasterxml.jackson.databind.DeserializationFeature
                          .FAIL_ON_UNKNOWN_PROPERTIES),
              result,
              Outgoing.Peers.class);
        });
  }

  public java.util.concurrent.CompletionStage<Outgoing.Peers> peersAsync(String project) {
    return peersAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Outgoing.Work> reportAsync(
      Outgoing.Report report, RequestOptions options) {

    Objects.requireNonNull(report, "report");
    return AsyncSupport.map(
        connection.requestAsync(
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
                report.error()),
            options),
        asyncResult -> {
          Outgoing.Work receipt = work(asyncResult.requirePayload(), report.id());
          if (receipt.revision() != report.revision() + 1
              || !receipt.state().equals(report.state())
              || !Objects.equals(receipt.remoteTask(), report.remoteTask())
              || !Objects.equals(receipt.remoteContext(), report.remoteContext())
              || !Objects.equals(receipt.result(), report.result())
              || !Objects.equals(receipt.error(), report.error()))
            throw new IOException("foreign outgoing report receipt; delivery remains unknown");
          return receipt;
        });
  }

  public java.util.concurrent.CompletionStage<Outgoing.Work> reportAsync(Outgoing.Report report) {
    return reportAsync(report, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Void> advertiseAsync(
      String project, List<String> peers, RequestOptions options) {

    return AsyncSupport.map(
        advertiseAsync(project, peers, null, options),
        asyncResult -> {
          return null;
        });
  }

  public java.util.concurrent.CompletionStage<Void> advertiseAsync(
      String project, List<String> peers) {
    return advertiseAsync(project, peers, (RequestOptions) null);
  }

  public java.util.concurrent.CompletionStage<Void> advertiseAsync(
      String project,
      List<String> peers,
      Map<String, io.aeyer.plowshare.protocol.AgentCard> agentCards,
      RequestOptions options) {

    var request = new Outgoing.Advertise(project, peers, agentCards);
    return AsyncSupport.map(
        connection.requestAsync(
            "outgoing.advertise",
            fields(
                "project",
                request.project(),
                "peers",
                request.peers(),
                "agentCards",
                request.agentCards()),
            options),
        asyncResult -> {
          var answer = asyncResult;
          if (!answer.successful())
            throw new IOException("outgoing advertise refused: " + answer.code());

          return null;
        });
  }

  public java.util.concurrent.CompletionStage<Void> advertiseAsync(
      String project,
      List<String> peers,
      Map<String, io.aeyer.plowshare.protocol.AgentCard> agentCards) {
    return advertiseAsync(project, peers, agentCards, null);
  }

  public java.util.concurrent.CompletionStage<Outgoing.Claimed> claimAsync(
      String project, List<String> peers, RequestOptions options) {

    var request = new Outgoing.Claim(project, peers);
    return AsyncSupport.map(
        connection.requestAsync(
            "outgoing.claim",
            fields("project", request.project(), "peers", request.peers()),
            options),
        asyncResult -> {
          JsonNode result = asyncResult.requirePayload();
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
              || !action.equals("send")
                  && (work.remoteTask() == null || work.remoteTask().isBlank()))
            throw new IOException("unreadable outgoing claim action");
          return new Outgoing.Claimed(work, action);
        });
  }

  public java.util.concurrent.CompletionStage<Outgoing.Claimed> claimAsync(
      String project, List<String> peers) {
    return claimAsync(project, peers, null);
  }
}
