package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.auth.ServerAdministration;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Server administration uses the authenticated event socket. */
@Component
public class AdminFrames implements FrameArea {
  private final AdminStore accounts;
  private ServerAdministration administration;

  public AdminFrames(AdminStore accounts) {
    this.accounts = accounts;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public void useAdministration(ServerAdministration administration) {
    this.administration = administration;
  }

  public record Status(String handle, boolean serverAdmin) {}

  private static String text(Map<String, Object> payload, String key) {
    if (!(payload.get(key) instanceof String value) || value.isBlank())
      throw new CallerFault(key + " is required");
    return value;
  }

  private static Boolean flag(Map<String, Object> payload, String key) {
    if (!payload.containsKey(key)) return null;
    if (!(payload.get(key) instanceof Boolean value))
      throw new CallerFault(key + " must be boolean");
    return value;
  }

  private static long number(Map<String, Object> payload, String key, long fallback) {
    if (!payload.containsKey(key)) return fallback;
    if (!(payload.get(key) instanceof Number n)
        || n.doubleValue() != n.longValue()
        || n.longValue() < 0
        || n.longValue() > Integer.MAX_VALUE)
      throw new CallerFault(key + " must be a nonnegative integer");
    return n.longValue();
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.ADMIN_STATUS,
            (payload, asking) -> {
              String handle = asking.requireHandle(FrameTypes.ADMIN_STATUS);
              return Outcome.ok(new Status(handle, accounts.isServerAdmin(handle)));
            },
        FrameTypes.ADMIN_ACCOUNTS,
            (p, a) -> Outcome.ok(administration.list(a.requireHandle(FrameTypes.ADMIN_ACCOUNTS))),
        FrameTypes.ADMIN_ACCOUNT_CREATE,
            (p, a) ->
                Outcome.ok(
                    administration.create(
                        a.requireHandle(FrameTypes.ADMIN_ACCOUNT_CREATE),
                        text(p, "handle"),
                        Boolean.TRUE.equals(flag(p, "serverAdmin")))),
        FrameTypes.ADMIN_ACCOUNT_UPDATE,
            (p, a) ->
                Outcome.ok(
                    administration.update(
                        a.requireHandle(FrameTypes.ADMIN_ACCOUNT_UPDATE),
                        text(p, "handle"),
                        flag(p, "enabled"),
                        flag(p, "serverAdmin"))),
        FrameTypes.ADMIN_ACCOUNT_RESET,
            (p, a) ->
                Outcome.ok(
                    administration.reset(
                        a.requireHandle(FrameTypes.ADMIN_ACCOUNT_RESET), text(p, "handle"))),
        FrameTypes.ADMIN_SESSIONS,
            (p, a) ->
                Outcome.ok(
                    administration.sessions(
                        a.requireHandle(FrameTypes.ADMIN_SESSIONS), text(p, "handle"))),
        FrameTypes.ADMIN_SESSION_REVOKE,
            (p, a) ->
                Outcome.ok(
                    administration.revokeSessions(
                        a.requireHandle(FrameTypes.ADMIN_SESSION_REVOKE), text(p, "handle"))),
        FrameTypes.ADMIN_AUDIT,
            (p, a) ->
                Outcome.ok(
                    administration.history(
                        a.requireHandle(FrameTypes.ADMIN_AUDIT),
                        p.containsKey("handle") ? text(p, "handle") : "",
                        number(p, "before", 0),
                        Math.toIntExact(number(p, "limit", 50)))));
  }
}
