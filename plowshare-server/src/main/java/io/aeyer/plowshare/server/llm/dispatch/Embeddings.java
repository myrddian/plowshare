package io.aeyer.plowshare.server.llm.dispatch;

import java.util.List;

/**
 * The vectors for one batch, in the order the inputs were given.
 *
 * <p>The response is positional — nothing in it names the input it came from — so a short list
 * cannot be repaired, only detected. {@code DispatchingEmbeddingClient} is where that detection
 * lives.
 */
public record Embeddings(
    List<float[]> vectors,
    TokenUsage usage,
    @com.fasterxml.jackson.annotation.JsonIgnore InferenceCapture capture) {

  public Embeddings(List<float[]> vectors, TokenUsage usage) {
    this(vectors, usage, null);
  }

  public Embeddings captured(InferenceCapture value) {
    return new Embeddings(vectors, usage, value);
  }

  public Embeddings {
    // Structural only, and knowingly so. List.copyOf fixes the list's
    // contents, but the elements are float[] and a caller holding one can
    // still rewrite a vector in place afterwards.
    //
    // Cloning here would not close that: the accessor hands the same arrays
    // straight back out, so a complete defence means cloning on the way out
    // too — 768 floats per row on every read, on the recall path. Today the
    // vectors are freshly allocated by JSON parsing with exactly one
    // producer, so there is no second holder to defend against, and a
    // constructor-only clone would buy nothing while looking like it had.
    // Deep-copy both ends, or neither. If a caller ever mutates a returned
    // vector, this is the comment that was wrong.
    vectors = List.copyOf(vectors);
  }
}
