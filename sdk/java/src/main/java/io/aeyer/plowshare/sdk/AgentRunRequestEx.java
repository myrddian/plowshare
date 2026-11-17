package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * Resolve an existing external alias and submit one ordinary conversation turn. A locator is not an
 * utterance idempotency key: a possibly transmitted run must never be automatically resent. Null
 * options are omitted; a missing alias does not open a conversation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunRequestEx(
    String project,
    String source,
    String externalConversation,
    String agent,
    String task,
    Session session,
    Integer maxModelCalls,
    Integer maxTurns,
    Boolean noTurnCap,
    List<String> images) {
  public AgentRunRequestEx {
    ConversationOpenRequestEx.locator(project, source, externalConversation);
    ConversationOpenRequestEx.nonblank(agent, "agent");
    // Task content is preserved; the common codec resource ceiling is not a prose budget.
    ConversationOpenRequestEx.nonblank(task, "task");
    ConversationOpenRequestEx.limits(maxModelCalls, maxTurns, noTurnCap);
    if (images != null) {
      images = List.copyOf(images);
      if (!images.isEmpty())
        throw new IllegalArgumentException("Images are not supported in conversation turns");
    }
  }

  public AgentRunRequestEx(
      String project, String source, String externalConversation, String agent, String task) {
    this(project, source, externalConversation, agent, task, null, null, null, null, null);
  }

  /**
   * Presence of the nullable session selector. A null outer Session omits the field; a Session
   * whose value is null writes explicit JSON null. Non-null names are validated without rewriting.
   */
  public record Session(@JsonValue String value) {
    public Session {
      if (value != null) ConversationOpenRequestEx.nonblank(value, "session");
    }
  }
}
