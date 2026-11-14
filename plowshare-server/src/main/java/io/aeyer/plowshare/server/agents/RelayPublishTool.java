package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.OperationFence;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.relay.RelayPublishing;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Ordinary granted tool, also used by journaled script commands; no model-controlled identity. */
public final class RelayPublishTool implements AgentTool {
  public static final String NAME = "relay_publish";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  static {
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private final RelayPublishing publishing;
  private final String definition;
  private final String session;
  private final RelayPublishing.DefinitionKind kind;
  private final BooleanSupplier cancelled;
  private final OperationFence fence;
  private String call;

  public RelayPublishTool(RelayPublishing publishing) {
    this(
        publishing,
        null,
        null,
        RelayPublishing.DefinitionKind.AGENT,
        () -> false,
        OperationFence.none());
  }

  private RelayPublishTool(
      RelayPublishing publishing,
      String definition,
      String session,
      RelayPublishing.DefinitionKind kind,
      BooleanSupplier cancelled,
      OperationFence fence) {
    this.publishing = Objects.requireNonNull(publishing);
    this.definition = definition;
    this.session = session;
    this.kind = Objects.requireNonNull(kind);
    this.cancelled = Objects.requireNonNull(cancelled);
    this.fence = Objects.requireNonNull(fence);
  }

  /** Mutable call identity belongs only to this run; concurrent jobs never share it. */
  public RelayPublishTool forRun(
      String definition,
      String session,
      RelayPublishing.DefinitionKind kind,
      BooleanSupplier cancelled) {
    return forRun(definition, session, kind, cancelled, OperationFence.none());
  }

  /**
   * Binds a run whose publications run {@code fence} inside the publication transaction, so an
   * operation revoked after the call began cannot append or receive a receipt.
   */
  public RelayPublishTool forRun(
      String definition,
      String session,
      RelayPublishing.DefinitionKind kind,
      BooleanSupplier cancelled,
      OperationFence fence) {
    return new RelayPublishTool(
        publishing, Objects.requireNonNull(definition), session, kind, cancelled, fence);
  }

  @Override
  public ToolSchema schema() {
    return ToolSchema.from(
        NAME,
        "Publish TEXT to an explicitly granted topic in this project's Relay. Returns a durable acceptance receipt, not proof of downstream execution. The harness supplies event identity and ancestry. Never repeat uncertain work using a new call.",
        ToolArguments.object(
            Map.of(
                "topic",
                ToolArguments.string("Exact granted project topic."),
                "text",
                ToolArguments.string("Text to publish verbatim.")),
            List.of("topic", "text")));
  }

  @Override
  public void calledAs(String id) {
    call = id;
  }

  @Override
  public String run(String arguments, Home home) {
    return "Relay publication requires an authenticated execution and harness call identity.";
  }

  @Override
  public String run(String arguments, Home home, UsageAttribution owner) {
    Objects.requireNonNull(arguments);
    Objects.requireNonNull(home);
    RelayPublishing.Request request;
    try {
      request = JSON.readValue(arguments, RelayPublishing.Request.class);
      if (request == null) throw new IllegalArgumentException("Missing request");
    } catch (JsonProcessingException | IllegalArgumentException invalid) {
      return "relay_publish requires only topic and text strings with valid names and bounded text.";
    }
    if (definition == null
        || call == null
        || owner == null
        || !definition.equals(owner.agentName())) return run(arguments, home);
    if (cancelled.getAsBoolean()) return "Relay publication cancelled before submission.";
    RelayPublishing.Receipt receipt;
    try {
      receipt =
          publishing.publish(
              home.project(),
              new RelayPublishing.Execution(owner, session, call, kind),
              request,
              fence);
    } catch (CallerFault | IllegalArgumentException refused) {
      return "Relay publication refused: " + refused.getMessage();
    }
    try {
      return JSON.writeValueAsString(receipt);
    } catch (JsonProcessingException failed) {
      throw new IllegalStateException("Cannot encode publication receipt", failed);
    }
  }
}
