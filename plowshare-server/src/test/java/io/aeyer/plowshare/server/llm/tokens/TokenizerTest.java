package io.aeyer.plowshare.server.llm.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aeyer.plowshare.server.llm.tokens.TokenCount.Basis;
import org.junit.jupiter.api.Test;

/**
 * The facade, and the one ordering rule it exists to enforce.
 *
 * <p>The rule is "real values trump the heuristic when obtained". It is stated once, in {@link
 * TokenCount#better}, so that no call site has to remember it.
 */
class TokenizerTest {

  @Test
  void a_measurement_beats_an_estimate_whichever_way_round_they_arrive() {
    TokenCount guessed = TokenCount.estimated(100, "a ratio");
    TokenCount counted = TokenCount.measured(87, "the model's own tokenizer");

    assertThat(guessed.better(counted)).isEqualTo(counted);
    // The other way round is the case that actually breaks: an estimate
    // computed after a measurement must not overwrite it.
    assertThat(counted.better(guessed)).isEqualTo(counted);
  }

  @Test
  void a_bound_beats_an_estimate_and_loses_to_a_measurement() {
    TokenCount guessed = TokenCount.estimated(100, "a ratio");
    TokenCount ceiling = TokenCount.bound(400, "one token per UTF-8 byte, at worst");
    TokenCount counted = TokenCount.measured(87, "the model's own tokenizer");

    // Larger and still better: a bound knows the direction of its error
    // and an estimate does not.
    assertThat(guessed.better(ceiling)).isEqualTo(ceiling);
    assertThat(ceiling.better(counted)).isEqualTo(counted);
  }

  @Test
  void two_counts_on_the_same_basis_leave_the_incumbent_standing() {
    TokenCount first = TokenCount.estimated(100, "one ratio");
    TokenCount second = TokenCount.estimated(120, "another ratio");

    // Otherwise the answer depends on the order they were offered in.
    assertThat(first.better(second)).isEqualTo(first);
  }

  @Test
  void a_count_carries_how_it_knows_because_a_bare_number_cannot_be_weighed() {
    assertThatThrownBy(() -> new TokenCount(10, Basis.ESTIMATED, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("says how it knows");
  }

  @Test
  void the_fallback_estimates_and_says_that_is_what_it_did() {
    Tokenizer tokenizer = new RatioTokenizer(4.0);

    TokenCount count = tokenizer.count("a".repeat(400));

    assertThat(count.tokens()).isEqualTo(100);
    assertThat(count.basis()).isEqualTo(Basis.ESTIMATED);
    assertThat(count.how()).contains("4.00 characters per token");
  }

  @Test
  void an_empty_string_is_the_one_zero_here_that_is_a_measurement() {
    // Nothing to be wrong about, so this is not the "absence is never a
    // zero" case the rest of this surface guards.
    assertThat(new RatioTokenizer(4.0).count("").basis()).isEqualTo(Basis.MEASURED);
    assertThat(new RatioTokenizer(4.0).count(null).tokens()).isZero();
  }

  @Test
  void anchoring_replaces_the_prior_with_this_model_s_own_ratio() {
    // 1000 characters that the model itself counted as 250 tokens is 4.0;
    // counted as 500 is 2.0, which is what dense JSON actually looks like
    // and what a fixed 4.0 would have understated by half.
    RatioTokenizer anchored = RatioTokenizer.anchoredOn(1000, 500);

    assertThat(anchored.charactersPerToken()).isEqualTo(2.0);
    assertThat(anchored.count("x".repeat(1000)).tokens()).isEqualTo(500);
    assertThat(anchored.describe()).contains("measured from this model's own count");
  }

  @Test
  void an_anchor_refuses_a_zero_rather_than_dividing_by_it() {
    assertThatThrownBy(() -> RatioTokenizer.anchoredOn(1000, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("an anchor needs both");
  }
}
