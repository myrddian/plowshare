package io.aeyer.plowshare.server.llm;

import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.util.EnumSet;
import java.util.Set;

/** Declared by the operator for a model family, never inferred from a deployment alias. */
public class RequestCapabilities {
  public enum OutputLimit {
    MAX_TOKENS,
    MAX_COMPLETION_TOKENS
  }

  private OutputLimit outputLimit = OutputLimit.MAX_COMPLETION_TOKENS;
  private int defaultOutputTokens = 4096;
  private boolean temperature = true;
  private boolean topP = true;
  private boolean samplingOnlyWithoutReasoning;
  private boolean structuredOutput;
  private boolean tools;
  private boolean toolsOnlyWithoutReasoning;
  private Set<Sampling.Effort> reasoningEfforts = EnumSet.noneOf(Sampling.Effort.class);

  public OutputLimit getOutputLimit() {
    return outputLimit;
  }

  public void setOutputLimit(OutputLimit value) {
    outputLimit = value;
  }

  public int getDefaultOutputTokens() {
    return defaultOutputTokens;
  }

  public void setDefaultOutputTokens(int value) {
    defaultOutputTokens = value;
  }

  public boolean isTemperature() {
    return temperature;
  }

  public void setTemperature(boolean value) {
    temperature = value;
  }

  public boolean isTopP() {
    return topP;
  }

  public void setTopP(boolean value) {
    topP = value;
  }

  public boolean isSamplingOnlyWithoutReasoning() {
    return samplingOnlyWithoutReasoning;
  }

  public void setSamplingOnlyWithoutReasoning(boolean value) {
    samplingOnlyWithoutReasoning = value;
  }

  public boolean isStructuredOutput() {
    return structuredOutput;
  }

  public void setStructuredOutput(boolean value) {
    structuredOutput = value;
  }

  public boolean isTools() {
    return tools;
  }

  public void setTools(boolean value) {
    tools = value;
  }

  public boolean isToolsOnlyWithoutReasoning() {
    return toolsOnlyWithoutReasoning;
  }

  public void setToolsOnlyWithoutReasoning(boolean value) {
    toolsOnlyWithoutReasoning = value;
  }

  public Set<Sampling.Effort> getReasoningEfforts() {
    return Set.copyOf(reasoningEfforts);
  }

  public void setReasoningEfforts(Set<Sampling.Effort> value) {
    reasoningEfforts = value == null ? Set.of() : Set.copyOf(value);
  }

  public void validate() {
    if (outputLimit == null || defaultOutputTokens < 1 || defaultOutputTokens > 1000000)
      throw new IllegalArgumentException(
          "cloud request capability needs a positive bounded default output limit");
    if ((samplingOnlyWithoutReasoning || toolsOnlyWithoutReasoning)
        && !reasoningEfforts.contains(Sampling.Effort.NONE))
      throw new IllegalArgumentException(
          "conditional cloud capabilities must declare reasoning effort none");
  }

  public Set<Sampling.Parameter> carries(Sampling sampling) {
    var result = EnumSet.of(Sampling.Parameter.MAX_TOKENS);
    boolean noReasoning =
        sampling.reasoningEffort().filter(e -> e == Sampling.Effort.NONE).isPresent();
    if (temperature && (!samplingOnlyWithoutReasoning || noReasoning))
      result.add(Sampling.Parameter.TEMPERATURE);
    if (topP && (!samplingOnlyWithoutReasoning || noReasoning))
      result.add(Sampling.Parameter.TOP_P);
    if (structuredOutput) result.add(Sampling.Parameter.RESPONSE_FORMAT);
    if (!reasoningEfforts.isEmpty()) result.add(Sampling.Parameter.REASONING_EFFORT);
    return result;
  }

  public void require(Sampling sampling, boolean offeringTools) {
    if (sampling.reasoningEffort().isPresent()
        && !reasoningEfforts.contains(sampling.reasoningEffort().get()))
      throw new IllegalArgumentException(
          "this cloud model family does not support the requested reasoning effort");
    if (offeringTools
        && (!tools
            || toolsOnlyWithoutReasoning
                && sampling.reasoningEffort().orElse(null) != Sampling.Effort.NONE))
      throw new IllegalArgumentException(
          "this cloud model family cannot use Chat Completions tools with the requested reasoning effort");
  }
}
