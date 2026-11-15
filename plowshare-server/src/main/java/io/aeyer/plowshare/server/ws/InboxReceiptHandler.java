package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.ApplicationInbox;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only reconciliation of an application publication under its original account and UUID. */
public final class InboxReceiptHandler implements FrameHandler {
  record Body(String project, UUID requestId) {}

  public record Receipt(boolean found) {}

  private final ApplicationInbox applications;

  public InboxReceiptHandler(ApplicationInbox applications) {
    this.applications = applications;
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.INBOX_RECEIPT);
    // Publication/receipt inputs are closed contracts: a caller cannot introduce a recipient
    // or rely on scalar coercion. Payloads owns binding after this transport-only validation.
    if (payload == null
        || !payload.keySet().equals(Set.of("project", "requestId"))
        || !(payload.get("project") instanceof String)
        || !(payload.get("requestId") instanceof String request)
        || !request.matches("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"))
      throw new CallerFault("Choose the exact project, requestId fields with their declared types");
    Body body = Payloads.as(payload, Body.class, FrameTypes.INBOX_RECEIPT);
    return Outcome.ok(
        new Receipt(applications.published(account, body.project(), body.requestId())));
  }
}
