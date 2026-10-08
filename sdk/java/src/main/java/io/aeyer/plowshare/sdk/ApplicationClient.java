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
    var result = connection.request("filestore.list", Map.of(), FileStoreCatalog.class);
    if (result == null) throw new IOException("Unreadable FileStore catalogue");
    return result;
  }

  public Receipt deploy(Deploy request) throws IOException {
    var result = connection.exchange("application.deploy", request, Receipt.class);
    return coordinates(request.project(), request.requestId(), result);
  }

  public Receipt activate(Activate request) throws IOException {
    var result =
        coordinates(
            request.project(),
            request.requestId(),
            connection.exchange("application.activate", request, Receipt.class));
    if (!result.release().revision().equals(request.revision()))
      throw new IOException("Foreign Application revision");
    return result;
  }

  public Status status(String project) throws IOException {
    projectIdentity(project);
    var result =
        connection.request(
            "application.deployment.status", Map.of("project", project), Status.class);
    if (result == null || !result.project().equals(project))
      throw new IOException("Foreign Application status");
    return result;
  }

  public Receipt receipt(String project, UUID requestId) throws IOException {
    projectIdentity(project);
    Objects.requireNonNull(requestId);
    return coordinates(
        project,
        requestId,
        connection.request(
            "application.deployment.receipt",
            Map.of("project", project, "requestId", requestId),
            Receipt.class));
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
}
