package io.aeyer.plowshare.protocol;

/**
 * Immutable runtime causation shared by Relay effects and owning work. Depth counts effects, not
 * lifecycle notices. A depth of -1 explicitly marks legacy or unavailable ancestry; it never
 * authorizes a fresh budget. Identifiers describe provenance, not access authority.
 */
public record RelayCausation(String rootId, String parentId, int depth) {
  public RelayCausation {
    identity(rootId);
    if (parentId != null) identity(parentId);
    if (depth < -1 || depth > 32 || (depth <= 0) != (parentId == null))
      throw new IllegalArgumentException("Invalid Relay causation depth or parent");
  }

  public static RelayCausation root(String id) {
    return new RelayCausation(id, null, 0);
  }

  public static RelayCausation unknown(String id) {
    return new RelayCausation(id, null, -1);
  }

  /** Consumes one effect at the trusted receiver boundary, preserving the original root. */
  public RelayCausation next(String parent, int maximum) {
    if (maximum < 1 || maximum > 32)
      throw new IllegalArgumentException("Invalid Relay effect limit");
    if (depth < 0) throw new IllegalStateException("Relay causation is unavailable");
    if (depth >= maximum) throw new IllegalStateException("Relay causation limit reached");
    return new RelayCausation(rootId, parent, depth + 1);
  }

  private static void identity(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException("Invalid Relay causation identity");
  }
}
