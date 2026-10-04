package io.aeyer.plowshare.protocol.search;

import java.util.List;

/**
 * One page of a search's results as they reach a caller.
 *
 * <p><b>No provider is ever named as the source of a result.</b> Which provider served a search is
 * invisible to the model by design — the tool exists to hide which one answered — and there is no
 * field on this record for one to travel in.
 *
 * <p>The wider claim, which this comment used to make, is false: {@code refusal} <em>does</em> name
 * providers, routinely. An exhausted ladder's refusal lists every rung and what became of it,
 * because spec §5, §8 and §12 all require it — an operator whose configured ladder names a provider
 * nobody registered finds out through a refusal a model read back, and there is no other channel
 * for it. The rule that actually holds is the narrower one above: a hit never carries provenance,
 * so a model cannot learn to ask for a rung by name, which is the behaviour the ladder exists to
 * prevent.
 *
 * <p>That has a consequence worth stating here rather than leaving to be rediscovered: a refusal
 * embeds a failing provider's own {@code message} verbatim, so <b>a registered provider can put
 * text of its choosing into a model's context by failing deliberately</b>. Both renderers flatten
 * it, so it cannot forge a line of their own prose, but it is prose in the context all the same.
 * Spec §13 records it as a cost.
 *
 * <p>{@code refusal} is non-null exactly when something went wrong, and it is text a person or a
 * model reads. This system returns refusals as content rather than throwing, so a caller always
 * gets something it can act on.
 */
public record SearchPage(
    List<Hit> hits, int page, int pageSize, int total, boolean hasMore, String refusal) {

  public SearchPage {
    hits = hits == null ? List.of() : List.copyOf(hits);
  }
}
