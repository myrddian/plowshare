package io.aeyer.plowshare.protocol;

import java.util.List;
import java.util.Objects;

/** Caller-visible server aliases and grants. Host paths and other accounts are never disclosed. */
public record FileStoreCatalog(List<Store> stores) {
  public FileStoreCatalog {
    stores = List.copyOf(stores);
    if (stores.size() > 100
        || stores.stream().map(Store::alias).distinct().count() != stores.size())
      throw new IllegalArgumentException("Invalid FileStore catalogue");
  }

  public enum Role {
    VIEWER,
    CONTRIBUTOR,
    MANAGER
  }

  /** A configured alias and this authenticated account's direct FileStore grant. */
  public record Store(String alias, Role role) {
    public Store {
      new FileStoreReference(alias, "");
      Objects.requireNonNull(role, "role");
    }
  }
}
