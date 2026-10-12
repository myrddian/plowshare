package io.aeyer.plowshare.server.providers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

/** Persistence codecs retain explicit nulls regardless of unrelated HTTP serializer settings. */
final class ProviderJson {
  private ProviderJson() {}

  static ObjectMapper closed(ObjectMapper source) {
    var mapper =
        source
            .copy()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
    return mapper;
  }
}
