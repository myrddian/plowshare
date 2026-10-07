package io.aeyer.plowshare.server.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileStoreReference;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strict persistence conversion; malformed stored grants never turn into legacy workspace access.
 */
final class ApplicationPlacementCodec {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ApplicationPlacementCodec() {}

  static String encode(ApplicationPlacement placement) {
    if (placement == null) return null;
    try {
      return JSON.writeValueAsString(placement);
    } catch (IOException impossible) {
      throw new IllegalStateException("Cannot encode Application placement", impossible);
    }
  }

  static ApplicationPlacement decode(String source) {
    if (source == null) return null;
    try {
      JsonNode value = JSON.readTree(source);
      fields(value, Set.of("applicationRoot", "writableAreas"));
      var areas = value.get("writableAreas");
      if (areas == null || !areas.isArray() || areas.size() > 100)
        throw new IllegalArgumentException("Invalid stored writable areas");
      List<FileStoreReference> references = new ArrayList<>();
      for (JsonNode area : areas) references.add(reference(area));
      return new ApplicationPlacement(reference(value.get("applicationRoot")), references);
    } catch (IOException | IllegalArgumentException invalid) {
      throw new IllegalStateException("Invalid stored Application placement", invalid);
    }
  }

  private static FileStoreReference reference(JsonNode value) {
    fields(value, Set.of("store", "path"));
    if (!value.path("store").isTextual() || !value.path("path").isTextual())
      throw new IllegalArgumentException("Invalid stored FileStore reference");
    return new FileStoreReference(value.get("store").textValue(), value.get("path").textValue());
  }

  private static void fields(JsonNode value, Set<String> allowed) {
    if (value == null || !value.isObject())
      throw new IllegalArgumentException("Expected an object");
    value
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!allowed.contains(field))
                throw new IllegalArgumentException("Unknown placement field");
            });
  }
}
