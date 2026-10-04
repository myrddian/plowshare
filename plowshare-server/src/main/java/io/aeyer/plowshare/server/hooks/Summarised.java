package io.aeyer.plowshare.server.hooks;

import java.util.Objects;

/**
 * {@code fold.post}: the folder has summarised a span, and the fold is not yet saved (spec
 * 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29). A hook reads {@link #summary} to keep a
 * marker only when the folder lost it, so a marker does not pile up over repeated folds.
 *
 * @param through the last turn the fold reaches
 * @param entries how many projecting entries the span holds
 * @param estimatedTokens what the ending turn sent and added, as the endpoint measured it: the
 *     number the fold was decided on
 * @param summary the folder's own text, before anything a hook keeps
 */
public record Summarised(int through, int entries, int estimatedTokens, String summary) {

  public Summarised {
    Objects.requireNonNull(summary, "summary");
  }
}
