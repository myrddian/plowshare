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

  /**
   * Submit once into an explicit project. A transport exception never retries or substitutes HTTP.
   */
  public Orchestration.Started start(Orchestration.Start request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> startAsync(request, options));
  }

  /** An absent receipt is explicit; callers must still decide whether a new submission is safe. */
  public Optional<Orchestration.Started> receipt(UUID requestId) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> receiptAsync(requestId, options));
  }

  public Orchestration.Status status(String id) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> statusAsync(id, options));
  }

  public Orchestration.Definitions definitions(Orchestration.DefinitionQuery query)
      throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> definitionsAsync(query, options));
  }

  public Orchestration.Listed list(Orchestration.ListedQuery query) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> listAsync(query, options));
  }

  public Orchestration.Changed answer(Orchestration.Answer answer) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> answerAsync(answer, options));
  }

  /** Submit one explicit recovery. Retain requestId to reconcile uncertain delivery safely. */
  public Orchestration.Changed resume(Orchestration.Resume request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> resumeAsync(request, options));
  }

  public Orchestration.Changed cancel(String id) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> cancelAsync(id, options));
  }

  private Orchestration.Changed changed(String operation, Object request, String id)
      throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> changedAsync(operation, request, id, options));
  }

  private static Orchestration.Started check(Orchestration.Started started, UUID requestId)
      throws IOException {
    if (!requestId.equals(started.requestId()))
      throw new IOException("Foreign orchestration start receipt; outcome unconfirmed");
    return started;
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Orchestration.Started> startAsync(
      Orchestration.Start request, RequestOptions options) {

    Objects.requireNonNull(request, "request");
    io.aeyer.plowshare.protocol.Home.of(request.project());
    return AsyncSupport.map(
        connection.exchangeAsync(
            "orchestration.start", request, Orchestration.Started.class, options),
        asyncResult -> {
          return check(asyncResult, request.requestId());
        });
  }

  public java.util.concurrent.CompletionStage<Orchestration.Started> startAsync(
      Orchestration.Start request) {
    return startAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Optional<Orchestration.Started>> receiptAsync(
      UUID requestId, RequestOptions options) {

    var request = new Orchestration.Receipt(requestId);
    return AsyncSupport.map(
        connection.requestAsync(
            "orchestration.receipt", java.util.Map.of("requestId", request.requestId()), options),
        asyncResult -> {
          var reply = asyncResult;
          if (reply.code().equals("BAD_REQUEST")
              && "No orchestration start receipt is owned by this account".equals(reply.said()))
            return Optional.empty();
          return Optional.of(
              check(
                  SdkJson.decode(JSON, reply.requirePayload(), Orchestration.Started.class),
                  requestId));
        });
  }

  public java.util.concurrent.CompletionStage<Optional<Orchestration.Started>> receiptAsync(
      UUID requestId) {
    return receiptAsync(requestId, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Orchestration.Status> statusAsync(
      String id, RequestOptions options) {

    var request = new Orchestration.Reference(id);
    return AsyncSupport.map(
        connection.exchangeAsync(
            "orchestration.status", request, Orchestration.Status.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (!request.id().equals(result.orchestration().id()))
            throw new IOException("Foreign orchestration status");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<Orchestration.Status> statusAsync(String id) {
    return statusAsync(id, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Orchestration.Definitions> definitionsAsync(
      Orchestration.DefinitionQuery query, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "orchestration.definitions", query, Orchestration.Definitions.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Orchestration.Definitions> definitionsAsync(
      Orchestration.DefinitionQuery query) {
    return definitionsAsync(query, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Orchestration.Listed> listAsync(
      Orchestration.ListedQuery query, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("orchestration.list", query, Orchestration.Listed.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Orchestration.Listed> listAsync(
      Orchestration.ListedQuery query) {
    return listAsync(query, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  private java.util.concurrent.CompletionStage<Orchestration.Changed> changedAsync(
      String operation, Object request, String id, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(operation, request, Orchestration.Changed.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (!id.equals(result.id()))
            throw new IOException("Foreign orchestration result; outcome unconfirmed");
          return result;
        });
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Orchestration.Changed> answerAsync(
      Orchestration.Answer answer, RequestOptions options) {

    return AsyncSupport.map(
        changedAsync("orchestration.answer", answer, answer.id(), options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Orchestration.Changed> answerAsync(
      Orchestration.Answer answer) {
    return answerAsync(answer, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Orchestration.Changed> resumeAsync(
      Orchestration.Resume request, RequestOptions options) {

    return AsyncSupport.map(
        changedAsync("orchestration.resume", request, request.id(), options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Orchestration.Changed> resumeAsync(
      Orchestration.Resume request) {
    return resumeAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Orchestration.Changed> cancelAsync(
      String id, RequestOptions options) {

    var request = new Orchestration.Reference(id);
    return AsyncSupport.map(
        changedAsync("orchestration.cancel", request, request.id(), options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Orchestration.Changed> cancelAsync(String id) {
    return cancelAsync(id, null);
  }
}
