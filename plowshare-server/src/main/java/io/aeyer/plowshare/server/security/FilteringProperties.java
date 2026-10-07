package io.aeyer.plowshare.server.security;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Native text rules are enabled by default; sensitive-data policies and external review are opt-in.
 */
@ConfigurationProperties(value = "plowshare.security.filtering", ignoreUnknownFields = false)
public final class FilteringProperties {
  public enum Action {
    BLOCK,
    MASK
  }

  public record PatternPolicy(String name, Action action) {
    public PatternPolicy {
      RelayPort.name(name);
      java.util.Objects.requireNonNull(action);
    }
  }

  /** Subjects and reviewer must be different to prevent recursive model-backed filtering. */
  public record External(
      String project,
      String account,
      String requestTopic,
      String responseTopic,
      String reviewer,
      int timeoutSeconds) {
    public External {
      RelayPort.identity(project);
      RelayPort.identity(account);
      RelayPort.name(requestTopic);
      RelayPort.name(responseTopic);
      RelayPort.identity(reviewer);
      if (account.equals(reviewer)
          || requestTopic.equals(responseTopic)
          || timeoutSeconds < 1
          || timeoutSeconds > 60)
        throw new IllegalArgumentException("Invalid external filter binding");
    }
  }

  private boolean enabled = true;
  private boolean conditionalMatches;
  private List<String> categories =
      List.of("prompt_injection_jailbreak", "prompt_injection_system_prompt");
  private List<PatternPolicy> patterns = List.of();
  private List<External> external = List.of();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    enabled = value;
  }

  public boolean isConditionalMatches() {
    return conditionalMatches;
  }

  public void setConditionalMatches(boolean value) {
    conditionalMatches = value;
  }

  public List<String> getCategories() {
    return categories;
  }

  public void setCategories(List<String> value) {
    categories = List.copyOf(value);
    if (categories.size() > 5)
      throw new IllegalArgumentException("At most five injection categories");
  }

  public List<PatternPolicy> getPatterns() {
    return patterns;
  }

  public void setPatterns(List<PatternPolicy> value) {
    patterns = List.copyOf(value);
    if (patterns.size() > 100) throw new IllegalArgumentException("At most 100 sensitive patterns");
  }

  public List<External> getExternal() {
    return external;
  }

  public void setExternal(List<External> value) {
    external = List.copyOf(value);
    if (external.size() > 100)
      throw new IllegalArgumentException("At most 100 external filter bindings");
    var seen = new java.util.HashSet<String>();
    for (var binding : external) {
      if (!seen.add(binding.project() + "\0" + binding.account()))
        throw new IllegalArgumentException("Duplicate external filter subject");
      if (external.stream()
          .anyMatch(
              other ->
                  other.project().equals(binding.project())
                      && other.account().equals(binding.reviewer())))
        throw new IllegalArgumentException(
            "Reviewer cannot also be a filtered subject in the same project");
    }
  }
}
