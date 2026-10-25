package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server admission and cleanup bounds; topic retention itself is durable per-topic policy. */
@ConfigurationProperties("plowshare.relay")
public final class RelayProperties {
  private int maxTextBytes = io.aeyer.plowshare.protocol.RelayPort.DEFAULT_TEXT_BYTES;

  /** Raw UTF-8 bytes allowed on new TEXT publications, independently of encoded packet size. */
  public int getMaxTextBytes() {
    return maxTextBytes;
  }

  public void setMaxTextBytes(int value) {
    maxTextBytes = new RelayTextLimit(value).bytes();
  }

  public RelayTextLimit textLimit() {
    return new RelayTextLimit(maxTextBytes);
  }

  private java.util.List<SystemTopic> systemTopics = java.util.List.of();

  /** Explicit deployment policy for a server-private topic; no project authority is inferred. */
  public record SystemTopic(String name, Duration retention, Long maxRecords) {
    public SystemTopic {
      name = RelayValues.topic(name);
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

  private Duration subscriptionMaximumAge;

  /**
   * Longest a Relay subscription is trusted without its read authority being re-established
   * (decision 0038). This bounds the failure of explicit invalidation; it is not the revocation
   * mechanism, and shortening it is not a substitute for invalidating on a membership change.
   *
   * <p>There is deliberately no default. A deployment that omits it gets an actionable startup
   * failure rather than a permissive window nobody chose.
   */
  public Duration getSubscriptionMaximumAge() {
    return subscriptionMaximumAge;
  }

  public void setSubscriptionMaximumAge(Duration value) {
    if (value == null
        || value.getNano() != 0
        || value.compareTo(Duration.ofMinutes(1)) < 0
        || value.compareTo(Duration.ofDays(30)) > 0)
      throw new IllegalArgumentException(
          "Relay subscription maximum age must be whole seconds, 1 minute through 30 days");
    subscriptionMaximumAge = value;
  }

  /** The configured window, or an actionable failure when the deployment did not supply one. */
  public Duration subscriptionMaximumAge() {
    if (subscriptionMaximumAge == null)
      throw new IllegalStateException(
          "plowshare.relay.subscription-maximum-age is required; it bounds how long a Relay"
              + " subscription is trusted without re-establishing its read authority");
    return subscriptionMaximumAge;
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
  private Duration payloadStagingWindow = Duration.ofHours(1);
  private Duration payloadDeferralCeiling = Duration.ofHours(24);
  private int payloadSweepBatch = 100;

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

  public Duration getPayloadStagingWindow() {
    return payloadStagingWindow;
  }

  /** Must exceed the longest gap between a payload insert and the insert that references it. */
  public void setPayloadStagingWindow(Duration payloadStagingWindow) {
    Objects.requireNonNull(payloadStagingWindow, "payloadStagingWindow");
    // The window must exceed the longest append: it covers the topic FOR UPDATE wait, the locked
    // retained read and position allocation between the payload store and the publication insert.
    // store() rounds the ejection up to the hour, so protection is only guaranteed to be W; a
    // one-second floor would make a failed publish (a foreign-key violation) configurable.
    if (payloadStagingWindow.compareTo(Duration.ofMinutes(5)) < 0
        || payloadStagingWindow.compareTo(Duration.ofDays(7)) > 0)
      throw new IllegalArgumentException("payload staging window must be 5 minutes through 7 days");
    this.payloadStagingWindow = payloadStagingWindow;
  }

  public Duration getPayloadDeferralCeiling() {
    return payloadDeferralCeiling;
  }

  public void setPayloadDeferralCeiling(Duration payloadDeferralCeiling) {
    Objects.requireNonNull(payloadDeferralCeiling, "payloadDeferralCeiling");
    if (payloadDeferralCeiling.compareTo(Duration.ofHours(1)) < 0
        || payloadDeferralCeiling.compareTo(Duration.ofDays(30)) > 0)
      throw new IllegalArgumentException("payload deferral ceiling must be 1 hour through 30 days");
    this.payloadDeferralCeiling = payloadDeferralCeiling;
  }

  public int getPayloadSweepBatch() {
    return payloadSweepBatch;
  }

  public void setPayloadSweepBatch(int payloadSweepBatch) {
    RelayValues.limit(payloadSweepBatch, 1000);
    this.payloadSweepBatch = payloadSweepBatch;
  }
}
