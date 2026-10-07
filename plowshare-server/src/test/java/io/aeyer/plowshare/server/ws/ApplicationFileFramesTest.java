package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.ApplicationFiles;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationFileFramesTest {
  @Test
  void rejects_coercion_null_unknown_fields_and_anonymous_access_before_source_access() {
    var files = mock(ApplicationFiles.class);
    var frames = new ApplicationFileFrames(files);
    var asking = new Asking("session", "reader");
    for (Map<String, Object> payload :
        java.util.List.<Map<String, Object>>of(
            Map.of("project", 3),
            Map.of("project", "app", "path", 1),
            Map.of("project", "app", "account", "other")))
      assertThrows(CallerFault.class, () -> frames.list(payload, asking));
    var nullable = new HashMap<String, Object>();
    nullable.put("project", "app");
    nullable.put("path", null);
    assertThrows(CallerFault.class, () -> frames.list(nullable, asking));
    assertThrows(CallerFault.class, () -> frames.read(Map.of("project", "app"), asking));
    assertThrows(
        CallerFault.class, () -> frames.list(Map.of("project", "app"), new Asking("anonymous")));
    verifyNoInteractions(files);
  }

  @Test
  void root_listing_and_empty_file_save_use_the_authenticated_account() {
    var files = mock(ApplicationFiles.class);
    var frames = new ApplicationFileFrames(files);
    var asking = new Asking("session", "reader");
    when(files.list(any(), anyString()))
        .thenReturn(new ApplicationFiles.Listing("app", "", java.util.List.of(), false));
    when(files.save(any(), anyString(), anyString(), anyString()))
        .thenReturn(new ApplicationFiles.Document("app", "empty.txt", "", "a".repeat(64), true));
    frames.list(Map.of("project", "app"), asking);
    verify(files).list(new ApplicationFiles.Caller("app", "reader"), "");
    frames.save(
        Map.of("project", "app", "path", "empty.txt", "text", "", "revision", "a".repeat(64)),
        asking);
    verify(files)
        .save(new ApplicationFiles.Caller("app", "reader"), "empty.txt", "", "a".repeat(64));
  }
}
