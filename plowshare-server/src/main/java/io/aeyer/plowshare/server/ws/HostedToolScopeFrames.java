package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.relay.tools.HostedToolScopes;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Closed typed worker facades prove delegation without accepting an owner or general project
 * authority.
 */
@Component
public final class HostedToolScopeFrames implements FrameArea {
  private final HostedToolScopes hosting;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;

  public HostedToolScopeFrames(HostedToolScopes hosting) {
    this.hosting = Objects.requireNonNull(hosting);
    mapper =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .findAndAddModules()
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_MISSING_CREATOR_PROPERTIES,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();
    for (var shape :
        new com.fasterxml.jackson.databind.cfg.CoercionInputShape[] {
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean
        })
      mapper
          .coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
          .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    var ids = new com.fasterxml.jackson.databind.module.SimpleModule();
    ids.addDeserializer(
        UUID.class,
        new com.fasterxml.jackson.databind.deser.std.StdDeserializer<UUID>(UUID.class) {
          public UUID deserialize(
              com.fasterxml.jackson.core.JsonParser parser,
              com.fasterxml.jackson.databind.DeserializationContext context)
              throws java.io.IOException {
            if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)
                || !parser.getText().matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
              throw com.fasterxml.jackson.databind.JsonMappingException.from(
                  parser, "Use a canonical UUID");
            return UUID.fromString(parser.getText());
          }
        });
    mapper.registerModule(ids);
  }

  private <T> T decode(Map<String, Object> payload, Class<T> type) {
    try {
      return mapper.convertValue(payload, type);
    } catch (IllegalArgumentException invalid) {
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Invalid hosted scope request; use the complete typed contract");
    }
  }

  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(FrameTypes.HOST_SCOPE_SAVE, this::save),
        Map.entry(FrameTypes.HOST_SCOPE_CHANGE, this::change),
        Map.entry(FrameTypes.HOST_SCOPE_LIST, this::list),
        Map.entry(FrameTypes.HOST_SCOPE_STATUS, this::status),
        Map.entry(FrameTypes.HOST_SCOPE_RECEIPT, this::receipt),
        Map.entry(FrameTypes.HOST_WORKER_REGISTER, this::register),
        Map.entry(FrameTypes.HOST_WORKER_RENEW, this::renew),
        Map.entry(FrameTypes.HOST_WORKER_STATUS, this::workerStatus),
        Map.entry(FrameTypes.HOST_WORKER_CREDENTIAL, this::credential),
        Map.entry(FrameTypes.HOST_WORKER_PUBLISH, this::publish),
        Map.entry(FrameTypes.HOST_WORKER_CONSUME, this::consume),
        Map.entry(FrameTypes.HOST_WORKER_READ, this::read),
        Map.entry(FrameTypes.HOST_WORKER_ACK, this::acknowledge));
  }

  private Outcome save(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.save(a.requireHandle(FrameTypes.HOST_SCOPE_SAVE), decode(p, Save.class)));
  }

  private Outcome change(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.change(a.requireHandle(FrameTypes.HOST_SCOPE_CHANGE), decode(p, Change.class)));
  }

  private Outcome list(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.list(a.requireHandle(FrameTypes.HOST_SCOPE_LIST), decode(p, Query.class)));
  }

  private Outcome status(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.status(a.requireHandle(FrameTypes.HOST_SCOPE_STATUS), decode(p, Status.class)));
  }

  private Outcome receipt(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.receipt(
            a.requireHandle(FrameTypes.HOST_SCOPE_RECEIPT), decode(p, ReceiptQuery.class)));
  }

  private Outcome register(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.register(
            a.requireHandle(FrameTypes.HOST_WORKER_REGISTER),
            a.sessionId(),
            a.connectionId(),
            decode(p, Register.class)));
  }

  private Outcome renew(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.renew(
            a.requireHandle(FrameTypes.HOST_WORKER_RENEW),
            a.sessionId(),
            a.connectionId(),
            decode(p, Worker.class)));
  }

  private Outcome workerStatus(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.workerStatus(
            a.requireHandle(FrameTypes.HOST_WORKER_STATUS),
            a.sessionId(),
            a.connectionId(),
            decode(p, WorkerQuery.class)));
  }

  private Outcome credential(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.credential(
            a.requireHandle(FrameTypes.HOST_WORKER_CREDENTIAL),
            a.sessionId(),
            a.connectionId(),
            decode(p, CredentialRequest.class)));
  }

  private Outcome publish(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.publish(
            a.requireHandle(FrameTypes.HOST_WORKER_PUBLISH),
            a.sessionId(),
            a.connectionId(),
            decode(p, Publish.class)));
  }

  private Outcome consume(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.consume(
            a.requireHandle(FrameTypes.HOST_WORKER_CONSUME),
            a.sessionId(),
            a.connectionId(),
            decode(p, Consume.class),
            a.relayBudget()));
  }

  private Outcome read(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.read(a.requireHandle(FrameTypes.HOST_WORKER_READ), decode(p, Read.class)));
  }

  private Outcome acknowledge(Map<String, Object> p, Asking a) {
    return Outcome.ok(
        hosting.acknowledge(
            a.requireHandle(FrameTypes.HOST_WORKER_ACK),
            a.sessionId(),
            a.connectionId(),
            decode(p, Ack.class)));
  }
}
