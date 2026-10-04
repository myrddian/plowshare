package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.MemoryProposal;

/**
 * Structural validation for write proposals — the first place the archive says no to a caller.
 *
 * <p>Structure only. There is deliberately no semantic quality gate: judging whether a memory is
 * <em>worth</em> keeping is precisely what a small local model is worst at, and a caller that
 * writes shallow memories gets a shallow archive. {@code memory_invalidate} is the remedy for that,
 * not a check here.
 *
 * <p>Ported from Excalibur's {@code archive/validation.py}.
 */
public final class Validation {

  private Validation() {}

  /**
   * Checks a proposal's shape: every field required by Excalibur present and non-blank, the summary
   * a single line, the body within the limit.
   *
   * <p>{@code formedWhere} is not checked — Excalibur defaults it to the empty string rather than
   * requiring it, so an omitted "where" is a proposal with less context, not a malformed one.
   *
   * @param proposal the proposal to check
   * @param maxBodyChars the body length limit; passed in rather than owned here because it is
   *     server configuration, not an archive rule
   * @throws ValidationException naming the offending field, and for the length rule, both the
   *     proposal's actual length and the limit — a message that says "too long" without saying how
   *     long costs the caller a round trip to find out
   */
  public static void check(MemoryProposal proposal, int maxBodyChars) {
    requireNonBlank("summary", proposal.summary());
    requireNonBlank("scope", proposal.scope());
    requireNonBlank("body", proposal.body());
    requireNonBlank("formedBy", proposal.formedBy());

    // A summary is the one line the index shows and the thing recall
    // matches against. A multi-line summary breaks the index's shape
    // silently, so this is checked on the stripped value — a trailing
    // newline is just whitespace, but one buried in the middle is a
    // second line the index was never built to show.
    if (proposal.summary().strip().contains("\n")) {
      throw new ValidationException("field 'summary' must be a single line");
    }

    if (proposal.body().length() > maxBodyChars) {
      throw new ValidationException(
          "field 'body' is "
              + proposal.body().length()
              + " chars, exceeding the limit of "
              + maxBodyChars);
    }
  }

  private static void requireNonBlank(String name, String value) {
    if (value.isBlank()) {
      throw new ValidationException("field '" + name + "' is required and must not be empty");
    }
  }
}
