package io.aeyer.plowshare.server.security;

/** Supplemental text inspection; preserves text or returns its complete masked replacement. */
public interface TextFiltering {
  /** Refuses before returning if any applicable policy blocks, including unmaskable structure. */
  String inspect(String text, boolean injection, boolean mayMask);

  /** Reinspect exact reviewed evidence, excluding only the indicated injection category. */
  default String inspectReleased(String text, String rule) {
    return inspect(text, true, true);
  }

  /** Output policies require withholding live answer and reasoning deltas. */
  boolean filtersOutput();
}
