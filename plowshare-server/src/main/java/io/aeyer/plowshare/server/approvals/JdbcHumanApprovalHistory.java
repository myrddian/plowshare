package io.aeyer.plowshare.server.approvals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stores target hashes, not command input or file contents. One indexed lookup, no history scan.
 */
public final class JdbcHumanApprovalHistory implements HumanApprovalHistory {
  private record Target(
      String project,
      String provider,
      ApprovalPolicy.Kind kind,
      List<String> argv,
      List<String> paths,
      String cwd,
      String inputDigest) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate jdbc;
  private final Supplier<Instant> clock;
  private final Duration lifetime;

  public JdbcHumanApprovalHistory(JdbcTemplate jdbc, Supplier<Instant> clock, Duration lifetime) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = Objects.requireNonNull(clock);
    this.lifetime = Objects.requireNonNull(lifetime);
    if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(Duration.ofDays(90)) > 0)
      throw new IllegalArgumentException("Human approval history must expire within 90 days");
  }

  @Override
  public boolean eligible(ApprovalIntent intent) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM human_approval_history h JOIN run_approvals a ON a.id = h.approval WHERE h.owner_handle = ? AND h.target_digest = ? AND h.approved_at >= ? AND a.state IN ('allowed', 'used') AND a.answered_by = h.owner_handle)",
            Boolean.class,
            intent.owner(),
            target(intent),
            Timestamp.from(clock.get().minus(lifetime))));
  }

  @Override
  public void remember(ApprovalIntent intent, String approval) {
    ApprovalPolicy.text(approval, "Source human approval", 512);
    // The authenticated human must be this policy's owner, and the source question must belong
    // to the same retained call's conversation. A command judge/bot cannot create such a row.
    jdbc.update(
        "INSERT INTO human_approval_history (approval, owner_handle, target_digest, approved_at) SELECT a.id, ?, ?, a.answered_at FROM run_approvals a JOIN suspended_run_calls c ON c.approval = a.id JOIN admins person ON person.handle = a.answered_by WHERE a.id = ? AND a.asked_in = ? AND c.call_id = ? AND c.owner_handle = ? AND a.answered_by = ? AND a.state IN ('allowed', 'used') AND person.enabled AND person.account_kind = 'USER' ON CONFLICT (approval) DO NOTHING",
        intent.owner(),
        target(intent),
        approval,
        intent.conversation(),
        intent.call(),
        intent.owner(),
        intent.owner());
  }

  private static String target(ApprovalIntent intent) {
    try {
      byte[] canonical =
          JSON.writeValueAsString(
                  new Target(
                      intent.project(),
                      intent.provider(),
                      intent.kind(),
                      intent.argv(),
                      intent.paths(),
                      intent.cwd(),
                      intent.inputDigest()))
              .getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (JsonProcessingException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Cannot encode approval target", impossible);
    }
  }
}
