package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.Outcome;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns stage row mappings and serializes paid outcomes only at the persistence boundary. */
@Repository
public class JdbcInformationStageRepository implements InformationStageRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate jdbc;

  public JdbcInformationStageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public LogState logState(UUID revision, Long project) {
    Objects.requireNonNull(revision);
    String name =
        project == null
            ? null
            : jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, project);
    return jdbc.queryForObject(
        "SELECT processing_log,allowance_total,allowance_spent,caller_session,"
            + " NOT EXISTS (SELECT 1 FROM inference_run_ownership o WHERE o.conversation_id=r.processing_log"
            + " AND o.attribution->>'status' IS DISTINCT FROM 'SYSTEM') FROM information_revisions r WHERE id=?",
        (row, n) ->
            new LogState(
                name,
                row.getString(1),
                row.getInt(2),
                row.getInt(3),
                row.getString(4),
                row.getBoolean(5)),
        revision);
  }

  @Override
  public boolean replaceLog(UUID revision, String previous, String replacement) {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(previous);
    Objects.requireNonNull(replacement);
    if (previous.equals(replacement))
      throw new IllegalArgumentException("replacement log must be new");
    return jdbc.update(
            "UPDATE information_revisions SET processing_log=? WHERE id=? AND processing_log=?",
            replacement,
            revision,
            previous)
        == 1;
  }

  @Override
  public boolean pinLog(UUID revision, String log) {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(log);
    return jdbc.update(
            "UPDATE information_revisions SET processing_log=? WHERE id=? AND processing_log IS NULL",
            log,
            revision)
        == 1;
  }

  @Override
  public UUID resource(UUID revision) {
    return jdbc.queryForObject(
        "SELECT resource_id FROM information_revisions WHERE id=?",
        UUID.class,
        Objects.requireNonNull(revision));
  }

  @Override
  public Optional<Outcome> paid(UUID revision, long generation, String key, String owner) {
    return jdbc
        .query(
            "SELECT response FROM information_model_steps WHERE revision_id=? AND generation=? AND stage_key=? AND owner_handle=?",
            (row, n) -> decode(row.getString(1)),
            revision,
            generation,
            key,
            owner)
        .stream()
        .findFirst();
  }

  private static Outcome decode(String value) {
    try {
      Outcome response = JSON.readValue(value, Outcome.class);
      if (response.ending() != Outcome.Ending.ANSWERED
          || response.text() == null
          || response.text().isBlank()
          || response.steps() < 0
          || response.modelCalls() < 0)
        throw new IllegalStateException("invalid persisted paid outcome");
      return response;
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("unreadable persisted paid outcome", invalid);
    }
  }

  @Override
  public void remember(
      UUID revision,
      long generation,
      String key,
      String stage,
      String owner,
      Outcome response,
      String log) {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(key);
    Objects.requireNonNull(owner);
    if (generation < 1
        || response.ending() != Outcome.Ending.ANSWERED
        || response.text() == null
        || response.text().isBlank())
      throw new IllegalArgumentException("only answered stages have paid receipts");
    try {
      jdbc.update(
          "INSERT INTO information_model_steps(revision_id,generation,stage_key,stage,owner_handle,response,state,log_id) VALUES(?,?,?,?,?,CAST(? AS jsonb),'blocked',?) ON CONFLICT DO NOTHING",
          revision,
          generation,
          key,
          stage,
          owner,
          JSON.writeValueAsString(response),
          log);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("unencodable stage response", invalid);
    }
  }

  @Override
  public void ready(UUID revision, long generation, String key) {
    if (jdbc.update(
            "UPDATE information_model_steps SET state='ready' WHERE revision_id=? AND generation=? AND stage_key=?",
            revision,
            generation,
            key)
        != 1) throw new IllegalStateException("paid stage receipt disappeared");
  }
}
