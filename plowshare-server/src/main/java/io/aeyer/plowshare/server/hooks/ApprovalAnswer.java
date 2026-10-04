package io.aeyer.plowshare.server.hooks;

import java.util.Objects;

/**
 * {@code approval.post}: a person answered an approval, or revoked a standing one (spec
 * 2026-09-28-hooks-reach-the-log §3).
 *
 * @param approval the approval's id
 * @param decision {@link #ALLOW}, {@link #DENY} or {@link #REVOKE}
 * @param scope what was allowed ({@code once}, {@code conversation}, {@code project}), what was
 *     revoked, or {@code null} for a denial
 */
public record ApprovalAnswer(String approval, String decision, String scope) {

  public static final String ALLOW = "allow";
  public static final String DENY = "deny";
  public static final String REVOKE = "revoke";

  public ApprovalAnswer {
    Objects.requireNonNull(approval, "approval");
    Objects.requireNonNull(decision, "decision");
  }
}
