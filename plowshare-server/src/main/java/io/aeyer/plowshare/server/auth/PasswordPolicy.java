package io.aeyer.plowshare.server.auth;

import java.util.Locale;
import java.util.Set;

/**
 * The one placeholder check this slice makes, held in one place so the two callers that need it
 * cannot drift apart.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>{@link AdminSeed} refuses to seed an account from {@code PLOWSHARE_ADMIN_PASSWORD} when it is
 * an obvious placeholder, on the reasoning that a seeded password nobody changes is worse than the
 * operator token it replaces, because it looks solved. {@code POST /v1/auth/password} — the
 * endpoint that is the actual way to change it — needs to refuse the exact same thing for the exact
 * same reason: a caller who "changes" a placeholder password into another placeholder has not
 * closed the gap {@link AdminSeed} opened. Two copies of one list is how the second one goes stale
 * the first time somebody adds an entry to only one of them, so the list and the comparison it
 * drives live here instead and both callers ask this class.
 *
 * <p>Package-private: nothing outside {@code auth} has a password to check.
 */
final class PasswordPolicy {

  /**
   * Values an operator or a caller plausibly types — pasted from a README, an example {@code .env}
   * file, or this very server's own documentation — compared after trimming and lowercasing. Not a
   * strength policy — nothing here scores entropy or checks length — because that is a different
   * feature with a different failure mode: this list exists to catch the one mistake both callers
   * care about, a password that was never really chosen, and a short, confident list of known
   * placeholders does that without also rejecting a real password for a reason its owner cannot
   * see.
   */
  private static final Set<String> OBVIOUS_PLACEHOLDERS =
      Set.of(
          "changeme",
          "change_me",
          "change-me",
          "please-change-me",
          "password",
          "admin",
          "admin123",
          "changeit",
          "letmein",
          "default",
          "root");

  private PasswordPolicy() {}

  /**
   * Whether {@code password}, trimmed and lowercased, is one of the values {@link
   * #OBVIOUS_PLACEHOLDERS} names.
   *
   * @param password the candidate, or {@code null} — answered {@code false} rather than thrown,
   *     since both callers already refuse a blank or absent password on their own terms before ever
   *     asking this
   */
  static boolean isObviousPlaceholder(String password) {
    return password != null
        && OBVIOUS_PLACEHOLDERS.contains(password.trim().toLowerCase(Locale.ROOT));
  }
}
