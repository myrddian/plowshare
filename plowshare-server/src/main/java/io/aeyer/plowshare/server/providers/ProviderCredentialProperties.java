package io.aeyer.plowshare.server.providers;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * No deployment addresses or key material have defaults. Enabling hosting validates them at boot.
 */
@ConfigurationProperties(prefix = "plowshare.providers.credentials")
public class ProviderCredentialProperties {
  private boolean enabled;
  private String activeKey;
  private Map<String, String> keys = Map.of();
  private Set<String> allowedOrigins = Set.of();
  private String callback;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getActiveKey() {
    return activeKey;
  }

  public void setActiveKey(String activeKey) {
    this.activeKey = activeKey;
  }

  public Map<String, String> getKeys() {
    return keys;
  }

  public void setKeys(Map<String, String> keys) {
    this.keys = Map.copyOf(keys);
  }

  public Set<String> getAllowedOrigins() {
    return allowedOrigins;
  }

  public void setAllowedOrigins(Set<String> allowedOrigins) {
    this.allowedOrigins = Set.copyOf(allowedOrigins);
  }

  public String getCallback() {
    return callback;
  }

  public void setCallback(String callback) {
    this.callback = callback;
  }

  @Override
  public String toString() {
    return "ProviderCredentialProperties[enabled=" + enabled + ", keys=<redacted>]";
  }
}
