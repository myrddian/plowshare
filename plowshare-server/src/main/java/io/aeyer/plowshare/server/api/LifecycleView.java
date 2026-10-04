package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.ConversationRecord;

/**
 * Where a conversation now is, answered with what was actually written.
 *
 * <p>{@code ProjectView}'s rule and {@code ConversationView}'s: answer with the row rather than
 * echoing the request. It matters more here than usual, because the interesting failure of this
 * endpoint is a move that did not happen — somebody else got there first — and that is refused
 * rather than answered, so an answer carrying the state means the row is in it.
 *
 * <p><b>Its own view and not {@link ConversationView}.</b> That one is the answer to opening and to
 * listing, and it is only ever given a {@code turn}-origin row — but this endpoint moves a
 * machine's log as readily as a person's, and a curator trace an operator is done with holds no
 * budget at all. Building a {@code ConversationView} of one would ask that class to describe an
 * allowance that is not the row's to describe, which is exactly the absence its own null-budget
 * guard is kept against and not the ordinary case this endpoint would be putting it in.
 *
 * @param id the conversation moved
 * @param lifecycle where it now is, as the column spells it
 */
public record LifecycleView(String id, String lifecycle) {

  public static LifecycleView of(ConversationRecord conversation) {
    return new LifecycleView(conversation.id(), conversation.lifecycle().wireName());
  }
}
