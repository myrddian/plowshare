package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Stable messaging lifecycle and inspection. Sending remains the granted agent tool API.
 * Conversation links are identities only; opening a conversation still requires server authority.
 */
public final class MessagingClient {
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record InstancesQuery(String project, Boolean archived, Integer offset, Integer limit) {
    public InstancesQuery {
      ForumChecks.id(project);
      ForumChecks.page(offset, limit);
    }
  }

  public record InstanceQuery(String instance) {
    public InstanceQuery {
      ForumChecks.id(instance);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Open(String project, String agent, Boolean makeDefault, String requestId) {
    public Open {
      ForumChecks.id(project);
      ForumChecks.id(agent);
      ForumChecks.uuid(requestId);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DeliveriesQuery(String instance, Integer offset, Integer limit) {
    public DeliveriesQuery {
      ForumChecks.id(instance);
      ForumChecks.page(offset, limit);
    }
  }

  public record DeliveryQuery(String message) {
    public DeliveryQuery {
      ForumChecks.id(message);
    }
  }

  public record Instance(
      String id,
      String project,
      String agent,
      String conversation,
      String lifetime,
      boolean defaultInstance,
      boolean active,
      boolean archived,
      String state,
      int pending,
      @JsonProperty(required = true) String job,
      Instant createdAt) {
    public Instance {
      ForumChecks.id(id);
      ForumChecks.id(project);
      ForumChecks.id(agent);
      ForumChecks.id(conversation);
      ContractChecks.oneOf(lifetime, "lifetime", Set.of("persistent", "task", "caller"));
      ForumChecks.id(state);
      ForumChecks.count(pending);
      ForumChecks.optional(job);
      Objects.requireNonNull(createdAt);
      if (defaultInstance && (!lifetime.equals("persistent") || !active) || archived && active)
        throw new IllegalArgumentException("Inconsistent instance lifecycle");
    }
  }

  public record Delivery(
      String message,
      String sender,
      String recipient,
      @JsonProperty(required = true) String replyTo,
      boolean replyExpected,
      boolean finalReply,
      boolean generated,
      String state,
      @JsonProperty(required = true) String ending,
      @JsonProperty(required = true) String reply,
      @JsonProperty(required = true) String job,
      String conversation,
      String sourceConversation,
      @JsonProperty(required = true) Instant deadlineAt,
      Instant postedAt,
      String body) {
    public Delivery {
      ForumChecks.id(message);
      ForumChecks.id(sender);
      ForumChecks.id(recipient);
      ForumChecks.optional(replyTo);
      ForumChecks.id(state);
      ForumChecks.optional(ending);
      ForumChecks.optional(reply);
      ForumChecks.optional(job);
      ForumChecks.optional(conversation);
      ForumChecks.optional(sourceConversation);
      Objects.requireNonNull(postedAt);
      ForumChecks.text(body, 32768, false);
      Objects.requireNonNull(body);
      if (finalReply && replyTo == null || generated && (!finalReply || ending == null))
        throw new IllegalArgumentException("Inconsistent reply lifecycle");
    }
  }

  public record Instances(List<Instance> instances, boolean more, int offset) {
    public Instances {
      instances = ForumChecks.list(instances, 200);
      ForumChecks.count(offset);
    }
  }

  public record Deliveries(List<Delivery> deliveries, boolean more, int offset) {
    public Deliveries {
      deliveries = ForumChecks.list(deliveries, 200);
      ForumChecks.count(offset);
    }
  }

  private final Plowshare connection;

  public MessagingClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Instances instances(InstancesQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> instancesAsync(request, options));
  }

  public CompletionStage<Instances> instancesAsync(InstancesQuery request) {
    return instancesAsync(request, null);
  }

  public CompletionStage<Instances> instancesAsync(InstancesQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("message.instances", request, Instances.class, options),
        result -> {
          if (!(result.offset() == (request.offset() == null ? 0 : request.offset())
              && result.instances().size() <= (request.limit() == null ? 100 : request.limit())
              && result.instances().stream()
                  .allMatch(
                      i ->
                          request.project().equals(i.project())
                              && (Boolean.TRUE.equals(request.archived()) || !i.archived()))))
            throw new IOException("Foreign or inconsistent message.instances response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Instance open(Open request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> openAsync(request, options));
  }

  public CompletionStage<Instance> openAsync(Open request) {
    return openAsync(request, null);
  }

  public CompletionStage<Instance> openAsync(Open request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("message.instance.open", request, Instance.class, options),
        result -> {
          if (!(request.project().equals(result.project())
              && request.agent().equals(result.agent())))
            throw new IOException("Foreign or inconsistent message.instance.open response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Deliveries deliveries(DeliveriesQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> deliveriesAsync(request, options));
  }

  public CompletionStage<Deliveries> deliveriesAsync(DeliveriesQuery request) {
    return deliveriesAsync(request, null);
  }

  public CompletionStage<Deliveries> deliveriesAsync(
      DeliveriesQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("message.deliveries", request, Deliveries.class, options),
        result -> {
          if (!(result.offset() == (request.offset() == null ? 0 : request.offset())
              && result.deliveries().size() <= (request.limit() == null ? 100 : request.limit())
              && result.deliveries().stream()
                  .allMatch(
                      d ->
                          request.instance().equals(d.sender())
                              || request.instance().equals(d.recipient()))))
            throw new IOException("Foreign or inconsistent message.deliveries response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Instance instance(InstanceQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> instanceAsync(request, options));
  }

  public CompletionStage<Instance> instanceAsync(InstanceQuery request) {
    return instanceAsync(request, null);
  }

  public CompletionStage<Instance> instanceAsync(InstanceQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("message.instance", request, Instance.class, options),
        result -> {
          if (!(request.instance().equals(result.id())))
            throw new IOException("Foreign or inconsistent message.instance response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Instance makeDefault(InstanceQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> makeDefaultAsync(request, options));
  }

  public CompletionStage<Instance> makeDefaultAsync(InstanceQuery request) {
    return makeDefaultAsync(request, null);
  }

  public CompletionStage<Instance> makeDefaultAsync(InstanceQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("message.instance.default", request, Instance.class, options),
        result -> {
          if (!(request.instance().equals(result.id())))
            throw new IOException("Foreign or inconsistent message.instance.default response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Instance stop(InstanceQuery request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> stopAsync(request, options));
  }

  public CompletionStage<Instance> stopAsync(InstanceQuery request) {
    return stopAsync(request, null);
  }

  public CompletionStage<Instance> stopAsync(InstanceQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("message.instance.stop", request, Instance.class, options),
        result -> {
          if (!(request.instance().equals(result.id())))
            throw new IOException("Foreign or inconsistent message.instance.stop response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Instance archive(InstanceQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> archiveAsync(request, options));
  }

  public CompletionStage<Instance> archiveAsync(InstanceQuery request) {
    return archiveAsync(request, null);
  }

  public CompletionStage<Instance> archiveAsync(InstanceQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("message.instance.archive", request, Instance.class, options),
        result -> {
          if (!(request.instance().equals(result.id())))
            throw new IOException("Foreign or inconsistent message.instance.archive response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Delivery delivery(DeliveryQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> deliveryAsync(request, options));
  }

  public CompletionStage<Delivery> deliveryAsync(DeliveryQuery request) {
    return deliveryAsync(request, null);
  }

  public CompletionStage<Delivery> deliveryAsync(DeliveryQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("message.delivery", request, Delivery.class, options),
        result -> {
          if (!(request.message().equals(result.message())))
            throw new IOException("Foreign or inconsistent message.delivery response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Delivery cancel(DeliveryQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> cancelAsync(request, options));
  }

  public CompletionStage<Delivery> cancelAsync(DeliveryQuery request) {
    return cancelAsync(request, null);
  }

  public CompletionStage<Delivery> cancelAsync(DeliveryQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("message.cancel", request, Delivery.class, options),
        result -> {
          if (!(request.message().equals(result.message())))
            throw new IOException("Foreign or inconsistent message.cancel response");
          return result;
        });
  }
}
