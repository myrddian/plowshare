package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.buffers.Buffers;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code BufferPurgeController}'s area of the frame surface — its one endpoint.
 *
 * <h2>Separate from {@link RetentionFrames}, as the controllers are</h2>
 *
 * <p>{@code BufferPurgeController} is {@code RetentionController}'s sibling and not part of it —
 * one reclaims derived data that can be fetched again, the other stages the removal of a person's
 * history — so they are two controllers, two services, and two areas. {@link RetentionFrames}
 * carries the argument for why one endpoint still earns a file.
 *
 * <p><b>Nothing here is runtime state.</b> {@link Buffers} holds two stores, a clock and two
 * live-config reads; no session table.
 */
@Component
public class BufferFrames implements FrameArea {

  private final Buffers buffers;

  /**
   * @param buffers the same bean the controller is injected with
   */
  public BufferFrames(Buffers buffers) {
    this.buffers = Objects.requireNonNull(buffers, "buffers");
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.BUFFER_PURGE, new BufferPurgeHandler(buffers));
  }
}
