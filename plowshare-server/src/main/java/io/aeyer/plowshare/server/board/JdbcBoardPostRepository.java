package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JSON receipt identity comparisons and the advisory lock remain inside the owning repository. */
@Repository
public class JdbcBoardPostRepository implements BoardPostRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate jdbc;

  public JdbcBoardPostRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private record Receipt(String message, boolean matches) {}

  private static String identity(Action action) {
    Objects.requireNonNull(action);
    try {
      return JSON.writeValueAsString(action);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("unencodable board action", invalid);
    }
  }

  @Override
  public Optional<String> lockAndRead(String account, UUID request, Action action) {
    Objects.requireNonNull(account);
    Objects.requireNonNull(request);
    String identity = identity(action);
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        account + ":" + request);
    var receipts =
        jdbc.query(
            "SELECT message_id,payload=CAST(? AS jsonb) AS matches FROM board_post_receipts WHERE account=? AND request_id=?",
            (row, n) -> new Receipt(row.getString(1), row.getBoolean(2)),
            identity,
            account,
            request);
    if (receipts.isEmpty()) return Optional.empty();
    if (!receipts.getFirst().matches())
      throw new CallerFault("This requestId was already used for a different board action.");
    return Optional.of(receipts.getFirst().message());
  }

  @Override
  public void save(String account, UUID request, Action action, String message) {
    Objects.requireNonNull(account);
    Objects.requireNonNull(request);
    Objects.requireNonNull(message);
    jdbc.update(
        "INSERT INTO board_post_receipts(account,request_id,payload,message_id) VALUES(?,?,CAST(? AS jsonb),?)",
        account,
        request,
        identity(action),
        message);
  }
}
