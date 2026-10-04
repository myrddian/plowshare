package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Outgoing;
import io.aeyer.plowshare.sdk.*;
import java.io.IOException;
import java.util.*;

/** Current public SDK contracts only. A separate seam permits protocol fixtures. */
public interface Gateway extends AutoCloseable {
  void advertise(String project, List<String> peers) throws IOException;

  Outgoing.Claimed claim(String project, List<String> peers) throws IOException;

  void report(Outgoing.Report report) throws IOException;

  Outgoing.Work outgoing(UUID id) throws IOException;

  JsonNode start(Map<String, Object> payload) throws IOException;

  JsonNode receipt(UUID request) throws IOException;

  JsonNode status(String run) throws IOException;

  @Override
  void close();

  final class Sdk implements Gateway {
    private final Plowshare sdk;
    private final OutgoingClient outgoing;

    public Sdk(Plowshare sdk) {
      this.sdk = sdk;
      outgoing = new OutgoingClient(sdk);
    }

    public void advertise(String project, List<String> peers) throws IOException {
      outgoing.advertise(project, peers);
    }

    public Outgoing.Claimed claim(String project, List<String> peers) throws IOException {
      return outgoing.claim(project, peers);
    }

    public void report(Outgoing.Report report) throws IOException {
      Outgoing.Work saved = outgoing.report(report);
      if (!report.state().equals(saved.state())
          || saved.revision() != report.revision() + 1
          || !Objects.equals(saved.remoteTask(), report.remoteTask())
          || !Objects.equals(saved.remoteContext(), report.remoteContext())
          || !Json.MAPPER
              .valueToTree(saved.result())
              .equals(Json.MAPPER.valueToTree(report.result())))
        throw new IOException("outgoing report acknowledgment differs; outcome unconfirmed");
    }

    public Outgoing.Work outgoing(UUID id) throws IOException {
      return outgoing.status(id);
    }

    public JsonNode start(Map<String, Object> payload) throws IOException {
      return started(
          sdk.request("orchestration.start", payload).requirePayload(),
          UUID.fromString((String) payload.get("requestId")));
    }

    public JsonNode receipt(UUID request) throws IOException {
      var reply = sdk.request("orchestration.receipt", Map.of("requestId", request.toString()));
      if (reply.code().equals("BAD_REQUEST")
          && "No orchestration start receipt is owned by this account".equals(reply.said()))
        return null;
      return started(reply.requirePayload(), request);
    }

    private static JsonNode started(JsonNode receipt, UUID request) throws IOException {
      if (!receipt.path("id").isTextual()
          || receipt.path("id").asText().isBlank()
          || !request.toString().equals(receipt.path("requestId").asText()))
        throw new IOException("foreign orchestration start receipt; outcome unconfirmed");
      return receipt;
    }

    public JsonNode status(String run) throws IOException {
      return sdk.request("orchestration.status", Map.of("id", run)).requirePayload();
    }

    public void close() {
      sdk.close();
    }
  }
}
