package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.time.Duration;
import java.util.List;

/**
 * One embedding batch. See {@link ChatRequest} for what {@code specifier} and {@code submitTimeout}
 * mean; they mean the same thing here.
 */
public record EmbeddingRequest(
    String specifier, List<String> input, Duration submitTimeout, UsageAttribution attribution) {

  public EmbeddingRequest(String specifier, List<String> input, Duration submitTimeout) {
    this(specifier, input, submitTimeout, UsageAttribution.LEGACY);
  }

  public EmbeddingRequest {
    java.util.Objects.requireNonNull(attribution, "attribution");
    Specifiers.require(specifier);
    // Null and empty answered with one type and one message, ahead of the
    // copy: List.copyOf reports a null batch as NullPointerException, which
    // is the same caller mistake wearing a type no boundary site can
    // translate into a 400.
    if (input == null || input.isEmpty()) {
      throw new IllegalArgumentException("an embedding request needs at least one input");
    }
    // The same argument one level down, and it has to be a loop: the
    // obvious spelling `input.contains(null)` throws NullPointerException
    // itself on the immutable lists List.of produces, so the guard against
    // an NPE would be the source of one.
    for (int i = 0; i < input.size(); i++) {
      if (input.get(i) == null) {
        throw new IllegalArgumentException(
            "an embedding request cannot embed a null input; index " + i + " is null");
      }
    }
    // Copied, not referenced: the vectors come back positional, so a list
    // mutated between construction and the call would attach every vector
    // to the wrong text with nothing recording that it happened. Complete,
    // unlike Embeddings' copy, because String is immutable — there is
    // nothing left for the caller to reach.
    input = List.copyOf(input);
  }

  public static EmbeddingRequest of(String specifier, List<String> input) {
    return new EmbeddingRequest(specifier, input, null);
  }

  public EmbeddingRequest withBudget(Duration budget) {
    return new EmbeddingRequest(specifier, input, budget, attribution);
  }

  public EmbeddingRequest withAttribution(UsageAttribution owner) {
    return new EmbeddingRequest(specifier, input, submitTimeout, owner);
  }
}
