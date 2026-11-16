package io.aeyer.plowshare.server.llm.vllm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.OpenAiCompatible;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.openai.OpenAiTransport;

/**
 * vLLM uses the shared OpenAI HTTP lifecycle, accounting and cancellation. Its explicit dialect
 * additionally carries thinking_token_budget; declaring OpenAI compatibility alone does not promise
 * support for that extension. No separate HTTP client or provider-level replay is added.
 */
public final class Vllm extends OpenAiCompatible {
  public Vllm(PoolProperties properties, ObjectMapper mapper) {
    super(properties, mapper, OpenAiTransport.Dialect.VLLM);
  }
}
