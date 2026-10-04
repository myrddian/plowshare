package io.aeyer.plowshare.server.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code plowshare.harness}: named pipeline configurations, and which one a model with no
 * assignment of its own runs.
 */
@ConfigurationProperties(prefix = "plowshare.harness")
public class HarnessProperties {

  /** The profile for a model no pool assigns one to; blank for no harness hooks. */
  private String defaultProfile = "";

  // Written "profile: []" (a flow-style empty sequence), not "profile: " nor
  // "profile:" with nothing after it: YAML's block form leaves the key's value null,
  // which the Binder treats as "not set" and drops the entry entirely, so an empty
  // profile written that way is indistinguishable from a missing one. "[ ]" (with a
  // space) also binds; both parse as an empty flow sequence. HarnessTest's PROFILES
  // constant uses "minimal: []" and it binds to an empty list, confirmed by
  // an_empty_profile_runs_no_harness_hooks.
  /** Profile name → its hooks in order, each a map with {@code hook} and parameters. */
  private Map<String, List<Map<String, Object>>> profiles = new LinkedHashMap<>();

  public String getDefaultProfile() {
    return defaultProfile;
  }

  public void setDefaultProfile(String defaultProfile) {
    this.defaultProfile = defaultProfile == null ? "" : defaultProfile.strip();
  }

  public Map<String, List<Map<String, Object>>> getProfiles() {
    return profiles;
  }

  public void setProfiles(Map<String, List<Map<String, Object>>> profiles) {
    this.profiles = profiles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(profiles);
  }
}
