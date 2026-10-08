package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.FileStoreCatalog;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileStores;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileStoreFramesTest {
  @Test
  void anonymous_and_caller_selected_accounts_cannot_reach_the_catalogue() {
    var stores = mock(FileStores.class);
    var handler = new FileStoreFrames(stores).frames().get(FrameTypes.FILESTORE_LIST);
    assertThrows(CallerFault.class, () -> handler.handle(Map.of(), new Asking("session", null)));
    assertThrows(
        CallerFault.class,
        () -> handler.handle(Map.of("account", "owner"), new Asking("session", "viewer")));
    verifyNoInteractions(stores);
  }

  @Test
  void authenticated_listing_uses_the_session_account() {
    var stores = mock(FileStores.class);
    var catalogue =
        new FileStoreCatalog(
            List.of(new FileStoreCatalog.Store("reports", FileStoreCatalog.Role.CONTRIBUTOR)));
    when(stores.catalog("viewer")).thenReturn(catalogue);
    var handler = new FileStoreFrames(stores).frames().get(FrameTypes.FILESTORE_LIST);
    assertEquals(catalogue, handler.handle(Map.of(), new Asking("session", "viewer")).payload());
    verify(stores).catalog("viewer");
  }
}
