package io.aeyer.plowshare.server.llm.dispatch;

/**
 * Where a streaming transport hands each piece of a response as it arrives.
 *
 * <h2>Two kinds, and they are not one text stream</h2>
 *
 * <p>A model that reasons emits two interleaved things: the thinking it does and the answer it
 * settles on. <b>Merging them would leave every caller guessing which it had</b>, and the volumes
 * make that guess expensive — measured on one node on 2026-09-02, 6 571 characters of reasoning
 * against 1 965 of answer, on a 51-token prompt that took 96 seconds. A terminal that interleaved
 * those would be unreadable and an archive that stored both would be storing mostly the wrong one.
 *
 * <h2>Thinking is dropped by default, and that is this interface's whole shape</h2>
 *
 * <p>{@link #answered} is abstract and {@link #thought} defaults to doing nothing. Three things
 * follow, and all three were the point:
 *
 * <ul>
 *   <li><b>This is still a functional interface</b>, so every lambda that was written against the
 *       {@code Consumer<String>} this replaces still compiles and still means exactly what it meant
 *       — the answer sink. The change is a type in a signature, not a rewrite at twenty call sites.
 *   <li><b>A caller opts in to reasoning by overriding a method</b>, which is harder to do by
 *       accident than passing a flag, and impossible to do without saying so in code.
 *   <li><b>The old behaviour is the default behaviour.</b> {@code OpenAiTransport}'s javadoc said
 *       thinking was dropped; it is still dropped for everyone who does not ask, and the
 *       runaway-cap reasoning that sat beside that sentence is untouched — reasoning volume is
 *       still charged against the cap whether or not anybody is listening to it.
 * </ul>
 *
 * <h2>What a sink must not do</h2>
 *
 * <p><b>Block.</b> These run on the lane thread that is draining the model's response and holding
 * an inference slot the pool is counting — see {@code LlmTransport#stream} for why that thread and
 * not an OkHttp one. A sink that waits slows the read from the model, which is the opposite of the
 * point of streaming. Hand off and return.
 *
 * <p><b>Assemble an answer and believe it.</b> Deltas are for watching. What an answer <em>is</em>
 * remains {@code Completion.text} from the final call, and a caller that stitched its own from
 * these could disagree with the outcome — most obviously when a delta was dropped on the way to it,
 * which further down the delivery chain is allowed and expected.
 */
@FunctionalInterface
public interface Deltas {

  /** Nothing wants either kind. The behaviour every non-streaming path had. */
  Deltas DISCARDING = delta -> {};

  /**
   * A piece of the answer, as it arrives.
   *
   * @param delta the characters this chunk added, never null and never empty
   */
  void answered(String delta);

  /**
   * A piece of the model's reasoning, as it arrives.
   *
   * <p><b>Does nothing unless overridden</b>, which is how "dropped by default" is spelled.
   * Override it only if there is somewhere for the text to go that has been thought about:
   * reasoning is not stored, not archived and not part of an answer.
   *
   * @param delta the reasoning this chunk added, never null and never empty
   */
  default void thought(String delta) {
    // Dropped. See the class javadoc.
  }
}
