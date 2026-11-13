package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns exact turn-source projection; immutable source rows are written by TurnStore.reserve. */
public final class JdbcTurnRelaySources implements TurnRelaySources {
  private final JdbcTemplate jdbc;

  public JdbcTurnRelaySources(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public Optional<RelayCausation> ancestry(long project, UsageAttribution owner) {
    Objects.requireNonNull(owner);
    if (project < 1
        || owner.status() != UsageAttribution.Status.ATTRIBUTED
        || owner.scope() != UsageAttribution.Scope.PROJECT
        || !Long.toString(project).equals(owner.projectId())
        || owner.accountHandle() == null
        || owner.agentName() == null
        || owner.conversations().id() == null
        || owner.runs().id() == null
        || owner.turnOrdinal() == null
        || owner.turnOrdinal() < 1) return Optional.empty();
    return jdbc
        .query(
            """
        SELECT COALESCE(s.relay_causation,
          CASE WHEN c.origin<>'message' THEN c.relay_causation END)::text
        FROM conversations c JOIN inference_run_ownership o ON o.conversation_id=c.id
        LEFT JOIN turn_relay_sources s ON s.conversation_id=o.conversation_id
          AND s.turn_ordinal=o.turn_ordinal
        WHERE c.project_id=? AND c.id=? AND o.turn_ordinal=?
          AND o.attribution->>'accountHandle'=? AND o.attribution->>'projectId'=?
          AND o.attribution->'runs'->>'id'=? AND o.attribution->>'agentName'=?
          AND o.attribution->>'status'='ATTRIBUTED'
        """,
            (row, index) -> RelayCausationCodec.read(row.getString(1)),
            project,
            owner.conversations().id(),
            owner.turnOrdinal(),
            owner.accountHandle(),
            Long.toString(project),
            owner.runs().id(),
            owner.agentName())
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }
}
