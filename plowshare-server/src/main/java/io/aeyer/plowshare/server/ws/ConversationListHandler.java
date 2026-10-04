package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedLifecycle;
import java.util.Map;
import java.util.Objects;

/**
 * {@code conversation.list} — what is open in one tier, which is what a console opens on. The frame
 * equivalent of {@code GET /v1/conversations}.
 *
 * <h2>The same store method the controller calls, and nothing between</h2>
 *
 * <p>{@code ConversationController.list} reads a home and a lifecycle through {@code requests} and
 * renders {@code ConversationStore.inHome}. This does those two things and no others. <b>An omitted
 * {@code project} is the global tier and not "all tiers"</b> — that is {@link RequestedHome}'s
 * reading of an absent field and the endpoint's, and a handler that treated an absent field as
 * "everywhere" would answer a superset of what the endpoint answers for the identical request.
 *
 * <h2>Why the payload names the query parameters</h2>
 *
 * <p>The endpoint takes both from its query string, so the payload names them under the spellings a
 * client already uses — {@link Payloads}' convention for a value that came from a query string,
 * which is that the name is already client-facing and is kept. {@link Asked} is where that shape is
 * written down; see {@link Paged} for why a record rather than a hand-read off the map.
 */
public final class ConversationListHandler implements FrameHandler {

  /**
   * {@code GET /v1/conversations}' two query parameters, as a payload.
   *
   * @param project which tier, or null for the global one
   * @param lifecycle which state to show, or null for the endpoint's own default
   */
  record Asked(String project, String lifecycle) {}

  private final ConversationStore conversations;

  /**
   * @param conversations the one store both surfaces list a tier from
   */
  public ConversationListHandler(ConversationStore conversations) {
    this.conversations = Objects.requireNonNull(conversations, "conversations");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    Asked asked = Payloads.as(payload, Asked.class, FrameTypes.CONVERSATION_LIST);
    return Outcome.ok(
        conversations
            .inHome(RequestedHome.in(asked.project()), RequestedLifecycle.in(asked.lifecycle()))
            .stream()
            .map(ConversationView::of)
            .toList());
  }
}
