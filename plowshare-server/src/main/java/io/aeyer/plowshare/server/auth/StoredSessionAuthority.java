package io.aeyer.plowshare.server.auth;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Narrow socket-session capability over the existing account repository. Keeping this adapter
 * separate preserves AdminStore's legacy class-based injection under either repository proxy mode;
 * adding an interface to that repository would silently switch it to a JDK proxy in installations
 * using interface proxies. All persistence and current account policy remain owned by AdminStore.
 */
@Component
public class StoredSessionAuthority implements SessionAuthority {
  private final AdminStore accounts;

  public StoredSessionAuthority(AdminStore accounts) {
    this.accounts = Objects.requireNonNull(accounts);
  }

  public boolean sessionCurrent(String handle, long version) {
    return accounts.sessionCurrent(handle, version);
  }

  public Set<Session> currentSessions(Collection<Session> sessions) {
    return accounts.currentSessions(sessions);
  }
}
