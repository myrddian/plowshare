package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.files.SessionChannel;
import java.util.Map;
import java.util.Objects;

/** A pure context read cannot publish a snapshot assembled across a file reconnect. */
final class ContextInputHandler implements FrameHandler {
  private final FrameHandler delegate;
  private final SessionChannel files;

  ContextInputHandler(FrameHandler delegate, SessionChannel files) {
    this.delegate = Objects.requireNonNull(delegate);
    this.files = Objects.requireNonNull(files);
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    // Authorization and payload validation remain with the original handler/router. The fence
    // covers definition resolution, rules, tools and pricing together, not just one file page.
    return files.readSnapshot(asking.sessionId(), () -> delegate.handle(payload, asking));
  }
}
