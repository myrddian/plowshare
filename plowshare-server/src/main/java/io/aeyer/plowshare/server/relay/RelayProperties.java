package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server cleanup bounds; topic retention itself is durable per-topic policy. */
@ConfigurationProperties("plowshare.relay")
public final class RelayProperties {
  private java.util.List<SystemTopic> systemTopics = java.util.List.of();

  /** Explicit deployment policy for a server-private topic; no project authority is inferred. */
  public record SystemTopic(String name, Duration retention, Long maxRecords) {
    public SystemTopic {
      name = RelayValues.name(name, "system topic policy");
      retention = new Relay.Policy(retention, maxRecords).retention();
    }

    public Relay.Policy policy() {
      return new Relay.Policy(retention, maxRecords);
    }
  }

  public java.util.List<SystemTopic> getSystemTopics() {
    return systemTopics;
  }

  public void setSystemTopics(java.util.List<SystemTopic> value) {
    var copy = java.util.List.copyOf(value);
    if (copy.size() > 100) throw new IllegalArgumentException("At most 100 system topic policies");
    var unique = new java.util.HashSet<String>();
    for (var topic : copy)
      if (!unique.add(topic.name()))
        throw new IllegalArgumentException("Duplicate system topic policy");
    systemTopics = copy;
  }

  public java.util.Optional<Relay.Policy> systemPolicy(Relay.TopicKey topic) {
    if (topic.scope() != Relay.SystemScope.SERVER) return java.util.Optional.empty();
    return systemTopics.stream()
        .filter(value -> value.name().equals(topic.name()))
        .findFirst()
        .map(SystemTopic::policy);
  }

  private boolean internalEnabled = true;

  public boolean isInternalEnabled() {
    return internalEnabled;
  }

  public void setInternalEnabled(boolean value) {
    internalEnabled = value;
  }

  private boolean cleanupEnabled = true;
  private Duration operatorRetention = Duration.ofDays(90);

  public Duration getOperatorRetention() {
    return operatorRetention;
  }

  public void setOperatorRetention(Duration value) {
    operatorRetention = new Relay.Policy(value, null).retention();
  }

  private int maxForwardingHops = 8;

  public int getMaxForwardingHops() {
    return maxForwardingHops;
  }

  public void setMaxForwardingHops(int value) {
    RelayValues.limit(value, 32);
    maxForwardingHops = value;
  }

  private Duration cleanupInterval = Duration.ofMinutes(1);
  private int topicBatchSize = 100;
  private int publicationBatchSize = 1000;
  private int deliveryBatchSize = 1000;
  private Duration settledRetention = Duration.ofDays(30);

  public int getDeliveryBatchSize() {
    return deliveryBatchSize;
  }

  public void setDeliveryBatchSize(int deliveryBatchSize) {
    RelayValues.limit(deliveryBatchSize, 1000);
    this.deliveryBatchSize = deliveryBatchSize;
  }

  public Duration getSettledRetention() {
    return settledRetention;
  }

  public void setSettledRetention(Duration settledRetention) {
    this.settledRetention = new Relay.Policy(settledRetention, null).retention();
  }

  public boolean isCleanupEnabled() {
    return cleanupEnabled;
  }

  public void setCleanupEnabled(boolean cleanupEnabled) {
    this.cleanupEnabled = cleanupEnabled;
  }

  public Duration getCleanupInterval() {
    return cleanupInterval;
  }

  public void setCleanupInterval(Duration cleanupInterval) {
    Objects.requireNonNull(cleanupInterval, "cleanupInterval");
    if (cleanupInterval.compareTo(Duration.ofSeconds(1)) < 0
        || cleanupInterval.compareTo(Duration.ofDays(1)) > 0)
      throw new IllegalArgumentException("cleanup interval must be 1 second through 1 day");
    this.cleanupInterval = cleanupInterval;
  }

  public int getTopicBatchSize() {
    return topicBatchSize;
  }

  public void setTopicBatchSize(int topicBatchSize) {
    RelayValues.limit(topicBatchSize, 100);
    this.topicBatchSize = topicBatchSize;
  }

  public int getPublicationBatchSize() {
    return publicationBatchSize;
  }

  public void setPublicationBatchSize(int publicationBatchSize) {
    RelayValues.limit(publicationBatchSize, 1000);
    this.publicationBatchSize = publicationBatchSize;
  }
}
