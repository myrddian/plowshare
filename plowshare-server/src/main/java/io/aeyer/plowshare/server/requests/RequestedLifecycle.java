package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The lifecycle a listing was asked for, or the one it shows when nobody said.
 *
 * <p>A sibling of {@link RequestedTurnCap}: the same job of turning one request field into a domain
 * value, and of turning the domain's refusal into a caller fault with a status behind it. It left
 * {@code ConversationController} because a second surface will need to read the same field and must
 * not re-derive what "absent" means.
 *
 * <p><b>Absent is {@code ACTIVE} and not "every state".</b> That is the listing's existing
 * behaviour and it is deliberate — the state a person works in is the one they mean when they do
 * not say.
 *
 * <p><b>And the other three are reachable, which is not decoration.</b> A state that can be entered
 * and not left is not reversible in practice — {@code ConversationLifecycle.ARCHIVED} is documented
 * as reversible, and unarchiving needs an id somebody can still get hold of. Without this parameter
 * a person who archived a conversation would have to have kept the id themselves.
 *
 * <p>A name nothing spells is a 400 and not an empty listing. {@code ConversationLifecycle.of}
 * refuses it with the value quoted, which is a mistake the caller can correct; answering with
 * nothing would read as "you have no archived conversations" to somebody who typed {@code archive}.
 */
public final class RequestedLifecycle {

  private RequestedLifecycle() {}

  public static ConversationLifecycle in(String lifecycle) {
    if (lifecycle == null || lifecycle.isBlank()) {
      return ConversationLifecycle.ACTIVE;
    }
    try {
      return ConversationLifecycle.of(lifecycle);
    } catch (IllegalArgumentException unknown) {
      throw new CallerFault(unknown.getMessage(), unknown);
    }
  }
}
