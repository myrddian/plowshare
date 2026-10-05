package io.aeyer.plowshare.server.agents;

/** Structural bounds for durable instruction identities; ownership is enforced separately. */
final class InvocationValues {
  private InvocationValues() {}

  static void identity(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException("invalid invocation identity");
  }

  static void text(String value, boolean optional) {
    if (value == null && optional) return;
    if (value == null || value.length() > 8388608 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("invalid invocation text");
  }
}
