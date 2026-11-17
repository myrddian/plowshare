package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.ExternalConversationUnavailableException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Typed Ex routing scope; SQL implementation and caller notification remain separate owners. */
public interface ExternalConversationRequestWork {
  <T> T within(Supplier<T> action);

  ExternalConversationCompletions.Request begin(long admittedAtNanos, Consumer<Outcome> reply);

  static boolean external(String operation) {
    return FrameTypes.CONVERSATION_OPEN_EX.equals(operation)
        || FrameTypes.AGENT_RUN_EX.equals(operation);
  }

  /** Legacy isolated routers remain usable for ordinary operations; Ex requires explicit wiring. */
  ExternalConversationRequestWork UNAVAILABLE =
      new ExternalConversationRequestWork() {
        @Override
        public <T> T within(Supplier<T> action) {
          throw new ExternalConversationUnavailableException(
              "External conversation request scope is unavailable", null);
        }

        @Override
        public ExternalConversationCompletions.Request begin(
            long admittedAtNanos, Consumer<Outcome> reply) {
          throw new ExternalConversationUnavailableException(
              "External conversation completion is unavailable", null);
        }
      };
}
