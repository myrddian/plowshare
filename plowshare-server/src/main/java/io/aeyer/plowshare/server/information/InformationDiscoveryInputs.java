package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.InformationDiscovery;
import io.aeyer.plowshare.protocol.InformationRequest.FacetFilter;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict wire/model boundary; no raw JSON reaches discovery services. */
public final class InformationDiscoveryInputs {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .build();

  static {
    for (var shape :
        List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean))
      JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private InformationDiscoveryInputs() {}

  public static <T> T decode(Map<String, Object> fields, Class<T> type) {
    validate(fields);
    try {
      return JSON.convertValue(fields, type);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid information discovery parameters");
    }
  }

  private static void validate(Object value) {
    if (value == null) throw new CallerFault("Discovery fields cannot be explicitly null");
    if (value instanceof Map<?, ?> fields) {
      for (var entry : fields.entrySet()) {
        if (Set.of("revision", "contentType", "contentTypeVersion").contains(entry.getKey())
            && (!(entry.getValue() instanceof String id)
                || !id.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")))
          throw new CallerFault("Exact discovery UUID required");
        validate(entry.getValue());
      }
    } else if (value instanceof List<?> list) {
      if (list.size() > 32) throw new CallerFault("Discovery collection exceeds 32 values");
      list.forEach(InformationDiscoveryInputs::validate);
    }
  }

  public static InformationDiscovery.Query query(String operation, Map<String, Object> fields) {
    Set<String> permitted =
        switch (operation) {
          case "list" -> Set.of("scopes", "filter", "limit", "cursor");
          case "search" -> Set.of("scopes", "filter", "query", "limit", "cursor");
          case "facets" -> Set.of("scopes", "filter", "facet", "limit", "cursor");
          default -> throw new CallerFault("Unknown discovery operation");
        };
    if (!permitted.containsAll(fields.keySet())) throw new CallerFault("Unknown discovery field");
    var query = decode(fields, InformationDiscovery.Query.class);
    if (operation.equals("search") && query.query() == null
        || operation.equals("facets") && query.facet() == null)
      throw new CallerFault("Discovery operation requires its query or facet");
    return query;
  }

  public static InformationFacets filter(FacetFilter filter) {
    return filter == null
        ? InformationFacets.NONE
        : new InformationFacets(
            filter.kind(),
            filter.tags(),
            filter.autoTag(),
            filter.tagGroup(),
            filter.author(),
            filter.documentAuthor(),
            filter.when(),
            filter.subtype(),
            null,
            filter.contentType(),
            filter.contentTypeVersion(),
            filter.untyped());
  }
}
