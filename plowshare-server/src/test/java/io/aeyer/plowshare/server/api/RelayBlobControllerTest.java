package io.aeyer.plowshare.server.api;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.protocol.RelayBlob;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.blobs.BlobUnavailableException;
import io.aeyer.plowshare.server.relay.RelayBlobs;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RelayBlobControllerTest {
  @Test
  void multipart_receipt_and_download_use_server_actor_and_safe_binary_headers() throws Exception {
    var service = mock(RelayBlobs.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new RelayBlobController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    String id = UUID.randomUUID().toString();
    var descriptor = new RelayBlob.Descriptor("a".repeat(64), 3, "image/png");
    when(service.publish(eq("writer"), any(), eq(3L), any()))
        .thenReturn(
            new RelayBlob.Published(
                new RelayPort.Published(id, "atlas", "media.capture", "1", Instant.EPOCH),
                descriptor));
    var file = new MockMultipartFile("file", "untrusted.html", "text/html", new byte[] {1, 2, 3});
    mvc.perform(
            multipart("/v1/relay/blobs")
                .file(file)
                .param("requestId", id)
                .param("project", "atlas")
                .param("topic", "media.capture")
                .param("occurredAt", Instant.EPOCH.toString())
                .param("mediaType", "image/png")
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "writer"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.publication.requestId").value(id))
        .andExpect(jsonPath("$.blob.sha256").value(descriptor.sha256()));
    var read = new RelayBlob.Read("atlas", "media.capture", "workers", id);
    when(service.read("reader", read))
        .thenReturn(new RelayBlobs.Content(descriptor, new byte[] {1, 2, 3}));
    mvc.perform(
            get("/v1/relay/blobs")
                .param("project", "atlas")
                .param("topic", "media.capture")
                .param("group", "workers")
                .param("eventId", id)
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "reader"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(new byte[] {1, 2, 3}))
        .andExpect(content().contentType("application/octet-stream"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Plowshare-Blob-Sha256", descriptor.sha256()))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(
            header()
                .string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
  }

  @Test
  void malformed_parameters_and_file_parts_are_refused_before_service_side_effects()
      throws Exception {
    var service = mock(RelayBlobs.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new RelayBlobController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    String id = UUID.randomUUID().toString();
    var file = new MockMultipartFile("file", "blob.bin", null, new byte[] {1});
    for (String invalid : new String[] {"owner", "sha256", "path", "bytes"}) {
      mvc.perform(
              multipart("/v1/relay/blobs")
                  .file(file)
                  .param("requestId", id)
                  .param("project", "atlas")
                  .param("topic", "media.capture")
                  .param("occurredAt", Instant.EPOCH.toString())
                  .param("mediaType", "image/png")
                  .param(invalid, "foreign")
                  .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "writer"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            multipart("/v1/relay/blobs")
                .file(file)
                .param("requestId", "invalid")
                .param("project", "atlas")
                .param("topic", "media.capture")
                .param("occurredAt", Instant.EPOCH.toString())
                .param("mediaType", "image/png")
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "writer"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            multipart("/v1/relay/blobs")
                .file(file)
                .file(file)
                .param("project", "atlas")
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "writer"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/v1/relay/blobs")
                .param("project", "atlas")
                .param("topic", "media.capture")
                .param("group", "workers")
                .param("eventId", id)
                .header("Range", "bytes=0-1")
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "reader"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/v1/relay/blobs")
                .param("project", "atlas")
                .param("topic", "media.capture")
                .param("group", "workers")
                .param("eventId", "invalid")
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "reader"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void managed_unavailability_has_a_shared_sanitized_refusal() throws Exception {
    var service = mock(RelayBlobs.class);
    when(service.read(any(), any())).thenThrow(new BlobUnavailableException());
    var mvc =
        MockMvcBuilders.standaloneSetup(new RelayBlobController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    mvc.perform(
            get("/v1/relay/blobs")
                .param("project", "atlas")
                .param("topic", "media.capture")
                .param("group", "workers")
                .param("eventId", UUID.randomUUID().toString())
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "reader"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("blob_unavailable"));
  }
}
