package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Orchestration;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authenticated caller operations, with explicit receipt recovery after uncertain delivery. */
public final class OrchestrationClient {
  private static final ObjectMapper JSON = SdkJson.mapper();
  private final Plowshare connection;

  public OrchestrationClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** Convert an external start payload before admitting it to the SDK contract. */
  public static Orchestration.Start decodeStart(String input) throws IOException {
    if (input == null || input.length() > 2097152)
      throw new IOException("Start payload exceeds its bound");
    var codec = JSON.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    return SdkJson.decode(codec, codec.readTree(input), Orchestration.Start.class);
  }

  /** Submit once. A transport exception never retries or substitutes HTTP. */
  public Orchestration.Started start(Orchestration.Start request) throws IOException {
    return check(
        connection.exchange("orchestration.start", request, Orchestration.Started.class),
        request.requestId());
  }

  /** An absent receipt is explicit; callers must still decide whether a new submission is safe. */
  public Optional<Orchestration.Started> receipt(UUID requestId) throws IOException {
    var request = new Orchestration.Receipt(requestId);
    var reply =
        connection.request(
            "orchestration.receipt", java.util.Map.of("requestId", request.requestId()));
    if (reply.code().equals("BAD_REQUEST")
        && "No orchestration start receipt is owned by this account".equals(reply.said()))
      return Optional.empty();
    return Optional.of(
        check(
            SdkJson.decode(JSON, reply.requirePayload(), Orchestration.Started.class), requestId));
  }

  public Orchestration.Status status(String id) throws IOException {
    var request = new Orchestration.Reference(id);
    var result = connection.exchange("orchestration.status", request, Orchestration.Status.class);
    if (!request.id().equals(result.orchestration().id()))
      throw new IOException("Foreign orchestration status");
    return result;
  }

  public Orchestration.Definitions definitions(Orchestration.DefinitionQuery query)
      throws IOException {
    return connection.exchange("orchestration.definitions", query, Orchestration.Definitions.class);
  }

  public Orchestration.Listed list(Orchestration.ListedQuery query) throws IOException {
    return connection.exchange("orchestration.list", query, Orchestration.Listed.class);
  }

  public Orchestration.Changed answer(Orchestration.Answer answer) throws IOException {
    return changed("orchestration.answer", answer, answer.id());
  }

  /** Submit one explicit recovery. Retain requestId to reconcile uncertain delivery safely. */
  public Orchestration.Changed resume(Orchestration.Resume request) throws IOException {
    return changed("orchestration.resume", request, request.id());
  }

  public Orchestration.Changed cancel(String id) throws IOException {
    var request = new Orchestration.Reference(id);
    return changed("orchestration.cancel", request, request.id());
  }

  private Orchestration.Changed changed(String operation, Object request, String id)
      throws IOException {
    var result = connection.exchange(operation, request, Orchestration.Changed.class);
    if (!id.equals(result.id()))
      throw new IOException("Foreign orchestration result; outcome unconfirmed");
    return result;
  }

  private static Orchestration.Started check(Orchestration.Started started, UUID requestId)
      throws IOException {
    if (!requestId.equals(started.requestId()))
      throw new IOException("Foreign orchestration start receipt; outcome unconfirmed");
    return started;
  }
}
