package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.RelayBlob;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.RelayBlobs;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** Explicit binary HTTP boundary; WebSocket Relay delivery carries immutable descriptors. */
@RestController
public class RelayBlobController {
  private final RelayBlobs blobs;

  public RelayBlobController(RelayBlobs blobs) {
    this.blobs = blobs;
  }

  @PostMapping(path = "/v1/relay/blobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<RelayBlob.Published> publish(MultipartHttpServletRequest request)
      throws IOException {
    parameters(
        request,
        Set.of(
            "requestId",
            "project",
            "topic",
            "occurredAt",
            "mediaType",
            "correlationId",
            "parentTopic",
            "parentEventId"));
    var parts = request.getMultiFileMap();
    if (parts.size() != 1 || !parts.containsKey("file") || parts.get("file").size() != 1)
      throw new CallerFault("Relay binary publication requires one file part");
    var metadata = publication(request);
    var file = parts.getFirst("file");
    if (file == null) throw new CallerFault("Relay binary publication requires one file part");
    try (var input = file.getInputStream()) {
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(blobs.publish(actor(request), metadata, file.getSize(), input));
    }
  }

  @GetMapping(path = "/v1/relay/blobs")
  public ResponseEntity<byte[]> read(HttpServletRequest request) {
    parameters(request, Set.of("project", "topic", "group", "eventId"));
    if (request.getHeader(HttpHeaders.RANGE) != null)
      throw new CallerFault("Relay blob ranges are not supported");
    var content = blobs.read(actor(request), reading(request));
    var descriptor = content.descriptor();
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(descriptor.byteLength())
        .cacheControl(CacheControl.noStore().cachePrivate())
        .header("X-Content-Type-Options", "nosniff")
        .header("X-Plowshare-Blob-Sha256", descriptor.sha256())
        .header("X-Plowshare-Blob-Media-Type", descriptor.mediaType())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"blob-" + descriptor.sha256() + "\"")
        .body(content.bytes());
  }

  private static RelayBlob.Publish publication(HttpServletRequest request) {
    try {
      return new RelayBlob.Publish(
          required(request, "requestId"),
          required(request, "project"),
          required(request, "topic"),
          instant(required(request, "occurredAt")),
          required(request, "mediaType"),
          request.getParameter("correlationId"),
          request.getParameter("parentTopic"),
          request.getParameter("parentEventId"));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("invalid Relay binary publication metadata");
    }
  }

  private static RelayBlob.Read reading(HttpServletRequest request) {
    try {
      return new RelayBlob.Read(
          required(request, "project"),
          required(request, "topic"),
          required(request, "group"),
          required(request, "eventId"));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("invalid Relay binary read metadata");
    }
  }

  private static void parameters(HttpServletRequest request, Set<String> allowed) {
    for (var entry : request.getParameterMap().entrySet()) {
      if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
        throw new CallerFault("invalid Relay binary parameters");
    }
  }

  private static String required(HttpServletRequest request, String name) {
    String value = request.getParameter(name);
    if (value == null) throw new CallerFault("missing Relay binary parameter");
    return value;
  }

  private static String actor(HttpServletRequest request) {
    if (!(request.getAttribute(AuthFilter.HANDLE_ATTRIBUTE) instanceof String value)
        || value.isBlank()) throw new CallerFault("Relay binary transfer requires authentication");
    return value;
  }

  private static Instant instant(String value) {
    try {
      return Instant.parse(value);
    } catch (java.time.DateTimeException invalid) {
      throw new CallerFault("invalid Relay binary timestamp");
    }
  }
}
