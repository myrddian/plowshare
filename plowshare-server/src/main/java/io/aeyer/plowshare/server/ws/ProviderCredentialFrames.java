package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.providers.ProviderConnections;
import java.util.*;
import org.springframework.stereotype.Component;

/** Closed, validated DTO conversion completes before invoking any credential/domain method. */
@Component
public final class ProviderCredentialFrames implements FrameArea {
  private final ProviderConnections connections;
  private final ObjectMapper mapper;

  public ProviderCredentialFrames(ProviderConnections connections) {
    this.connections = connections;
    mapper =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    var ids = new SimpleModule();
    ids.addDeserializer(
        UUID.class,
        new com.fasterxml.jackson.databind.deser.std.StdDeserializer<UUID>(UUID.class) {
          @Override
          public UUID deserialize(
              com.fasterxml.jackson.core.JsonParser parser, DeserializationContext context)
              throws java.io.IOException {
            if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)
                || !parser.getText().matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
              throw JsonMappingException.from(parser, "Use a canonical UUID");
            return UUID.fromString(parser.getText());
          }
        });
    mapper.registerModule(ids);
  }

  private <T> T decode(Map<String, Object> payload, Class<T> type) {
    try {
      return mapper.convertValue(payload, type);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault(
          "Invalid provider management request; use the complete typed operation contract");
    }
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(FrameTypes.PROVIDER_CONNECTION_LIST, this::list),
        Map.entry(FrameTypes.PROVIDER_CONNECTION_STATUS, this::status),
        Map.entry(FrameTypes.PROVIDER_CONNECTION_SAVE, this::save),
        Map.entry(FrameTypes.PROVIDER_CONNECTION_CHANGE, this::change),
        Map.entry(FrameTypes.PROVIDER_CONNECTION_RECEIPT, this::receipt),
        Map.entry(FrameTypes.PROVIDER_CREDENTIAL_STATUS, this::credential),
        Map.entry(FrameTypes.PROVIDER_CREDENTIAL_PUT, this::put),
        Map.entry(FrameTypes.PROVIDER_CREDENTIAL_REVOKE, this::revoke),
        Map.entry(FrameTypes.PROVIDER_CREDENTIAL_REKEY, this::rekey),
        Map.entry(FrameTypes.PROVIDER_CREDENTIAL_AUTHORIZE, this::authorize),
        Map.entry(FrameTypes.PROVIDER_CREDENTIAL_REFRESH, this::refresh));
  }

  private Outcome list(Map<String, Object> p, Asking a) {
    var r = decode(p, Project.class);
    return Outcome.ok(
        connections.list(a.requireHandle(FrameTypes.PROVIDER_CONNECTION_LIST), r.project()));
  }

  private Outcome status(Map<String, Object> p, Asking a) {
    var r = decode(p, StatusRequest.class);
    return Outcome.ok(
        connections.status(
            a.requireHandle(FrameTypes.PROVIDER_CONNECTION_STATUS), r.project(), r.connection()));
  }

  private Outcome save(Map<String, Object> p, Asking a) {
    var r = decode(p, Save.class);
    return Outcome.ok(connections.save(a.requireHandle(FrameTypes.PROVIDER_CONNECTION_SAVE), r));
  }

  private Outcome change(Map<String, Object> p, Asking a) {
    var r = decode(p, Change.class);
    return Outcome.ok(
        connections.change(a.requireHandle(FrameTypes.PROVIDER_CONNECTION_CHANGE), r));
  }

  private Outcome receipt(Map<String, Object> p, Asking a) {
    var r = decode(p, ReceiptRequest.class);
    return Outcome.ok(
        connections.receipt(
            a.requireHandle(FrameTypes.PROVIDER_CONNECTION_RECEIPT), r.project(), r.requestId()));
  }

  private Outcome credential(Map<String, Object> p, Asking a) {
    var r = decode(p, Target.class);
    return Outcome.ok(
        connections.credential(a.requireHandle(FrameTypes.PROVIDER_CREDENTIAL_STATUS), r));
  }

  private Outcome put(Map<String, Object> p, Asking a) {
    var r = decode(p, Put.class);
    return Outcome.ok(connections.put(a.requireHandle(FrameTypes.PROVIDER_CREDENTIAL_PUT), r));
  }

  private Outcome revoke(Map<String, Object> p, Asking a) {
    var r = decode(p, Mutation.class);
    return Outcome.ok(
        connections.revoke(a.requireHandle(FrameTypes.PROVIDER_CREDENTIAL_REVOKE), r));
  }

  private Outcome rekey(Map<String, Object> p, Asking a) {
    var r = decode(p, Mutation.class);
    return Outcome.ok(connections.rekey(a.requireHandle(FrameTypes.PROVIDER_CREDENTIAL_REKEY), r));
  }

  private Outcome authorize(Map<String, Object> p, Asking a) {
    var r = decode(p, Mutation.class);
    return Outcome.ok(
        connections.authorize(a.requireHandle(FrameTypes.PROVIDER_CREDENTIAL_AUTHORIZE), r));
  }

  private Outcome refresh(Map<String, Object> p, Asking a) {
    var r = decode(p, Mutation.class);
    return Outcome.ok(
        connections.refresh(a.requireHandle(FrameTypes.PROVIDER_CREDENTIAL_REFRESH), r));
  }
}
