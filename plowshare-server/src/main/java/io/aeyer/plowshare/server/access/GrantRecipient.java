package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit recipient identity prevents direct account authority from leaking into agent grants.
 * Agent keys must come from retained server identity, never a caller-selected display/definition
 * name. Runtime consumers must identify children separately even when using the same definition.
 */
public record GrantRecipient(AccountId account, Optional<String> agent) {
  public GrantRecipient {
    Objects.requireNonNull(account);
    Objects.requireNonNull(agent);
    agent.ifPresent(
        value -> {
          if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}"))
            throw new IllegalArgumentException("Invalid agent grant recipient");
        });
  }

  public static GrantRecipient account(AccountId account) {
    return new GrantRecipient(account, Optional.empty());
  }

  public static GrantRecipient agent(AccountId account, String agent) {
    return new GrantRecipient(account, Optional.of(agent));
  }
}
