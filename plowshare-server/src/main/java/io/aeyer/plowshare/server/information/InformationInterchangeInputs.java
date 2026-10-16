package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Raw JSON ends here. Schema metadata retains explicit null; command optionals never do. */
public final class InformationInterchangeInputs {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  private InformationInterchangeInputs() {}

  public static InformationRequest command(String operation, Map<String, Object> fields) {
    if (InformationJson.json(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > InformationInterchange.MAX_JSON_BYTES)
      throw new CallerFault("Portable JSON exceeds 8 MiB");
    if (fields.containsKey("selection")) throw new CallerFault("Use explicit interchange scope");
    identifiers(fields);
    var body = new LinkedHashMap<>(fields);
    var selection = new LinkedHashMap<String, Object>();
    if (!body.containsKey("scope") || body.get("scope") == null)
      throw new CallerFault("Interchange requires explicit scope");
    selection.put("scope", body.remove("scope"));
    for (String key : List.of("corpus", "view"))
      if (body.containsKey(key)) selection.put(key, body.remove(key));
    body.put("selection", selection);
    try {
      Class<? extends InformationRequest> shape =
          switch (operation) {
            case "interchange.capabilities" -> InformationRequest.InterchangeCapabilities.class;
            case "interchange.export" -> InformationRequest.InterchangeExport.class;
            case "interchange.readable" -> InformationRequest.InterchangeReadable.class;
            case "interchange.import" -> InformationRequest.InterchangeImport.class;
            case "interchange.origin" -> InformationRequest.InterchangeOrigin.class;
            default -> throw new IllegalArgumentException("Unknown interchange operation");
          };
      return JSON.convertValue(body, shape);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid portable interchange parameters");
    }
  }

  private static void identifiers(Object value) {
    if (value == null)
      throw new CallerFault("Portable command optionals must be omitted rather than null");
    if (value instanceof Map<?, ?> map)
      for (var entry : map.entrySet()) {
        if (Set.of("metadata", "schema", "presentation").contains(entry.getKey())) continue;
        if (Set.of(
                "id",
                "resource",
                "revision",
                "source",
                "target",
                "pinnedRevision",
                "foreignResource",
                "foreignRevision",
                "foreignVersion",
                "localVersion",
                "localRevision",
                "expectedRevision",
                "requestId",
                "type",
                "version")
            .contains(entry.getKey())) {
          if (!(entry.getValue() instanceof String id)
              || !id.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
            throw new CallerFault("Portable identifiers must be canonical UUIDs");
        }
        if (entry.getKey().equals("revisions") || entry.getKey().equals("dependencies")) {
          if (!(entry.getValue() instanceof List<?> list))
            throw new CallerFault("Portable revision list required");
          for (var item : list)
            if (!(item instanceof String id)
                || !id.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
              throw new CallerFault("Portable identifiers must be canonical UUIDs");
        }
        identifiers(entry.getValue());
      }
    else if (value instanceof List<?> list) {
      if (list.size() > 64) throw new CallerFault("Portable collection exceeds bounds");
      list.forEach(InformationInterchangeInputs::identifiers);
    }
  }
}
