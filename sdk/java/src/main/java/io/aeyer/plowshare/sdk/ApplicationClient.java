package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.ApplicationDeployment.*;
import io.aeyer.plowshare.protocol.FileStoreCatalog;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Source deployment over the authenticated WebSocket. Unknown delivery is never replayed. */
public final class ApplicationClient {
  private final Plowshare connection;

  public ApplicationClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** Lists this account's server aliases/grants; storage host paths are private. */
  public FileStoreCatalog stores() throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> storesAsync(options));
  }

  public Receipt deploy(Deploy request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> deployAsync(request, options));
  }

  public Receipt activate(Activate request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> activateAsync(request, options));
  }

  public Status status(String project) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> statusAsync(project, options));
  }

  public Receipt receipt(String project, UUID requestId) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> receiptAsync(project, requestId, options));
  }

  public io.aeyer.plowshare.protocol.ApplicationIdentity.Status identity(String project)
      throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> identityAsync(project, options));
  }

  /**
   * Returns the secret once. Unknown delivery requires receipt inspection, then explicit rotation.
   */
  public io.aeyer.plowshare.protocol.ApplicationIdentity.Credential issue(
      io.aeyer.plowshare.protocol.ApplicationIdentity.Issue request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> issueAsync(request, options));
  }

  public io.aeyer.plowshare.protocol.ApplicationIdentity.Receipt tokenReceipt(
      String project, UUID requestId) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> tokenReceiptAsync(project, requestId, options));
  }

  private static void tokenCoordinates(
      String project,
      UUID requestId,
      io.aeyer.plowshare.protocol.ApplicationIdentity.Receipt receipt)
      throws IOException {
    if (receipt == null
        || !project.equals(receipt.project())
        || !requestId.equals(receipt.requestId()))
      throw new IOException("Foreign Application credential receipt");
  }

  private static void projectIdentity(String project) {
    ContractChecks.identity(project, "project");
    if (!project.equals(project.strip()) || project.length() > 512)
      throw new IllegalArgumentException("Invalid Application project identity");
  }

  private static Receipt coordinates(String project, UUID requestId, Receipt receipt)
      throws IOException {
    if (receipt == null
        || !project.equals(receipt.project())
        || !requestId.equals(receipt.requestId()))
      throw new IOException("Foreign Application deployment receipt");
    return receipt;
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<FileStoreCatalog> storesAsync(
      RequestOptions options) {

    return AsyncSupport.map(
        connection.requestAsync("filestore.list", Map.of(), FileStoreCatalog.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (result == null) throw new IOException("Unreadable FileStore catalogue");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<FileStoreCatalog> storesAsync() {
    return storesAsync(null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> deployAsync(
      Deploy request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("application.deploy", request, Receipt.class, options),
        asyncResult -> {
          var result = asyncResult;
          return coordinates(request.project(), request.requestId(), result);
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> deployAsync(Deploy request) {
    return deployAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> activateAsync(
      Activate request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync("application.activate", request, Receipt.class, options),
        asyncResult -> {
          var result = coordinates(request.project(), request.requestId(), asyncResult);
          if (!result.release().revision().equals(request.revision()))
            throw new IOException("Foreign Application revision");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> activateAsync(Activate request) {
    return activateAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Status> statusAsync(
      String project, RequestOptions options) {

    projectIdentity(project);
    return AsyncSupport.map(
        connection.requestAsync(
            "application.deployment.status", Map.of("project", project), Status.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (result == null || !result.project().equals(project))
            throw new IOException("Foreign Application status");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<Status> statusAsync(String project) {
    return statusAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Receipt> receiptAsync(
      String project, UUID requestId, RequestOptions options) {

    projectIdentity(project);
    Objects.requireNonNull(requestId);
    return AsyncSupport.map(
        connection.requestAsync(
            "application.deployment.receipt",
            Map.of("project", project, "requestId", requestId),
            Receipt.class,
            options),
        asyncResult -> {
          return coordinates(project, requestId, asyncResult);
        });
  }

  public java.util.concurrent.CompletionStage<Receipt> receiptAsync(
      String project, UUID requestId) {
    return receiptAsync(project, requestId, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<
          io.aeyer.plowshare.protocol.ApplicationIdentity.Status>
      identityAsync(String project, RequestOptions options) {

    io.aeyer.plowshare.protocol.ApplicationIdentity.project(project);
    return AsyncSupport.map(
        connection.requestAsync(
            "application.identity.status",
            Map.of("project", project),
            io.aeyer.plowshare.protocol.ApplicationIdentity.Status.class,
            options),
        asyncResult -> {
          var result = asyncResult;
          if (result == null || !project.equals(result.project()))
            throw new IOException("Foreign Application identity status");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<
          io.aeyer.plowshare.protocol.ApplicationIdentity.Status>
      identityAsync(String project) {
    return identityAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<
          io.aeyer.plowshare.protocol.ApplicationIdentity.Receipt>
      tokenReceiptAsync(String project, UUID requestId, RequestOptions options) {

    io.aeyer.plowshare.protocol.ApplicationIdentity.project(project);
    Objects.requireNonNull(requestId);
    return AsyncSupport.map(
        connection.requestAsync(
            "application.token.receipt",
            Map.of("project", project, "requestId", requestId),
            io.aeyer.plowshare.protocol.ApplicationIdentity.Receipt.class,
            options),
        asyncResult -> {
          var result = asyncResult;
          tokenCoordinates(project, requestId, result);
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<
          io.aeyer.plowshare.protocol.ApplicationIdentity.Receipt>
      tokenReceiptAsync(String project, UUID requestId) {
    return tokenReceiptAsync(project, requestId, null);
  }

  public java.util.concurrent.CompletionStage<
          io.aeyer.plowshare.protocol.ApplicationIdentity.Credential>
      issueAsync(
          io.aeyer.plowshare.protocol.ApplicationIdentity.Issue request, RequestOptions options) {

    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync(
            "application.token.issue",
            request,
            io.aeyer.plowshare.protocol.ApplicationIdentity.Credential.class,
            options),
        asyncResult -> {
          var result = asyncResult;
          if (result == null) throw new IOException("Unreadable Application credential");
          tokenCoordinates(request.project(), request.requestId(), result.receipt());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<
          io.aeyer.plowshare.protocol.ApplicationIdentity.Credential>
      issueAsync(io.aeyer.plowshare.protocol.ApplicationIdentity.Issue request) {
    return issueAsync(request, null);
  }
}
