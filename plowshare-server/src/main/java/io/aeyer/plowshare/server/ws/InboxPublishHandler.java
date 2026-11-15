package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.ApplicationInbox;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Public application output; recipients are resolved by RBAC, never supplied by the producer. */
public final class InboxPublishHandler implements FrameHandler {
  record Body(String project, UUID requestId, String kind, String text) {}

  private final ApplicationInbox applications;

  public InboxPublishHandler(ApplicationInbox applications) {
    this.applications = applications;
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.INBOX_PUBLISH);
    // Publication/receipt inputs are closed contracts: a caller cannot introduce a recipient
    // or rely on scalar coercion. Payloads owns binding after this transport-only validation.
    if (payload == null
        || !payload.keySet().equals(Set.of("project", "requestId", "kind", "text"))
        || !(payload.get("project") instanceof String)
        || !(payload.get("requestId") instanceof String request)
        || !request.matches("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")
        || !(payload.get("kind") instanceof String)
        || !(payload.get("text") instanceof String))
      throw new CallerFault(
          "Choose the exact project, requestId, kind and text fields with their declared types");
    Body body = Payloads.as(payload, Body.class, FrameTypes.INBOX_PUBLISH);
    if (!Set.of("finding", "failure", "attention").contains(body.kind()))
      throw new CallerFault("Choose finding, failure or attention");
    if (body.text() == null
        || body.text().isBlank()
        || body.text().length() > 131072
        || body.text().indexOf('\0') >= 0)
      throw new CallerFault(
          "Application notice text must contain 1..131072 characters without NUL");
    applications.publish(account, body.project(), body.requestId(), body.kind(), body.text());
    return new Outcome(Code.NO_CONTENT, null, null);
  }
}
