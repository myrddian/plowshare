package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.ImageAdmissions;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.images.ArtifactAdmissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactFramesTest {
  @Test
  void strict_status_request_is_authorized_as_the_authenticated_producer() {
    var service = mock(ArtifactAdmissions.class);
    var frames = new ArtifactFrames(service);
    UUID invocation = UUID.randomUUID();
    UUID id = ImageAdmissions.requestId(invocation, 0);
    var request = new ImageAdmissions.Request(invocation, 0, id);
    when(service.status("producer", request))
        .thenReturn(new ImageAdmissions.Status(ImageAdmissions.State.ABSENT, null));
    frames.status(
        Map.of("invocation", invocation.toString(), "ordinal", 0, "requestId", id.toString()),
        new Asking("session", "producer"));
    verify(service).status("producer", request);
    clearInvocations(service);
    for (Object ordinal : List.of(0.0, "0", -1, 16)) {
      assertThrows(
          CallerFault.class,
          () ->
              frames.status(
                  Map.of(
                      "invocation",
                      invocation.toString(),
                      "ordinal",
                      ordinal,
                      "requestId",
                      id.toString()),
                  new Asking("s", "producer")));
    }
    assertThrows(
        CallerFault.class,
        () ->
            frames.status(
                Map.of(
                    "invocation",
                    invocation.toString(),
                    "ordinal",
                    0,
                    "requestId",
                    id.toString(),
                    "project",
                    "foreign"),
                new Asking("s", "producer")));
    assertThrows(
        CallerFault.class,
        () ->
            frames.status(
                Map.of(
                    "invocation", invocation.toString(), "ordinal", 0, "requestId", id.toString()),
                new Asking("s")));
    verifyNoInteractions(service);
  }
}
