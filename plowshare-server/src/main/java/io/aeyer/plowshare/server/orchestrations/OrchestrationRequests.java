package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Set;

/**
 * Boundary for shared orchestration DTOs. Known values cannot be coerced into another type. V1
 * still discards fields added by a separate client release; those fields never enter the DTO or
 * application logic. This codec changes no other server request family.
 */
public final class OrchestrationRequests {
  private static final Set<Class<?>> SHAPES =
      Set.of(
          Orchestration.Start.class,
          Orchestration.Resume.class,
          Orchestration.Receipt.class,
          Orchestration.Reference.class,
          Orchestration.Answer.class,
          Orchestration.ListedQuery.class,
          Orchestration.DefinitionQuery.class);
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .findAndAddModules()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  static {
    for (CoercionInputShape shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private OrchestrationRequests() {}

  public static <T> T as(Map<String, Object> payload, Class<T> shape, String operation) {
    if (!SHAPES.contains(shape))
      throw new IllegalArgumentException("unsupported orchestration request shape");
    try {
      return JSON.convertValue(payload == null ? Map.of() : payload, shape);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid " + operation + " request: " + invalid.getMessage(), invalid);
    }
  }
}
