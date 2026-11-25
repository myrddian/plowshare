package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.WorkCallers;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalEventSubscriptions;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Only an authenticated human can activate the exact file from their own Personal workspace. */
@Component
final class PersonalSubscriptionFrames implements FrameArea {
  private final PersonalEventSubscriptions subscriptions;
  private final WorkCallers callers;

  PersonalSubscriptionFrames(PersonalEventSubscriptions subscriptions, WorkCallers callers) {
    this.subscriptions = subscriptions;
    this.callers = callers;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.PERSONAL_SUBSCRIPTION_PREVIEW,
        this::preview,
        FrameTypes.PERSONAL_SUBSCRIPTION_ACTIVATE,
        this::activate);
  }

  private Outcome preview(Map<String, Object> payload, Asking asking) {
    if (payload != null && !payload.isEmpty())
      throw new CallerFault("Subscription preview takes no fields");
    return Outcome.ok(
        subscriptions.preview(owner(asking, FrameTypes.PERSONAL_SUBSCRIPTION_PREVIEW)));
  }

  private Outcome activate(Map<String, Object> payload, Asking asking) {
    String owner = owner(asking, FrameTypes.PERSONAL_SUBSCRIPTION_ACTIVATE);
    var request =
        Payloads.exact(
            payload,
            PersonalEventSubscriptions.Activation.class,
            FrameTypes.PERSONAL_SUBSCRIPTION_ACTIVATE);
    return Outcome.ok(subscriptions.activate(owner, request));
  }

  private String owner(Asking asking, String operation) {
    String owner = asking.requireHandle(operation);
    if (ServiceCredentials.principal(owner))
      throw new CallerFault("Subscription control requires the Personal owner");
    callers.requireSession(asking.sessionId(), owner);
    return owner;
  }
}
