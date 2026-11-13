package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Account-authorized, idempotent forum closure; it grants no opener seat or message-history access.
 */
public interface BoardClosing {
  record Command(String project, String topic, String body, List<String> cites, UUID requestId) {
    public Command {
      project = RelayPort.identity(project);
      topic = identifier(topic, "bdt_");
      body = text(body);
      cites = List.copyOf(cites);
      if (cites.size() > 200) throw new IllegalArgumentException("At most 200 board citations");
      cites.forEach(value -> identifier(value, "bdm_"));
      Objects.requireNonNull(requestId, "requestId");
    }
  }

  record Receipt(String requestId, BoardTopic topic, BoardMessage message) {}

  /** Checks live project write authority and closes atomically with its per-account receipt. */
  Receipt close(String account, Command command);

  /** Read-only live role/scope check, reusing the selected topic in the owning mutation. */
  BoardTopic require(String account, Command command);

  /** Same check using a snapshot already resolved by an owning server operation. */
  BoardTopic require(String account, Command command, BoardTopic selected);

  /** Read-only reconciliation with the same intent and current write authority. */
  Optional<Receipt> find(String account, Command command);

  static String identifier(String value, String prefix) {
    RelayPort.identity(value);
    if (!value.startsWith(prefix) || !value.substring(prefix.length()).matches("[A-Za-z0-9]+"))
      throw new IllegalArgumentException("Invalid board identity");
    return value;
  }

  static String text(String value) {
    RelayPort.text(value, 64000);
    if (value.length() > 16000)
      throw new IllegalArgumentException("Board text exceeds 16000 characters");
    return value;
  }
}
