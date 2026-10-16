package io.aeyer.plowshare.server.acting;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator switch; retained consent stays readable/revocable while acting is disabled. */
@ConfigurationProperties(prefix = "plowshare.acting")
public final class ActingProperties {
  private boolean enabled;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
