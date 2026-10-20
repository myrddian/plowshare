package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.ProviderCredentials;
import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import java.io.IOException;
import java.util.*;

/**
 * Upstream grant management over WebSockets. Retain mutation UUIDs; uncertain sends are not
 * replayed.
 */
public final class ProviderConnectionClient {
  private final Plowshare connection;

  public ProviderConnectionClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  public List<Connection> list(String project) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> listAsync(project, options));
  }

  public Connection status(StatusRequest request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> statusAsync(request, options));
  }

  public Credential credential(Target request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> credentialAsync(request, options));
  }

  public Receipt save(Save request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> saveAsync(request, options));
  }

  public Receipt change(Change request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> changeAsync(request, options));
  }

  public Receipt put(Put request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> putAsync(request, options));
  }

  public Receipt revoke(Mutation request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> revokeAsync(request, options));
  }

  public Receipt rekey(Mutation request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> rekeyAsync(request, options));
  }

  public Receipt refresh(Mutation request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> refreshAsync(request, options));
  }

  private Receipt credentialMutation(String operation, Mutation request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(),
        options -> credentialMutationAsync(operation, request, options));
  }

  /**
   * Only this once-only reply carries the browser URL. Persist the redacted receipt coordinates.
   */
  public Authorization authorize(Mutation request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> authorizeAsync(request, options));
  }

  public Receipt receipt(ReceiptRequest request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> receiptAsync(request, options));
  }

  private Receipt mutation(
      String operation, Object request, String project, UUID id, UUID requestId, String subject)
      throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(),
        options -> mutationAsync(operation, request, project, id, requestId, subject, options));
  }

  private static void correlate(
      Receipt value, String project, UUID id, UUID requestId, String subject, String operation)
      throws IOException {
    if (value == null
        || !value.requestId().equals(requestId)
        || !value.connection().project().equals(project)
        || (id != null && !value.connection().connection().equals(id))
        || (operation != null && !value.operation().equals(operation))
        || (subject != null
            && (value.credential() == null || !subject.equals(value.credential().subject()))))
      throw new IOException("Foreign provider mutation receipt");
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<Connection>> listAsync(
      String project, RequestOptions options) {

    ProviderCredentials.project(project);
    return AsyncSupport.map(
        connection.requestAsync("provider.connection.list", Map.of("project", project), options),
        asyncResult -> {
          var rows = asyncResult.requirePayload();
          if (!rows.isArray() || rows.size() > 256)
            throw new IOException("Invalid provider connection listing");
          var result = new ArrayList<Connection>();
          for (var row : rows) {
            var value = SdkJson.decode(SdkJson.mapper(), row, Connection.class);
            if (!project.equals(value.project()))
              throw new IOException("Foreign provider connection");
            result.add(value);
          }
          return List.copyOf(result);
        });
  }

  public java.util.concurrent.CompletionStage<List<Connection>> listAsync(String project) {
    return listAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Connection> statusAsync(
      StatusRequest request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("provider.connection.status", request, Connection.class, options),
        asyncResult -> {
          var value = asyncResult;
          if (value == null
              || !value.project().equals(request.project())
              || !value.connection().equals(request.connection()))
            throw new IOException("Foreign provider connection status");
          return value;
        });
  }

  public java.util.concurrent.CompletionStage<Connection> statusAsync(StatusRequest request) {
    return statusAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Credential> credentialAsync(
      Target request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("provider.credential.status", request, Credential.class, options),
        asyncResult -> {
          var value = asyncResult;
          if (value == null
              || !value.project().equals(request.project())
              || !value.connection().equals(request.connection())
              || (request.subject() != null && !request.subject().equals(value.subject())))
            throw new IOException("Foreign provider credential status");
          return value;
        });
  }

  public java.util.concurrent.CompletionStage<Credential> credentialAsync(Target request) {
    return credentialAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> receiptAsync(
      ReceiptRequest request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("provider.connection.receipt", request, Receipt.class, options),
        asyncResult -> {
          var result = asyncResult;
          correlate(result, request.project(), null, request.requestId(), null, null);
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> receiptAsync(ReceiptRequest request) {
    return receiptAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  private java.util.concurrent.CompletionStage<Receipt> mutationAsync(
      String operation,
      Object request,
      String project,
      UUID id,
      UUID requestId,
      String subject,
      RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(operation, request, Receipt.class, options),
        asyncResult -> {
          var result = asyncResult;
          correlate(result, project, id, requestId, subject, operation);
          return result;
        });
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> saveAsync(
      Save request, RequestOptions options) {

    return AsyncSupport.map(
        mutationAsync(
            "provider.connection.save",
            request,
            request.project(),
            request.connection(),
            request.requestId(),
            null,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> saveAsync(Save request) {
    return saveAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> changeAsync(
      Change request, RequestOptions options) {

    return AsyncSupport.map(
        mutationAsync(
            "provider.connection.change",
            request,
            request.project(),
            request.connection(),
            request.requestId(),
            null,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> changeAsync(Change request) {
    return changeAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> putAsync(
      Put request, RequestOptions options) {

    return AsyncSupport.map(
        mutationAsync(
            "provider.credential.put",
            request,
            request.project(),
            request.connection(),
            request.requestId(),
            request.subject(),
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> putAsync(Put request) {
    return putAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  private java.util.concurrent.CompletionStage<Receipt> credentialMutationAsync(
      String operation, Mutation request, RequestOptions options) {

    return AsyncSupport.map(
        mutationAsync(
            operation,
            request,
            request.project(),
            request.connection(),
            request.requestId(),
            request.subject(),
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> revokeAsync(
      Mutation request, RequestOptions options) {

    return AsyncSupport.map(
        credentialMutationAsync("provider.credential.revoke", request, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> revokeAsync(Mutation request) {
    return revokeAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> rekeyAsync(
      Mutation request, RequestOptions options) {

    return AsyncSupport.map(
        credentialMutationAsync("provider.credential.rekey", request, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> rekeyAsync(Mutation request) {
    return rekeyAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> refreshAsync(
      Mutation request, RequestOptions options) {

    return AsyncSupport.map(
        credentialMutationAsync("provider.credential.refresh", request, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> refreshAsync(Mutation request) {
    return refreshAsync(request, null);
  }

  public java.util.concurrent.CompletionStage<Authorization> authorizeAsync(
      Mutation request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "provider.credential.authorize", request, Authorization.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (result == null) throw new IOException("Unreadable provider authorization");
          correlate(
              result.receipt(),
              request.project(),
              request.connection(),
              request.requestId(),
              request.subject(),
              "provider.credential.authorize");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<Authorization> authorizeAsync(Mutation request) {
    return authorizeAsync(request, null);
  }
}
