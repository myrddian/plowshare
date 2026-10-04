package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code conversation.follow}: replace this session's log subscriptions. The legacy {@code
 * conversation} names one log; {@code conversations} names the complete set of logs a multi-view
 * client wants, including an empty set.
 */
public final class ConversationFollowHandler implements FrameHandler {

  /** Wildcard elements keep malformed non-string IDs from being coerced to strings. */
  record Body(List<?> conversations) {}

  private final Watchers watchers;
  private final Conversations rules;

  /**
   * @param watchers where a session's follow is recorded
   * @param rules the service {@code conversation.trajectory} asks the existence question of
   */
  public ConversationFollowHandler(Watchers watchers, Conversations rules) {
    this.watchers = Objects.requireNonNull(watchers, "watchers");
    this.rules = Objects.requireNonNull(rules, "rules");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String session = asking.sessionId();
    if (session == null || session.isBlank()) {
      throw new CallerFault(
          "'session' is required: a conversation's log is followed by a"
              + " listener, and this frame names none. Nothing is followed.");
    }
    if (payload != null && payload.containsKey("conversations")) {
      if (payload.containsKey("conversation")) {
        throw new CallerFault(
            FrameTypes.CONVERSATION_FOLLOW
                + " takes either 'conversation' or 'conversations', not both."
                + " The existing follows were not changed.");
      }
      Body body = Payloads.as(payload, Body.class, FrameTypes.CONVERSATION_FOLLOW);
      if (body.conversations() == null) {
        throw new CallerFault(
            FrameTypes.CONVERSATION_FOLLOW
                + " needs 'conversations' as an array of conversation ids; send []"
                + " to follow none. The existing follows were not changed.");
      }
      Set<String> conversations = new LinkedHashSet<>();
      for (Object item : body.conversations()) {
        if (!(item instanceof String conversation) || conversation.isBlank()) {
          throw new CallerFault(
              FrameTypes.CONVERSATION_FOLLOW
                  + " needs every 'conversations' item to be a non-blank string."
                  + " The existing follows were not changed.");
        }
        conversations.add(conversation);
      }
      // Validate the entire snapshot before replacing any follows. A stale
      // view must not discard subscriptions to the client's other logs.
      for (String conversation : conversations) {
        rules.requireExistsOrThereIsNo(conversation, "log to follow");
      }
      watchers.follows(session, conversations);
      return Outcome.ok();
    }
    String conversation =
        Payloads.required(
            payload,
            "conversation",
            FrameTypes.CONVERSATION_FOLLOW,
            "the id of the conversation this session is showing. Nothing is followed.");
    // Refused as the trajectory a follower reads is, and in the same door's words: an id
    // nothing opened would otherwise be followed silently, and a client restoring a stale
    // one would wait on pushes for a log that never grows.
    rules.requireExistsOrThereIsNo(conversation, "log to follow");
    watchers.follows(session, conversation);
    return Outcome.ok();
  }
}
