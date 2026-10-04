package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.buffers.Buffers;
import java.util.Map;
import java.util.Objects;

/**
 * {@code buffer.purge} — reclaim every expired row in both buffers, and say how many each lost. The
 * frame equivalent of {@code POST /v1/buffers/purge}.
 *
 * <h2>{@link Code#OK} and a report, and not {@link Code#NO_CONTENT}</h2>
 *
 * <p>The mistake this type most invites, and it is the opposite of {@link RetentionSweepHandler}'s:
 * a verb called "purge" sounds like one that answers nothing. It answers two numbers an operator
 * ran it for — how many fetched pages and how many stored result sets went — and {@code NO_CONTENT}
 * would drop them while still looking like a working frame.
 *
 * <h2>A sibling of {@code retention.sweep} and not a part of it</h2>
 *
 * <p>{@link Buffers}' own argument, unchanged by the transport: a sweep stages
 * mark-then-export-then-null because its bytes leave the database, and a fetched page or a stored
 * result set is derived data that can simply be fetched or searched again. Folding the two into one
 * frame type would put two retention policies behind one verb.
 *
 * <h2>A payload with nothing in it, deliberately</h2>
 *
 * <p>No body, no path value, no query parameter — {@code job.list}'s shape.
 */
public final class BufferPurgeHandler implements FrameHandler {

  private final Buffers buffers;

  /**
   * @param buffers the same bean the controller is injected with, which owns the clock and both
   *     stores' calls
   */
  public BufferPurgeHandler(Buffers buffers) {
    this.buffers = Objects.requireNonNull(buffers, "buffers");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(buffers.purge());
  }
}
