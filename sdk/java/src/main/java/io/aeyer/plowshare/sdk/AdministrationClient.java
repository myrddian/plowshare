package io.aeyer.plowshare.sdk;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Account administration reads. Server authorization applies to every call. */
public final class AdministrationClient {
  private final Plowshare connection;

  public AdministrationClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  public record Status(String handle, boolean serverAdmin) {
    public Status {
      handle = ContractChecks.identity(handle, "handle");
    }
  }

  public record Account(
      String handle,
      boolean enabled,
      boolean serverAdmin,
      boolean mustChangePassword,
      OffsetDateTime createdAt) {
    public Account {
      handle = ContractChecks.identity(handle, "handle");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }

  public Status status() throws IOException {
    return connection.request("admin.status", Map.of(), Status.class);
  }

  public List<Account> accounts() throws IOException {
    var node = connection.request("admin.accounts", Map.of()).requirePayload();
    if (!node.isArray() || node.size() > 10000) throw new IOException("Invalid account listing");
    var accounts = new ArrayList<Account>();
    for (var item : node) accounts.add(SdkJson.decode(SdkJson.mapper(), item, Account.class));
    return List.copyOf(accounts);
  }
}
