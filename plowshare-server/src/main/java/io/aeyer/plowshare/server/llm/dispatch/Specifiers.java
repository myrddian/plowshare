package io.aeyer.plowshare.server.llm.dispatch;

/**
 * The one rule about specifiers, in one place.
 *
 * <p>Both request types enforce it identically, and a second copy is a second thing to forget: the
 * version of this check that omitted {@code isBlank} would let a whitespace specifier through to
 * the dispatcher, where it matches no pool and fails several layers from the call site that
 * produced it.
 */
final class Specifiers {

  private Specifiers() {}

  static void require(String specifier) {
    if (specifier == null || specifier.isBlank()) {
      throw new IllegalArgumentException(
          "a request must name a model or a class of model; "
              + "the dispatcher has no default and will not pick one");
    }
  }
}
