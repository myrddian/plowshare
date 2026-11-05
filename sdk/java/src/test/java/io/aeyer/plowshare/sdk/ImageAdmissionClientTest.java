package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.ImageAdmissions;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class ImageAdmissionClientTest {
  @Test
  void opt_in_upload_has_stable_identity_and_no_project_override_or_automatic_replay()
      throws Exception {
    var invocation = UUID.randomUUID();
    var request =
        new ImageAdmissions.Request(invocation, 0, ImageAdmissions.requestId(invocation, 0));
    var receipt =
        new ImageAdmissions.Receipt(
            request,
            "atlas",
            "owner",
            "producer",
            "img_" + "a".repeat(32),
            "image/png",
            2,
            2,
            1,
            "a".repeat(64),
            "2026-10-22T00:00:00Z");
    try (var server = new MockWebServer();
        var client = new WsServerClient(server.url("/").toString(), "fixture")) {
      server.enqueue(new MockResponse().setBody(SdkJson.mapper().writeValueAsString(receipt)));
      assertEquals(receipt, client.admitImage(request, "capture.png", new byte[] {1}));
      var sent = server.takeRequest();
      var body = sent.getBody().readUtf8();
      assertEquals("/v1/images", sent.getPath());
      assertTrue(body.contains(ImageAdmissions.PROFILE));
      assertTrue(body.contains(request.requestId().toString()));
      assertFalse(body.contains("name=\"project\""));
      server.enqueue(new MockResponse().setResponseCode(503));
      assertThrows(
          ServerClient.ServerError.class,
          () -> client.admitImage(request, "capture.png", new byte[] {1}));
      assertEquals(2, server.getRequestCount());
    }
  }
}
