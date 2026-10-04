package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.auth.ServiceAccounts;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Machine identities are administered through the same authenticated socket as human accounts. */
@Component
public class ServiceAccountFrames implements FrameArea {
  private final ServiceAccounts services;

  public ServiceAccountFrames(ServiceAccounts services) {
    this.services = services;
  }

  public record Issue(
      String handle, String name, List<ServiceAccounts.Scope> scopes, Integer expiresInDays) {}

  private static String text(Map<String, Object> payload, String key) {
    if (!(payload.get(key) instanceof String value) || value.isBlank())
      throw new CallerFault(key + " is required");
    return value;
  }

  private static UUID id(Map<String, Object> payload) {
    try {
      return UUID.fromString(text(payload, "id"));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("id must be a token UUID");
    }
  }

  private static int days(Map<String, Object> payload) {
    if (!payload.containsKey("expiresInDays")) return 30;
    if (!(payload.get("expiresInDays") instanceof Number n)
        || n.doubleValue() != n.intValue()
        || n.intValue() < 1
        || n.intValue() > 365) throw new CallerFault("expiresInDays must be 1–365");
    return n.intValue();
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(
            FrameTypes.ADMIN_SERVICE_ACCOUNTS,
            (p, a) ->
                Outcome.ok(services.list(a.requireHandle(FrameTypes.ADMIN_SERVICE_ACCOUNTS)))),
        Map.entry(
            FrameTypes.ADMIN_SERVICE_ACCOUNT_CREATE,
            (p, a) ->
                Outcome.ok(
                    services.create(
                        a.requireHandle(FrameTypes.ADMIN_SERVICE_ACCOUNT_CREATE),
                        text(p, "handle")))),
        Map.entry(
            FrameTypes.ADMIN_SERVICE_ACCOUNT_UPDATE,
            (p, a) -> {
              if (!(p.get("enabled") instanceof Boolean enabled))
                throw new CallerFault("enabled must be boolean");
              return Outcome.ok(
                  services.update(
                      a.requireHandle(FrameTypes.ADMIN_SERVICE_ACCOUNT_UPDATE),
                      text(p, "handle"),
                      enabled));
            }),
        Map.entry(
            FrameTypes.ADMIN_SERVICE_TOKENS,
            (p, a) ->
                Outcome.ok(
                    services.tokens(
                        a.requireHandle(FrameTypes.ADMIN_SERVICE_TOKENS), text(p, "handle")))),
        Map.entry(
            FrameTypes.ADMIN_SERVICE_TOKEN_CREATE,
            (p, a) -> {
              Issue issue = Payloads.as(p, Issue.class, FrameTypes.ADMIN_SERVICE_TOKEN_CREATE);
              return Outcome.ok(
                  services.issue(
                      a.requireHandle(FrameTypes.ADMIN_SERVICE_TOKEN_CREATE),
                      text(p, "handle"),
                      text(p, "name"),
                      issue.scopes(),
                      days(p)));
            }),
        Map.entry(
            FrameTypes.ADMIN_SERVICE_TOKEN_ROTATE,
            (p, a) ->
                Outcome.ok(
                    services.rotate(
                        a.requireHandle(FrameTypes.ADMIN_SERVICE_TOKEN_ROTATE),
                        text(p, "handle"),
                        id(p),
                        days(p)))),
        Map.entry(
            FrameTypes.ADMIN_SERVICE_TOKEN_REVOKE,
            (p, a) ->
                Outcome.ok(
                    services.revoke(
                        a.requireHandle(FrameTypes.ADMIN_SERVICE_TOKEN_REVOKE),
                        text(p, "handle"),
                        id(p)))));
  }
}
