package io.aeyer.plowshare.server.access;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** Flyway creates identities; every startup verifies them before accepting work or provisioning. */
@Component
public final class IdentityBootstrap implements SmartInitializingSingleton {
  private final IdentityRepository identities;

  public IdentityBootstrap(IdentityRepository identities) {
    this.identities = identities;
  }

  @Override
  public void afterSingletonsInstantiated() {
    identities.verifyBuiltIns();
  }
}
