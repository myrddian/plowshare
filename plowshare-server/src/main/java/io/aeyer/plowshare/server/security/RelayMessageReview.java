package io.aeyer.plowshare.server.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.FilterReview;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayLogRepository;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayPortProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** A bounded, fail-closed application protocol over arbitrary configured text topics. */
public final class RelayMessageReview implements MessageReview {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  static {
    JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
  }

  private final FilteringProperties properties;
  private final Relay relay;
  private final RelayLogRepository logs;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final RelayPortProperties ports;

  public RelayMessageReview(
      FilteringProperties properties,
      Relay relay,
      RelayLogRepository logs,
      ProjectMembers members,
      ProjectWorkspaces projects,
      RelayPortProperties ports) {
    this.properties = Objects.requireNonNull(properties);
    this.relay = Objects.requireNonNull(relay);
    this.logs = Objects.requireNonNull(logs);
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    this.ports = Objects.requireNonNull(ports);
  }

  @Override
  public String review(
      UsageAttribution owner, String role, String text, BooleanSupplier abandoned) {
    var selected =
        properties.getExternal().stream()
            .filter(
                b ->
                    b.project().equals(owner.projectId())
                        && b.account().equals(owner.accountHandle()))
            .findFirst();
    if (selected.isEmpty()) return text;
    var binding = selected.get();
    long id = require(binding);
    var requestTopic = new Relay.TopicKey(id, binding.requestTopic());
    var responseTopic = new Relay.TopicKey(id, binding.responseTopic());
    relay.registerTopic(requestTopic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    relay.registerTopic(responseTopic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    long cursor = relay.topic(responseTopic).lastPosition();
    String requestId = UUID.randomUUID().toString();
    String hash = RelayPort.hash(role + "\0" + text);
    final String encoded;
    try {
      encoded = JSON.writeValueAsString(new FilterReview.Request(1, requestId, hash, role, text));
    } catch (com.fasterxml.jackson.core.JsonProcessingException
        | IllegalArgumentException invalid) {
      throw refused("request_invalid");
    }
    if (encoded.length() > 65536) throw refused("request_limit");
    checkCancelled(abandoned);
    // Publication is the durable held message. It commits before waiting, outside any model
    // pool slot or DB transaction. Restart/timeout does not automatically republish this request.
    relay.publish(
        requestTopic,
        new Relay.Draft(
            requestId,
            "filter:" + RelayPort.hash(binding.account()),
            Instant.now(),
            requestId,
            null,
            new RelayPayload.Text(encoded),
            new RelayCausation(requestId, null, 0)));
    long deadline =
        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(binding.timeoutSeconds());
    while (System.nanoTime() < deadline) {
      checkCancelled(abandoned);
      require(binding);
      var page = logs.read(responseTopic, cursor, 100, binding.account());
      if (page.topic().expiredThrough() > cursor) throw refused("response_gap");
      for (var publication : page.events()) {
        cursor = publication.position();
        var event = publication.event();
        if (!requestId.equals(event.correlationId())) continue;
        if (!event.publisher().equals(RelayPort.publisher(binding.reviewer()))) continue;
        if (event.causation() == null
            || event.causation().depth() != 1
            || !requestId.equals(event.causationId())
            || !requestId.equals(event.causation().parentId())
            || !requestId.equals(event.causation().rootId())
            || !(event.payload() instanceof RelayPayload.Text payload))
          throw refused("response_identity");
        final FilterReview.Response response;
        try {
          response = JSON.readValue(payload.text(), FilterReview.Response.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
          throw refused("response_invalid");
        }
        if (!requestId.equals(response.requestId()) || !hash.equals(response.sourceHash()))
          throw refused("response_identity");
        if (!response.accepted()) throw refused("reviewer_rejected");
        checkCancelled(abandoned);
        require(binding);
        return response.message();
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new CallerAbandonedException("filter-review");
      }
    }
    throw refused("timeout");
  }

  private long require(FilteringProperties.External b) {
    Long id = projects.id(b.project());
    var personal = projects.personalOwner(b.project());
    if (id == null
        || personal.isPresent()
            && (!personal.get().equals(b.account()) || !personal.get().equals(b.reviewer()))
        || !members.mayWork(b.project(), b.account())
        || !members.mayWork(b.project(), b.reviewer())
        || !ports.permits(
            b.reviewer(), b.project(), b.requestTopic(), RelayPortProperties.Direction.EGRESS, null)
        || !ports.permits(
            b.reviewer(),
            b.project(),
            b.responseTopic(),
            RelayPortProperties.Direction.INGRESS,
            null)) throw refused("authority");
    return id;
  }

  private static void checkCancelled(BooleanSupplier abandoned) {
    if (abandoned.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new CallerAbandonedException("filter-review");
  }

  private static LlmException refused(String code) {
    return new LlmException("External message review refused: " + code);
  }
}
