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
    return AsyncSupport.await(connection.defaultOptions(), options -> statusAsync(options));
  }

  public List<Account> accounts() throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> accountsAsync(options));
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Status> statusAsync(RequestOptions options) {

    return AsyncSupport.map(
        connection.requestAsync("admin.status", Map.of(), Status.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Status> statusAsync() {
    return statusAsync(null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<Account>> accountsAsync(RequestOptions options) {

    return AsyncSupport.map(
        connection.requestAsync("admin.accounts", Map.of(), options),
        asyncResult -> {
          var node = asyncResult.requirePayload();
          if (!node.isArray() || node.size() > 10000)
            throw new IOException("Invalid account listing");
          var accounts = new ArrayList<Account>();
          for (var item : node) accounts.add(SdkJson.decode(SdkJson.mapper(), item, Account.class));
          return List.copyOf(accounts);
        });
  }

  public java.util.concurrent.CompletionStage<List<Account>> accountsAsync() {
    return accountsAsync(null);
  }
}
