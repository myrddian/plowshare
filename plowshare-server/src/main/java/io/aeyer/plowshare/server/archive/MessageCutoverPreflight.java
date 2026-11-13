package io.aeyer.plowshare.server.archive;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * Database-owned V239 preflight on Flyway's transaction/connection, before its first table rename.
 * Legacy corruption must stop with a content-free diagnostic instead of dropping publications or
 * failing later at a generic NOT NULL constraint. Existing shipped migrations remain unchanged.
 */
@Component
public final class MessageCutoverPreflight extends BaseCallback {
  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_EACH_MIGRATE
        && context.getMigrationInfo() != null
        && MigrationVersion.fromVersion("239").equals(context.getMigrationInfo().getVersion());
  }

  @Override
  public void handle(Event event, Context context) {
    if (!supports(event, context)) return;
    // The connection belongs to Flyway. This wrapper neither borrows another connection nor closes
    // it.
    var jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
    Long brokenInstances =
        jdbc.queryForObject(
            """
        SELECT count(*) FROM board_message_instances i
        LEFT JOIN projects p ON p.name=i.project
        LEFT JOIN board_topics t ON t.id=i.topic
        LEFT JOIN execution_allowances a ON a.id=t.allowance_id
        WHERE p.id IS NULL OR t.id IS NULL OR t.allowance_id IS NULL OR a.id IS NULL
        """,
            Long.class);
    Long brokenRoutes =
        jdbc.queryForObject(
            """
        SELECT count(*) FROM board_message_routes r
        LEFT JOIN board_message_instances i ON i.id=r.recipient
        LEFT JOIN board_messages m ON m.id=r.message
        WHERE i.id IS NULL OR m.id IS NULL
        """,
            Long.class);
    if (brokenInstances == null
        || brokenRoutes == null
        || brokenInstances != 0
        || brokenRoutes != 0)
      throw new FlywayException(
          "V239 messaging preflight refused: invalid instance/project/allowance bindings="
              + brokenInstances
              + ", missing route/body bindings="
              + brokenRoutes
              + ". Repair the legacy data from retained evidence before retrying; no cutover changes were applied.");
  }
}
