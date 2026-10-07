package io.aeyer.plowshare.server.security;

/** Supplemental text inspection; preserves text or returns its complete masked replacement. */
public interface TextFiltering {
  /** Refuses before returning if any applicable policy blocks, including unmaskable structure. */
  String inspect(String text, boolean injection, boolean mayMask);

  /** Output policies require withholding live answer and reasoning deltas. */
  boolean filtersOutput();
}
