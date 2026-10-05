package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit execution identities and bounded local resources; empty projects enable no workers. */
@ConfigurationProperties(value = "plowshare.relay.workers", ignoreUnknownFields = false)
public final class RelayWorkerProperties {
  private List<Project> projects = List.of();
  private Duration configurationInterval = Duration.ofSeconds(30);
  private Duration idleInterval = Duration.ofSeconds(5);
  private Duration failureBackoff = Duration.ofSeconds(30);
  private int maxWorkers = 256;
  private int concurrency = 8;
  private int batchSize = 8;

  /** A trusted deployment binding supplies the principal; project JS cannot choose authority. */
  public record Project(String project, String account) {
    public Project {
      project = RelayValues.identity(project, "worker project");
      account = RelayValues.identity(account, "worker account");
    }
  }

  public List<Project> getProjects() {
    return projects;
  }

  public void setProjects(List<Project> projects) {
    projects = List.copyOf(projects);
    if (projects.size() > 32) throw new IllegalArgumentException("at most 32 worker projects");
    var unique = new HashSet<String>();
    for (var binding : projects)
      if (!unique.add(binding.project()))
        throw new IllegalArgumentException("duplicate worker project");
    this.projects = projects;
  }

  public Duration getConfigurationInterval() {
    return configurationInterval;
  }

  public void setConfigurationInterval(Duration value) {
    configurationInterval = interval(value);
  }

  public Duration getIdleInterval() {
    return idleInterval;
  }

  public void setIdleInterval(Duration value) {
    idleInterval = interval(value);
  }

  public Duration getFailureBackoff() {
    return failureBackoff;
  }

  public void setFailureBackoff(Duration value) {
    failureBackoff = interval(value);
  }

  public int getMaxWorkers() {
    return maxWorkers;
  }

  public void setMaxWorkers(int value) {
    RelayValues.limit(value, 4096);
    maxWorkers = value;
  }

  public int getConcurrency() {
    return concurrency;
  }

  public void setConcurrency(int value) {
    RelayValues.limit(value, 64);
    concurrency = value;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int value) {
    RelayValues.limit(value, 32);
    batchSize = value;
  }

  private static Duration interval(Duration value) {
    Objects.requireNonNull(value);
    if (value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(Duration.ofMinutes(5)) > 0)
      throw new IllegalArgumentException("worker interval must be 1 second through 5 minutes");
    return value;
  }
}
