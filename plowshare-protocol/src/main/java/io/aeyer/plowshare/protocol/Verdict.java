package io.aeyer.plowshare.protocol;

import java.util.Objects;

/**
 * The decision about how a proposal should be filed, made before the archive touches anything.
 *
 * <p>Ported from Excalibur's {@code Verdict}, where a small local model — the scribe — produces it.
 * The archive performs the filing and never rewrites prose: a verdict says <em>where this
 * goes</em>, and the words that get stored are the caller's own.
 *
 * <p>{@code reason} is required even for {@link VerdictKind#NEW}, where there is no target to
 * explain. It is the record of why the archive was reshaped, and a supersession whose reason was
 * optional would routinely arrive without one — leaving a retired memory and no account of what
 * retired it.
 *
 * @param kind new, a merge, or a supersession
 * @param targetId the memory being merged into or superseded; {@code null} for {@link
 *     VerdictKind#NEW}, which names nothing
 * @param reason why this shape was chosen, in prose
 */
public record Verdict(VerdictKind kind, String targetId, String reason) {

  public Verdict {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(reason, "reason");
  }
}
