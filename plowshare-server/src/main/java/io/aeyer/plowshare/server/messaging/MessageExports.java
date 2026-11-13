package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Native correlated returns; transport callers cannot select a messaging instance or conversation.
 */
public interface MessageExports {
  record Return(UUID requestId, UUID sourceEvent, String body, boolean finalReply) {
    public Return {
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(sourceEvent);
      body = RelayPort.text(body);
      if (body.isBlank()) throw new IllegalArgumentException("Return body is blank");
    }
  }

  /** Scope and adapter account are authenticated receiver snapshots, never fields in Return. */
  record Command(
      long projectId,
      String project,
      String channel,
      String returnTopic,
      String adapterAccount,
      Return value,
      String returnEvent) {
    public Command {
      if (projectId < 1) throw new IllegalArgumentException("Return requires a project");
      project = RelayPort.identity(project);
      channel = RelayPort.identity(channel);
      returnTopic = RelayPort.topic(returnTopic);
      adapterAccount = RelayPort.identity(adapterAccount);
      Objects.requireNonNull(value);
      returnEvent = RelayPort.identity(returnEvent);
    }
  }

  record Receipt(String message) {
    public Receipt {
      message = MessagePayload.identifier(message, "bdm_");
    }
  }

  void require(Command command);

  Receipt accept(Command command);

  /** Immutable receipt recovery never republishes or rechecks current execution authority. */
  Optional<Receipt> inspect(Command command);
}
