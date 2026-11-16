package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import java.util.function.BooleanSupplier;

/** Streaming provider boundary; cancellation is requested until physical provider work settles. */
@FunctionalInterface
public interface ReviewModelTransport {
  Completion stream(ChatRequest request, BooleanSupplier cancelled);
}
