package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileStores;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Account-scoped discovery for selectors; mutations still enforce their owning permissions. */
@Component
public final class FileStoreFrames implements FrameArea {
  private final FileStores stores;

  public FileStoreFrames(FileStores stores) {
    this.stores = stores;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.FILESTORE_LIST, this::list);
  }

  private Outcome list(Map<String, Object> payload, Asking asking) {
    if (!payload.isEmpty()) throw new CallerFault("FileStore listing accepts no fields");
    return Outcome.ok(stores.catalog(asking.requireHandle(FrameTypes.FILESTORE_LIST)));
  }
}
