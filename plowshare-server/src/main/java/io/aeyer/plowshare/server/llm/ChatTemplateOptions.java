package io.aeyer.plowshare.server.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Supported local chat-template controls. Provider-specific additions need an explicit contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatTemplateOptions(@JsonProperty("enable_thinking") Boolean enableThinking) {
  public static final ChatTemplateOptions NONE = new ChatTemplateOptions(null);

  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isEmpty() {
    return enableThinking == null;
  }
}
