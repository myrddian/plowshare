package io.aeyer.plowshare.server.access;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Resolves only allowlisted resource kinds and binds every caller identifier. */
@Repository
public class JdbcResourceScopeRepository implements ResourceScopeRepository {
  private final JdbcTemplate jdbc;

  public JdbcResourceScopeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<String> projects(AccessRequest.Resource resource) {
    String sql =
        switch (resource.kind()) {
          case CONVERSATION ->
              "SELECT p.name FROM conversations c LEFT JOIN projects p ON p.id=c.project_id WHERE c.id=?";
          case JOB ->
              "SELECT p.name FROM jobs j LEFT JOIN projects p ON p.id=j.project_id WHERE j.id=?";
          case MEMORY ->
              "SELECT p.name FROM memories m LEFT JOIN projects p ON p.id=m.project_id WHERE m.id=?";
          case PROPOSAL ->
              "SELECT pr.name FROM proposals p JOIN memories m ON m.id=p.memory_id LEFT JOIN projects pr ON pr.id=m.project_id WHERE p.id=?";
          case TOPIC -> "SELECT project FROM board_topics WHERE id=?";
          case INSTANCE -> "SELECT project FROM board_message_instances WHERE id=?";
          case ORCHESTRATION -> "SELECT project FROM orchestrations WHERE id=?";
          case MESSAGE ->
              "SELECT i.project FROM board_message_routes r JOIN board_message_instances i ON i.id IN (r.sender,r.recipient) WHERE r.message=?";
          case SCHEDULE ->
              "SELECT p.name FROM schedule_files f JOIN schedule_sources s ON s.id=f.source_id LEFT JOIN projects p ON p.id=s.project_id WHERE f.internal_name=?";
          case TRIGGER ->
              "SELECT COALESCE(t.project,p.name,'personal:'||encode(convert_to(t.defined_by,'UTF8'),'hex')) FROM triggers t LEFT JOIN conversations c ON c.id=t.conversation LEFT JOIN projects p ON p.id=c.project_id WHERE t.name=?";
          case APPROVAL ->
              "SELECT p.name FROM run_approvals a JOIN projects p ON p.id=a.project_id WHERE a.id=?";
          case OUTGOING ->
              "SELECT p.name FROM outgoing_work w LEFT JOIN projects p ON p.id=w.project_id WHERE w.id=?";
        };
    return jdbc.queryForList(
        sql,
        String.class,
        resource.kind() == Kind.OUTGOING ? UUID.fromString(resource.id()) : resource.id());
  }

  @Override
  public boolean triggerOwnedBy(String trigger, String account) {
    return jdbc.queryForList("SELECT defined_by FROM triggers WHERE name=?", String.class, trigger)
        .contains(account);
  }
}
