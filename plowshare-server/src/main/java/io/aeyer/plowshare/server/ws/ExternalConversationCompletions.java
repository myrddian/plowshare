package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded Ex reply ownership, separate from the original thread's physical operation. */
public interface ExternalConversationCompletions {
  default Request begin(Consumer<Outcome> notify) {
    return begin(System.nanoTime(), notify);
  }

  Request begin(long admittedAtNanos, Consumer<Outcome> notify);

  interface Request extends AutoCloseable {
    /** Preserve the original caller thread without creating a workflow deadline. */
    <T> T within(Supplier<T> action);

    /** Deliver at most one outcome, including success after slow accepted work settles. */
    void answer(Outcome outcome);

    /** Release physical ownership only after both the route and any notification have settled. */
    @Override
    void close();
  }
}
