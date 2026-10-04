package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The three shapes a write can take — and there is deliberately no fourth.
 *
 * <p>A verdict is a decision about <em>shape</em>, never about truth: is this proposal a new claim,
 * more detail on a claim already held, or a replacement for one? Whether the claim is
 * <em>right</em> is a fact about the world the archive does not hold.
 *
 * <p>Excalibur measured the alternative. A fourth verdict meaning "no, this restates something we
 * retired" asked the model to decide whether a proposal contradicting the archive was correct; on
 * two word-for-word identical proposals — one a stale fact rediscovered from old code, one a
 * genuine revert — it scored 9/12 and 1/6. It did not learn to discriminate, it only moved which
 * error it makes. So the policy is newest-wins, nothing is ever refused, and wrongness arrives
 * later through invalidation, from the caller that has the context.
 */
public enum VerdictKind {
  NEW("new"),
  MERGED_INTO("merged_into"),
  SUPERSEDES("supersedes");

  private final String wireName;

  VerdictKind(String wireName) {
    this.wireName = wireName;
  }

  /**
   * The spelling that goes on the wire.
   *
   * <p>Written out rather than derived from {@link #name()}, for the same reason as {@link
   * MemoryState#wireName()}: the constant is a Java identifier any refactor may rename, while this
   * string is a contract with the MCP tool result an agent reads. {@code MERGED_INTO} in particular
   * does not survive a naive {@code name().toLowerCase()} round trip through any future rename
   * without silently changing the wire.
   *
   * <p>{@code @JsonValue}: this is also the literal JSON a {@link
   * io.aeyer.plowshare.protocol.Verdict} serialises to. Without it, plain Jackson binding falls
   * back to {@link #name()} and every write over HTTP would carry {@code "MERGED_INTO"} instead of
   * the documented {@code "merged_into"} — a client coded against this class's own javadoc would
   * fail to parse its response.
   */
  @JsonValue
  public String wireName() {
    return wireName;
  }

  /**
   * The inverse, for a verdict arriving over the wire.
   *
   * <p>{@code @JsonCreator}: without it, Jackson cannot deserialise the lowercase wire spelling
   * this method exists to produce — it would look for an enum constant literally named {@code
   * "new"} and fail every inbound write.
   */
  @JsonCreator
  public static VerdictKind fromWireName(String wireName) {
    for (VerdictKind kind : values()) {
      if (kind.wireName.equals(wireName)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("unknown verdict kind: " + wireName);
  }
}
