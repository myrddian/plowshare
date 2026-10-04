package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The whole lifecycle of a memory in four states — and two of them are not failures.
 *
 * <p>{@code COLD} is still true; it has only fallen out of the working set for disuse, and filing
 * it with the tombstones would make true knowledge unreachable. {@code SUPERSEDED} is a tombstone,
 * and the reason it carries is the whole point of keeping it: it is what stops a future agent
 * rediscovering a stale fact and writing it straight back in.
 *
 * <p>Nothing is ever deleted from the archive in any of the four states. A state says where a
 * memory may be returned from, never whether it survives.
 */
public enum MemoryState {
  ACTIVE("active"),
  SUPERSEDED("superseded"),
  INVALIDATED("invalidated"),
  COLD("cold");

  private final String wireName;

  MemoryState(String wireName) {
    this.wireName = wireName;
  }

  /**
   * The spelling that goes on the wire and into a stored row.
   *
   * <p>Written out here rather than derived from {@link #name()}: the enum constant is a Java
   * identifier that any future refactor is free to rename, while this string is a contract with
   * rows already written and with Excalibur's archive files, whose {@code state:} frontmatter key
   * holds exactly these four words. {@code name().toLowerCase()} would let a rename silently orphan
   * every row that used the old spelling, with no compile error anywhere to catch it.
   *
   * <p>{@code @JsonValue}: this is also the literal JSON a {@link
   * io.aeyer.plowshare.protocol.Memory} serialises to. Without it, plain Jackson binding falls back
   * to {@link #name()} and a memory returned over HTTP would carry {@code "SUPERSEDED"} instead of
   * the documented {@code "superseded"}, breaking the same contract this method exists to keep.
   */
  @JsonValue
  public String wireName() {
    return wireName;
  }

  /**
   * The inverse, for rehydrating a stored row.
   *
   * <p>Here so that no caller has to reach for {@code valueOf(s.toUpperCase(Locale.ROOT))}, which
   * would reintroduce on the read side exactly the constant-name coupling {@link #wireName()}
   * exists to break on the write side.
   *
   * <p>{@code @JsonCreator}: also the deserialisation side of the same contract, for any request
   * body that names a state directly.
   */
  @JsonCreator
  public static MemoryState fromWireName(String wireName) {
    for (MemoryState state : values()) {
      if (state.wireName.equals(wireName)) {
        return state;
      }
    }
    throw new IllegalArgumentException("unknown memory state: " + wireName);
  }
}
