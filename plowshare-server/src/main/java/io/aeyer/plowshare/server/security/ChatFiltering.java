package io.aeyer.plowshare.server.security;

import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import java.util.function.BooleanSupplier;

/** Model boundary inspection. Refusals contain rule codes, never rejected text or credentials. */
public interface ChatFiltering {
  ChatRequest input(ChatRequest request, BooleanSupplier abandoned);

  Completion output(Completion completion);

  /**
   * True requires withholding both answer and reasoning deltas until output inspection succeeds.
   */
  boolean bufferOutput();

  ChatFiltering NONE =
      new ChatFiltering() {
        public ChatRequest input(ChatRequest request, BooleanSupplier abandoned) {
          return request;
        }

        public Completion output(Completion completion) {
          return completion;
        }

        public boolean bufferOutput() {
          return false;
        }
      };
}
