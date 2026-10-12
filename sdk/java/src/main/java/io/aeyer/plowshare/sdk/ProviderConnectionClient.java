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
    ProviderCredentials.project(project);
    var rows =
        connection.request("provider.connection.list", Map.of("project", project)).requirePayload();
    if (!rows.isArray() || rows.size() > 256)
      throw new IOException("Invalid provider connection listing");
    var result = new ArrayList<Connection>();
    for (var row : rows) {
      var value = SdkJson.decode(SdkJson.mapper(), row, Connection.class);
      if (!project.equals(value.project())) throw new IOException("Foreign provider connection");
      result.add(value);
    }
    return List.copyOf(result);
  }

  public Connection status(StatusRequest request) throws IOException {
    var value = connection.exchange("provider.connection.status", request, Connection.class);
    if (value == null
        || !value.project().equals(request.project())
        || !value.connection().equals(request.connection()))
      throw new IOException("Foreign provider connection status");
    return value;
  }

  public Credential credential(Target request) throws IOException {
    var value = connection.exchange("provider.credential.status", request, Credential.class);
    if (value == null
        || !value.project().equals(request.project())
        || !value.connection().equals(request.connection())
        || (request.subject() != null && !request.subject().equals(value.subject())))
      throw new IOException("Foreign provider credential status");
    return value;
  }

  public Receipt save(Save request) throws IOException {
    return mutation(
        "provider.connection.save",
        request,
        request.project(),
        request.connection(),
        request.requestId(),
        null);
  }

  public Receipt change(Change request) throws IOException {
    return mutation(
        "provider.connection.change",
        request,
        request.project(),
        request.connection(),
        request.requestId(),
        null);
  }

  public Receipt put(Put request) throws IOException {
    return mutation(
        "provider.credential.put",
        request,
        request.project(),
        request.connection(),
        request.requestId(),
        request.subject());
  }

  public Receipt revoke(Mutation request) throws IOException {
    return credentialMutation("provider.credential.revoke", request);
  }

  public Receipt rekey(Mutation request) throws IOException {
    return credentialMutation("provider.credential.rekey", request);
  }

  public Receipt refresh(Mutation request) throws IOException {
    return credentialMutation("provider.credential.refresh", request);
  }

  private Receipt credentialMutation(String operation, Mutation request) throws IOException {
    return mutation(
        operation,
        request,
        request.project(),
        request.connection(),
        request.requestId(),
        request.subject());
  }

  /**
   * Only this once-only reply carries the browser URL. Persist the redacted receipt coordinates.
   */
  public Authorization authorize(Mutation request) throws IOException {
    var result = connection.exchange("provider.credential.authorize", request, Authorization.class);
    if (result == null) throw new IOException("Unreadable provider authorization");
    correlate(
        result.receipt(),
        request.project(),
        request.connection(),
        request.requestId(),
        request.subject(),
        "provider.credential.authorize");
    return result;
  }

  public Receipt receipt(ReceiptRequest request) throws IOException {
    var result = connection.exchange("provider.connection.receipt", request, Receipt.class);
    correlate(result, request.project(), null, request.requestId(), null, null);
    return result;
  }

  private Receipt mutation(
      String operation, Object request, String project, UUID id, UUID requestId, String subject)
      throws IOException {
    var result = connection.exchange(operation, request, Receipt.class);
    correlate(result, project, id, requestId, subject, operation);
    return result;
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
}
