package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.RelayToolCodec;
import io.aeyer.plowshare.server.relay.tools.RelayToolInvocations;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolResultFramesTest {
  @Test
  void reads_as_the_authenticated_owner_and_rejects_extra_authority() {
    var service = mock(RelayToolInvocations.class);
    var frames = new ToolResultFrames(service);
    var id = UUID.randomUUID();
    when(service.read("atlas", "owner", id))
        .thenReturn(new RelayToolInvocations.Outcome(id, RelayToolCodec.State.UNKNOWN, "Pending"));
    frames.read(Map.of("project", "atlas", "invocation", id.toString()), new Asking("s", "owner"));
    verify(service).read("atlas", "owner", id);
    clearInvocations(service);
    assertThrows(
        CallerFault.class,
        () ->
            frames.read(
                Map.of("project", "atlas", "invocation", id.toString(), "owner", "foreign"),
                new Asking("s", "owner")));
    assertThrows(
        CallerFault.class,
        () ->
            frames.read(Map.of("project", "atlas", "invocation", "bad"), new Asking("s", "owner")));
    assertThrows(
        CallerFault.class,
        () ->
            frames.read(Map.of("project", "atlas", "invocation", id.toString()), new Asking("s")));
    verifyNoInteractions(service);
  }
}
