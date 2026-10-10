package io.aeyer.plowshare.server.archive;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the owner and registered home together, without exposing rows to the authorization layer.
 */
@Repository
public final class JdbcConversationContextRepository implements ConversationContextRepository {
  private final JdbcTemplate jdbc;

  public JdbcConversationContextRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Ownership> ownership(String conversation) {
    if (conversation == null || conversation.isBlank())
      throw new IllegalArgumentException("A conversation is required for context inspection");
    return ArchiveUnavailableException.translating(
        "read conversation context ownership",
        () ->
            jdbc
                .query(
                    "SELECT c.owner_handle,c.project_id::text,p.name,p.personal_owner IS NOT NULL AS personal "
                        + "FROM conversations c LEFT JOIN projects p ON p.id=c.project_id WHERE c.id=? AND c.owner_handle IS NOT NULL",
                    (rs, row) ->
                        new Ownership(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)),
                    conversation)
                .stream()
                .findFirst());
  }
}
