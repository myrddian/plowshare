package io.aeyer.plowshare.server.llm.counting;

import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.EmbeddingRequest;

/** Explicit full-request refresh outside inference lanes and consumed accounting. */
public interface PromptCounter {
    default PromptCount countChat(String model, ChatRequest request) {
        return PromptCount.unknown(null, model, "unsupported_counter");
    }
    default PromptCount countEmbedding(String model, EmbeddingRequest request) {
        return PromptCount.unknown(null, model, "unsupported_embedding_counter");
    }
    default boolean automaticCounting() { return false; }
}
