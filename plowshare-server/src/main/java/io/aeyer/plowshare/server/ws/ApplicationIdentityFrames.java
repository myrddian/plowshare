package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.ApplicationIdentity.*;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.applications.ApplicationIdentities;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.springframework.stereotype.Component;

/** Closed typed boundary. Issuance returns a secret once; receipts return metadata only. */
@Component
public final class ApplicationIdentityFrames implements FrameArea {
  private final ApplicationIdentities identities;

  public ApplicationIdentityFrames(ApplicationIdentities identities) {
    this.identities = identities;
  }

  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.APPLICATION_IDENTITY_STATUS,
        this::status,
        FrameTypes.APPLICATION_TOKEN_ISSUE,
        this::issue,
        FrameTypes.APPLICATION_TOKEN_RECEIPT,
        this::receipt);
  }

  private static String project(Map<String, Object> payload) {
    String value =
        Payloads.required(
            payload, "project", FrameTypes.APPLICATION_IDENTITY_STATUS, "an Application project");
    try {
      io.aeyer.plowshare.protocol.ApplicationIdentity.project(value);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault(invalid.getMessage());
    }
    return value;
  }

  private static UUID requestId(Map<String, Object> payload) {
    if (!(payload.get("requestId") instanceof String value)
        || !value.matches("(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
      throw new CallerFault("Retain a UUID requestId for credential receipt recovery");
    return UUID.fromString(value);
  }

  private static void fields(Map<String, Object> payload, Set<String> keys) {
    if (!payload.keySet().equals(keys))
      throw new CallerFault("Specify exactly " + String.join(", ", new TreeSet<>(keys)));
  }

  private Outcome status(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("project"));
    String project = project(payload);
    return Outcome.ok(
        identities.status(asking.requireHandle(FrameTypes.APPLICATION_IDENTITY_STATUS), project));
  }

  private Outcome receipt(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("project", "requestId"));
    String project = project(payload);
    UUID id = requestId(payload);
    return Outcome.ok(
        identities.receipt(
            asking.requireHandle(FrameTypes.APPLICATION_TOKEN_RECEIPT), project, id));
  }

  private Outcome issue(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("project", "requestId", "expiresInDays", "rotate"));
    String project = project(payload);
    UUID id = requestId(payload);
    if (!(payload.get("expiresInDays") instanceof Integer days)
        || days < 1
        || days > 365
        || !(payload.get("rotate") instanceof Boolean rotate))
      throw new CallerFault("Choose expiresInDays 1–365 and an explicit rotate boolean");
    return Outcome.ok(
        identities.issue(
            asking.requireHandle(FrameTypes.APPLICATION_TOKEN_ISSUE),
            new Issue(project, id, days, rotate)));
  }
}
