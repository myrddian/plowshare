package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Strict transport decoding finishes before navigation services see a command. */
public final class InformationNavigationInputs {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  private InformationNavigationInputs() {}

  public static boolean supports(String operation) {
    return shape(operation) != null;
  }

  public static InformationRequest command(String operation, Map<String, Object> fields) {
    Class<? extends InformationRequest> type = shape(operation);
    if (type == null) throw new CallerFault("unknown document navigation operation");
    rejectNulls(fields);
    var body = new LinkedHashMap<>(fields);
    var selected = new LinkedHashMap<String, Object>();
    selected.put("scope", body.remove("scope"));
    if (selected.get("scope") == null)
      throw new CallerFault("navigation requires an explicit scope");
    for (String key : List.of("corpus", "filter", "view"))
      if (body.containsKey(key)) selected.put(key, body.remove(key));
    body.put("selection", selected);
    try {
      return JSON.convertValue(body, type);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("invalid document navigation parameters");
    }
  }

  private static void rejectNulls(Object value) {
    if (value == null) throw new CallerFault("navigation command fields cannot be explicitly null");
    if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        if (Set.of(
                "resource",
                "revision",
                "requestId",
                "source",
                "expectedSourceRevision",
                "relationship",
                "id",
                "relatedTo",
                "otherResource")
            .contains(entry.getKey())) {
          if (!(entry.getValue() instanceof String s)
              || !s.matches("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"))
            throw new CallerFault("navigation identifiers must be UUIDs");
        }
        rejectNulls(entry.getValue());
      }
    } else if (value instanceof List<?> list) {
      if (list.size() > 100) throw new CallerFault("navigation collection is too large");
      list.forEach(InformationNavigationInputs::rejectNulls);
    }
  }

  private static Class<? extends InformationRequest> shape(String operation) {
    return switch (operation) {
      case "navigation.capabilities" -> InformationRequest.NavigationCapabilities.class;
      case "documents.list" -> InformationRequest.DocumentList.class;
      case "documents.facets" -> InformationRequest.DocumentFacets.class;
      case "documents.get" -> InformationRequest.DocumentGet.class;
      case "relationships.list" -> InformationRequest.RelationshipList.class;
      case "relationships.create" -> InformationRequest.RelationshipCreate.class;
      case "relationships.remove" -> InformationRequest.RelationshipRemove.class;
      case "policy.get" -> InformationRequest.NavigationPolicyGet.class;
      case "policy.set" -> InformationRequest.NavigationPolicySet.class;
      case "views.list" -> InformationRequest.NavigationViews.class;
      case "views.get" -> InformationRequest.NavigationViewGet.class;
      case "views.save" -> InformationRequest.NavigationViewSave.class;
      case "views.delete" -> InformationRequest.NavigationViewDelete.class;
      default -> null;
    };
  }
}
