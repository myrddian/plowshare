package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.EntryPageView;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.requests.RequestedWindow;
import java.util.Map;
import java.util.Objects;

/**
 * {@code conversation.chat} — what the model is shown, one page at a time. The frame equivalent of
 * {@code GET /v1/conversations/&#123;id&#125;/chat}.
 *
 * <h2>The window is read before the conversation is looked up, and the order is part of the answer
 * </h2>
 *
 * <p>{@code ConversationController.chat} builds its window first and asks the existence question
 * second, so a limit of zero against an id nobody opened is a 400 about the limit rather than a 404
 * about the id. <b>This does the same two things in the same order</b>, which is the kind of parity
 * no payload comparison sees: a handler that checked existence first would agree with the endpoint
 * on every other case and answer a different status for that one.
 *
 * <h2>Refused and narrowed are {@link RequestedWindow}'s, not this handler's</h2>
 *
 * <p>A limit past the cap is narrowed to {@link RequestedWindow#MOST_ENTRIES_A_PAGE} and reported
 * in {@code EntryPageView.limit}; a limit of zero or a negative offset is refused. Both decisions
 * and both sentences are in the one {@code requests} type both surfaces read a page bound through,
 * which is where they went when this controller's last inline throws came down.
 */
public final class ConversationChatHandler implements FrameHandler {

  private final Conversations rules;
  private final EntryStore entries;

  /**
   * @param rules the service the controller asks the existence question of
   * @param entries the one store both surfaces read a page from
   */
  public ConversationChatHandler(Conversations rules, EntryStore entries) {
    this.rules = Objects.requireNonNull(rules, "rules");
    this.entries = Objects.requireNonNull(entries, "entries");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String conversation =
        Payloads.required(
            payload,
            "conversation",
            FrameTypes.CONVERSATION_CHAT,
            "the id POST /v1/conversations answered with. Nothing was read.");
    Paged paged = Payloads.as(payload, Paged.class, FrameTypes.CONVERSATION_CHAT);
    RequestedWindow window = RequestedWindow.in(paged.offset(), paged.limit());
    rules.requireExists(conversation, "chat");
    return Outcome.ok(
        EntryPageView.of(
            entries.pageOfProjection(conversation, window.skip(), window.most()),
            window.skip(),
            window.most()));
  }
}
