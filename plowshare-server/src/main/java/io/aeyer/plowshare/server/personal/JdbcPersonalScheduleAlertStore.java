package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcPersonalScheduleAlertStore implements PersonalScheduleAlertStore {
  private static final String PENDING = "message IS NULL AND NOT refused";
  private final JdbcTemplate jdbc;

  JdbcPersonalScheduleAlertStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<String> due(int limit) {
    return jdbc.queryForList(
        "SELECT item FROM personal_schedule_bot_alerts WHERE "
            + PENDING
            + " AND next_attempt<=clock_timestamp() ORDER BY next_attempt,item LIMIT ?",
        String.class,
        limit);
  }

  @Override
  public Optional<Alert> lock(String item) {
    return jdbc
        .query(
            "SELECT item, owner, firing, route, causation FROM personal_schedule_bot_alerts"
                + " WHERE item=? AND "
                + PENDING
                + " AND next_attempt<=clock_timestamp() FOR UPDATE SKIP LOCKED",
            (row, index) ->
                new Alert(
                    row.getString(1),
                    row.getString(2),
                    row.getString(3),
                    row.getString(4),
                    RelayCausationCodec.read(row.getString(5))),
            item)
        .stream()
        .findFirst();
  }

  @Override
  public void sent(String item, String message) {
    jdbc.update(
        "UPDATE personal_schedule_bot_alerts SET message=? WHERE item=? AND " + PENDING,
        message,
        item);
  }

  @Override
  public void retry(String item) {
    jdbc.update(
        "UPDATE personal_schedule_bot_alerts SET next_attempt=clock_timestamp()+interval '30 seconds'"
            + " WHERE item=? AND "
            + PENDING,
        item);
  }

  @Override
  public void refuse(String item) {
    jdbc.update(
        "UPDATE personal_schedule_bot_alerts SET refused=TRUE WHERE item=? AND " + PENDING, item);
  }
}
