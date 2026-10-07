package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.archive.ApplicationPlacement;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict transport conversion; nested references are validated before provisioning or persistence.
 */
final class FileStoreRequests {
  private FileStoreRequests() {}

  static FileStoreReference reference(Object value) {
    if (!(value instanceof Map<?, ?> object)
        || !object.keySet().equals(Set.of("store", "path"))
        || !(object.get("store") instanceof String store)
        || !(object.get("path") instanceof String path))
      throw new CallerFault("FileStore references need exactly store and path text fields");
    try {
      return new FileStoreReference(store, path);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault(invalid.getMessage());
    }
  }

  static ApplicationPlacement placement(Map<String, Object> payload) {
    FileStoreReference root = reference(payload.get("applicationRoot"));
    Object raw = payload.get("writableAreas");
    if (!(raw instanceof List<?> list) || list.size() > 100)
      throw new CallerFault("Specify at most 100 explicit writableAreas; use [] for read-only");
    List<FileStoreReference> areas = new ArrayList<>();
    for (Object item : list) areas.add(reference(item));
    try {
      return new ApplicationPlacement(root, areas);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault(invalid.getMessage());
    }
  }
}
